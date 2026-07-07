package com.mingeek.opiczh

import android.app.Application
import android.util.Log
import com.mingeek.opiczh.core.ai.gemini.ApiKeyHolder
import com.mingeek.opiczh.core.ai.ondevice.OnDeviceModelManager
import com.mingeek.opiczh.core.data.seed.SeedImporter
import com.mingeek.opiczh.core.data.settings.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OpicApplication : Application() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var apiKeyHolder: ApiKeyHolder

    @Inject
    lateinit var seedImporter: SeedImporter

    @Inject
    lateinit var onDeviceModelManager: OnDeviceModelManager

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // 복호화된 API 키를 메모리 홀더에 유지 → OkHttp 인터셉터(동기)에서 사용
        applicationScope.launch {
            settingsRepository.apiKey.collect { key ->
                apiKeyHolder.current = key
            }
        }
        // 문제은행 시드 임포트 (최초 1회)
        applicationScope.launch {
            runCatching { seedImporter.ensureSeeded() }
                .onFailure { Log.e("OpicApplication", "문제은행 시드 임포트 실패", it) }
        }
        // 백그라운드에서 끝난 모델 교체 다운로드 수습 (구모델 삭제 + 활성 전환)
        applicationScope.launch {
            runCatching { onDeviceModelManager.reconcile() }
                .onFailure { Log.e("OpicApplication", "모델 교체 정리 실패", it) }
        }
    }
}
