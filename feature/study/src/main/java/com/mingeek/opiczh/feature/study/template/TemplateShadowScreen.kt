package com.mingeek.opiczh.feature.study.template

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
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.mingeek.opiczh.core.designsystem.component.FeedbackBlock
import com.mingeek.opiczh.core.designsystem.component.KeepScreenOn
import com.mingeek.opiczh.core.designsystem.component.ScreenScaffold
import com.mingeek.opiczh.core.designsystem.component.SectionCard

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun TemplateShadowScreen(
    onBack: () -> Unit,
    viewModel: TemplateShadowViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissions = rememberMultiplePermissionsState(
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        },
    )

    BackHandler {
        if (uiState.selected != null) viewModel.select(null) else onBack()
    }

    if (uiState.recording) KeepScreenOn()

    ScreenScaffold(
        title = uiState.selected?.title ?: "만능 템플릿 · 쉐도잉",
        onBack = { if (uiState.selected != null) viewModel.select(null) else onBack() },
    ) { modifier ->
        val selected = uiState.selected
        if (selected == null) {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.templates) { template ->
                    Card(
                        onClick = { viewModel.select(template) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AssistChip(onClick = {}, label = { Text(template.category) })
                                Text(template.title, style = MaterialTheme.typography.titleSmall)
                            }
                            Text(
                                text = template.zh,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                uiState.error?.let { ErrorBanner(message = it) }

                SectionCard(title = "${selected.category} — ${selected.title}") {
                    Text(selected.zh, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        selected.pinyin,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(selected.ko, style = MaterialTheme.typography.bodyMedium)
                    if (selected.tip.isNotBlank()) {
                        Text(
                            "💡 ${selected.tip}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::play, enabled = !uiState.playing && !uiState.recording) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(if (uiState.playing) " 재생 중…" else " 원어민 음성 듣기")
                    }
                    OutlinedButton(onClick = viewModel::addToSrs, enabled = !uiState.addedToSrs) {
                        Icon(Icons.Default.LibraryAdd, contentDescription = null)
                        Text(if (uiState.addedToSrs) " 추가됨" else " 복습 카드로")
                    }
                }

                if (!permissions.allPermissionsGranted) {
                    Button(
                        onClick = { permissions.launchMultiplePermissionRequest() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("마이크 권한 허용") }
                } else {
                    Button(
                        onClick = viewModel::toggleShadowing,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.coaching,
                    ) {
                        if (uiState.recording) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Text(" 따라 말하기 끝 (피드백 받기)")
                        } else {
                            Icon(Icons.Default.Mic, contentDescription = null)
                            Text(" 따라 말하기 (쉐도잉)")
                        }
                    }
                }

                if (uiState.coaching) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text("발음·성조 분석 중…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                uiState.coachFeedback?.let { feedback ->
                    SectionCard(title = "발음 코치") {
                        FeedbackBlock("피드백") {
                            Text(feedback, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
