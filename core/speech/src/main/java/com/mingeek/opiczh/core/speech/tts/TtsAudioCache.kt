package com.mingeek.opiczh.core.speech.tts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 합성된 음성 파일 캐시. 같은 문장은 다시 합성하지 않는다 (쿼터·지연 절약,
 * 시험 중 네트워크 문제에도 재생 보장).
 *
 * filesDir에 저장한다 — cacheDir는 저장공간 부족 시 시스템이 비울 수 있어,
 * 무료 TTS 한도를 들여 합성한 문항 음성이 사라지면 다음 시험에서 한도를 다시 쓰게 된다.
 */
@Singleton
class TtsAudioCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val dir: File by lazy {
        val target = File(context.filesDir, "tts").apply { mkdirs() }
        migrateLegacyCache(target)
        target
    }

    /** 구버전 cacheDir/tts에 남은 합성분을 옮겨 재합성을 막는다 */
    private fun migrateLegacyCache(target: File) {
        val legacy = File(context.cacheDir, "tts")
        if (!legacy.isDirectory) return
        legacy.listFiles()?.forEach { file ->
            val moved = File(target, file.name)
            if (moved.exists() || !file.renameTo(moved)) file.delete()
        }
        legacy.delete()
    }

    fun get(key: String): File? =
        File(dir, "$key.wav").takeIf { it.exists() && it.length() > MIN_VALID_BYTES }

    /** 원자적 쓰기(tmp → rename)로 손상 파일 방지 */
    fun put(key: String, wavBytes: ByteArray): File {
        val target = File(dir, "$key.wav")
        val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(wavBytes)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val MIN_VALID_BYTES = 44L // WAV 헤더 크기
    }
}
