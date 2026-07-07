package com.mingeek.opiczh.core.speech

import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.ChineseSentenceChunker
import com.mingeek.opiczh.core.common.CrashReporter
import com.mingeek.opiczh.core.speech.player.AudioFilePlayer
import com.mingeek.opiczh.core.speech.tts.TtsAudioCache
import com.mingeek.opiczh.core.speech.tts.TtsSpeaker
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 중국어 음성 출력의 단일 진입점. 장문도 끊김 없이 재생하는 것이 목표.
 *
 * 재생 전략(폴백 체인):
 * 1. 문장 청크 단위로 캐시 확인 → 없으면 Gemini TTS 합성 (다음 청크는 재생 중 미리 합성)
 * 2. 원격 합성이 실패하면 남은 텍스트를 시스템 TTS(zh-CN)로 이어서 재생
 * 3. 시스템 TTS도 실패하면 오류 반환 (UI에 안내)
 */
@Singleton
class ChineseSpeaker @Inject constructor(
    private val remoteTts: RemoteTtsSynthesizer,
    private val cache: TtsAudioCache,
    private val filePlayer: AudioFilePlayer,
    private val systemTts: TtsSpeaker,
    private val crashReporter: CrashReporter,
) {

    private val _state = MutableStateFlow<SpeakerState>(SpeakerState.Idle)
    val state: StateFlow<SpeakerState> = _state.asStateFlow()

    /**
     * @param preferNatural true면 Gemini 자연 음성 우선, false면 시스템 TTS만 사용
     */
    suspend fun speak(text: String, preferNatural: Boolean = true): AppResult<Unit> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return AppResult.success(Unit)

        return try {
            if (preferNatural) speakNatural(trimmed) else speakWithSystemTts(trimmed)
        } finally {
            _state.value = SpeakerState.Idle
        }
    }

    /** 사전 합성만 수행 (시험 시작 전 문항 프리로드용) */
    suspend fun prefetch(text: String): AppResult<Unit> {
        val chunks = ChineseSentenceChunker.chunk(text.trim(), NATURAL_CHUNK_LENGTH)
        for (chunk in chunks) {
            val key = remoteTts.cacheKey(chunk)
            if (cache.get(key) != null) continue
            when (val synth = remoteTts.synthesize(chunk)) {
                is AppResult.Success -> cache.put(key, synth.value)
                is AppResult.Failure -> return synth
            }
        }
        return AppResult.success(Unit)
    }

    fun stop() {
        filePlayer.stop()
        systemTts.stop()
    }

    private suspend fun speakNatural(text: String): AppResult<Unit> = coroutineScope {
        val chunks = ChineseSentenceChunker.chunk(text, NATURAL_CHUNK_LENGTH)
        var prefetched: Deferred<File?> = async { ensureChunkAudio(chunks[0]) }

        for (i in chunks.indices) {
            _state.value = SpeakerState.Preparing(i + 1, chunks.size)
            val file = prefetched.await()
            // 재생과 겹치게 다음 청크를 미리 합성해 무음 구간을 없앤다
            if (i + 1 <= chunks.lastIndex) {
                prefetched = async { ensureChunkAudio(chunks[i + 1]) }
            }

            if (file == null) {
                // 원격 합성 실패 → 남은 텍스트는 시스템 TTS로 이어서
                crashReporter.log("tts: 원격 합성 실패, 시스템 TTS 폴백 (청크 ${i + 1}/${chunks.size})")
                prefetched.cancel()
                val remaining = chunks.drop(i).joinToString("")
                return@coroutineScope speakWithSystemTts(remaining)
            }

            _state.value = SpeakerState.Playing(i + 1, chunks.size)
            val played = filePlayer.play(file)
            if (played is AppResult.Failure) {
                crashReporter.log("tts: 재생 실패 (청크 ${i + 1}/${chunks.size}) ${played.error}")
                prefetched.cancel()
                return@coroutineScope played
            }
        }
        AppResult.success(Unit)
    }

    private suspend fun ensureChunkAudio(chunk: String): File? {
        val key = remoteTts.cacheKey(chunk)
        cache.get(key)?.let { return it }
        return when (val synth = remoteTts.synthesize(chunk)) {
            is AppResult.Success -> cache.put(key, synth.value)
            is AppResult.Failure -> null
        }
    }

    private suspend fun speakWithSystemTts(text: String): AppResult<Unit> =
        systemTts.speak(text)

    private companion object {
        /** 원격 합성 청크 길이 — TTS 모델 입력·지연·캐시 재사용의 균형점 */
        const val NATURAL_CHUNK_LENGTH = 500
    }
}
