package com.mingeek.opiczh.core.ai.ondevice

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

sealed interface ModelStatus {
    data object NotInstalled : ModelStatus
    data class Downloading(val progressPct: Int) : ModelStatus
    data object Queued : ModelStatus
    data class Installed(val sizeBytes: Long) : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

/**
 * 온디바이스 모델 다운로드/설치/교체 관리.
 *
 * 교체(스왑) 흐름 — LLM이 계속 발전하는 시기에 앱을 최신 모델로 유지:
 * 1. [requestSwapTo] — 새 모델을 pending으로 기록하고 다운로드 시작 (기존 모델은 그대로 사용)
 * 2. 다운로드 완료 후 [reconcile] — 이전 활성 모델 파일 삭제 → 새 모델을 active로 승격
 * 3. 엔진은 다음 호출에서 active 경로 변경을 감지해 새 모델을 로드
 * 실패해도 기존 모델이 남아 있어 안전하다.
 */
@Singleton
class OnDeviceModelManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: OnDeviceModelStore,
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    private val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    /** 엔진이 사용하는 현재 모델 */
    val activeSpec: Flow<OnDeviceModelSpec?> = store.active

    /** 마지막 추천 결과 */
    val recommendation: Flow<RecommendationRecord?> = store.recommendation

    fun modelFile(spec: OnDeviceModelSpec): File = File(modelsDir, spec.fileName)

    fun statusFlow(spec: OnDeviceModelSpec): Flow<ModelStatus> =
        workManager.getWorkInfosForUniqueWorkFlow(workName(spec)).map { infos ->
            val file = modelFile(spec)
            if (file.exists() && file.length() > 0) {
                return@map ModelStatus.Installed(file.length())
            }
            val info = infos.firstOrNull()
            when (info?.state) {
                WorkInfo.State.RUNNING ->
                    ModelStatus.Downloading(info.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0))
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelStatus.Queued
                WorkInfo.State.FAILED -> ModelStatus.Failed(
                    info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "다운로드 실패",
                )
                else -> ModelStatus.NotInstalled
            }
        }

    suspend fun startDownload(spec: OnDeviceModelSpec) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_URL to spec.url,
                    ModelDownloadWorker.KEY_FILE_NAME to spec.fileName,
                    ModelDownloadWorker.KEY_DISPLAY_NAME to spec.displayName,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(workName(spec), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(spec: OnDeviceModelSpec) {
        workManager.cancelUniqueWork(workName(spec))
    }

    suspend fun delete(spec: OnDeviceModelSpec) {
        workManager.cancelUniqueWork(workName(spec))
        modelFile(spec).delete()
        File(modelsDir, "${spec.fileName}.part").delete()
        store.clearActiveIfFile(spec.fileName)
    }

    /** 추천/신규 모델로 교체 시작: 다운로드 성공 후 reconcile이 구모델을 지우고 승격한다 */
    suspend fun requestSwapTo(spec: OnDeviceModelSpec) {
        store.setPending(spec)
        startDownload(spec)
    }

    /** 설치된 모델을 수동으로 활성화 */
    suspend fun setActiveManual(spec: OnDeviceModelSpec) {
        val file = modelFile(spec)
        if (file.exists() && file.length() > 0) store.setActive(spec)
    }

    suspend fun setRecommendation(record: RecommendationRecord) = store.setRecommendation(record)

    /**
     * pending 모델의 다운로드가 끝났으면: 이전 활성 모델 파일 삭제 → pending을 active로.
     * 앱 시작 시와 설치 완료 감지 시 호출 (백그라운드 완료도 여기서 수습).
     */
    suspend fun reconcile() {
        val pending = store.pending.first() ?: return
        val file = modelFile(pending)
        if (!file.exists() || file.length() == 0L) return

        val previous = store.active.first()
        if (previous != null && previous.fileName != pending.fileName) {
            File(modelsDir, previous.fileName).delete()
            File(modelsDir, "${previous.fileName}.part").delete()
        }
        store.setActive(pending)
        store.clearPending()
    }

    /** 엔진이 로드할 모델: 활성 모델 우선, 없으면 설치된 프리셋(하위 호환) */
    suspend fun readyModelFile(): File? {
        store.active.first()?.let { spec ->
            modelFile(spec).takeIf { it.exists() && it.length() > 0 }?.let { return it }
        }
        return OnDeviceModels.ALL.asSequence()
            .map { modelFile(it) }
            .firstOrNull { it.exists() && it.length() > 0 }
    }

    private fun workName(spec: OnDeviceModelSpec) = "model_download_${spec.id}"
}
