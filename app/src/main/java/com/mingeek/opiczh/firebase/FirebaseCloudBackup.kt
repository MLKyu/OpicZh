package com.mingeek.opiczh.firebase

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.storage.storage
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.BackupSelection
import com.mingeek.opiczh.core.common.BackupSummary
import com.mingeek.opiczh.core.common.CloudBackup
import com.mingeek.opiczh.core.data.db.OpicDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Firebase Storage 요청형 백업.
 * - 익명 인증 후 backups/{uid}/ 아래에만 기록 (규칙으로 본인 경로만 허용)
 * - 녹음은 증분(같은 이름 존재 시 건너뜀), DB는 체크포인트 후 스냅샷 덮어쓰기
 * - API 키·토큰·캐시·온디바이스 모델은 절대 업로드하지 않는다
 */
@Singleton
class FirebaseCloudBackup @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: OpicDatabase,
    private val dataStore: DataStore<Preferences>,
) : CloudBackup {

    private val lastBackupKey = longPreferencesKey("last_cloud_backup_at")

    override val lastBackupAtMs: Flow<Long?> = dataStore.data
        .map { it[lastBackupKey] }
        .distinctUntilChanged()

    override suspend fun backupNow(selection: BackupSelection): AppResult<BackupSummary> {
        if (selection.isEmpty) {
            return AppResult.failure(AppError.BadRequest("백업할 항목을 선택하세요"))
        }
        return withContext(Dispatchers.IO) {
            try {
                val user = Firebase.auth.currentUser
                    ?: Firebase.auth.signInAnonymously().await().user
                    ?: return@withContext AppResult.failure(
                        AppError.Unknown("익명 인증에 실패했습니다. Firebase 콘솔에서 Authentication > 익명 로그인을 활성화하세요."),
                    )
                val root = Firebase.storage.reference.child("backups/${user.uid}")

                var uploaded = 0
                var skipped = 0
                var totalBytes = 0L

                if (selection.recordings) {
                    val recordingsDir = File(context.filesDir, "recordings")
                    val localFiles = recordingsDir.listFiles { f -> f.extension == "m4a" }.orEmpty()
                    if (localFiles.isNotEmpty()) {
                        val remoteNames = runCatching {
                            root.child("recordings").listAll().await().items.map { it.name }.toSet()
                        }.getOrDefault(emptySet())
                        localFiles.forEach { file ->
                            if (file.name in remoteNames) {
                                skipped++
                            } else {
                                root.child("recordings/${file.name}")
                                    .putFile(Uri.fromFile(file)).await()
                                uploaded++
                                totalBytes += file.length()
                            }
                        }
                    }
                }

                if (selection.database) {
                    // WAL 내용을 본파일로 몰아넣은 뒤 스냅샷 복사본을 업로드
                    database.openHelper.writableDatabase
                        .query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                    val dbFile = context.getDatabasePath("opiczh.db")
                    if (dbFile.exists()) {
                        val snapshot = File(context.cacheDir, "backup-opiczh.db")
                        dbFile.copyTo(snapshot, overwrite = true)
                        try {
                            root.child("db/opiczh.db").putFile(Uri.fromFile(snapshot)).await()
                            uploaded++
                            totalBytes += snapshot.length()
                        } finally {
                            snapshot.delete()
                        }
                    }
                }

                dataStore.edit { it[lastBackupKey] = System.currentTimeMillis() }
                AppResult.success(BackupSummary(uploaded, skipped, totalBytes))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                AppResult.failure(
                    AppError.Network(
                        "백업 실패: ${t.message}. 처음이라면 Firebase 콘솔에서 " +
                            "Storage 시작(버킷 생성)과 Authentication 익명 로그인 활성화, 보안 규칙 설정이 필요합니다.",
                    ),
                )
            }
        }
    }
}
