package com.mingeek.opiczh.core.speech.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.ChineseSentenceChunker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Android 시스템 TTS 래퍼 (오프라인 폴백 경로).
 *
 * 장문 안정화 전략:
 * 1. 문장 청크 분할 후 QUEUE_ADD로 순차 큐잉 — speak() 입력 한도 초과 원천 차단
 * 2. onError 시 엔진 재초기화 후 실패 청크부터 재시도 (최대 [MAX_RETRIES]회)
 * 3. 진행 상태를 StateFlow로 노출
 */
@Singleton
class TtsSpeaker @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private var engine: TextToSpeech? = null
    private var engineReady = false

    private val _progress = MutableStateFlow(0 to 0)

    /** (완료 청크 수, 전체 청크 수) */
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    /** zh-CN 보이스 사용 가능 여부 (설정 화면 안내용) */
    suspend fun isChineseAvailable(): Boolean =
        ensureEngine() is AppResult.Success

    /**
     * 장문 텍스트를 끝까지 읽는다. 완료(또는 복구 불가 오류)까지 suspend.
     */
    suspend fun speak(text: String, speechRate: Float = 1.0f): AppResult<Unit> {
        val safeLimit = maxOf(
            64,
            minOf(300, TextToSpeech.getMaxSpeechInputLength() / 4),
        )
        val chunks = ChineseSentenceChunker.chunk(text, maxChunkLength = safeLimit)
        if (chunks.isEmpty()) return AppResult.success(Unit)

        _progress.value = 0 to chunks.size
        var startIndex = 0
        var attempt = 0
        while (true) {
            val result = speakChunks(chunks, startIndex, speechRate)
            when (result) {
                is SpeakOutcome.Completed -> {
                    _progress.value = chunks.size to chunks.size
                    return AppResult.success(Unit)
                }
                is SpeakOutcome.Stopped ->
                    return AppResult.failure(AppError.Audio("재생이 중단되었습니다"))
                is SpeakOutcome.Failed -> {
                    attempt++
                    if (attempt > MAX_RETRIES) {
                        return AppResult.failure(
                            AppError.Audio("TTS 재생 실패 (청크 ${result.failedIndex + 1}/${chunks.size})"),
                        )
                    }
                    // 엔진을 버리고 실패 지점부터 재시도
                    releaseEngine()
                    startIndex = result.failedIndex
                }
            }
        }
    }

    fun stop() {
        stopped.set(true)
        engine?.stop()
    }

    fun release() {
        releaseEngine()
    }

    // --- 내부 구현 ---

    private sealed interface SpeakOutcome {
        data object Completed : SpeakOutcome
        data object Stopped : SpeakOutcome
        data class Failed(val failedIndex: Int) : SpeakOutcome
    }

    private val stopped = AtomicBoolean(false)

    private suspend fun speakChunks(
        chunks: List<String>,
        startIndex: Int,
        speechRate: Float,
    ): SpeakOutcome {
        val tts = when (val ready = ensureEngine()) {
            is AppResult.Success -> ready.value
            is AppResult.Failure -> return SpeakOutcome.Failed(startIndex)
        }
        stopped.set(false)

        return suspendCancellableCoroutine { cont ->
            val resumed = AtomicBoolean(false)
            fun resumeOnce(outcome: SpeakOutcome) {
                if (resumed.compareAndSet(false, true) && cont.isActive) cont.resume(outcome)
            }

            val lastIndex = chunks.lastIndex
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    val index = utteranceId?.removePrefix(UTTERANCE_PREFIX)?.toIntOrNull() ?: return
                    _progress.value = (index + 1) to chunks.size
                    if (index >= lastIndex) resumeOnce(SpeakOutcome.Completed)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handleError(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    handleError(utteranceId)
                }

                private fun handleError(utteranceId: String?) {
                    if (stopped.get()) {
                        resumeOnce(SpeakOutcome.Stopped)
                        return
                    }
                    val index = utteranceId?.removePrefix(UTTERANCE_PREFIX)?.toIntOrNull()
                        ?: _progress.value.first
                    resumeOnce(SpeakOutcome.Failed(index))
                }
            })

            tts.setSpeechRate(speechRate)
            for (i in startIndex..lastIndex) {
                val queueResult = tts.speak(
                    chunks[i],
                    TextToSpeech.QUEUE_ADD,
                    null,
                    "$UTTERANCE_PREFIX$i",
                )
                if (queueResult == TextToSpeech.ERROR) {
                    resumeOnce(SpeakOutcome.Failed(i))
                    break
                }
            }

            cont.invokeOnCancellation {
                stopped.set(true)
                runCatching { tts.stop() }
            }
        }
    }

    private suspend fun ensureEngine(): AppResult<TextToSpeech> = withContext(Dispatchers.Main) {
        engine?.takeIf { engineReady }?.let { return@withContext AppResult.success(it) }
        releaseEngine()
        suspendCancellableCoroutine { cont: CancellableContinuation<AppResult<TextToSpeech>> ->
            var created: TextToSpeech? = null
            created = TextToSpeech(context) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    if (cont.isActive) {
                        cont.resume(AppResult.failure(AppError.Audio("TTS 엔진 초기화 실패")))
                    }
                    return@TextToSpeech
                }
                val tts = created ?: return@TextToSpeech
                val langResult = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    runCatching { tts.shutdown() }
                    if (cont.isActive) {
                        cont.resume(
                            AppResult.failure(
                                AppError.Audio("중국어(zh-CN) 음성 데이터가 설치되어 있지 않습니다. 시스템 TTS 설정에서 중국어 음성을 설치해 주세요."),
                            ),
                        )
                    }
                    return@TextToSpeech
                }
                engine = tts
                engineReady = true
                if (cont.isActive) cont.resume(AppResult.success(tts))
            }
            cont.invokeOnCancellation { runCatching { created?.shutdown() } }
        }
    }

    private fun releaseEngine() {
        engineReady = false
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }
        engine = null
    }

    private companion object {
        const val UTTERANCE_PREFIX = "opiczh-chunk-"
        const val MAX_RETRIES = 2
    }
}
