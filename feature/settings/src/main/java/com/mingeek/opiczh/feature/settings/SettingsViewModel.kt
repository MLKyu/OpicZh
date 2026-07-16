package com.mingeek.opiczh.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.opiczh.core.ai.ApiUsage
import com.mingeek.opiczh.core.ai.NanoDownloadState
import com.mingeek.opiczh.core.ai.NanoLlmEngine
import com.mingeek.opiczh.core.ai.NanoStatus
import com.mingeek.opiczh.core.ai.UsageTracker
import com.mingeek.opiczh.core.ai.gemini.GeminiModelCatalog
import com.mingeek.opiczh.core.ai.gemini.ModelChainProvider
import com.mingeek.opiczh.core.ai.ondevice.ModelRecommender
import com.mingeek.opiczh.core.ai.ondevice.ModelStatus
import com.mingeek.opiczh.core.ai.ondevice.OnDeviceModelManager
import com.mingeek.opiczh.core.ai.ondevice.OnDeviceModelSpec
import com.mingeek.opiczh.core.ai.ondevice.OnDeviceModels
import android.content.Context
import android.net.Uri
import com.mingeek.opiczh.core.ai.ondevice.RecommendationRecord
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.onFailure
import com.mingeek.opiczh.core.common.onSuccess
import com.mingeek.opiczh.core.data.backup.BackupArchiver
import com.mingeek.opiczh.core.data.backup.BackupSelection
import com.mingeek.opiczh.core.data.exam.ExamRepository
import com.mingeek.opiczh.core.data.settings.ModelChainStore
import com.mingeek.opiczh.core.data.settings.SettingsRepository
import com.mingeek.opiczh.core.speech.ChineseSpeaker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import com.mingeek.opiczh.core.model.AppSettings
import com.mingeek.opiczh.core.model.OnDeviceEnginePriority
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

/** 자동 모델 체인의 한 항목 — cooldownUntilMs가 있으면 그 시각까지 한도 초과 */
data class ModelChainEntry(
    val modelId: String,
    val cooldownUntilMs: Long?,
)

data class ModelChainUi(
    val entries: List<ModelChainEntry> = emptyList(),
    val refreshing: Boolean = false,
)

/** 기기 내장 Gemini Nano(AICore) 상태 — status null이면 확인 중 */
data class NanoUi(
    val status: NanoStatus? = null,
    val downloading: Boolean = false,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val message: String? = null,
)

/** 문항 음성 미리 준비(선합성) 진행 상태 */
data class PresynthUi(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val message: String? = null,
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val apiKeyInput: String = "",
    val keyStatus: KeyStatus = KeyStatus.Idle,
    // 온디바이스 모델
    val onDeviceSpecs: List<OnDeviceModelSpec> = OnDeviceModels.ALL,
    val onDeviceStatuses: Map<String, ModelStatus> = emptyMap(),
    // 기기 내장 Gemini Nano
    val nano: NanoUi = NanoUi(),
    // 모델 추천/교체
    val recommendation: RecommendationRecord? = null,
    val activeModelFileName: String? = null,
    val loadingRecommendation: Boolean = false,
    val recommendationError: String? = null,
    // 백업 내보내기 (요청형·선택형, SAF)
    val lastBackupAtMs: Long? = null,
    val backupDb: Boolean = true,
    val backupRecordings: Boolean = true,
    val backingUp: Boolean = false,
    val backupMessage: String? = null,
    /** zip 준비 완료 → 화면이 이 이름으로 SAF 저장 선택기를 띄운다 */
    val backupSuggestedName: String? = null,
)

private data class KeyPanel(
    val input: String,
    val status: KeyStatus,
)

private data class RecPanel(
    val recommendation: RecommendationRecord?,
    val activeFileName: String?,
    val loading: Boolean,
    val error: String?,
)

private data class OnDevicePanel(
    val statuses: Map<String, ModelStatus>,
    val rec: RecPanel,
)

private data class BackupPanel(
    val lastBackupAtMs: Long?,
    val db: Boolean,
    val recordings: Boolean,
    val running: Boolean,
    val message: String?,
    val suggestedName: String?,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val modelCatalog: GeminiModelCatalog,
    private val modelChainProvider: ModelChainProvider,
    private val modelChainStore: ModelChainStore,
    private val examRepository: ExamRepository,
    private val speaker: ChineseSpeaker,
    private val modelManager: OnDeviceModelManager,
    private val modelRecommender: ModelRecommender,
    private val nanoEngine: NanoLlmEngine,
    private val backupArchiver: BackupArchiver,
    @param:ApplicationContext private val appContext: Context,
    usageTracker: UsageTracker,
) : ViewModel() {

    /** 이번 세션의 Gemini 사용량 */
    val usage: StateFlow<ApiUsage> = usageTracker.usage

    private val apiKeyInput = MutableStateFlow("")
    private val keyStatus = MutableStateFlow<KeyStatus>(KeyStatus.Idle)
    private val refreshingChain = MutableStateFlow(false)
    private val onDeviceStatuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
    private val nanoState = MutableStateFlow(NanoUi())
    private var nanoDownloadJob: Job? = null
    private val loadingRecommendation = MutableStateFlow(false)
    private val recommendationError = MutableStateFlow<String?>(null)
    private val backupDb = MutableStateFlow(true)
    private val backupRecordings = MutableStateFlow(true)
    private val backingUp = MutableStateFlow(false)
    private val backupMessage = MutableStateFlow<String?>(null)
    private val backupSuggestedName = MutableStateFlow<String?>(null)
    private var pendingArchive: File? = null

    /** statusFlow 수집을 시작한 스펙 id (중복 수집 방지) */
    private val watchedSpecIds = mutableSetOf<String>()

    private val keyPanel = combine(apiKeyInput, keyStatus) { input, status ->
        KeyPanel(input, status)
    }

    /** 자동 모델 체인 현황 (좋은 모델부터, 한도 초과 모델은 해제 시각 표시) */
    val modelChain: StateFlow<ModelChainUi> = combine(
        modelChainStore.chain,
        modelChainStore.cooldowns,
        refreshingChain,
    ) { chain, cooldowns, refreshing ->
        val now = System.currentTimeMillis()
        ModelChainUi(
            entries = chain.map { id ->
                ModelChainEntry(modelId = id, cooldownUntilMs = cooldowns[id]?.takeIf { it > now })
            },
            refreshing = refreshing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ModelChainUi(),
    )

    /** 문항 음성 선합성 진행 상태 */
    private val presynthState = MutableStateFlow(PresynthUi())
    val presynth: StateFlow<PresynthUi> = presynthState
    private var presynthJob: Job? = null

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
        recPanel,
    ) { statuses, rec ->
        OnDevicePanel(statuses, rec)
    }

    private val backupSelectionFlow = combine(backupDb, backupRecordings) { db, rec -> db to rec }

    private val backupPanel = combine(
        settingsRepository.lastBackupAt,
        backupSelectionFlow,
        backingUp,
        backupMessage,
        backupSuggestedName,
    ) { last, selection, running, message, suggestedName ->
        BackupPanel(last, selection.first, selection.second, running, message, suggestedName)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        keyPanel,
        onDevicePanel,
        backupPanel,
        nanoState,
    ) { settings, key, onDevice, backup, nano ->
        SettingsUiState(
            settings = settings,
            apiKeyInput = key.input,
            keyStatus = key.status,
            onDeviceStatuses = onDevice.statuses,
            nano = nano,
            recommendation = onDevice.rec.recommendation,
            activeModelFileName = onDevice.rec.activeFileName,
            loadingRecommendation = onDevice.rec.loading,
            recommendationError = onDevice.rec.error,
            lastBackupAtMs = backup.lastBackupAtMs,
            backupDb = backup.db,
            backupRecordings = backup.recordings,
            backingUp = backup.running,
            backupMessage = backup.message,
            backupSuggestedName = backup.suggestedName,
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
        refreshNanoStatus()
    }

    // --- 기기 내장 Gemini Nano (AICore) ---

    fun refreshNanoStatus() {
        viewModelScope.launch {
            nanoState.update { it.copy(status = nanoEngine.checkStatus()) }
        }
    }

    /** Nano 시스템 모델 준비 — 다운로드·저장은 AICore(시스템) 몫이라 앱 용량을 쓰지 않는다 */
    fun downloadNano() {
        if (nanoState.value.downloading) return
        nanoState.update { it.copy(downloading = true, message = null) }
        nanoDownloadJob = viewModelScope.launch {
            nanoEngine.download().collect { state ->
                when (state) {
                    is NanoDownloadState.Running -> nanoState.update {
                        it.copy(
                            downloadedBytes = state.bytesDownloaded,
                            totalBytes = state.totalBytes ?: it.totalBytes,
                        )
                    }
                    NanoDownloadState.Completed -> nanoState.update {
                        it.copy(
                            status = NanoStatus.AVAILABLE,
                            downloading = false,
                            message = "준비 완료 — 이제 다운로드 없이 내장 Nano를 사용할 수 있습니다.",
                        )
                    }
                    is NanoDownloadState.Failed -> {
                        nanoState.update {
                            it.copy(downloading = false, message = "준비 실패: ${state.message}")
                        }
                        refreshNanoStatus()
                    }
                }
            }
        }
    }

    fun setOnDeviceEnginePriority(priority: OnDeviceEnginePriority) {
        viewModelScope.launch { settingsRepository.setOnDeviceEnginePriority(priority) }
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
                    // 새 키로 쓸 수 있는 모델을 즉시 서열화해 둔다
                    modelChainProvider.refresh(candidate)
                }
                .onFailure { error ->
                    keyStatus.value = KeyStatus.Invalid(error.userMessageKo())
                }
        }
    }

    fun clearKey() {
        viewModelScope.launch {
            settingsRepository.clearApiKey()
            keyStatus.value = KeyStatus.Idle
        }
    }

    /** 자동 모델 체인 재조회 (models.list → 서열화) */
    fun refreshModelChain() {
        if (refreshingChain.value) return
        refreshingChain.value = true
        viewModelScope.launch {
            modelChainProvider.refresh()
                .onFailure { error ->
                    if (keyStatus.value is KeyStatus.Idle) {
                        keyStatus.value = KeyStatus.Invalid(error.userMessageKo())
                    }
                }
            refreshingChain.value = false
        }
    }

    // --- 일반 설정 ---

    fun setTargetGrade(grade: TargetGrade) {
        viewModelScope.launch { settingsRepository.setTargetGrade(grade) }
    }

    fun setRoutingPolicy(policy: RoutingPolicy) {
        viewModelScope.launch { settingsRepository.setRoutingPolicy(policy) }
    }

    // --- 문항 음성 미리 준비 (선합성) ---

    /**
     * 문제은행 전체 문항 음성을 미리 합성해 영구 캐시에 저장한다.
     * 준비가 끝나면 시험·연습 중 TTS 호출이 없어 무료 한도를 아끼고 오프라인 재생도 된다.
     * 페이서가 TTS 분당 한도에 맞춰 간격을 조절하므로 수십 분 걸릴 수 있다.
     */
    fun startPresynth() {
        if (presynthState.value.running) return
        presynthJob = viewModelScope.launch {
            val questions = examRepository.questionPool()
            presynthState.value = PresynthUi(running = true, done = 0, total = questions.size)
            questions.forEachIndexed { index, question ->
                when (val result = speaker.prefetch(question.zh)) {
                    is AppResult.Success ->
                        presynthState.update { it.copy(done = index + 1) }
                    is AppResult.Failure -> {
                        presynthState.update {
                            it.copy(
                                running = false,
                                message = "${index}개 준비 후 중단 — ${result.error.userMessageKo()} " +
                                    "준비된 문항은 저장되었고, 다시 누르면 이어서 진행합니다.",
                            )
                        }
                        return@launch
                    }
                }
            }
            presynthState.update {
                it.copy(running = false, message = "완료 — ${questions.size}개 문항 음성이 저장되었습니다.")
            }
        }
    }

    fun cancelPresynth() {
        presynthJob?.cancel()
        presynthState.update {
            it.copy(running = false, message = "중단됨 — 이미 준비된 문항은 유지되며, 다시 누르면 이어서 진행합니다.")
        }
    }

    // --- 모델 추천/교체 ---

    /** HuggingFace 실시간 목록 → 점수화 → Gemini 판정으로 최적 모델 추천 */
    fun refreshRecommendation() {
        if (loadingRecommendation.value) return
        loadingRecommendation.value = true
        recommendationError.value = null
        viewModelScope.launch {
            modelRecommender.recommend()
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

    // --- 백업 내보내기 (요청형·카테고리 선택, SAF — 무료) ---

    fun toggleBackupDb() = backupDb.update { !it }

    fun toggleBackupRecordings() = backupRecordings.update { !it }

    /** zip 생성 → 준비되면 화면이 저장 위치 선택기(SAF)를 띄운다 */
    fun runBackup() {
        if (backingUp.value) return
        backingUp.value = true
        backupMessage.value = null
        viewModelScope.launch {
            backupArchiver.createArchive(
                BackupSelection(database = backupDb.value, recordings = backupRecordings.value),
            )
                .onSuccess { archive ->
                    pendingArchive = archive
                    backupSuggestedName.value = archive.name
                }
                .onFailure { error ->
                    backupMessage.value = error.userMessageKo()
                    backingUp.value = false
                }
        }
    }

    /** SAF 선택 결과: 사용자가 고른 위치(구글 드라이브·다운로드 등)로 zip 복사 */
    fun onBackupDestination(uri: Uri?) {
        val archive = pendingArchive
        pendingArchive = null
        backupSuggestedName.value = null
        viewModelScope.launch {
            try {
                if (uri == null || archive == null) {
                    backupMessage.value = "백업이 취소되었습니다"
                    return@launch
                }
                val bytes = archive.length()
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { output ->
                        archive.inputStream().use { it.copyTo(output) }
                    } ?: error("저장 위치를 열 수 없습니다")
                }
                settingsRepository.markBackupDone()
                backupMessage.value = "백업 완료 — %.1fMB 저장됨".format(bytes / 1_000_000.0)
            } catch (t: Throwable) {
                backupMessage.value = "저장 실패: ${t.message}"
            } finally {
                archive?.delete()
                backingUp.value = false
            }
        }
    }

    override fun onCleared() {
        pendingArchive?.delete()
        super.onCleared()
    }
}
