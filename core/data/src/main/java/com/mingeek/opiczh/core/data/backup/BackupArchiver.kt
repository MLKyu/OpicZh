package com.mingeek.opiczh.core.data.backup

import android.content.Context
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.data.db.OpicDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 백업 대상 카테고리 선택 (개별 파일 선택 대신 카테고리 단위) */
data class BackupSelection(
    /** 학습 기록 DB: 시험 세션·리포트·SRS 카드 */
    val database: Boolean = true,
    /** 시험·연습 녹음 파일 */
    val recordings: Boolean = true,
) {
    val isEmpty: Boolean get() = !database && !recordings
}

/**
 * 무료 백업: 선택한 항목을 zip 하나로 묶는다.
 * 저장 위치는 UI가 SAF(시스템 문서 선택기)로 받으므로 Google Drive·다운로드 등
 * 사용자가 원하는 곳에 저장된다 — 요금제·서버·콘솔 설정 불필요.
 * API 키·토큰·캐시·온디바이스 모델은 어떤 경우에도 포함하지 않는다.
 */
@Singleton
class BackupArchiver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: OpicDatabase,
) {

    /** zip을 캐시에 생성해 반환한다. 저장(SAF 복사) 후 호출부가 삭제한다. */
    suspend fun createArchive(selection: BackupSelection): AppResult<File> {
        if (selection.isEmpty) {
            return AppResult.failure(AppError.BadRequest("백업할 항목을 선택하세요"))
        }
        return withContext(Dispatchers.IO) {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                val zipFile = File(context.cacheDir, "OpicZh-backup-$stamp.zip")

                ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                    if (selection.database) {
                        // WAL 내용을 본파일로 몰아넣은 뒤 스냅샷을 담는다
                        database.openHelper.writableDatabase
                            .query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                        val dbFile = context.getDatabasePath("opiczh.db")
                        if (dbFile.exists()) zip.putFile(dbFile, "db/opiczh.db")
                    }
                    if (selection.recordings) {
                        File(context.filesDir, "recordings")
                            .listFiles { f -> f.extension == "m4a" }
                            .orEmpty()
                            .forEach { file -> zip.putFile(file, "recordings/${file.name}") }
                    }
                }

                if (zipFile.length() <= EMPTY_ZIP_BYTES) {
                    zipFile.delete()
                    AppResult.failure(AppError.BadRequest("백업할 데이터가 아직 없습니다"))
                } else {
                    AppResult.success(zipFile)
                }
            } catch (t: Throwable) {
                AppResult.failure(AppError.Unknown("백업 파일 생성 실패: ${t.message}"))
            }
        }
    }

    private fun ZipOutputStream.putFile(file: File, entryName: String) {
        putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(this) }
        closeEntry()
    }

    private companion object {
        /** 항목이 하나도 안 담긴 zip의 대략적 크기 */
        const val EMPTY_ZIP_BYTES = 100L
    }
}
