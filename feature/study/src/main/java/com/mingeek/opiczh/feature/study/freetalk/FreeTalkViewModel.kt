package com.mingeek.opiczh.feature.study.freetalk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.opiczh.core.ai.AiTask
import com.mingeek.opiczh.core.ai.AnswerTranscriber
import com.mingeek.opiczh.core.ai.LlmRequest
import com.mingeek.opiczh.core.ai.LlmRouter
import com.mingeek.opiczh.core.ai.stt.SttModelManager
import com.mingeek.opiczh.core.common.onFailure
import com.mingeek.opiczh.core.common.onSuccess
import com.mingeek.opiczh.core.data.settings.SettingsRepository
import com.mingeek.opiczh.core.model.RoutingPolicy
import com.mingeek.opiczh.core.speech.ChineseSpeaker
import com.mingeek.opiczh.core.speech.record.AnswerRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TalkRole { USER, COACH }

data class ChatTurn(val role: TalkRole, val text: String)

data class FreeTalkUiState(
    val turns: List<ChatTurn> = emptyList(),
    val input: String = "",
    val recording: Boolean = false,
    val transcribing: Boolean = false,
    val replying: Boolean = false,
    val speakReplies: Boolean = true,
    /** 음성 입력 가능 여부 — '온디바이스만' 모드에서도 STT 모델이 설치돼 있으면 true */
    val voiceInputAvailable: Boolean = true,
    val error: String? = null,
)

/** 자유회화: 말하거나(전사) 입력해서 AI 시험관과 턴제 중국어 대화 */
@HiltViewModel
class FreeTalkViewModel @Inject constructor(
    private val router: LlmRouter,
    private val transcriber: AnswerTranscriber,
    private val recorder: AnswerRecorder,
    private val speaker: ChineseSpeaker,
    private val settingsRepository: SettingsRepository,
    private val sttManager: SttModelManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FreeTalkUiState())
    val uiState: StateFlow<FreeTalkUiState> = _uiState.asStateFlow()

    private var speakJob: Job? = null

    init {
        // 코치의 첫 인사
        _uiState.update {
            it.copy(
                turns = listOf(
                    ChatTurn(
                        TalkRole.COACH,
                        "你好！我是你的中文口语教练。我们像OPIc考试一样聊天吧。今天过得怎么样？",
                    ),
                ),
            )
        }
        viewModelScope.launch {
            combine(settingsRepository.settings, sttManager.installedFlow) { settings, sttInstalled ->
                // 온디바이스 STT가 있으면 '온디바이스만' 모드에서도 음성 입력이 된다
                settings.routingPolicy != RoutingPolicy.ON_DEVICE_ONLY || sttInstalled
            }.collect { available ->
                _uiState.update { it.copy(voiceInputAvailable = available) }
            }
        }
    }

    fun onInputChange(value: String) = _uiState.update { it.copy(input = value) }

    fun toggleSpeakReplies() = _uiState.update { it.copy(speakReplies = !it.speakReplies) }

    fun sendTyped() {
        val text = _uiState.value.input.trim()
        if (text.isEmpty()) return
        _uiState.update { it.copy(input = "") }
        send(text)
    }

    fun toggleRecording() {
        if (!_uiState.value.voiceInputAvailable && !_uiState.value.recording) {
            _uiState.update {
                it.copy(
                    error = "'온디바이스만' 모드에서 음성 입력을 쓰려면 설정 > 음성 인식 모델을 다운로드하세요. " +
                        "지금은 텍스트로 입력해 주세요.",
                )
            }
            return
        }
        if (_uiState.value.recording) {
            val result = recorder.stop()
            _uiState.update { it.copy(recording = false) }
            result
                .onSuccess { file ->
                    viewModelScope.launch {
                        _uiState.update { it.copy(transcribing = true) }
                        transcriber.transcribe(file)
                            .onSuccess { result -> if (result.text.isNotBlank()) send(result.text) }
                            .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
                        _uiState.update { it.copy(transcribing = false) }
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
        } else {
            stopSpeaking()
            recorder.start(namePrefix = "freetalk")
                .onSuccess { _uiState.update { it.copy(recording = true, error = null) } }
                .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
        }
    }

    private fun send(userText: String) {
        _uiState.update {
            it.copy(turns = it.turns + ChatTurn(TalkRole.USER, userText), replying = true, error = null)
        }
        viewModelScope.launch {
            val target = settingsRepository.settings.first().targetGrade
            // 온디바이스 폴백(내장 Nano)은 입력 ~4천 토큰 제한 — 최근 턴만 컨텍스트로 보낸다
            val history = _uiState.value.turns.takeLast(MAX_HISTORY_TURNS).joinToString("\n") { turn ->
                when (turn.role) {
                    TalkRole.COACH -> "교관: ${turn.text}"
                    TalkRole.USER -> "학습자: ${turn.text}"
                }
            }
            val request = LlmRequest.text(
                prompt = """
                    지금까지의 대화:
                    $history

                    교관의 다음 응답을 작성하세요.
                """.trimIndent(),
                systemPrompt = """
                    당신은 OPIc 중국어 회화 교관입니다. 학습자의 목표 등급은 ${target.display}입니다.
                    규칙:
                    - 간체 중국어로 2~4문장, 목표 등급 수준의 어휘로 자연스럽게 대화를 이어간다
                    - 항상 후속 질문 하나로 끝내 학습자가 계속 말하게 한다
                    - 학습자의 직전 발화에 문법·표현 오류가 있으면 답변 마지막 줄에 "💡 " 뒤에 한국어 한 줄 교정 팁을 덧붙인다
                    - 교관 응답 텍스트만 출력한다 ("교관:" 같은 접두사 금지)
                """.trimIndent(),
            )
            router.generate(AiTask.CHAT, request)
                .onSuccess { reply ->
                    val text = reply.text.trim()
                    _uiState.update { it.copy(turns = it.turns + ChatTurn(TalkRole.COACH, text)) }
                    if (_uiState.value.speakReplies) speakReply(text)
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
            _uiState.update { it.copy(replying = false) }
        }
    }

    private fun speakReply(text: String) {
        // 교정 팁(한국어 줄)은 읽지 않고 중국어 본문만 재생
        val zhOnly = text.lineSequence()
            .filterNot { it.trimStart().startsWith("💡") }
            .joinToString("\n")
            .trim()
        if (zhOnly.isEmpty()) return
        speakJob?.cancel()
        speakJob = viewModelScope.launch {
            speaker.speak(zhOnly, preferNatural = true)
        }
    }

    fun stopSpeaking() {
        speakJob?.cancel()
        speakJob = null
        speaker.stop()
    }

    override fun onCleared() {
        stopSpeaking()
        if (_uiState.value.recording) recorder.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_HISTORY_TURNS = 12
    }
}
