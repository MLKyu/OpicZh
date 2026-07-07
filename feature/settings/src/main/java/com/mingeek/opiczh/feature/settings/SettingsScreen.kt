package com.mingeek.opiczh.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mingeek.opiczh.core.ai.ondevice.ModelStatus
import com.mingeek.opiczh.core.designsystem.component.ScreenScaffold
import com.mingeek.opiczh.core.designsystem.component.SectionCard
import com.mingeek.opiczh.core.model.RoutingPolicy
import com.mingeek.opiczh.core.model.TargetGrade
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSpeechLab: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ScreenScaffold(title = "설정", onBack = onBack) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ApiKeySection(uiState, viewModel)
            TargetGradeSection(uiState, viewModel)
            ModelSection(uiState, viewModel)
            RoutingSection(uiState, viewModel)
            OnDeviceModelsSection(uiState, viewModel)
            CloudBackupSection(uiState, viewModel)
            SectionCard(title = "음성 점검") {
                Text(
                    text = "시험 전 스피커(장문 TTS)와 마이크(장시간 녹음·전사)가 정상 동작하는지 확인하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenSpeechLab) { Text("음성 점검 열기") }
            }
            Text(
                text = "※ 등급 추정은 실제 OPIc 공식 채점(ACTFL)이 아닌 AI 근사치입니다. " +
                    "실전과 동일한 형식의 반복 훈련과 피드백으로 합격 확률을 최대화하는 것이 이 앱의 목표입니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ApiKeySection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    var showKey by remember { mutableStateOf(false) }

    SectionCard(title = "Gemini API 키") {
        if (uiState.settings.hasApiKey) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "키가 등록되어 있습니다 (기기 내 암호화 저장)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::clearKey) { Text("삭제") }
            }
        }

        OutlinedTextField(
            value = uiState.apiKeyInput,
            onValueChange = viewModel::onApiKeyInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (uiState.settings.hasApiKey) "새 키로 교체" else "API 키 입력") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showKey) "키 숨기기" else "키 표시",
                    )
                }
            },
            supportingText = { Text("Google AI Studio에서 발급한 키를 입력하세요. 키는 이 기기 밖으로 나가지 않습니다.") },
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = viewModel::saveAndValidateKey,
                enabled = uiState.keyStatus != KeyStatus.Validating,
            ) {
                Text("저장 후 검증")
            }
            when (val status = uiState.keyStatus) {
                KeyStatus.Idle -> Unit
                KeyStatus.Validating -> {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("검증 중…", style = MaterialTheme.typography.bodySmall)
                }
                is KeyStatus.Valid -> Text(
                    text = "유효한 키입니다 (모델 ${status.modelCount}개 사용 가능)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                is KeyStatus.Invalid -> Text(
                    text = status.messageKo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun TargetGradeSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    SectionCard(title = "목표 등급") {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TargetGrade.entries.forEachIndexed { index, grade ->
                SegmentedButton(
                    selected = uiState.settings.targetGrade == grade,
                    onClick = { viewModel.setTargetGrade(grade) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = TargetGrade.entries.size,
                    ),
                ) {
                    Text(grade.display)
                }
            }
        }
        val target = uiState.settings.targetGrade
        Text(
            text = "실전 Self-Assessment 권장 난이도: " +
                "${target.recommendedSelfAssessment.first}~${target.recommendedSelfAssessment.last}단계 · " +
                "모의고사 출제 난이도 ${target.questionDifficulty.first}~${target.questionDifficulty.last}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    SectionCard(title = "AI 모델") {
        var expanded by remember { mutableStateOf(false) }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = uiState.settings.textModelId,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text("채점·피드백 모델") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    uiState.availableModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.id) },
                            onClick = {
                                viewModel.setTextModel(model.id)
                                expanded = false
                            },
                        )
                    }
                    if (uiState.availableModels.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("키 등록 후 새로고침을 눌러 목록을 불러오세요") },
                            onClick = { expanded = false },
                        )
                    }
                }
            }
            if (uiState.loadingModels) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            } else {
                IconButton(onClick = { viewModel.refreshModels() }) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "모델 목록 새로고침")
                }
            }
        }
        Text(
            text = "TTS 모델: ${uiState.settings.ttsModelId} (음성 학습 기능에서 사용)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val usage by viewModel.usage.collectAsStateWithLifecycle()
        if (usage.requests > 0) {
            Text(
                text = "이번 세션 사용량: 요청 ${usage.requests}회 · 입력 ${usage.promptTokens} · 출력 ${usage.outputTokens} 토큰",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RoutingSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    SectionCard(title = "AI 엔진 사용 방식") {
        RoutingPolicy.entries.forEach { policy ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RadioButton(
                    selected = uiState.settings.routingPolicy == policy,
                    onClick = { viewModel.setRoutingPolicy(policy) },
                )
                Text(policy.ko, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            text = "온디바이스 모델은 아래 '온디바이스 모델' 섹션에서 다운로드하세요. " +
                "정밀 채점·전사는 클라우드가 담당하고, 오프라인에선 드릴·회화가 온디바이스로 동작합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OnDeviceModelsSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    SectionCard(title = "온디바이스 모델 (오프라인 AI)") {
        RecommendationBlock(uiState, viewModel)

        HorizontalDivider()
        Text(
            text = "직접 선택 (고급)",
            style = MaterialTheme.typography.titleSmall,
        )
        uiState.onDeviceSpecs.forEach { spec ->
            val status = uiState.onDeviceStatuses[spec.id] ?: ModelStatus.NotInstalled
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(spec.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = spec.description + " (약 ${spec.approxSizeMb / 1000.0}GB)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (status) {
                    is ModelStatus.Installed -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "설치됨 (${status.sizeBytes / 1_000_000}MB)" +
                                if (uiState.activeModelFileName == spec.fileName) " · 사용 중" else "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (uiState.activeModelFileName != spec.fileName) {
                            TextButton(onClick = { viewModel.useModel(spec) }) { Text("사용") }
                        }
                        TextButton(onClick = { viewModel.deleteModel(spec) }) { Text("삭제") }
                    }

                    is ModelStatus.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { status.progressPct / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "다운로드 중 ${status.progressPct}%",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.cancelDownload(spec) }) { Text("취소") }
                        }
                    }

                    ModelStatus.Queued -> Text(
                        "대기 중… (네트워크/저장공간 조건 확인)",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    is ModelStatus.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = status.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = { viewModel.downloadModel(spec) }) { Text("다시 시도") }
                    }

                    ModelStatus.NotInstalled -> Button(
                        onClick = { viewModel.downloadModel(spec) },
                        enabled = !spec.requiresHfToken || uiState.hfTokenSet,
                    ) {
                        Text(
                            if (spec.requiresHfToken && !uiState.hfTokenSet) {
                                "다운로드 (HF 토큰 필요)"
                            } else {
                                "다운로드"
                            },
                        )
                    }
                }
            }
        }

        // HuggingFace 토큰 (게이트 모델용)
        Text(
            text = "Gemma 계열은 HuggingFace에서 라이선스 동의 후 토큰이 필요합니다. Qwen3는 토큰 없이 받을 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (uiState.hfTokenSet) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "HuggingFace 토큰 등록됨",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::clearHfToken) { Text("삭제") }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = uiState.hfTokenInput,
                    onValueChange = viewModel::onHfTokenInputChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("HuggingFace 토큰 (선택)") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                Button(
                    onClick = viewModel::saveHfToken,
                    enabled = uiState.hfTokenInput.isNotBlank(),
                ) { Text("저장") }
            }
        }
    }
}

/** 이 앱(한국어 기반 OPIc 중국어) 기준 최적 온디바이스 모델 추천 + 원클릭 교체 */
@Composable
private fun RecommendationBlock(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    Text(
        text = "HuggingFace의 최신 LiteRT 모델 중 이 앱 용도(중국어 회화·모범답안 + 한국어 교정 설명)에 " +
            "가장 적합한 모델을 실시간으로 추천합니다. 새 추천으로 교체하면 이전 모델은 자동 삭제됩니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    uiState.recommendationError?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    val record = uiState.recommendation
    if (record == null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = viewModel::refreshRecommendation,
                enabled = !uiState.loadingRecommendation,
            ) { Text("최적 모델 추천받기") }
            if (uiState.loadingRecommendation) CircularProgressIndicator()
        }
        return
    }

    val spec = record.spec
    val status = uiState.onDeviceStatuses[spec.id] ?: ModelStatus.NotInstalled
    val isActive = uiState.activeModelFileName == spec.fileName
    val dateText = remember(record.checkedAtEpochMs) {
        SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(record.checkedAtEpochMs))
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("🏆", style = MaterialTheme.typography.titleMedium)
            Text(
                text = spec.displayName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (isActive && status is ModelStatus.Installed) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(text = record.reasonKo, style = MaterialTheme.typography.bodySmall)
        Text(
            text = "${record.decidedBy} · 후보 ${record.candidatesConsidered}개 검토 · $dateText 조회 · " +
                "약 ${spec.approxSizeMb / 1000.0}GB" +
                if (spec.requiresHfToken) " · HF 토큰 필요" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            isActive && status is ModelStatus.Installed -> Text(
                text = "✓ 최신 추천 모델을 사용 중입니다",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            status is ModelStatus.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { status.progressPct / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "교체 다운로드 중 ${status.progressPct}% (완료되면 기존 모델 자동 삭제)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.cancelDownload(spec) }) { Text("취소") }
                }
            }

            status is ModelStatus.Queued -> Text(
                "다운로드 대기 중… (네트워크/저장공간 조건 확인)",
                style = MaterialTheme.typography.bodySmall,
            )

            status is ModelStatus.Installed && !isActive -> Button(
                onClick = { viewModel.useModel(spec) },
            ) { Text("이 모델 사용하기") }

            else -> {
                if (status is ModelStatus.Failed) {
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = viewModel::upgradeToRecommended,
                    enabled = !spec.requiresHfToken || uiState.hfTokenSet,
                ) {
                    Text(
                        if (uiState.activeModelFileName != null) {
                            "추천 모델로 교체 (기존 모델 자동 삭제)"
                        } else {
                            "추천 모델 다운로드"
                        },
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                onClick = viewModel::refreshRecommendation,
                enabled = !uiState.loadingRecommendation,
            ) { Text("추천 새로고침 (최신 모델 확인)") }
            if (uiState.loadingRecommendation) CircularProgressIndicator()
        }
    }
}

/** 요청형·카테고리 선택형 클라우드 백업 (Firebase Storage) — 자동 백업 없음 */
@Composable
private fun CloudBackupSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    SectionCard(title = "클라우드 백업 (요청 시에만)") {
        Text(
            text = "선택한 항목만 Firebase Storage에 업로드합니다. 자동 백업은 하지 않으며, " +
                "API 키·토큰·캐시·온디바이스 모델은 절대 포함되지 않습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Checkbox(
                checked = uiState.backupDb,
                onCheckedChange = { viewModel.toggleBackupDb() },
            )
            Text("학습 기록 (시험·리포트·복습 카드 DB)", style = MaterialTheme.typography.bodyMedium)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Checkbox(
                checked = uiState.backupRecordings,
                onCheckedChange = { viewModel.toggleBackupRecordings() },
            )
            Text("녹음 파일 (이미 올라간 파일은 건너뜀)", style = MaterialTheme.typography.bodyMedium)
        }

        uiState.lastBackupAtMs?.let { last ->
            Text(
                text = "마지막 백업: " +
                    SimpleDateFormat("yyyy년 M월 d일 HH:mm", Locale.KOREA).format(Date(last)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = viewModel::runBackup,
                enabled = !uiState.backingUp && (uiState.backupDb || uiState.backupRecordings),
            ) { Text(if (uiState.backingUp) "백업 중…" else "지금 백업") }
            if (uiState.backingUp) CircularProgressIndicator()
        }

        uiState.backupMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (message.startsWith("백업 완료")) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}
