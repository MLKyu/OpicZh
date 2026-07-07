package com.mingeek.opiczh.feature.settings.speechlab

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.mingeek.opiczh.core.designsystem.component.SectionCard
import com.mingeek.opiczh.core.speech.SpeakerState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SpeechLabScreen(
    onBack: () -> Unit,
    viewModel: SpeechLabViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissions = rememberMultiplePermissionsState(
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        },
    )

    if (uiState.isRecording) KeepScreenOn()

    ScreenScaffold(title = "음성 점검", onBack = onBack) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            uiState.error?.let { ErrorBanner(message = it) }

            SectionCard(title = "장문 TTS 테스트 (스피커)") {
                OutlinedTextField(
                    value = uiState.ttsText,
                    onValueChange = viewModel::onTtsTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                    label = { Text("읽을 중국어 텍스트") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.speak(natural = true) },
                        enabled = !uiState.speaking,
                    ) { Text("자연 음성 (Gemini)") }
                    OutlinedButton(
                        onClick = { viewModel.speak(natural = false) },
                        enabled = !uiState.speaking,
                    ) { Text("시스템 TTS") }
                    OutlinedButton(
                        onClick = viewModel::stopSpeaking,
                        enabled = uiState.speaking,
                    ) { Text("정지") }
                }
                when (val s = uiState.speakerState) {
                    is SpeakerState.Preparing -> StatusText("음성 합성 중… (${s.chunk}/${s.totalChunks})")
                    is SpeakerState.Playing -> StatusText("재생 중 (${s.chunk}/${s.totalChunks})")
                    SpeakerState.Idle -> if (uiState.speaking) StatusText("준비 중…")
                }
            }

            SectionCard(title = "장시간 녹음 테스트 (마이크)") {
                if (!permissions.allPermissionsGranted) {
                    Text(
                        text = "녹음 테스트에는 마이크(및 알림) 권한이 필요합니다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { permissions.launchMultiplePermissionRequest() }) {
                        Text("권한 허용")
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (uiState.isRecording) {
                            Button(onClick = viewModel::stopRecording) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Text(" 녹음 정지")
                            }
                            Text(
                                text = formatElapsed(uiState.elapsedSec),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        } else {
                            Button(onClick = viewModel::startRecording) {
                                Icon(Icons.Default.Mic, contentDescription = null)
                                Text(" 녹음 시작")
                            }
                        }
                    }
                    if (uiState.isRecording) {
                        LinearProgressIndicator(
                            progress = { uiState.amplitude },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        StatusText("2분 이상 길게 말해 보세요. 홈으로 나가거나 화면을 꺼도 녹음이 유지됩니다.")
                    }
                    uiState.lastRecording?.let { file ->
                        StatusText("녹음 완료: ${file.name} (${file.length() / 1024}KB)")
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = viewModel::transcribe,
                                enabled = !uiState.transcribing,
                            ) { Text("Gemini로 전사하기") }
                            if (uiState.transcribing) CircularProgressIndicator()
                        }
                    }
                    uiState.transcript?.let { transcript ->
                        Text(
                            text = transcript,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatElapsed(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)
