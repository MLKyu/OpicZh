package com.mingeek.opiczh.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.opiczh.core.data.exam.ExamRepository
import com.mingeek.opiczh.core.data.settings.SettingsRepository
import com.mingeek.opiczh.core.data.study.StudyRepository
import com.mingeek.opiczh.core.model.AppSettings
import com.mingeek.opiczh.core.model.ExamStatus
import com.mingeek.opiczh.core.model.ExamSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val settings: AppSettings = AppSettings(),
    /** 최근 채점 완료 세션 (최신순) */
    val recentSessions: List<ExamSummary> = emptyList(),
    val srsDueCount: Int = 0,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    examRepository: ExamRepository,
    studyRepository: StudyRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        examRepository.sessionSummaries(),
        studyRepository.dueCountFlow(System.currentTimeMillis()),
    ) { settings, sessions, dueCount ->
        HomeUiState(
            settings = settings,
            recentSessions = sessions
                .filter { it.status == ExamStatus.GRADED && it.overallGrade != null }
                .take(10),
            srsDueCount = dueCount,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )
}
