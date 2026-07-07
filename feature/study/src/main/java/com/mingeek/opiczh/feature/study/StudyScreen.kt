package com.mingeek.opiczh.feature.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mingeek.opiczh.core.data.study.StudyRepository
import com.mingeek.opiczh.core.designsystem.component.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class StudyHomeViewModel @Inject constructor(
    studyRepository: StudyRepository,
) : ViewModel() {
    val dueCount: StateFlow<Int> = studyRepository.dueCountFlow(System.currentTimeMillis())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

@Composable
fun StudyScreen(
    onBack: () -> Unit,
    onTopicPractice: () -> Unit,
    onTemplates: () -> Unit,
    onSrsReview: () -> Unit,
    onFreeTalk: () -> Unit,
    viewModel: StudyHomeViewModel = hiltViewModel(),
) {
    val dueCount by viewModel.dueCount.collectAsStateWithLifecycle()

    ScreenScaffold(title = "학습", onBack = onBack) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StudyMenuCard(
                icon = Icons.Default.Forum,
                title = "주제별 실전 연습",
                description = "주제를 골라 문항 하나씩: 듣기 → 답변 녹음 → 즉시 AI 채점·교정",
                onClick = onTopicPractice,
            )
            StudyMenuCard(
                icon = Icons.Default.RecordVoiceOver,
                title = "만능 템플릿 · 쉐도잉",
                description = "IM2 필수 패턴 암기 + 원어민 음성 따라 말하고 발음·성조 피드백",
                onClick = onTemplates,
            )
            StudyMenuCard(
                icon = Icons.Default.Style,
                title = "복습 카드 (SRS)",
                description = "시험에서 교정받은 문장과 템플릿을 간격 반복으로 암기",
                onClick = onSrsReview,
                badgeCount = dueCount,
            )
            StudyMenuCard(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = "자유회화 (AI 교관)",
                description = "말하거나 입력해서 턴제 중국어 대화 + 실시간 교정 팁",
                onClick = onFreeTalk,
            )
        }
    }
}

@Composable
private fun StudyMenuCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    badgeCount: Int = 0,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (badgeCount > 0) {
                Badge { Text("$badgeCount") }
            }
        }
    }
}
