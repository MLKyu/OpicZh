package com.mingeek.opiczh.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mingeek.opiczh.core.ai.NanoStatus
import com.mingeek.opiczh.core.ai.ondevice.ModelStatus
import com.mingeek.opiczh.core.designsystem.component.KeepScreenOn
import com.mingeek.opiczh.core.designsystem.component.ScreenScaffold
import com.mingeek.opiczh.core.designsystem.component.SectionCard
import com.mingeek.opiczh.core.model.OnDeviceEnginePriority
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
    val modelChain by viewModel.modelChain.collectAsStateWithLifecycle()
    val presynth by viewModel.presynth.collectAsStateWithLifecycle()

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
            ModelSection(uiState, modelChain, viewModel)
            PresynthSection(uiState, presynth, viewModel)
            RoutingSection(uiState, viewModel)
            NanoSection(uiState, viewModel)
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

@Composable
private fun ModelSection(
    uiState: SettingsUiState,
    chain: ModelChainUi,
    viewModel: SettingsViewModel,
) {
    SectionCard(title = "AI 모델 (자동 관리)") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "키로 쓸 수 있는 모델을 좋은 순서로 자동 사용합니다. " +
                    "무료 한도가 찬 모델은 건너뛰고 다음 모델로 자동 전환돼요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (chain.refreshing) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            } else {
                IconButton(onClick = viewModel::refreshModelChain) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "모델 체인 새로고침")
                }
            }
        }

        if (chain.entries.isEmpty()) {
            Text(
                text = "API 키를 등록하면 사용 가능한 모델을 자동으로 정렬해 보여드립니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            chain.entries.forEachIndexed { index, entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "${index + 1}. ${entry.modelId}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    val until = entry.cooldownUntilMs
                    if (until == null) {
                        Text(
                            text = "사용 가능",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        val untilText = remember(until) {
                            SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(until))
                        }
                        Text(
                            text = "한도 초과 · ${untilText}까지",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        Text(
            text = "TTS 모델: ${uiState.settings.ttsModelId} (한도 초과 시 시스템 음성으로 자동 대체)",
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
private fun PresynthSection(
    uiState: SettingsUiState,
    presynth: PresynthUi,
    viewModel: SettingsViewModel,
) {
    SectionCard(title = "문항 음성 미리 준비") {
        Text(
            text = "문제은행 전체 문항의 질문 음성을 미리 합성해 기기에 저장합니다. " +
                "한 번 준비해 두면 시험·연습 중 TTS 호출이 없어 무료 한도를 아끼고, " +
                "오프라인에서도 자연 음성으로 재생됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (presynth.running) {
            KeepScreenOn()
            LinearProgressIndicator(
                progress = {
                    if (presynth.total == 0) 0f
                    else (presynth.done.toFloat() / presynth.total).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "합성 중… ${presynth.done}/${presynth.total} " +
                        "(무료 한도에 맞춰 천천히 진행됩니다. 화면을 켜 두세요)",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = viewModel::cancelPresynth) { Text("중단") }
            }
        } else {
            Button(
                onClick = viewModel::startPresynth,
                enabled = uiState.settings.hasApiKey,
            ) { Text("음성 준비 시작") }
            if (!uiState.settings.hasApiKey) {
                Text(
                    text = "API 키 등록 후 사용할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        presynth.message?.let { message ->
            Text(
                text = message,
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
            text = "정밀 채점·전사는 클라우드가 담당하고, 한도 초과·오프라인에선 드릴·회화가 온디바이스로 동작합니다. " +
                "온디바이스는 아래 '내장 AI'(다운로드 불필요)와 '온디바이스 모델'(직접 다운로드) 두 가지입니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 기기 내장 Gemini Nano(AICore) 상태 + 온디바이스 엔진 우선순위 */
@Composable
private fun NanoSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    SectionCard(title = "내장 AI (Gemini Nano)") {
        Text(
            text = "갤럭시 S26 울트라에 내장된 Gemini Nano입니다. 모델을 시스템(AICore)이 관리해 " +
                "앱이 따로 다운로드하지 않으며, 클라우드 한도 초과 시 회화·드릴을 이어받습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (uiState.nano.status) {
            null -> Text("상태 확인 중…", style = MaterialTheme.typography.bodySmall)

            NanoStatus.AVAILABLE -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("사용 가능", style = MaterialTheme.typography.bodyMedium)
            }

            NanoStatus.DOWNLOADABLE ->
                if (uiState.nano.downloading) {
                    NanoDownloadingRow(uiState.nano)
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "시스템 모델 준비 필요 (최초 1회, 앱 용량 미사용)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = viewModel::downloadNano) { Text("준비") }
                    }
                }

            NanoStatus.DOWNLOADING -> NanoDownloadingRow(uiState.nano)

            NanoStatus.UNSUPPORTED -> Text(
                text = "이 기기에서는 내장 Nano를 사용할 수 없습니다. 아래 온디바이스 모델을 다운로드해 주세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        uiState.nano.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        Text("온디바이스 우선순위", style = MaterialTheme.typography.titleSmall)
        OnDeviceEnginePriority.entries.forEach { priority ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RadioButton(
                    selected = uiState.settings.onDeviceEnginePriority == priority,
                    onClick = { viewModel.setOnDeviceEnginePriority(priority) },
                )
                Text(priority.ko, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            text = "'다운로드한 모델 우선'이면 받아둔 모델(중국어 기준 추천 모델)이 있을 때 그 모델을 쓰고, " +
                "없으면 자동으로 내장 Nano를 사용합니다. 준비 안 된 쪽은 건너뛰므로 어느 쪽이든 손해가 없습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NanoDownloadingRow(nano: NanoUi) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val total = nano.totalBytes
        if (total != null && total > 0) {
            LinearProgressIndicator(
                progress = { (nano.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = "시스템이 모델 준비 중… (${nano.downloadedBytes / 1_000_000}MB" +
                (total?.let { "/${it / 1_000_000}MB" } ?: "") + " 수신)",
            style = MaterialTheme.typography.bodySmall,
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
                    ) {
                        Text("다운로드")
                    }
                }
            }
        }

        Text(
            text = "모든 추천·기본 모델은 무료라 토큰이나 로그인 없이 바로 다운로드됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                "약 ${spec.approxSizeMb / 1000.0}GB · 무료",
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
                Button(onClick = viewModel::upgradeToRecommended) {
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
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> viewModel.onBackupDestination(uri) }
    LaunchedEffect(uiState.backupSuggestedName) {
        uiState.backupSuggestedName?.let(saveLauncher::launch)
    }

    SectionCard(title = "백업 내보내기 (무료 · 요청 시에만)") {
        Text(
            text = "선택한 항목을 zip 파일 하나로 만들어 원하는 위치(구글 드라이브·다운로드 등)에 저장합니다. " +
                "자동 백업은 하지 않으며, API 키·토큰·캐시·온디바이스 모델은 절대 포함되지 않습니다.",
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
