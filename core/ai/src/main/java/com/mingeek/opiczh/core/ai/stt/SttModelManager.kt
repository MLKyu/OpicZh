package com.mingeek.opiczh.core.ai.stt

import com.mingeek.opiczh.core.ai.ondevice.ModelStatus
import com.mingeek.opiczh.core.ai.ondevice.OnDeviceModelManager
import com.mingeek.opiczh.core.ai.ondevice.OnDeviceModelSpec
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * STT 파일 세트(모델+토큰+VAD)의 다운로드/설치 상태를 하나로 묶는 얇은 파사드.
 * 실제 다운로드는 기존 [OnDeviceModelManager](이어받기 워커·FGS·진행률)를 그대로 쓴다.
 */
@Singleton
class SttModelManager @Inject constructor(
    private val manager: OnDeviceModelManager,
) {

    fun file(spec: OnDeviceModelSpec): File = manager.modelFile(spec)

    private fun installed(spec: OnDeviceModelSpec): Boolean =
        file(spec).let { it.exists() && it.length() > 0 }

    /** 세 파일 모두 있어야 STT 사용 가능 */
    fun isInstalled(): Boolean = SttModels.ALL.all(::installed)

    /** STT 세트 논리 상태 (설정 화면용) */
    val statusFlow: Flow<ModelStatus> =
        combine(SttModels.ALL.map { spec -> manager.statusFlow(spec).map { spec to it } }) {
            SttModels.combineStatuses(it.toList())
        }.distinctUntilChanged()

    /** 화면 게이팅용 (주제연습 말하기 칩·자유회화 마이크·시험 임시 채점 버튼) */
    val installedFlow: Flow<Boolean> =
        statusFlow.map { it is ModelStatus.Installed }.distinctUntilChanged()

    /** 빠진 파일만 받는다 — 유니크 워크가 KEEP 정책이라 재호출해도 안전 */
    suspend fun startDownload() {
        SttModels.ALL.filterNot(::installed).forEach { manager.startDownload(it) }
    }

    fun cancelDownload() {
        SttModels.ALL.forEach { manager.cancelDownload(it) }
    }

    /** 세 파일과 .part 잔여물까지 제거 */
    suspend fun delete() {
        SttModels.ALL.forEach { manager.delete(it) }
    }
}
