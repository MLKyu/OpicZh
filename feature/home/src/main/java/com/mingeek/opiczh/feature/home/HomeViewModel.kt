package com.mingeek.opiczh.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.opiczh.core.ai.stt.SttModelManager
import com.mingeek.opiczh.core.data.exam.ExamRepository
import com.mingeek.opiczh.core.data.settings.SettingsRepository
import com.mingeek.opiczh.core.data.study.StudyRepository
import com.mingeek.opiczh.core.model.AppSettings
import com.mingeek.opiczh.core.model.ExamStatus
import com.mingeek.opiczh.core.model.ExamSummary
import com.mingeek.opiczh.core.model.PendingGrading
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val settings: AppSettings = AppSettings(),
    /** 최근 채점 완료 세션 (최신순) */
    val recentSessions: List<ExamSummary> = emptyList(),
    val srsDueCount: Int = 0,
    /** 채점이 끝나지 않은 보관 세션 — 답변 녹음은 전부 저장돼 있다 */
    val pendingGrading: List<PendingGrading> = emptyList(),
    /** 음성 인식(STT) 모델 설치 여부 — 대기함 '임시 채점(기기)' 버튼 노출 조건 */
    val sttReady: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val examRepository: ExamRepository,
    studyRepository: StudyRepository,
    sttManager: SttModelManager,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        examRepository.sessionSummaries(),
        studyRepository.dueCountFlow(System.currentTimeMillis()),
        examRepository.pendingGradingFlow(),
        sttManager.installedFlow,
    ) { settings, sessions, dueCount, pending, sttReady ->
        HomeUiState(
            settings = settings,
            recentSessions = sessions
                .filter { it.status == ExamStatus.GRADED && it.overallGrade != null }
                .take(10),
            srsDueCount = dueCount,
            pendingGrading = pending,
            sttReady = sttReady,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    /** 채점 대기 세션을 명시적으로 버린다 (녹음·답변을 더는 채점하지 않을 때) */
    fun discardPendingSession(sessionId: String) {
        viewModelScope.launch { examRepository.abortSession(sessionId) }
    }
}
