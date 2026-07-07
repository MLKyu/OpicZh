package com.mingeek.opiczh.core.ai.ondevice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * .litertlm 모델 파일 다운로더.
 * - Range 이어받기: 중단돼도 .part 파일에서 재개
 * - Foreground(dataSync) + 진행률 알림
 * - 네트워크 오류는 retry, 인증 오류(게이트 모델)는 명확한 실패 메시지
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return@withContext Result.failure()
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: fileName

        val modelsDir = File(applicationContext.filesDir, "models").apply { mkdirs() }
        val target = File(modelsDir, fileName)
        val part = File(modelsDir, "$fileName.part")
        if (target.exists()) return@withContext Result.success()

        setForeground(createForegroundInfo(displayName, 0))

        val existingBytes = if (part.exists()) part.length() else 0L
        val request = Request.Builder()
            .url(url)
            .apply {
                if (existingBytes > 0) header("Range", "bytes=$existingBytes-")
            }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 ->
                        return@withContext Result.failure(
                            workDataOf(KEY_ERROR to "접근 거부(${response.code}): 이 모델은 공개 다운로드가 제한되어 있습니다. 다른 무료 모델을 선택하세요."),
                        )
                    response.code == 404 ->
                        return@withContext Result.failure(
                            workDataOf(KEY_ERROR to "모델 파일을 찾을 수 없습니다 (404)"),
                        )
                    !response.isSuccessful ->
                        return@withContext Result.retry()
                }

                val body = response.body ?: return@withContext Result.retry()
                val resuming = response.code == 206
                if (!resuming && existingBytes > 0) part.delete()

                val alreadyHave = if (resuming) existingBytes else 0L
                val totalBytes = alreadyHave + body.contentLength().coerceAtLeast(0L)

                body.byteStream().use { input ->
                    java.io.FileOutputStream(part, resuming).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var downloaded = alreadyHave
                        var lastReportedPct = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val pct = ((downloaded * 100) / totalBytes).toInt()
                                if (pct != lastReportedPct) {
                                    lastReportedPct = pct
                                    setProgress(workDataOf(KEY_PROGRESS to pct))
                                    setForeground(createForegroundInfo(displayName, pct))
                                }
                            }
                        }
                    }
                }
            }

            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            Result.success()
        } catch (c: CancellationException) {
            throw c // .part 유지 → 다음에 이어받기
        } catch (io: IOException) {
            Result.retry()
        } catch (t: Throwable) {
            Result.failure(workDataOf(KEY_ERROR to "다운로드 실패: ${t.message}"))
        }
    }

    private fun createForegroundInfo(displayName: String, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "모델 다운로드", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("온디바이스 모델 다운로드")
            .setContentText("$displayName ($progress%)")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "opiczh_model_download"
        private const val NOTIFICATION_ID = 2001
    }
}
