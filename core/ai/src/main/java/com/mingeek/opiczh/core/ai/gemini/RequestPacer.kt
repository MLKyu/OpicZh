package com.mingeek.opiczh.core.ai.gemini

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 모델별 분당 호출 수를 클라이언트에서 미리 제한한다 (무료 티어 RPM 방어).
 * 13문항 채점처럼 호출이 몰릴 때 429를 맞고 물러나는 대신, 애초에 분당 창 안으로
 * 간격을 벌려 내보낸다. 이 앱은 1인용이라 로컬 카운팅이 실제 사용량과 일치한다.
 *
 * @param nowMs 테스트용 시계 주입 지점 — 프로덕션은 시스템 시계
 */
@Singleton
class RequestPacer internal constructor(
    private val nowMs: () -> Long,
) {

    @Inject
    constructor() : this(System::currentTimeMillis)

    private val mutex = Mutex()
    private val history = mutableMapOf<String, ArrayDeque<Long>>()

    /** 모델의 분당 창에 자리가 날 때까지 대기한 뒤 슬롯을 점유한다. */
    suspend fun awaitSlot(modelId: String) {
        val limit = perMinute(modelId)
        while (true) {
            val waitMs = mutex.withLock {
                val now = nowMs()
                val calls = history.getOrPut(modelId) { ArrayDeque() }
                while (calls.isNotEmpty() && now - calls.first() >= WINDOW_MS) calls.removeFirst()
                if (calls.size < limit) {
                    calls.addLast(now)
                    0L
                } else {
                    WINDOW_MS - (now - calls.first()) + SLACK_MS
                }
            }
            if (waitMs <= 0L) return
            delay(waitMs)
        }
    }

    /**
     * 무료 티어 실측 대비 보수적 기본값 (pro 5 / flash 10 / flash-lite 15 RPM).
     * 실제 한도가 더 낮아도 429 → retryDelay 대기 → 체인 전환으로 회복된다.
     */
    private fun perMinute(modelId: String): Int = when {
        modelId.contains("tts") -> 3
        modelId.contains("pro") -> 4
        modelId.contains("flash-lite") -> 12
        else -> 8
    }

    private companion object {
        const val WINDOW_MS = 60_000L
        const val SLACK_MS = 100L
    }
}
