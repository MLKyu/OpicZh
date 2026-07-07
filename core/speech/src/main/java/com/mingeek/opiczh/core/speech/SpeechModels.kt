package com.mingeek.opiczh.core.speech

import com.mingeek.opiczh.core.common.AppResult

/** 말하기(재생) 진행 상태 */
sealed interface SpeakerState {
    data object Idle : SpeakerState

    /** 원격 TTS 합성/캐시 준비 중 */
    data class Preparing(val chunk: Int, val totalChunks: Int) : SpeakerState

    data class Playing(val chunk: Int, val totalChunks: Int) : SpeakerState
}

/** 녹음 상태 */
sealed interface RecorderState {
    data object Idle : RecorderState
    data class Recording(val startedElapsedRealtimeMs: Long) : RecorderState
}

/**
 * 클라우드 TTS(자연 음성) 합성기 추상화.
 * 구현은 core:ai의 GeminiTtsClient가 제공한다 (의존 역전).
 */
interface RemoteTtsSynthesizer {
    /** 모델·보이스·텍스트가 반영된 캐시 키 */
    suspend fun cacheKey(text: String): String

    /** WAV 바이트를 반환 */
    suspend fun synthesize(text: String): AppResult<ByteArray>
}
