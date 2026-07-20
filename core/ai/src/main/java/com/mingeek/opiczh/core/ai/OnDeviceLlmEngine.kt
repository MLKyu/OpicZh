package com.mingeek.opiczh.core.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.mingeek.opiczh.core.ai.ondevice.OnDeviceModelManager
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.AppTracer
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * LiteRT-LM(Gemma/Qwen .litertlm) 기반 온디바이스 엔진.
 * 다운로드된 모델 중 우선순위가 가장 높은 것을 GPU 백엔드로 로드한다.
 * 오디오 입력은 지원하지 않는다(전사·정밀 채점은 클라우드 담당).
 */
@Singleton
class OnDeviceLlmEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val modelManager: OnDeviceModelManager,
    private val tracer: AppTracer,
) : LlmEngine {

    override val id: LlmEngineId = LlmEngineId.ON_DEVICE

    private var engine: Engine? = null
    private var loadedModelPath: String? = null
    private val engineMutex = Mutex()

    override suspend fun isReady(): Boolean = modelManager.readyModelFile() != null

    override suspend fun generate(request: LlmRequest): AppResult<LlmReply> {
        if (request.parts.any { it is LlmPart.Audio }) {
            return AppResult.failure(
                AppError.OnDeviceUnavailable("온디바이스 엔진은 오디오 입력을 지원하지 않습니다. 전사·채점은 클라우드(Gemini)를 사용하세요."),
            )
        }
        val modelFile = modelManager.readyModelFile()
            ?: return AppResult.failure(
                AppError.OnDeviceUnavailable("다운로드된 온디바이스 모델이 없습니다. 설정에서 모델을 다운로드해 주세요."),
            )

        return tracer.trace("ondevice_generate", "model" to modelFile.name) {
            generateWithModel(modelFile, request)
        }
    }

    private suspend fun generateWithModel(
        modelFile: java.io.File,
        request: LlmRequest,
    ): AppResult<LlmReply> =
        withContext(Dispatchers.Default) {
            try {
                val loadedEngine = obtainEngine(modelFile.absolutePath)
                val prompt = buildPrompt(request)
                val config = ConversationConfig(
                    systemInstruction = request.systemPrompt?.let { Contents.of(it) },
                    samplerConfig = SamplerConfig(
                        topK = 40,
                        topP = 0.95,
                        temperature = (request.temperature ?: 0.7f).toDouble(),
                    ),
                )
                val text = loadedEngine.createConversation(config).use { conversation ->
                    val reply = conversation.sendMessage(prompt)
                    reply.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                }
                if (text.isBlank()) {
                    AppResult.failure(AppError.Parsing("온디바이스 모델이 빈 응답을 반환했습니다"))
                } else {
                    AppResult.success(LlmReply(text = text.trim()))
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                AppResult.failure(AppError.OnDeviceUnavailable("온디바이스 추론 실패: ${t.message}"))
            }
        }

    /** 메모리 확보가 필요할 때 (예: 시험 채점 전) 명시적으로 내릴 수 있다 */
    suspend fun unload() {
        engineMutex.withLock {
            runCatching { engine?.close() }
            engine = null
            loadedModelPath = null
        }
    }

    private suspend fun obtainEngine(modelPath: String): Engine = engineMutex.withLock {
        engine?.let { existing ->
            if (loadedModelPath == modelPath && existing.isInitialized()) return existing
            runCatching { existing.close() }
            engine = null
        }
        val created = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                cacheDir = context.cacheDir.absolutePath,
            ),
        )
        created.initialize()
        engine = created
        loadedModelPath = modelPath
        created
    }

    private fun buildPrompt(request: LlmRequest): String = buildString {
        request.parts.filterIsInstance<LlmPart.Text>().forEach { appendLine(it.text) }
        request.responseJsonSchema?.let { schema ->
            appendLine()
            appendLine("반드시 다음 JSON 스키마에 맞는 JSON만 출력하세요. 다른 텍스트는 금지:")
            appendLine(schema.toString())
        }
    }.trim()
}
