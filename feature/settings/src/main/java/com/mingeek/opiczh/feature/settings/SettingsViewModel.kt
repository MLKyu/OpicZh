package com.mingeek.opiczh.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.opiczh.core.ai.ApiUsage
import com.mingeek.opiczh.core.ai.UsageTracker
import com.mingeek.opiczh.core.ai.gemini.GeminiModelCatalog
import com.mingeek.opiczh.core.ai.gemini.LlmModelInfo
import com.mingeek.opiczh.core.ai.ondevice.ModelRecommender
import com.mingeek.opiczh.core.ai.ondevice.ModelStatus
import com.mingeek.opiczh.core.ai.ondevice.OnDeviceModelManager
import com.mingeek.opiczh.core.ai.ondevice.OnDeviceModelSpec
import com.mingeek.opiczh.core.ai.ondevice.OnDeviceModels
import com.mingeek.opiczh.core.ai.ondevice.RecommendationRecord
import com.mingeek.opiczh.core.common.BackupSelection
import com.mingeek.opiczh.core.common.CloudBackup
import com.mingeek.opiczh.core.common.onFailure
import com.mingeek.opiczh.core.common.onSuccess
import com.mingeek.opiczh.core.data.settings.SettingsRepository
import com.mingeek.opiczh.core.model.AppSettings
import com.mingeek.opiczh.core.model.RoutingPolicy
import com.mingeek.opiczh.core.model.TargetGrade
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface KeyStatus {
    data object Idle : KeyStatus
    data object Validating : KeyStatus
    data class Valid(val modelCount: Int) : KeyStatus
    data class Invalid(val messageKo: String) : KeyStatus
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val apiKeyInput: String = "",
    val keyStatus: KeyStatus = KeyStatus.Idle,
    val availableModels: List<LlmModelInfo> = emptyList(),
    val loadingModels: Boolean = false,
    // 온디바이스 모델
    val onDeviceSpecs: List<OnDeviceModelSpec> = OnDeviceModels.ALL,
    val onDeviceStatuses: Map<String, ModelStatus> = emptyMap(),
    val hfTokenInput: String = "",
    val hfTokenSet: Boolean = false,
    // 모델 추천/교체
    val recommendation: RecommendationRecord? = null,
    val activeModelFileName: String? = null,
    val loadingRecommendation: Boolean = false,
    val recommendationError: String? = null,
    // 클라우드 백업 (요청형·선택형)
    val lastBackupAtMs: Long? = null,
    val backupDb: Boolean = true,
    val backupRecordings: Boolean = true,
    val backingUp: Boolean = false,
    val backupMessage: String? = null,
)

private data class KeyPanel(
    val input: String,
    val status: KeyStatus,
    val models: List<LlmModelInfo>,
    val loading: Boolean,
)

private data class RecPanel(
    val recommendation: RecommendationRecord?,
    val activeFileName: String?,
    val loading: Boolean,
    val error: String?,
)

private data class OnDevicePanel(
    val statuses: Map<String, ModelStatus>,
    val tokenInput: String,
    val tokenSet: Boolean,
    val rec: RecPanel,
)

private data class BackupPanel(
    val lastBackupAtMs: Long?,
    val db: Boolean,
    val recordings: Boolean,
    val running: Boolean,
    val message: String?,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val modelCatalog: GeminiModelCatalog,
    private val modelManager: OnDeviceModelManager,
    private val modelRecommender: ModelRecommender,
    private val cloudBackup: CloudBackup,
    usageTracker: UsageTracker,
) : ViewModel() {

    /** 이번 세션의 Gemini 사용량 */
    val usage: StateFlow<ApiUsage> = usageTracker.usage

    private val apiKeyInput = MutableStateFlow("")
    private val keyStatus = MutableStateFlow<KeyStatus>(KeyStatus.Idle)
    private val availableModels = MutableStateFlow<List<LlmModelInfo>>(emptyList())
    private val loadingModels = MutableStateFlow(false)
    private val onDeviceStatuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
    private val hfTokenInput = MutableStateFlow("")
    private val loadingRecommendation = MutableStateFlow(false)
    private val recommendationError = MutableStateFlow<String?>(null)
    private val backupDb = MutableStateFlow(true)
    private val backupRecordings = MutableStateFlow(true)
    private val backingUp = MutableStateFlow(false)
    private val backupMessage = MutableStateFlow<String?>(null)

    /** statusFlow 수집을 시작한 스펙 id (중복 수집 방지) */
    private val watchedSpecIds = mutableSetOf<String>()

    private val keyPanel = combine(apiKeyInput, keyStatus, availableModels, loadingModels) {
            input, status, models, loading ->
        KeyPanel(input, status, models, loading)
    }

    private val recPanel = combine(
        modelManager.recommendation,
        modelManager.activeSpec,
        loadingRecommendation,
        recommendationError,
    ) { rec, active, loading, error ->
        RecPanel(rec, active?.fileName, loading, error)
    }

    private val onDevicePanel = combine(
        onDeviceStatuses,
        hfTokenInput,
        settingsRepository.hfToken,
        recPanel,
    ) { statuses, input, token, rec ->
        OnDevicePanel(statuses, input, token != null, rec)
    }

    private val backupPanel = combine(
        cloudBackup.lastBackupAtMs,
        backupDb,
        backupRecordings,
        backingUp,
        backupMessage,
    ) { last, db, rec, running, message ->
        BackupPanel(last, db, rec, running, message)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        keyPanel,
        onDevicePanel,
        backupPanel,
    ) { settings, key, onDevice, backup ->
        SettingsUiState(
            settings = settings,
            apiKeyInput = key.input,
            keyStatus = key.status,
            availableModels = key.models,
            loadingModels = key.loading,
            onDeviceStatuses = onDevice.statuses,
            hfTokenInput = onDevice.tokenInput,
            hfTokenSet = onDevice.tokenSet,
            recommendation = onDevice.rec.recommendation,
            activeModelFileName = onDevice.rec.activeFileName,
            loadingRecommendation = onDevice.rec.loading,
            recommendationError = onDevice.rec.error,
            lastBackupAtMs = backup.lastBackupAtMs,
            backupDb = backup.db,
            backupRecordings = backup.recordings,
            backingUp = backup.running,
            backupMessage = backup.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    init {
        OnDeviceModels.ALL.forEach(::watchSpec)
        // 저장된 추천 모델의 다운로드 상태도 추적
        viewModelScope.launch {
            modelManager.recommendation.collect { rec ->
                rec?.spec?.let(::watchSpec)
            }
        }
        // 앱이 켜진 채 다운로드가 끝난 교체 건 수습
        viewModelScope.launch { modelManager.reconcile() }
    }

    private fun watchSpec(spec: OnDeviceModelSpec) {
        if (!watchedSpecIds.add(spec.id)) return
        viewModelScope.launch {
            modelManager.statusFlow(spec).collect { status ->
                onDeviceStatuses.update { it + (spec.id to status) }
                // 교체 다운로드 완료 → 구모델 삭제 + 활성 전환 (idempotent)
                if (status is ModelStatus.Installed) modelManager.reconcile()
            }
        }
    }

    // --- Gemini API 키 ---

    fun onApiKeyInputChange(value: String) {
        apiKeyInput.value = value
        if (keyStatus.value !is KeyStatus.Idle) keyStatus.value = KeyStatus.Idle
    }

    /** 후보 키를 models.list 핑으로 검증하고, 성공 시에만 암호화 저장한다. */
    fun saveAndValidateKey() {
        val candidate = apiKeyInput.value.trim()
        if (candidate.isEmpty()) {
            keyStatus.value = KeyStatus.Invalid("키를 입력해 주세요.")
            return
        }
        keyStatus.value = KeyStatus.Validating
        viewModelScope.launch {
            modelCatalog.validateApiKey(candidate)
                .onSuccess { count ->
                    settingsRepository.setApiKey(candidate)
                    apiKeyInput.value = ""
                    keyStatus.value = KeyStatus.Valid(count)
                    refreshModels(candidate)
                }
                .onFailure { error ->
                    keyStatus.value = KeyStatus.Invalid(error.userMessageKo())
                }
        }
    }

    fun clearKey() {
        viewModelScope.launch {
            settingsRepository.clearApiKey()
            availableModels.value = emptyList()
            keyStatus.value = KeyStatus.Idle
        }
    }

    /** @param apiKeyOverride 방금 검증한 키(홀더 갱신 레이스 회피용) */
    fun refreshModels(apiKeyOverride: String? = null) {
        loadingModels.value = true
        viewModelScope.launch {
            modelCatalog.listTextModels(apiKeyOverride)
                .onSuccess { models -> availableModels.value = models }
                .onFailure { error ->
                    if (keyStatus.value is KeyStatus.Idle) {
                        keyStatus.value = KeyStatus.Invalid(error.userMessageKo())
                    }
                }
            loadingModels.update { false }
        }
    }

    // --- 일반 설정 ---

    fun setTargetGrade(grade: TargetGrade) {
        viewModelScope.launch { settingsRepository.setTargetGrade(grade) }
    }

    fun setTextModel(modelId: String) {
        viewModelScope.launch { settingsRepository.setTextModelId(modelId) }
    }

    fun setRoutingPolicy(policy: RoutingPolicy) {
        viewModelScope.launch { settingsRepository.setRoutingPolicy(policy) }
    }

    // --- 모델 추천/교체 ---

    /** HuggingFace 실시간 목록 → 점수화 → Gemini 판정으로 최적 모델 추천 */
    fun refreshRecommendation() {
        if (loadingRecommendation.value) return
        loadingRecommendation.value = true
        recommendationError.value = null
        viewModelScope.launch {
            val hasToken = settingsRepository.hfToken.first() != null
            modelRecommender.recommend(hasHfToken = hasToken)
                .onSuccess { record ->
                    modelManager.setRecommendation(record)
                    watchSpec(record.spec)
                }
                .onFailure { error -> recommendationError.value = error.userMessageKo() }
            loadingRecommendation.value = false
        }
    }

    /** 추천 모델로 교체: 다운로드 완료 시 기존 활성 모델은 자동 삭제된다 */
    fun upgradeToRecommended() {
        val record = uiState.value.recommendation ?: return
        viewModelScope.launch {
            modelManager.requestSwapTo(record.spec)
            watchSpec(record.spec)
        }
    }

    /** 설치된 모델을 수동으로 활성화 */
    fun useModel(spec: OnDeviceModelSpec) {
        viewModelScope.launch { modelManager.setActiveManual(spec) }
    }

    // --- 온디바이스 모델 (수동) ---

    fun downloadModel(spec: OnDeviceModelSpec) {
        viewModelScope.launch { modelManager.startDownload(spec) }
    }

    fun cancelDownload(spec: OnDeviceModelSpec) = modelManager.cancelDownload(spec)

    fun deleteModel(spec: OnDeviceModelSpec) {
        viewModelScope.launch {
            modelManager.delete(spec)
            onDeviceStatuses.update { it + (spec.id to ModelStatus.NotInstalled) }
        }
    }

    fun onHfTokenInputChange(value: String) {
        hfTokenInput.value = value
    }

    fun saveHfToken() {
        val token = hfTokenInput.value.trim()
        if (token.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.setHfToken(token)
            hfTokenInput.value = ""
        }
    }

    fun clearHfToken() {
        viewModelScope.launch { settingsRepository.clearHfToken() }
    }

    // --- 클라우드 백업 (요청형·카테고리 선택) ---

    fun toggleBackupDb() = backupDb.update { !it }

    fun toggleBackupRecordings() = backupRecordings.update { !it }

    fun runBackup() {
        if (backingUp.value) return
        backingUp.value = true
        backupMessage.value = null
        viewModelScope.launch {
            cloudBackup.backupNow(
                BackupSelection(database = backupDb.value, recordings = backupRecordings.value),
            )
                .onSuccess { summary ->
                    val mb = "%.1f".format(summary.totalBytes / 1_000_000.0)
                    backupMessage.value =
                        "백업 완료 — 업로드 ${summary.uploadedFiles}개 · 건너뜀 ${summary.skippedFiles}개 · ${mb}MB"
                }
                .onFailure { error -> backupMessage.value = error.userMessageKo() }
            backingUp.value = false
        }
    }
}
