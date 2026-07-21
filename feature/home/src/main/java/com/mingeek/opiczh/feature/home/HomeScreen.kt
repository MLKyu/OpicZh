package com.mingeek.opiczh.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mingeek.opiczh.core.designsystem.component.ErrorBanner
import com.mingeek.opiczh.core.designsystem.component.SectionCard
import com.mingeek.opiczh.core.designsystem.component.TargetGradeBadge
import com.mingeek.opiczh.core.model.ExamSummary
import com.mingeek.opiczh.core.model.OpicGrade
import com.mingeek.opiczh.core.model.PendingGrading
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    onStartExam: () -> Unit,
    onResumeGrading: (sessionId: String) -> Unit,
    onProvisionalGrading: (sessionId: String) -> Unit,
    onStudy: () -> Unit,
    onSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(text = "OPIc 中文", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "실전처럼 연습하고, AI 피드백으로 합격까지",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TargetGradeBadge(target = uiState.settings.targetGrade)
            }

            if (!uiState.settings.hasApiKey) {
                ErrorBanner(
                    message = "Gemini API 키가 없습니다. AI 채점·피드백을 쓰려면 키를 등록하세요.",
                    actionLabel = "설정",
                    onAction = onSettings,
                )
            }

            if (uiState.pendingGrading.isNotEmpty()) {
                PendingGradingSection(
                    pending = uiState.pendingGrading,
                    sttReady = uiState.sttReady,
                    onResume = onResumeGrading,
                    onProvisional = onProvisionalGrading,
                    onDiscard = viewModel::discardPendingSession,
                )
            }

            if (uiState.recentSessions.isNotEmpty()) {
                GradeTrendSection(uiState.recentSessions, uiState.settings.targetGrade.gradeFloor)
            }

            MenuCard(
                icon = Icons.Default.Mic,
                title = "실전 모의고사",
                description = "서베이 → Self-Assessment → 12~15문항, 실제 시험과 동일한 흐름",
                onClick = onStartExam,
            )
            MenuCard(
                icon = Icons.Default.School,
                title = "학습",
                description = "주제별 연습 · 템플릿·쉐도잉 · 복습 카드 · 자유회화",
                onClick = onStudy,
                badgeCount = uiState.srsDueCount,
            )
            MenuCard(
                icon = Icons.Default.Settings,
                title = "설정",
                description = "API 키 · 목표 등급 · AI 모델 · 온디바이스 모델 · 음성 점검",
                onClick = onSettings,
            )
        }
    }
}

/**
 * 채점이 끝나지 않은 시험 보관함. 답변 녹음은 전부 저장돼 있으므로
 * 채점이 실패했든 앱이 죽었든 여기서 언제든 이어서 채점할 수 있다.
 */
@Composable
private fun PendingGradingSection(
    pending: List<PendingGrading>,
    sttReady: Boolean,
    onResume: (String) -> Unit,
    onProvisional: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    val dateFormat = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
    SectionCard(title = "채점 대기함") {
        Text(
            text = "답변 녹음이 보관된 시험입니다. 무료 한도가 풀린 뒤 이어서 채점하세요." +
                if (sttReady) " 기다리기 싫다면 온디바이스 임시 채점(발음·유창성 제외)도 가능합니다." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        pending.take(3).forEach { session ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = dateFormat.format(Date(session.startedAtEpochMs)),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "답변 ${session.answerCount}개 보관 중 · ${session.gradedCount}개 채점됨" +
                                if (session.provisionalCount > 0) " · 임시 ${session.provisionalCount}개" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onDiscard(session.sessionId) }) { Text("버리기") }
                    Button(onClick = { onResume(session.sessionId) }) { Text("이어서 채점") }
                }
                if (sttReady && session.gradedCount + session.provisionalCount < session.answerCount) {
                    TextButton(onClick = { onProvisional(session.sessionId) }) {
                        Text("지금 임시 채점 (기기에서, 발음 제외)")
                    }
                }
            }
        }
        if (pending.size > 3) {
            Text(
                text = "외 ${pending.size - 3}개 세션이 더 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 최근 모의고사 예상 등급 추이 (간단 막대) */
@Composable
private fun GradeTrendSection(sessions: List<ExamSummary>, targetFloor: OpicGrade) {
    val dateFormat = SimpleDateFormat("M/d", Locale.KOREA)
    val chronological = sessions.reversed() // 과거 → 최신

    SectionCard(title = "예상 등급 추이") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            chronological.takeLast(8).forEach { session ->
                val grade = session.overallGrade ?: return@forEach
                val reached = grade.rank >= targetFloor.rank
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(grade.display, style = MaterialTheme.typography.labelSmall)
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height((10 + grade.rank * 9).dp)
                            .background(
                                color = if (reached) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
                            ),
                    )
                    Text(
                        text = dateFormat.format(Date(session.startedAtEpochMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val latest = sessions.firstOrNull()?.overallGrade
        if (latest != null) {
            Text(
                text = if (latest.rank >= targetFloor.rank) {
                    "최근 시험이 목표(${targetFloor.display}) 수준에 도달했어요. 꾸준히 유지하세요!"
                } else {
                    "목표 ${targetFloor.display}까지 ${(targetFloor.rank - latest.rank)}단계. 약점 위주로 반복하세요."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MenuCard(
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
