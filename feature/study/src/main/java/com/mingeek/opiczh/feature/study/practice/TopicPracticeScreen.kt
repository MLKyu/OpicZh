package com.mingeek.opiczh.feature.study.practice

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun TopicPracticeScreen(
    onBack: () -> Unit,
    viewModel: TopicPracticeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissions = rememberMultiplePermissionsState(
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        },
    )

    // 내부 단계 뒤로가기: 연습 → 문항 목록 → 주제 목록 → 나가기
    BackHandler {
        when {
            uiState.currentQuestion != null -> viewModel.backToQuestions()
            uiState.selectedTopic != null -> viewModel.backToTopics()
            else -> onBack()
        }
    }

    if (uiState.recording) KeepScreenOn()

    val title = when {
        uiState.currentQuestion != null -> uiState.selectedTopic?.nameKo ?: "연습"
        uiState.selectedTopic != null -> "${uiState.selectedTopic?.nameKo} — 문항 선택"
        else -> "주제별 실전 연습"
    }

    ScreenScaffold(
        title = title,
        onBack = {
            when {
                uiState.currentQuestion != null -> viewModel.backToQuestions()
                uiState.selectedTopic != null -> viewModel.backToTopics()
                else -> onBack()
            }
        },
    ) { modifier ->
        when {
            uiState.currentQuestion != null ->
                PracticeContent(modifier, uiState, viewModel, permissions.allPermissionsGranted) {
                    permissions.launchMultiplePermissionRequest()
                }
            uiState.selectedTopic != null -> QuestionListContent(modifier, uiState, viewModel)
            else -> TopicListContent(modifier, uiState, viewModel)
        }
    }
}

@Composable
private fun TopicListContent(
    modifier: Modifier,
    uiState: TopicPracticeUiState,
    viewModel: TopicPracticeViewModel,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(uiState.topics) { topic ->
            Card(onClick = { viewModel.selectTopic(topic) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(topic.nameKo, style = MaterialTheme.typography.titleMedium)
                    Text(
                        topic.nameZh,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionListContent(
    modifier: Modifier,
    uiState: TopicPracticeUiState,
    viewModel: TopicPracticeViewModel,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(uiState.questions) { question ->
            Card(
                onClick = { viewModel.selectQuestion(question) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text(question.type.ko) })
                        AssistChip(onClick = {}, label = { Text("난이도 ${question.difficulty}") })
                    }
                    Text(
                        text = question.ko ?: question.zh,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun PracticeContent(
    modifier: Modifier,
    uiState: TopicPracticeUiState,
    viewModel: TopicPracticeViewModel,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
) {
    val question = uiState.currentQuestion ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        uiState.error?.let { ErrorBanner(message = it) }

        SectionCard(title = "${question.type.ko} · 난이도 ${question.difficulty}") {
            if (uiState.showQuestionText) {
                Text(question.zh, style = MaterialTheme.typography.bodyLarge)
                question.ko?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "실전처럼 텍스트 없이 듣고 답해 보세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::playQuestion,
                    enabled = !uiState.playing && !uiState.recording,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(if (uiState.playing) " 재생 중…" else " 질문 듣기")
                }
                TextButton(onClick = viewModel::toggleQuestionText) {
                    Text(if (uiState.showQuestionText) "텍스트 숨기기" else "텍스트 보기")
                }
            }
        }

        if (uiState.onDeviceOnly) {
            Text(
                text = if (uiState.sttReady) {
                    "온디바이스 모드: 녹음 답변을 기기 안에서 전사해 채점합니다 — " +
                        "발음·유창성 축이 빠진 임시 채점이며 전사 오차가 있을 수 있습니다."
                } else {
                    "온디바이스 모드: 음성 채점은 텍스트 답변으로 연습합니다. " +
                        "설정에서 음성 인식 모델을 받으면 말하기(녹음) 채점도 됩니다."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !uiState.typing,
                onClick = { viewModel.setTyping(false) },
                label = { Text("말하기 (녹음)") },
            )
            FilterChip(
                selected = uiState.typing,
                onClick = { viewModel.setTyping(true) },
                label = { Text("텍스트로 쓰기") },
            )
        }

        if (uiState.typing) {
            OutlinedTextField(
                value = uiState.typedAnswer,
                onValueChange = viewModel::onTypedAnswerChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("중국어로 답변을 작성하세요") },
                minLines = 4,
                enabled = !uiState.grading,
            )
            Button(
                onClick = viewModel::submitTypedAnswer,
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.typedAnswer.isNotBlank() && !uiState.grading,
            ) {
                Text("채점 받기")
            }
        } else if (!permissionGranted) {
            Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                Text("마이크 권한 허용")
            }
        } else {
            Button(
                onClick = viewModel::toggleRecording,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.grading,
            ) {
                if (uiState.recording) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text(" 답변 완료 (${uiState.elapsedSec}초)")
                } else {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Text(" 답변 녹음 시작")
                }
            }
        }

        if (uiState.grading) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text("AI 채점 중…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        uiState.feedback?.let { feedback ->
            SectionCard(title = "채점 결과") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("이 답변의 예상 등급", style = MaterialTheme.typography.bodyMedium)
                    GradeBadge(feedback.estimatedGrade)
                }
                AnswerFeedbackDetail(feedback)
            }
        }
    }
}
