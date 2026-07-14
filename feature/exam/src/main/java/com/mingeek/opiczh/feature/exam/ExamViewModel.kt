package com.mingeek.opiczh.feature.exam

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.opiczh.core.ai.AnswerGrader
import com.mingeek.opiczh.core.common.AppError
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
import kotlinx.coroutines.withTimeoutOrNull

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
    /** 지금까지 저장된 답변 수 (종료 대화상자의 보존 안내용) */
    val answeredCount: Int = 0,
    // 채점 단계
    val gradingDone: Int = 0,
    val gradingTotal: Int = 0,
    val gradingFailed: Int = 0,
    /** 한도 대기 등 채점 중 안내 문구 */
    val gradingNotice: String? = null,
    /** 채점이 하나도 성공하지 못했을 때의 오류 (다시 채점 버튼 노출) */
    val gradingError: String? = null,
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
            gradedByIndex.clear()
            correctionsSubmitted.clear()
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
            _uiState.update { it.copy(answeredCount = pendingAnswers.size) }

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
                    _uiState.update { it.copy(answeredCount = pendingAnswers.size) }
                    startGrading()
                }
                return
            }
        }
        startGrading()
    }

    // --- 채점 단계 ---

    /** 채점 완료된 문항 (재채점 시 성공분은 다시 호출하지 않는다) */
    private val gradedByIndex = linkedMapOf<Int, GradedAnswer>()

    /** 교정 카드를 이미 SRS에 보낸 문항 (재채점 리포트 갱신 시 중복 등록 방지) */
    private val correctionsSubmitted = mutableSetOf<Int>()

    private var gradingJob: Job? = null

    private fun startGrading() {
        // 마지막 답변 저장과 시험 타이머 만료가 동시에 도착해도 채점은 한 번만 시작한다
        if (gradingJob?.isActive == true) return
        examTimerJob?.cancel()
        speaker.stop()
        crashReporter.log("exam:grading answers=${pendingAnswers.size} resumed=${gradedByIndex.size}")
        gradingJob = viewModelScope.launch { runGrading() }
    }

    /**
     * 홈 '채점 대기함' 재진입: 저장된 답변·문항·기존 채점을 DB에서 복원해
     * 미채점 문항만 이어서 채점한다. 앱이 죽었든 한도로 실패했든 답변은 유실되지 않는다.
     */
    fun resumeGrading(resumeSessionId: String) {
        if (sessionId != null || _uiState.value.step != ExamStep.SETUP) return
        sessionId = resumeSessionId // 동기 선점 — LaunchedEffect 재실행 등 중복 진입 방지
        viewModelScope.launch {
            val target = examRepository.sessionTargetGrade(resumeSessionId)
            val stored = examRepository.sessionAnswers(resumeSessionId)
            if (target == null || stored.isEmpty()) {
                sessionId = null
                _uiState.update { it.copy(error = "이어서 채점할 답변을 찾지 못했습니다.") }
                return@launch
            }
            pendingAnswers.clear()
            gradedByIndex.clear()
            correctionsSubmitted.clear()
            stored.forEachIndexed { index, answer ->
                pendingAnswers.add(Triple(answer.answerId, answer.question, answer.audioPath))
                answer.feedback?.let { feedback ->
                    gradedByIndex[index] = GradedAnswer(index, answer.question, feedback)
                    // 이전 실행에서 교정 카드가 이미 등록됐을 수 있다 — 중복 등록 방지
                    correctionsSubmitted.add(index)
                }
            }
            _uiState.update {
                it.copy(
                    targetGrade = target,
                    questions = stored.map { a -> a.question },
                    answeredCount = stored.size,
                )
            }
            crashReporter.setKey("exam_active", "true")
            crashReporter.log("exam:resume answers=${stored.size} graded=${gradedByIndex.size}")
            startGrading()
        }
    }

    /** 실패 문항만 다시 채점 (채점 실패 화면·리포트의 '다시 채점' 버튼) */
    fun retryFailedGrading() {
        if (gradingJob?.isActive == true) return
        gradingJob = viewModelScope.launch { runGrading() }
    }

    private suspend fun runGrading() {
        val session = sessionId ?: return
        val target = _uiState.value.targetGrade
        _uiState.update {
            it.copy(
                step = ExamStep.GRADING,
                answering = false,
                gradingDone = gradedByIndex.size,
                gradingTotal = pendingAnswers.size,
                gradingFailed = 0,
                gradingNotice = null,
                gradingError = null,
                error = null,
            )
        }

        var lastError = gradingPass(target)

        // 분당 한도(RPM)에 걸린 실패는 창이 다시 열린 뒤 자동으로 한 번 더 시도한다.
        // 일일 한도(긴 대기)는 기다려도 소용없으므로 리포트로 넘기고 수동 재채점에 맡긴다.
        val missing = pendingAnswers.size - gradedByIndex.size
        val rateLimit = lastError as? AppError.RateLimited
        val hintedWaitSec = rateLimit?.retryAfterSec
        if (missing > 0 && rateLimit != null &&
            (hintedWaitSec == null || hintedWaitSec <= AUTO_RETRY_MAX_WAIT_SEC)
        ) {
            val waitSec = (hintedWaitSec ?: DEFAULT_AUTO_RETRY_WAIT_SEC) + 1
            crashReporter.log("exam:grading auto-retry missing=$missing wait=${waitSec}s")
            _uiState.update {
                it.copy(
                    gradingNotice = "무료 한도 대기 중… 약 ${waitSec}초 후 실패한 ${missing}개 문항을 다시 채점합니다",
                )
            }
            delay(waitSec * 1_000L)
            _uiState.update {
                it.copy(gradingNotice = null, gradingFailed = 0, gradingDone = gradedByIndex.size)
            }
            lastError = gradingPass(target)
        }

        if (gradedByIndex.isEmpty()) {
            _uiState.update {
                it.copy(
                    gradingError = lastError?.userMessageKo()
                        ?: "채점에 실패했습니다. 네트워크와 API 키를 확인한 뒤 다시 시도하세요.",
                )
            }
            return
        }

        val graded = gradedByIndex.values.sortedBy { it.orderIndex }
        val report = ExamReportAggregator.aggregate(graded, target)
        examRepository.completeSession(session, report)
        crashReporter.setKey("exam_active", "false")
        crashReporter.log(
            "exam:report grade=${report.overallGrade.name} failed=${pendingAnswers.size - graded.size}",
        )
        // 교정받은 문장은 복습 카드로 자동 등록 (재채점 시 새로 성공한 문항만)
        val newCorrections = graded
            .filter { it.orderIndex !in correctionsSubmitted }
            .onEach { correctionsSubmitted.add(it.orderIndex) }
            .flatMap { it.feedback.corrections }
        if (newCorrections.isNotEmpty()) {
            runCatching {
                studyRepository.addCorrectionCards(newCorrections, sourceTag = "모의고사")
            }
        }
        _uiState.update {
            it.copy(
                step = ExamStep.REPORT,
                report = report,
                gradedAnswers = graded,
                gradingFailed = pendingAnswers.size - graded.size,
                gradingError = null,
            )
        }
    }

    /** 아직 채점되지 않은 문항만 순회. 마지막 실패 원인을 반환한다. */
    private suspend fun gradingPass(target: TargetGrade): AppError? {
        var lastError: AppError? = null
        var failed = 0
        pendingAnswers.forEachIndexed { index, (answerId, question, audioPath) ->
            if (gradedByIndex.containsKey(index)) return@forEachIndexed
            val feedback = if (audioPath == null) {
                AppResult.success(grader.skippedFeedback())
            } else {
                // 문항당 하드 타임아웃 — 어떤 재시도·대기 조합도 채점 화면을
                // 한 문항에 이 이상 붙잡아 둘 수 없다 (녹음은 보존, '다시 채점'으로 복구)
                withTimeoutOrNull(GRADE_TIMEOUT_MS) {
                    grader.grade(question, File(audioPath), target)
                } ?: AppResult.failure(
                    AppError.Unknown("채점 응답 지연 (문항당 ${GRADE_TIMEOUT_MS / 60_000}분 초과)"),
                )
            }
            when (feedback) {
                is AppResult.Success -> {
                    examRepository.saveAnswerFeedback(answerId, feedback.value)
                    gradedByIndex[index] = GradedAnswer(index, question, feedback.value)
                }
                is AppResult.Failure -> {
                    lastError = feedback.error
                    failed++
                }
            }
            _uiState.update {
                it.copy(gradingDone = gradedByIndex.size + failed, gradingFailed = failed)
            }
        }
        return lastError
    }

    // --- 종료/정리 ---

    fun requestExit() = _uiState.update { it.copy(showExitDialog = true) }

    fun dismissExit() = _uiState.update { it.copy(showExitDialog = false) }

    fun confirmExit(onExited: () -> Unit) {
        viewModelScope.launch {
            cleanupRun()
            val session = sessionId
            when {
                session == null -> Unit
                // 답변이 하나도 없으면 보관할 게 없다 — 세션 폐기
                pendingAnswers.isEmpty() -> {
                    examRepository.abortSession(session)
                    crashReporter.log("exam:abort q${_uiState.value.currentIndex + 1}")
                }
                // 답변이 있으면 세션 보존 — 홈 '채점 대기함'에서 언제든 이어서 채점
                else -> crashReporter.log("exam:exit-preserved answers=${pendingAnswers.size}")
            }
            crashReporter.setKey("exam_active", "false")
            onExited()
        }
    }

    private fun cleanupRun() {
        examTimerJob?.cancel()
        playJob?.cancel()
        gradingJob?.cancel()
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

        /** 이보다 긴 429 대기는 일일 한도로 보고 자동 재시도하지 않는다 */
        const val AUTO_RETRY_MAX_WAIT_SEC = 90

        /** 429가 대기시간을 알려주지 않았을 때: 분당 창이 확실히 새로 열릴 때까지 */
        const val DEFAULT_AUTO_RETRY_WAIT_SEC = 65

        /** 문항 하나 채점(업로드+추론+재시도 전부)의 벽시계 상한 */
        const val GRADE_TIMEOUT_MS = 3 * 60_000L
    }
}
