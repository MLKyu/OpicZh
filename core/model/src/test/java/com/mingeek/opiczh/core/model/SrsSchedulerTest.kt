package com.mingeek.opiczh.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrsSchedulerTest {

    private val now = 1_000_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000
    private val newCard = SrsCard(id = "c1", front = "안녕하세요", back = "你好")

    @Test
    fun `again resets reps and schedules in 10 minutes`() {
        val reviewed = SrsScheduler.review(newCard.copy(reps = 5, intervalDays = 10.0), SrsRating.AGAIN, now)
        assertEquals(0, reviewed.reps)
        assertEquals(now + 10 * 60 * 1000, reviewed.dueAtEpochMs)
    }

    @Test
    fun `good progression grows interval`() {
        var card = newCard
        card = SrsScheduler.review(card, SrsRating.GOOD, now)
        assertEquals(1.0, card.intervalDays, 0.001)
        assertEquals(now + dayMs, card.dueAtEpochMs)

        card = SrsScheduler.review(card, SrsRating.GOOD, now)
        assertEquals(3.0, card.intervalDays, 0.001)

        card = SrsScheduler.review(card, SrsRating.GOOD, now)
        assertEquals(3.0 * 2.5, card.intervalDays, 0.001)
    }

    @Test
    fun `ease never drops below floor`() {
        var card = newCard
        repeat(20) { card = SrsScheduler.review(card, SrsRating.AGAIN, now) }
        assertTrue(card.ease >= 1.3)
    }

    @Test
    fun `easy grows faster than good`() {
        val good = SrsScheduler.review(newCard, SrsRating.GOOD, now)
        val easy = SrsScheduler.review(newCard, SrsRating.EASY, now)
        assertTrue(easy.dueAtEpochMs > good.dueAtEpochMs)
        assertTrue(easy.ease > good.ease)
    }
}
