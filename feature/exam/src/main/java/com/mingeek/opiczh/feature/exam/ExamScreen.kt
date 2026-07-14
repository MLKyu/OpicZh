package com.mingeek.opiczh.feature.exam

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.mingeek.opiczh.core.designsystem.component.AnswerFeedbackDetail
import com.mingeek.opiczh.core.designsystem.component.ErrorBanner
import com.mingeek.opiczh.core.designsystem.component.GradeBadge
import com.mingeek.opiczh.core.designsystem.component.KeepScreenOn
import com.mingeek.opiczh.core.designsystem.component.ScreenScaffold
import com.mingeek.opiczh.core.designsystem.component.SectionCard
import com.mingeek.opiczh.core.model.DifficultyAdjust
import com.mingeek.opiczh.core.model.GradedAnswer
import com.mingeek.opiczh.core.model.RubricAxis

@Composable
fun ExamScreen(
    onBack: () -> Unit,
    resumeSessionId: String? = null,
    viewModel: ExamViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 홈 '채점 대기함'에서 진입한 경우 — 저장된 답변으로 곧장 채점을 이어간다
    LaunchedEffect(resumeSessionId) {
        resumeSessionId?.let(viewModel::resumeGrading)
    }

    // 시험 중 실수로 뒤로가기 → 종료 확인
    BackHandler(enabled = uiState.step == ExamStep.RUNNING || uiState.step == ExamStep.MID_CHECK) {
        viewModel.requestExit()
    }

    when (uiState.step) {
        ExamStep.SETUP -> ExamSetupContent(uiState, viewModel, onBack)
        ExamStep.RUNNING, ExamStep.MID_CHECK -> ExamRunContent(uiState, viewModel, onBack)
        ExamStep.GRADING -> ExamGradingContent(uiState, viewModel)
        ExamStep.REPORT -> ExamReportContent(uiState, viewModel, onBack)
    }

    if (uiState.showExitDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissExit,
            title = { Text("시험을 종료할까요?") },
            text = {
                Text(
                    if (uiState.answeredCount > 0) {
                        "지금까지 답변한 ${uiState.answeredCount}개 문항은 버려지지 않아요.\n" +
                            "홈의 '채점 대기함'에서 언제든 이어서 채점할 수 있습니다."
                    } else {
                        "지금 종료하면 이번 모의고사는 채점되지 않습니다."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmExit(onBack) }) { Text("종료") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissExit) { Text("계속 응시") }
            },
        )
    }
}

// --- 1단계: 시험 구성 ---

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ExamSetupContent(
    uiState: ExamUiState,
    viewModel: ExamViewModel,
    onBack: () -> Unit,
) {
    val permissions = rememberMultiplePermissionsState(
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        },
    )

    ScreenScaffold(title = "실전 모의고사", onBack = onBack) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            uiState.error?.let { ErrorBanner(message = it) }

            SectionCard(title = "Background Survey — 주제 선택 (2개 이상)") {
                Text(
                    text = "실전처럼 잘 아는 주제만 고르세요. 선택한 주제에서 콤보 문항이 출제됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        uiState.topics.chunked(2).forEach { rowTopics ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowTopics.forEach { topic ->
                                    FilterChip(
                                        selected = topic.id in uiState.selectedTopicIds,
                                        onClick = { viewModel.toggleTopic(topic.id) },
                                        label = { Text(topic.nameKo) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SectionCard(title = "Self-Assessment — 난이도 선택") {
                Text(
                    text = "목표 ${uiState.targetGrade.display} 기준 권장: " +
                        "${uiState.recommendedSelfAssessment.first}~${uiState.recommendedSelfAssessment.last}단계",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    (1..6).forEach { level ->
                        SegmentedButton(
                            selected = uiState.selfAssessment == level,
                            onClick = { viewModel.setSelfAssessment(level) },
                            shape = SegmentedButtonDefaults.itemShape(index = level - 1, count = 6),
                        ) { Text("$level") }
                    }
                }
            }

            SectionCard(title = "시험 안내") {
                Text(
                    text = "· 총 12~15문항, 제한시간 40분\n" +
                        "· 각 질문은 최대 2회까지 들을 수 있습니다\n" +
                        "· 7번 문항 후 난이도를 다시 선택합니다 (실전 동일)\n" +
                        "· 질문이 끝나면 [답변 시작]을 눌러 말하고, 끝나면 [답변 완료]",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (!permissions.allPermissionsGranted) {
                Button(
                    onClick = { permissions.launchMultiplePermissionRequest() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("마이크 권한 허용") }
            } else {
                Button(
                    onClick = viewModel::startExam,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.selectedTopicIds.size >= 2,
                ) { Text("시험 시작") }
            }
        }
    }
}

// --- 2단계: 시험 진행 ---

@Composable
private fun ExamRunContent(
    uiState: ExamUiState,
    viewModel: ExamViewModel,
    onBack: () -> Unit,
) {
    KeepScreenOn()

    ScreenScaffold(title = "문항 ${uiState.currentIndex + 1} / ${uiState.questions.size}") { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 남은 시간
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "남은 시간 ${formatMmSs(uiState.remainingExamSec)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (uiState.remainingExamSec < 300) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                TextButton(onClick = viewModel::requestExit) { Text("종료") }
            }
            LinearProgressIndicator(
                progress = {
                    (uiState.currentIndex.toFloat() / uiState.questions.size).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth(),
            )

            uiState.error?.let { ErrorBanner(message = it) }

            // 질문 카드 (실전처럼 텍스트는 숨기고 음성으로만)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Hearing,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = if (uiState.questionPlaying) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = when {
                            uiState.questionPlaying -> "질문을 듣고 있습니다…"
                            uiState.answering -> "답변 중입니다"
                            else -> "질문을 들은 뒤 답변을 시작하세요"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(
                        onClick = viewModel::replayQuestion,
                        enabled = uiState.listensLeft > 0 && !uiState.questionPlaying && !uiState.answering,
                    ) {
                        Icon(Icons.Default.Replay, contentDescription = null)
                        Text(" 다시 듣기 (${uiState.listensLeft}회 남음)")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 답변 컨트롤
            if (uiState.answering) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = formatMmSs(uiState.answerElapsedSec),
                        style = MaterialTheme.typography.displaySmall,
                    )
                    LinearProgressIndicator(
                        progress = { uiState.amplitude },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = viewModel::finishAnswer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Text(" 답변 완료")
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = viewModel::startAnswer,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.questionPlaying,
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Text(" 답변 시작")
                    }
                    OutlinedButton(
                        onClick = viewModel::skipQuestion,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null)
                        Text(" 이 문항 건너뛰기")
                    }
                }
            }
        }
    }

    if (uiState.step == ExamStep.MID_CHECK) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("난이도 재선택") },
            text = { Text("지금까지의 문제와 비교해 남은 문제의 난이도를 선택하세요. (실전 동일 단계)") },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DifficultyAdjust.entries.forEach { adjust ->
                        TextButton(onClick = { viewModel.chooseDifficulty(adjust) }) {
                            Text(adjust.ko)
                        }
                    }
                }
            },
        )
    }
}

// --- 3단계: 채점 중 ---

@Composable
private fun ExamGradingContent(uiState: ExamUiState, viewModel: ExamViewModel) {
    KeepScreenOn()
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (uiState.gradingError != null) {
                ErrorBanner(message = uiState.gradingError)
                Button(
                    onClick = viewModel::retryFailedGrading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("다시 채점") }
                OutlinedButton(
                    onClick = viewModel::requestExit,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("시험 종료") }
                Text(
                    text = "답변 녹음은 모두 저장되어 있습니다. 한도가 풀린 뒤 다시 채점을 누르거나, " +
                        "지금 나가도 홈의 '채점 대기함'에서 언제든 이어서 채점할 수 있어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(56.dp))
                Text(
                    text = "AI 채점 중… (${uiState.gradingDone}/${uiState.gradingTotal})",
                    style = MaterialTheme.typography.titleMedium,
                )
                uiState.gradingNotice?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                }
                if (uiState.gradingFailed > 0) {
                    Text(
                        text = "${uiState.gradingFailed}개 문항 채점 실패 (자동으로 다시 시도합니다)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "답변 하나하나를 전사하고 루브릭으로 평가하고 있습니다.\n" +
                        "무료 한도에 맞춰 간격을 두고 진행해 몇 분 걸릴 수 있어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// --- 4단계: 결과 리포트 ---

@Composable
private fun ExamReportContent(
    uiState: ExamUiState,
    viewModel: ExamViewModel,
    onBack: () -> Unit,
) {
    val report = uiState.report ?: return

    ScreenScaffold(title = "채점 리포트", onBack = onBack) { modifier ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("예상 등급", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = report.overallGrade.display,
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "추정 범위 ${report.gradeLow.display} ~ ${report.gradeHigh.display}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (report.passedTarget) {
                                "🎉 목표 ${uiState.targetGrade.display} 달성 수준입니다!"
                            } else {
                                "목표 ${uiState.targetGrade.display}까지 조금 더! 약점을 집중 연습하세요."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "※ AI 근사 추정치로, 실제 공식 성적과 다를 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (uiState.gradingFailed > 0) {
                item {
                    SectionCard(title = "채점되지 않은 문항") {
                        Text(
                            text = "${uiState.gradingFailed}개 문항이 무료 한도 초과로 채점되지 못했습니다. " +
                                "지금 다시 시도하거나, 한도가 풀린 뒤(분당 한도는 1분, 일일 한도는 " +
                                "한국 시간 오후 4~5시경) 눌러 주세요. 성공한 문항은 다시 채점하지 않습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = viewModel::retryFailedGrading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("실패 문항 다시 채점 (${uiState.gradingFailed}개)") }
                    }
                }
            }

            item {
                SectionCard(title = "축별 평가") {
                    RubricAxis.entries.forEach { axis ->
                        val score = report.axisAverages[axis] ?: 0.0
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(axis.ko, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "%.1f / 10".format(score),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { (score / 10.0).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            if (report.topWeaknesses.isNotEmpty()) {
                item {
                    SectionCard(title = "집중 보완 포인트") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            report.topWeaknesses.take(3).forEach { tag ->
                                AssistChip(onClick = {}, label = { Text(tag) })
                            }
                        }
                    }
                }
            }

            items(uiState.gradedAnswers) { answer ->
                AnswerReportCard(answer)
            }

            item {
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("홈으로")
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AnswerReportCard(answer: GradedAnswer) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Q${answer.orderIndex + 1} · ${answer.question.type.ko}",
                    style = MaterialTheme.typography.titleSmall,
                )
                GradeBadge(answer.feedback.estimatedGrade)
            }
            Text(text = answer.question.zh, style = MaterialTheme.typography.bodyMedium)
            answer.question.ko?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                AnswerFeedbackDetail(answer.feedback)
                answer.feedback.gradedBy?.let { model ->
                    Text(
                        text = "채점 모델: $model",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = "탭하면 전사·교정·모범답안을 볼 수 있어요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun formatMmSs(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)
