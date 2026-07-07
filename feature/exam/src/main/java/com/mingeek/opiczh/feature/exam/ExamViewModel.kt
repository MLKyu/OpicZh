package com.mingeek.opiczh.feature.exam

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.opiczh.core.ai.AnswerGrader
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.CrashReporter
import com.mingeek.opiczh.core.common.onFailure
import com.mingeek.opiczh.core.common.onSuccess
import com.mingeek.opiczh.core.data.exam.ExamRepository
import com.mingeek.opiczh.core.data.settings.SettingsRepository
import com.mingeek.opiczh.core.data.study.StudyRepository
import com.mingeek.opiczh.core.model.DifficultyAdjust
import com.mingeek.opiczh.core.model.ExamComposer
import com.mingeek.opiczh.core.model.ExamReport
import com.mingeek.opiczh.core.model.ExamReportAggregator
import com.mingeek.opiczh.core.model.GradedAnswer
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

enum class ExamStep { SETUP, RUNNING, MID_CHECK, GRADING, REPORT }

data class ExamUiState(
    val step: ExamStep = ExamStep.SETUP,
    // 설정 단계
    val topics: List<Topic> = emptyList(),
    val selectedTopicIds: Set<String> = emptySet(),
    val selfAssessment: Int = 3,
    val targetGrade: TargetGrade = TargetGrade.DEFAULT,
    // 진행 단계
    val questions: List<Question> = emptyList(),
    val currentIndex: Int = 0,
    val listensLeft: Int = 2,
    val questionPlaying: Boolean = false,
    val answering: Boolean = false,
    val answerElapsedSec: Long = 0,
    val amplitude: Float = 0f,
    val remainingExamSec: Long = EXAM_DURATION_SEC,
    val showExitDialog: Boolean = false,
    // 채점 단계
    val gradingDone: Int = 0,
    val gradingTotal: Int = 0,
    val gradingFailed: Int = 0,
    // 리포트 단계
    val report: ExamReport? = null,
    val gradedAnswers: List<GradedAnswer> = emptyList(),
    val error: String? = null,
) {
    val currentQuestion: Question? get() = questions.getOrNull(currentIndex)
    val recommendedSelfAssessment: IntRange get() = targetGrade.recommendedSelfAssessment
}

private const val EXAM_DURATION_SEC = 40L * 60

/** 실전 모의고사 상태 머신: 설정 → 진행(중간 난이도 재선택) → 채점 → 리포트 */
@HiltViewModel
class ExamViewModel @Inject constructor(
    private val examRepository: ExamRepository,
    private val settingsRepository: SettingsRepository,
    private val studyRepository: StudyRepository,
    private val speaker: ChineseSpeaker,
    private val recorder: AnswerRecorder,
    private val grader: AnswerGrader,
    private val crashReporter: CrashReporter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExamUiState())
    val uiState: StateFlow<ExamUiState> = _uiState.asStateFlow()

    private var sessionId: String? = null
    private var pool: List<Question> = emptyList()
    private var midCheckDone = false

    /** (answerId, question, audioPath) — 채점 대기 목록 */
    private val pendingAnswers = mutableListOf<Triple<String, Question, String?>>()

    private var examTimerJob: Job? = null
    private var recordTickerJob: Job? = null
    private var playJob: Job? = null

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val topics = examRepository.surveyTopics().filter { it.id != "self_intro" }
            _uiState.update {
                it.copy(
                    topics = topics,
                    targetGrade = settings.targetGrade,
                    selfAssessment = settings.targetGrade.recommendedSelfAssessment.first,
                    selectedTopicIds = topics.take(3).map { t -> t.id }.toSet(),
                )
            }
        }
        viewModelScope.launch {
            recorder.state.collect { s ->
                when (s) {
                    is RecorderState.Recording -> startRecordTicker(s.startedElapsedRealtimeMs)
                    RecorderState.Idle -> stopRecordTicker()
                }
            }
        }
    }

    // --- 설정 단계 ---

    fun toggleTopic(topicId: String) {
        _uiState.update {
            val selected = it.selectedTopicIds.toMutableSet()
            if (!selected.add(topicId)) selected.remove(topicId)
            it.copy(selectedTopicIds = selected)
        }
    }

    fun setSelfAssessment(level: Int) {
        _uiState.update { it.copy(selfAssessment = level.coerceIn(1, 6)) }
    }

    fun startExam() {
        val state = _uiState.value
        if (state.selectedTopicIds.size < 2) {
            _uiState.update { it.copy(error = "서베이 주제를 2개 이상 선택하세요.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            pool = examRepository.questionPool()
            val composition = ExamComposer.compose(
                pool = pool,
                surveyTopicIds = state.selectedTopicIds.toList(),
                target = state.targetGrade,
                randomSeed = System.currentTimeMillis(),
            )
            if (composition.questions.isEmpty()) {
                _uiState.update { it.copy(error = "문제은행에서 문항을 구성하지 못했습니다.") }
                return@launch
            }
            sessionId = examRepository.createSession(state.targetGrade, state.selfAssessment)
            pendingAnswers.clear()
            midCheckDone = false
            crashReporter.setKey("exam_active", "true")
            crashReporter.log(
                "exam:start topics=${state.selectedTopicIds.size} level=${state.selfAssessment} " +
                    "questions=${composition.questions.size}",
            )
            _uiState.update {
                it.copy(
                    step = ExamStep.RUNNING,
                    questions = composition.questions,
                    currentIndex = 0,
                    listensLeft = 2,
                    remainingExamSec = EXAM_DURATION_SEC,
                )
            }
            startExamTimer()
            playCurrentQuestion(auto = true)
        }
    }

    // --- 진행 단계 ---

    fun replayQuestion() = playCurrentQuestion(auto = false)

    private fun playCurrentQuestion(auto: Boolean) {
        val state = _uiState.value
        val question = state.currentQuestion ?: return
        if (state.listensLeft <= 0 || state.questionPlaying || state.answering) return

        playJob?.cancel()
        playJob = viewModelScope.launch {
            _uiState.update {
                it.copy(listensLeft = it.listensLeft - 1, questionPlaying = true, error = null)
            }
            speaker.speak(question.zh, preferNatural = true)
                .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
            _uiState.update { it.copy(questionPlaying = false) }
            // 다음 문항 오디오를 미리 합성해 둔다 (실패해도 무시 — 재생 시 폴백)
            _uiState.value.questions.getOrNull(_uiState.value.currentIndex + 1)?.let { next ->
                launch { speaker.prefetch(next.zh) }
            }
        }
    }

    fun startAnswer() {
        if (_uiState.value.answering) return
        speaker.stop()
        playJob?.cancel()
        _uiState.update { it.copy(questionPlaying = false) }
        recorder.start(namePrefix = "exam")
            .onSuccess { _uiState.update { it.copy(answering = true, error = null) } }
            .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
    }

    fun finishAnswer() {
        if (!_uiState.value.answering) return
        val result = recorder.stop()
        _uiState.update { it.copy(answering = false) }
        val audioPath = (result as? AppResult.Success<File>)?.value?.absolutePath
        saveAnswerAndAdvance(audioPath)
    }

    fun skipQuestion() {
        if (_uiState.value.answering) {
            recorder.cancel()
            _uiState.update { it.copy(answering = false) }
        }
        saveAnswerAndAdvance(audioPath = null)
    }

    private fun saveAnswerAndAdvance(audioPath: String?) {
        val state = _uiState.value
        val question = state.currentQuestion ?: return
        val session = sessionId ?: return
        viewModelScope.launch {
            val answerId = examRepository.saveAnswer(
                sessionId = session,
                orderIndex = state.currentIndex,
                question = question,
                audioPath = audioPath,
            )
            pendingAnswers.add(Triple(answerId, question, audioPath))

            val answeredCount = state.currentIndex + 1
            when {
                answeredCount >= state.questions.size -> startGrading()
                !midCheckDone && answeredCount == MID_CHECK_AFTER ->
                    _uiState.update { it.copy(step = ExamStep.MID_CHECK) }
                else -> moveToQuestion(state.currentIndex + 1)
            }
        }
    }

    fun chooseDifficulty(adjust: DifficultyAdjust) {
        midCheckDone = true
        val state = _uiState.value
        val answered = state.questions.take(state.currentIndex + 1)
        val remaining = state.questions.drop(state.currentIndex + 1)
        val adjusted = ExamComposer.adjustRemaining(
            pool = pool,
            answered = answered,
            remaining = remaining,
            adjust = adjust,
            randomSeed = System.currentTimeMillis(),
        )
        _uiState.update {
            it.copy(step = ExamStep.RUNNING, questions = answered + adjusted)
        }
        moveToQuestion(state.currentIndex + 1)
    }

    private fun moveToQuestion(index: Int) {
        _uiState.update {
            it.copy(currentIndex = index, listensLeft = 2, questionPlaying = false)
        }
        _uiState.value.currentQuestion?.let { q ->
            crashReporter.log("exam:q${index + 1}/${_uiState.value.questions.size} ${q.type.name}")
        }
        playCurrentQuestion(auto = true)
    }

    // --- 시험 타이머 ---

    private fun startExamTimer() {
        examTimerJob?.cancel()
        examTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val remaining = _uiState.value.remainingExamSec - 1
                _uiState.update { it.copy(remainingExamSec = remaining.coerceAtLeast(0)) }
                if (remaining <= 0) {
                    onTimeUp()
                    break
                }
            }
        }
    }

    private fun onTimeUp() {
        val state = _uiState.value
        if (state.step != ExamStep.RUNNING && state.step != ExamStep.MID_CHECK) return
        if (state.answering) {
            val result = recorder.stop()
            val audioPath = (result as? AppResult.Success<File>)?.value?.absolutePath
            val question = state.currentQuestion
            val session = sessionId
            if (question != null && session != null) {
                viewModelScope.launch {
                    val answerId = examRepository.saveAnswer(
                        session, state.currentIndex, question, audioPath,
                    )
                    pendingAnswers.add(Triple(answerId, question, audioPath))
                    startGrading()
                }
                return
            }
        }
        viewModelScope.launch { startGrading() }
    }

    // --- 채점 단계 ---

    private suspend fun startGrading() {
        examTimerJob?.cancel()
        speaker.stop()
        val session = sessionId ?: return
        val target = _uiState.value.targetGrade
        crashReporter.log("exam:grading answers=${pendingAnswers.size}")
        _uiState.update {
            it.copy(
                step = ExamStep.GRADING,
                answering = false,
                gradingDone = 0,
                gradingTotal = pendingAnswers.size,
                gradingFailed = 0,
            )
        }

        val graded = mutableListOf<GradedAnswer>()
        pendingAnswers.forEachIndexed { index, (answerId, question, audioPath) ->
            val feedback = if (audioPath == null) {
                AppResult.success(grader.skippedFeedback())
            } else {
                grader.grade(question, File(audioPath), target)
            }
            when (feedback) {
                is AppResult.Success -> {
                    examRepository.saveAnswerFeedback(answerId, feedback.value)
                    graded.add(GradedAnswer(index, question, feedback.value))
                }
                is AppResult.Failure ->
                    _uiState.update { it.copy(gradingFailed = it.gradingFailed + 1) }
            }
            _uiState.update { it.copy(gradingDone = index + 1) }
        }

        if (graded.isEmpty()) {
            _uiState.update {
                it.copy(
                    step = ExamStep.RUNNING,
                    error = "채점에 모두 실패했습니다. 네트워크와 API 키를 확인한 뒤 다시 시도하세요.",
                )
            }
            return
        }

        val report = ExamReportAggregator.aggregate(graded, target)
        examRepository.completeSession(session, report)
        crashReporter.setKey("exam_active", "false")
        crashReporter.log("exam:report grade=${report.overallGrade.name} failed=${_uiState.value.gradingFailed}")
        // 교정받은 문장은 복습 카드로 자동 등록
        runCatching {
            studyRepository.addCorrectionCards(
                graded.flatMap { it.feedback.corrections },
                sourceTag = "모의고사",
            )
        }
        _uiState.update {
            it.copy(step = ExamStep.REPORT, report = report, gradedAnswers = graded)
        }
    }

    // --- 종료/정리 ---

    fun requestExit() = _uiState.update { it.copy(showExitDialog = true) }

    fun dismissExit() = _uiState.update { it.copy(showExitDialog = false) }

    fun confirmExit(onExited: () -> Unit) {
        viewModelScope.launch {
            cleanupRun()
            sessionId?.let { examRepository.abortSession(it) }
            crashReporter.setKey("exam_active", "false")
            crashReporter.log("exam:abort q${_uiState.value.currentIndex + 1}")
            onExited()
        }
    }

    private fun cleanupRun() {
        examTimerJob?.cancel()
        playJob?.cancel()
        if (_uiState.value.answering) recorder.cancel()
        speaker.stop()
    }

    private fun startRecordTicker(startedMs: Long) {
        recordTickerJob?.cancel()
        recordTickerJob = viewModelScope.launch {
            while (isActive) {
                _uiState.update {
                    it.copy(
                        answerElapsedSec = (SystemClock.elapsedRealtime() - startedMs) / 1000,
                        amplitude = (recorder.pollMaxAmplitude() / 32767f).coerceIn(0f, 1f),
                    )
                }
                delay(200)
            }
        }
    }

    private fun stopRecordTicker() {
        recordTickerJob?.cancel()
        recordTickerJob = null
        _uiState.update { it.copy(answerElapsedSec = 0, amplitude = 0f) }
    }

    override fun onCleared() {
        cleanupRun()
        super.onCleared()
    }

    private companion object {
        /** 7번째 문항 답변 후 난이도 재선택 (실전 규칙) */
        const val MID_CHECK_AFTER = 7
    }
}
