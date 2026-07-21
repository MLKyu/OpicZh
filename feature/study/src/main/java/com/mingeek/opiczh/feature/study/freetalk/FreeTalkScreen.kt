package com.mingeek.opiczh.feature.study.freetalk

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.mingeek.opiczh.core.designsystem.component.ErrorBanner
import com.mingeek.opiczh.core.designsystem.component.KeepScreenOn
import com.mingeek.opiczh.core.designsystem.component.ScreenScaffold

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun FreeTalkScreen(
    onBack: () -> Unit,
    viewModel: FreeTalkViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val permissions = rememberMultiplePermissionsState(
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        },
    )

    if (uiState.recording) KeepScreenOn()

    LaunchedEffect(uiState.turns.size) {
        if (uiState.turns.isNotEmpty()) listState.animateScrollToItem(uiState.turns.size - 1)
    }

    ScreenScaffold(title = "자유회화 (AI 교관)", onBack = onBack) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            uiState.error?.let {
                ErrorBanner(message = it, modifier = Modifier.padding(horizontal = 16.dp))
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.turns) { turn ->
                    ChatBubble(turn)
                }
                if (uiState.replying || uiState.transcribing) {
                    item {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = if (uiState.transcribing) "전사 중…" else "교관이 생각 중…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::toggleSpeakReplies) {
                    Icon(
                        imageVector = if (uiState.speakReplies) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = "응답 음성 켜기/끄기",
                    )
                }
                OutlinedTextField(
                    value = uiState.input,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (uiState.voiceInputAvailable) {
                                "중국어로 입력하거나 마이크로 말하세요"
                            } else {
                                "중국어로 입력하세요 (음성 입력: 설정에서 음성 인식 모델 다운로드)"
                            },
                        )
                    },
                    maxLines = 3,
                )
                if (uiState.input.isBlank()) {
                    FilledIconButton(
                        onClick = {
                            if (permissions.allPermissionsGranted) {
                                viewModel.toggleRecording()
                            } else {
                                permissions.launchMultiplePermissionRequest()
                            }
                        },
                        // 녹음 중에는 항상 활성 — 음성 입력이 막혀도 정지는 가능해야 한다
                        enabled = !uiState.transcribing && !uiState.replying &&
                            (uiState.voiceInputAvailable || uiState.recording),
                    ) {
                        Icon(
                            imageVector = if (uiState.recording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (uiState.recording) "녹음 끝" else "말하기",
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = viewModel::sendTyped,
                        enabled = !uiState.replying,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "보내기")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(turn: ChatTurn) {
    val isUser = turn.role == TalkRole.USER
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text = turn.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
