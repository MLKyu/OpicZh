package com.mingeek.opiczh.core.speech.record

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.speech.RecorderState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OPIc 답변 녹음기.
 *
 * SpeechRecognizer를 쓰지 않는 이유: 침묵 컷오프·세션 제한 때문에 1~2분 이상의
 * 답변에서 끊긴다(장문 STT가 뻑나는 주원인). 대신 파일로 길이 제한 없이 녹음한 뒤
 * Gemini 오디오 이해로 전사+채점한다.
 *
 * 포맷: m4a(AAC) 16kHz mono 64kbps — 2분 녹음 ≈ 1MB로 Gemini 인라인 한도(20MB) 안쪽.
 * 녹음 중에는 마이크 타입 Foreground Service로 백그라운드 전환에도 유실을 막는다.
 */
@Singleton
class AnswerRecorder @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    fun start(namePrefix: String = "answer"): AppResult<Unit> {
        if (_state.value is RecorderState.Recording) {
            return AppResult.failure(AppError.Audio("이미 녹음 중입니다"))
        }
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "${namePrefix}_${System.currentTimeMillis()}.m4a")

        return try {
            recorder = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioChannels(1)
                setAudioEncodingBitRate(64_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            currentFile = file
            RecordingService.start(context)
            _state.value = RecorderState.Recording(SystemClock.elapsedRealtime())
            AppResult.success(Unit)
        } catch (se: SecurityException) {
            cleanup(deleteFile = true)
            AppResult.failure(AppError.Audio("마이크 권한이 없습니다"))
        } catch (t: Throwable) {
            cleanup(deleteFile = true)
            AppResult.failure(AppError.Audio("녹음 시작 실패: ${t.message}"))
        }
    }

    /** 정지 후 녹음 파일 반환 */
    fun stop(): AppResult<File> {
        val file = currentFile
        return try {
            recorder?.stop()
            cleanup(deleteFile = false)
            if (file != null && file.exists() && file.length() > 0) {
                AppResult.success(file)
            } else {
                AppResult.failure(AppError.Audio("녹음 파일이 비어 있습니다"))
            }
        } catch (t: Throwable) {
            // 너무 짧은 녹음 등 — stop()이 던질 수 있다
            cleanup(deleteFile = true)
            AppResult.failure(AppError.Audio("녹음이 너무 짧거나 저장에 실패했습니다"))
        }
    }

    fun cancel() {
        runCatching { recorder?.stop() }
        cleanup(deleteFile = true)
    }

    /** UI 파형용 진폭 (0~32767, 읽을 때마다 리셋됨) */
    fun pollMaxAmplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    private fun cleanup(deleteFile: Boolean) {
        runCatching { recorder?.release() }
        recorder = null
        if (deleteFile) {
            currentFile?.delete()
            currentFile = null
        }
        RecordingService.stop(context)
        _state.value = RecorderState.Idle
    }
}
