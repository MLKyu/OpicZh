package com.mingeek.opiczh.core.model

/** 복습 평가 (SM-2 계열) */
enum class SrsRating(val ko: String) {
    AGAIN("다시"),
    HARD("어려움"),
    GOOD("보통"),
    EASY("쉬움"),
}

/** 간격 반복 카드. front=한국어 뜻/의도, back=올바른 중국어 표현 */
data class SrsCard(
    val id: String,
    val front: String,
    val back: String,
    val pinyin: String? = null,
    /** 출처: 시험 교정, 템플릿 등 */
    val sourceTag: String = "",
    val dueAtEpochMs: Long = 0,
    val intervalDays: Double = 0.0,
    val ease: Double = 2.5,
    val reps: Int = 0,
)

/**
 * SM-2 단순화 버전. 외부 시간 주입(now)으로 결정적 — 단위 테스트 대상.
 */
object SrsScheduler {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MIN_EASE = 1.3

    fun review(card: SrsCard, rating: SrsRating, nowEpochMs: Long): SrsCard {
        return when (rating) {
            SrsRating.AGAIN -> card.copy(
                reps = 0,
                intervalDays = 0.0,
                ease = (card.ease - 0.2).coerceAtLeast(MIN_EASE),
                dueAtEpochMs = nowEpochMs + 10 * 60 * 1000, // 10분 후 다시
            )
            SrsRating.HARD -> {
                val interval = if (card.reps == 0) 0.5 else card.intervalDays * 1.2
                card.copy(
                    reps = card.reps + 1,
                    intervalDays = interval,
                    ease = (card.ease - 0.15).coerceAtLeast(MIN_EASE),
                    dueAtEpochMs = nowEpochMs + (interval * DAY_MS).toLong(),
                )
            }
            SrsRating.GOOD -> {
                val interval = when (card.reps) {
                    0 -> 1.0
                    1 -> 3.0
                    else -> card.intervalDays * card.ease
                }
                card.copy(
                    reps = card.reps + 1,
                    intervalDays = interval,
                    dueAtEpochMs = nowEpochMs + (interval * DAY_MS).toLong(),
                )
            }
            SrsRating.EASY -> {
                val interval = when (card.reps) {
                    0 -> 2.0
                    1 -> 5.0
                    else -> card.intervalDays * card.ease * 1.3
                }
                card.copy(
                    reps = card.reps + 1,
                    intervalDays = interval,
                    ease = card.ease + 0.1,
                    dueAtEpochMs = nowEpochMs + (interval * DAY_MS).toLong(),
                )
            }
        }
    }
}
