package com.mingeek.opiczh.core.common

import kotlinx.coroutines.flow.Flow

/** 백업 대상 카테고리 선택 (개별 파일 선택 대신 카테고리 단위) */
data class BackupSelection(
    /** 학습 기록 DB: 시험 세션·리포트·SRS 카드 */
    val database: Boolean = true,
    /** 시험·연습 녹음 파일 (이미 올라간 파일은 건너뜀) */
    val recordings: Boolean = true,
) {
    val isEmpty: Boolean get() = !database && !recordings
}

data class BackupSummary(
    val uploadedFiles: Int,
    val skippedFiles: Int,
    val totalBytes: Long,
)

/**
 * 요청형(수동) 클라우드 백업. 자동/실시간 백업은 하지 않는다.
 * API 키·토큰·TTS 캐시·온디바이스 모델은 어떤 경우에도 업로드하지 않는다.
 * 구현: :app의 Firebase Storage 바인딩.
 */
interface CloudBackup {
    /** 마지막 백업 완료 시각 (epoch ms), 없으면 null */
    val lastBackupAtMs: Flow<Long?>

    suspend fun backupNow(selection: BackupSelection): AppResult<BackupSummary>
}
