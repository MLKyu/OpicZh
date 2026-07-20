package com.mingeek.opiczh.feature.study.template

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.opiczh.core.ai.PronunciationCoach
import com.mingeek.opiczh.core.common.onFailure
import com.mingeek.opiczh.core.common.onSuccess
import com.mingeek.opiczh.core.data.settings.SettingsRepository
import com.mingeek.opiczh.core.data.study.StudyRepository
import com.mingeek.opiczh.core.model.RoutingPolicy
import com.mingeek.opiczh.core.model.StudyTemplate
import com.mingeek.opiczh.core.speech.ChineseSpeaker
import com.mingeek.opiczh.core.speech.record.AnswerRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TemplateShadowUiState(
    val templates: List<StudyTemplate> = emptyList(),
    val selected: StudyTemplate? = null,
    val playing: Boolean = false,
    val recording: Boolean = false,
    val coaching: Boolean = false,
    val coachFeedback: String? = null,
    /** 발음 코치(음성 분석)는 클라우드 전용 — '온디바이스만' 모드에선 false */
    val coachAvailable: Boolean = true,
    val addedToSrs: Boolean = false,
    val error: String? = null,
)

/** 만능 템플릿 학습 + 쉐도잉(따라 말하기 → 발음·성조 코칭) */
@HiltViewModel
class TemplateShadowViewModel @Inject constructor(
    private val studyRepository: StudyRepository,
    private val settingsRepository: SettingsRepository,
    private val speaker: ChineseSpeaker,
    private val recorder: AnswerRecorder,
    private val coach: PronunciationCoach,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TemplateShadowUiState())
    val uiState: StateFlow<TemplateShadowUiState> = _uiState.asStateFlow()

    private var playJob: Job? = null

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(templates = studyRepository.templates()) }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update {
                    it.copy(coachAvailable = settings.routingPolicy != RoutingPolicy.ON_DEVICE_ONLY)
                }
            }
        }
    }

    fun select(template: StudyTemplate?) {
        stopAll()
        _uiState.update {
            it.copy(
                selected = template,
                coachFeedback = null,
                addedToSrs = false,
                error = null,
            )
        }
    }

    fun play() {
        val template = _uiState.value.selected ?: return
        playJob?.cancel()
        playJob = viewModelScope.launch {
            _uiState.update { it.copy(playing = true, error = null) }
            speaker.speak(template.zh, preferNatural = true)
                .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
            _uiState.update { it.copy(playing = false) }
        }
    }

    fun toggleShadowing() {
        val template = _uiState.value.selected ?: return
        if (_uiState.value.recording) {
            val result = recorder.stop()
            _uiState.update { it.copy(recording = false) }
            result
                .onSuccess { file ->
                    viewModelScope.launch {
                        _uiState.update { it.copy(coaching = true, coachFeedback = null) }
                        coach.coach(template.zh, file)
                            .onSuccess { fb -> _uiState.update { it.copy(coachFeedback = fb) } }
                            .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
                        _uiState.update { it.copy(coaching = false) }
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
        } else {
            speaker.stop()
            playJob?.cancel()
            _uiState.update { it.copy(playing = false) }
            recorder.start(namePrefix = "shadow")
                .onSuccess { _uiState.update { it.copy(recording = true, error = null) } }
                .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
        }
    }

    fun addToSrs() {
        val template = _uiState.value.selected ?: return
        viewModelScope.launch {
            studyRepository.addTemplateCard(template)
            _uiState.update { it.copy(addedToSrs = true) }
        }
    }

    private fun stopAll() {
        playJob?.cancel()
        speaker.stop()
        if (_uiState.value.recording) recorder.cancel()
        _uiState.update { it.copy(playing = false, recording = false) }
    }

    override fun onCleared() {
        stopAll()
        super.onCleared()
    }
}
