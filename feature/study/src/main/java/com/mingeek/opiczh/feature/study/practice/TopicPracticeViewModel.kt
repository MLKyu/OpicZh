package com.mingeek.opiczh.feature.study.practice

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.opiczh.core.ai.AnswerGrader
import com.mingeek.opiczh.core.common.onFailure
import com.mingeek.opiczh.core.common.onSuccess
import com.mingeek.opiczh.core.data.settings.SettingsRepository
import com.mingeek.opiczh.core.data.study.StudyRepository
import com.mingeek.opiczh.core.model.AnswerFeedback
import com.mingeek.opiczh.core.model.Question
import com.mingeek.opiczh.core.model.TargetGrade
import com.mingeek.opiczh.core.model.Topic
import com.mingeek.opiczh.core.speech.ChineseSpeaker
import com.mingeek.opiczh.core.speech.RecorderState
import com.mingeek.opiczh.core.speech.record.AnswerRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TopicPracticeUiState(
    val topics: List<Topic> = emptyList(),
    val selectedTopic: Topic? = null,
    val questions: List<Question> = emptyList(),
    val currentQuestion: Question? = null,
    val targetGrade: TargetGrade = TargetGrade.DEFAULT,
    val playing: Boolean = false,
    val recording: Boolean = false,
    val elapsedSec: Long = 0,
    val grading: Boolean = false,
    val feedback: AnswerFeedback? = null,
    val showQuestionText: Boolean = false,
    val error: String? = null,
)

/** 주제별 실전 연습: 문항 듣기 → 답변 녹음 → 즉시 채점·교정 */
@HiltViewModel
class TopicPracticeViewModel @Inject constructor(
    private val studyRepository: StudyRepository,
    private val settingsRepository: SettingsRepository,
    private val speaker: ChineseSpeaker,
    private val recorder: AnswerRecorder,
    private val grader: AnswerGrader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TopicPracticeUiState())
    val uiState: StateFlow<TopicPracticeUiState> = _uiState.asStateFlow()

    private var playJob: Job? = null
    private var tickerJob: Job? = null

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            _uiState.update {
                it.copy(
                    topics = studyRepository.allTopics(),
                    targetGrade = settings.targetGrade,
                )
            }
        }
        viewModelScope.launch {
            recorder.state.collect { s ->
                when (s) {
                    is RecorderState.Recording -> startTicker(s.startedElapsedRealtimeMs)
                    RecorderState.Idle -> stopTicker()
                }
            }
        }
    }

    fun selectTopic(topic: Topic) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedTopic = topic,
                    questions = studyRepository.questionsByTopic(topic.id),
                    currentQuestion = null,
                    feedback = null,
                )
            }
        }
    }

    fun selectQuestion(question: Question) {
        _uiState.update {
            it.copy(
                currentQuestion = question,
                feedback = null,
                showQuestionText = false,
                error = null,
            )
        }
        playQuestion()
    }

    fun backToTopics() {
        stopAll()
        _uiState.update {
            it.copy(selectedTopic = null, currentQuestion = null, feedback = null)
        }
    }

    fun backToQuestions() {
        stopAll()
        _uiState.update { it.copy(currentQuestion = null, feedback = null) }
    }

    fun toggleQuestionText() =
        _uiState.update { it.copy(showQuestionText = !it.showQuestionText) }

    fun playQuestion() {
        val question = _uiState.value.currentQuestion ?: return
        if (_uiState.value.playing) return
        playJob?.cancel()
        playJob = viewModelScope.launch {
            _uiState.update { it.copy(playing = true, error = null) }
            speaker.speak(question.zh, preferNatural = true)
                .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
            _uiState.update { it.copy(playing = false) }
        }
    }

    fun toggleRecording() {
        if (_uiState.value.recording) {
            val result = recorder.stop()
            _uiState.update { it.copy(recording = false) }
            result
                .onSuccess { file -> gradeAnswer(file) }
                .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
        } else {
            speaker.stop()
            playJob?.cancel()
            _uiState.update { it.copy(playing = false, feedback = null) }
            recorder.start(namePrefix = "practice")
                .onSuccess { _uiState.update { it.copy(recording = true, error = null) } }
                .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
        }
    }

    private fun gradeAnswer(file: File) {
        val question = _uiState.value.currentQuestion ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(grading = true, error = null) }
            grader.grade(question, file, _uiState.value.targetGrade)
                .onSuccess { feedback ->
                    _uiState.update { it.copy(feedback = feedback) }
                    // 교정 문장은 자동으로 복습 카드에 등록
                    studyRepository.addCorrectionCards(feedback.corrections, "주제연습")
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
            _uiState.update { it.copy(grading = false) }
        }
    }

    private fun startTicker(startedMs: Long) {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                _uiState.update {
                    it.copy(elapsedSec = (SystemClock.elapsedRealtime() - startedMs) / 1000)
                }
                delay(500)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
        _uiState.update { it.copy(elapsedSec = 0) }
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
