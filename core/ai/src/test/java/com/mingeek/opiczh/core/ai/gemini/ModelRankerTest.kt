package com.mingeek.opiczh.core.ai.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelRankerTest {

    @Test
    fun `ranks newer generation first, then pro over flash over lite`() {
        val ranked = ModelRanker.rank(
            listOf(
                "gemini-2.5-flash",
                "gemini-3.1-flash-lite",
                "gemini-3.5-flash",
                "gemini-3.5-pro",
                "gemini-2.5-pro",
            ),
        )
        assertEquals(
            listOf(
                "gemini-3.5-pro",
                "gemini-3.5-flash",
                "gemini-3.1-flash-lite",
                "gemini-2.5-pro",
                "gemini-2.5-flash",
            ),
            ranked,
        )
    }

    @Test
    fun `prefers stable over preview and snapshot at same tier`() {
        val ranked = ModelRanker.rank(
            listOf(
                "gemini-3.5-flash-preview-05-2026",
                "gemini-3.5-flash",
                "gemini-3.5-flash-001",
            ),
        )
        assertEquals(
            listOf("gemini-3.5-flash", "gemini-3.5-flash-001", "gemini-3.5-flash-preview-05-2026"),
            ranked,
        )
    }

    @Test
    fun `excludes specialized variants and non-gemini models`() {
        val ranked = ModelRanker.rank(
            listOf(
                "gemini-3.1-flash-tts-preview",
                "gemini-3.1-flash-live-preview",
                "gemini-3.1-flash-image",
                "gemini-3.5-flash-native-audio",
                "gemma-3-27b-it",
                "embedding-001",
                "gemini-3.5-flash",
            ),
        )
        assertEquals(listOf("gemini-3.5-flash"), ranked)
    }

    @Test
    fun `score is null for unrankable ids`() {
        assertNull(ModelRanker.score("veo-3"))
        assertNull(ModelRanker.score("gemini-3.5-flash-tts"))
        assertNull(ModelRanker.score("gemini-embedding-001"))
    }

    @Test
    fun `flash-lite ranks below flash of the same generation`() {
        val ranked = ModelRanker.rank(listOf("gemini-3.5-flash-lite", "gemini-3.5-flash"))
        assertEquals(listOf("gemini-3.5-flash", "gemini-3.5-flash-lite"), ranked)
    }

    @Test
    fun `deduplicates ids`() {
        val ranked = ModelRanker.rank(listOf("gemini-3.5-flash", "gemini-3.5-flash"))
        assertEquals(listOf("gemini-3.5-flash"), ranked)
    }
}
