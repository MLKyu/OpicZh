package com.mingeek.opiczh.core.ai.gemini

import com.mingeek.opiczh.core.common.CrashReporter
import com.mingeek.opiczh.core.data.settings.ModelChainStore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * 모델별 한도 초과(429) 상태를 기록·조회한다.
 * Gemini에는 잔여 쿼터 조회 API가 없으므로, 429 응답의 retryDelay를 그대로
 * "이 시각까지 이 모델 사용 불가"로 기록하고 그동안 체인의 다음 모델을 쓴다.
 */
@Singleton
class ModelQuotaGuard @Inject constructor(
    private val store: ModelChainStore,
    private val crashReporter: CrashReporter,
) {

    /** 모델별 연속 429 횟수. 성공([markRecovered])하면 리셋. 프로세스 생존 동안만 유지 */
    private val strikes = ConcurrentHashMap<String, Int>()

    /** 모델이 쿨다운 중이면 남은 시간(ms), 아니면 0 */
    suspend fun remainingCooldownMs(modelId: String, nowMs: Long = System.currentTimeMillis()): Long {
        val until = store.cooldowns.first()[modelId] ?: return 0L
        return (until - nowMs).coerceAtLeast(0L)
    }

    /** 429를 만난 모델을 쿨다운시킨다. 연속 429는 힌트와 무관하게 점점 길게 쉰다. */
    suspend fun markLimited(modelId: String, retryAfterSec: Int?, nowMs: Long = System.currentTimeMillis()) {
        val strike = strikes.merge(modelId, 1, Int::plus) ?: 1
        val cooldownSec = cooldownSec(strike, retryAfterSec)
        store.setCooldown(modelId, nowMs + cooldownSec * 1_000L, nowMs)
        crashReporter.log("quota: $modelId 한도 초과(연속 ${strike}회), ${cooldownSec}s 쿨다운")
    }

    /** 모델이 정상 응답하면 연속 429 기록을 지운다. */
    fun markRecovered(modelId: String) {
        strikes.remove(modelId)
    }

    /** 체인 전체가 막혔을 때 가장 빨리 풀리는 모델까지 남은 시간(초) */
    suspend fun soonestAvailableSec(
        modelIds: List<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): Int? {
        val cooldowns = store.cooldowns.first()
        val soonest = modelIds.mapNotNull { cooldowns[it] }.minOrNull() ?: return null
        return ((soonest - nowMs) / 1_000L).coerceAtLeast(1L).toInt()
    }

    companion object {
        /** 재시도가 429 직후 같은 분 창에 다시 부딪히지 않게 하는 여유 */
        private const val MARGIN_SEC = 5
        /** retryDelay가 없을 때: RPM 초과로 보고 다음 분 창까지 쉰다 */
        private const val DEFAULT_COOLDOWN_SEC = 75
        private const val MIN_COOLDOWN_SEC = 15
        /** 연속 429 에스컬레이션: 2회째 하한과 상한 (60s → 120s → … → 30분) */
        private const val ESCALATION_BASE_SEC = 60
        private const val ESCALATION_MAX_SEC = 1_800

        /**
         * 무료 티어의 일일 한도(RPD) 소진 429는 retryDelay를 분당 토큰 리필 기준으로
         * 짧게(수 초~수십 초) 알려주는 경우가 많다. 그 값을 믿고 쿨다운을 짧게 잡으면
         * 다음 문항에서 같은 모델을 또 두드려 429가 하루 종일 반복된다.
         * 그래서 같은 모델이 연속으로 429를 내면 힌트보다 긴 하한을 지수로 올린다.
         * (정직하게 긴 힌트는 그대로 존중 — 하한만 에스컬레이션한다)
         */
        internal fun cooldownSec(strike: Int, retryAfterSec: Int?): Int {
            val hinted = retryAfterSec?.plus(MARGIN_SEC) ?: DEFAULT_COOLDOWN_SEC
            val floor = if (strike <= 1) {
                MIN_COOLDOWN_SEC
            } else {
                val doublings = (strike - 2).coerceAtMost(10)
                (ESCALATION_BASE_SEC.toLong() shl doublings)
                    .coerceAtMost(ESCALATION_MAX_SEC.toLong())
                    .toInt()
            }
            return maxOf(hinted, floor)
        }
    }
}
