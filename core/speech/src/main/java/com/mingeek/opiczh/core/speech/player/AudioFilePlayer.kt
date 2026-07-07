package com.mingeek.opiczh.core.speech.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 캐시된 음성 파일(WAV/M4A) 재생기. ExoPlayer가 오디오 포커스를 관리한다.
 * play()는 재생 완료까지 suspend — 시험 문항 재생 흐름을 순차 코드로 쓸 수 있다.
 */
@Singleton
class AudioFilePlayer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private var player: ExoPlayer? = null
    private var activeContinuation: CancellableContinuation<AppResult<Unit>>? = null
    private val playMutex = Mutex()

    suspend fun play(file: File): AppResult<Unit> = playMutex.withLock {
        if (!file.exists() || file.length() == 0L) {
            return@withLock AppResult.failure(AppError.Audio("재생할 파일이 없습니다"))
        }
        withContext(Dispatchers.Main) {
            val exo = obtainPlayer()
            suspendCancellableCoroutine<AppResult<Unit>> { cont ->
                activeContinuation = cont
                exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                exo.prepare()
                exo.play()
                cont.invokeOnCancellation {
                    activeContinuation = null
                    runCatching { exo.stop() }
                }
            }
        }
    }

    /** 재생 중이면 즉시 중단. 대기 중인 play()는 실패로 종료된다. */
    fun stop() {
        val exo = player ?: return
        exo.applicationLooper.let {
            android.os.Handler(it).post {
                resumeActive(AppResult.failure(AppError.Audio("재생이 중단되었습니다")))
                runCatching { exo.stop() }
            }
        }
    }

    fun releasePlayer() {
        val exo = player ?: return
        player = null
        android.os.Handler(exo.applicationLooper).post {
            runCatching { exo.release() }
        }
    }

    private fun obtainPlayer(): ExoPlayer {
        player?.let { return it }
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        val exo = ExoPlayer.Builder(context)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    resumeActive(AppResult.success(Unit))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                runCatching { exo.stop() }
                resumeActive(AppResult.failure(AppError.Audio("재생 오류: ${error.errorCodeName}")))
            }
        })
        player = exo
        return exo
    }

    private fun resumeActive(result: AppResult<Unit>) {
        val cont = activeContinuation ?: return
        activeContinuation = null
        if (cont.isActive) cont.resume(result)
    }
}
