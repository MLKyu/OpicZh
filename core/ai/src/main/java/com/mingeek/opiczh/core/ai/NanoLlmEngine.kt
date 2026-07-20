package com.mingeek.opiczh.core.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.AppTracer
import com.mingeek.opiczh.core.common.CrashReporter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** 설정 화면 표기용 내장 Nano 상태 */
enum class NanoStatus { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNSUPPORTED }

/** AICore 모델 준비(다운로드) 진행 상태 — totalBytes는 시작 콜백 전까지 null */
sealed interface NanoDownloadState {
    data class Running(val bytesDownloaded: Long, val totalBytes: Long? = null) : NanoDownloadState
    data object Completed : NanoDownloadState
    data class Failed(val message: String) : NanoDownloadState
}

/**
 * 기기 내장 Gemini Nano 엔진 (ML Kit GenAI Prompt API).
 * 모델 파일을 앱이 받아 관리하지 않는다 — AICore 시스템 서비스가 기기 차원에서 관리하며
 * 모든 앱이 공유한다(갤럭시 S26 울트라 = nano-v3). 오디오 입력은 미지원(전사·정밀 채점은
 * 클라우드 담당). 입력 약 4천 토큰 제한이 있어 장문 컨텍스트 작업에는 부적합.
 */
@Singleton
class NanoLlmEngine @Inject constructor(
    private val tracer: AppTracer,
    private val crashReporter: CrashReporter,
) : LlmEngine {

    override val id: LlmEngineId = LlmEngineId.NANO

    private val model: GenerativeModel by lazy { Generation.getClient() }

    /** AVAILABLE 확인 후에는 매 요청마다 AICore IPC로 재확인하지 않는다 */
    @Volatile
    private var knownAvailable = false

    override suspend fun isReady(): Boolean = knownAvailable || checkStatus() == NanoStatus.AVAILABLE

    /** AICore 상태 조회. 미지원 기기·AICore 부재 등 확인 실패는 UNSUPPORTED로 취급한다. */
    suspend fun checkStatus(): NanoStatus = withContext(Dispatchers.IO) {
        try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> {
                    knownAvailable = true
                    NanoStatus.AVAILABLE
                }
                FeatureStatus.DOWNLOADABLE -> NanoStatus.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> NanoStatus.DOWNLOADING
                else -> NanoStatus.UNSUPPORTED
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // AICore 부재뿐 아니라 일시 오류(바인딩 실패 등)도 여기로 온다 — 원인을 남겨 진단 가능하게
            crashReporter.log("nano: checkStatus 실패 — ${t.javaClass.simpleName}: ${t.message}")
            NanoStatus.UNSUPPORTED
        }
    }

    /** Nano 모델 준비 요청 — 다운로드·저장 모두 시스템(AICore) 몫이라 앱 저장공간을 쓰지 않는다 */
    fun download(): Flow<NanoDownloadState> = flow {
        emit(NanoDownloadState.Running(0))
        var totalBytes: Long? = null
        model.download().collect { status ->
            when (status) {
                is DownloadStatus.DownloadStarted -> {
                    totalBytes = status.bytesToDownload
                    emit(NanoDownloadState.Running(0, totalBytes))
                }
                is DownloadStatus.DownloadProgress ->
                    emit(NanoDownloadState.Running(status.totalBytesDownloaded, totalBytes))
                DownloadStatus.DownloadCompleted -> {
                    knownAvailable = true
                    emit(NanoDownloadState.Completed)
                }
                is DownloadStatus.DownloadFailed ->
                    emit(NanoDownloadState.Failed(status.e.message ?: "알 수 없는 오류"))
            }
        }
    }.catch { t -> emit(NanoDownloadState.Failed(t.message ?: "알 수 없는 오류")) }

    override suspend fun generate(request: LlmRequest): AppResult<LlmReply> {
        if (request.parts.any { it is LlmPart.Audio }) {
            return AppResult.failure(
                AppError.OnDeviceUnavailable("내장 Nano는 오디오 입력을 지원하지 않습니다. 전사·채점은 클라우드(Gemini)를 사용하세요."),
            )
        }
        if (!isReady()) {
            return AppResult.failure(AppError.OnDeviceUnavailable("내장 Nano가 아직 준비되지 않았습니다."))
        }
        return tracer.trace("nano_generate", "model" to MODEL_ID) {
            generateWithNano(request)
        }
    }

    private suspend fun generateWithNano(request: LlmRequest): AppResult<LlmReply> =
        withContext(Dispatchers.IO) {
            try {
                val prompt = buildPrompt(request)
                val genRequest = generateContentRequest(TextPart(prompt)) {
                    request.temperature?.let { temperature = it }
                    request.maxOutputTokens?.let { maxOutputTokens = it }
                }
                val text = model.generateContent(genRequest)
                    .candidates.firstOrNull()?.text.orEmpty()
                if (text.isBlank()) {
                    AppResult.failure(AppError.Parsing("내장 Nano가 빈 응답을 반환했습니다"))
                } else {
                    AppResult.success(LlmReply(text = text.trim(), modelId = MODEL_ID))
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                AppResult.failure(AppError.OnDeviceUnavailable("내장 Nano 추론 실패: ${t.message}"))
            }
        }

    // Prompt API 1.0.0-beta2에는 systemInstruction이 없어 시스템 프롬프트를 본문에 접두한다
    private fun buildPrompt(request: LlmRequest): String = buildString {
        request.systemPrompt?.let {
            appendLine(it)
            appendLine()
        }
        request.parts.filterIsInstance<LlmPart.Text>().forEach { appendLine(it.text) }
        request.responseJsonSchema?.let { schema ->
            appendLine()
            appendLine("반드시 다음 JSON 스키마에 맞는 JSON만 출력하세요. 다른 텍스트는 금지:")
            appendLine(schema.toString())
        }
    }.trim()

    companion object {
        const val MODEL_ID = "gemini-nano"
    }
}
