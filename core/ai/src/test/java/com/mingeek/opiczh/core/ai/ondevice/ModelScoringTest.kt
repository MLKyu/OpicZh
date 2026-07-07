package com.mingeek.opiczh.core.ai.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelScoringTest {

    private fun input(
        repoId: String,
        paramsB: Double,
        downloads: Long = 10_000,
        days: Long? = 30,
        instruct: Boolean = true,
    ) = ModelScoring.ScoreInput(
        repoId = repoId,
        paramsB = paramsB,
        downloads = downloads,
        daysSinceUpdate = days,
        isInstruct = instruct,
    )

    @Test
    fun `parses parameter counts from various naming styles`() {
        assertEquals(4.0, ModelScoring.parseParamsB("litert-community/Qwen3-4B-Instruct-2507")!!, 0.001)
        assertEquals(1.5, ModelScoring.parseParamsB("Qwen2.5-1.5B-Instruct")!!, 0.001)
        assertEquals(2.0, ModelScoring.parseParamsB("google/gemma-3n-E2B-it-litert-lm")!!, 0.001)
        assertEquals(0.27, ModelScoring.parseParamsB("gemma-3-270m-it")!!, 0.001)
        assertEquals(1.0, ModelScoring.parseParamsB("Gemma3-1B-IT")!!, 0.001)
        assertNull(ModelScoring.parseParamsB("some-model-without-size"))
    }

    @Test
    fun `korean opic chinese use case prefers qwen3 4b over small gemma`() {
        val qwen = ModelScoring.score(input("litert-community/Qwen3-4B-Instruct-2507", 4.0))
        val gemma1b = ModelScoring.score(input("litert-community/Gemma3-1B-IT", 1.0))
        assertTrue("Qwen3-4B($qwen)이 Gemma3-1B($gemma1b)보다 높아야 함", qwen > gemma1b)
    }

    @Test
    fun `bilingual weighting beats chinese-only strength`() {
        // DeepSeek distill: 중국어는 강하지만 한국어 약함 + 사고사슬 페널티
        val qwen = ModelScoring.score(input("litert-community/Qwen3-4B-Instruct-2507", 4.0))
        val deepseek = ModelScoring.score(input("litert-community/DeepSeek-R1-Distill-Qwen-1.5B", 1.5))
        assertTrue(qwen > deepseek)
    }

    @Test
    fun `gemma-4 is its own family with strong korean and parses E-params`() {
        val g4 = ModelScoring.detectFamily("litert-community/gemma-4-E2B-it-litert-lm")
        assertEquals("gemma-4", g4.family)
        // 최신 Gemma는 구형 gemma보다 한국어 설명 능력이 높게 잡혀 있다
        assertTrue(g4.ko >= ModelScoring.detectFamily("litert-community/Gemma3-1B-IT").ko)
        assertEquals(2.0, ModelScoring.parseParamsB("litert-community/gemma-4-E2B-it-litert-lm")!!, 0.001)
        assertEquals(4.0, ModelScoring.parseParamsB("litert-community/gemma-4-E4B-it-litert-lm")!!, 0.001)
    }

    @Test
    fun `device-specific gemma-4 variants excluded, generic file picked`() {
        val mb = 1_048_576L
        val picked = ModelScoring.pickBestFile(
            listOf(
                "gemma-4-E2B-it-web.litertlm" to 1915 * mb,
                "gemma-4-E2B-it.litertlm" to 2468 * mb,
                "gemma-4-E2B-it_Google_Tensor_G5.litertlm" to 3770 * mb,
                "gemma-4-E2B-it_intel_LNL.litertlm" to 2823 * mb,
                "gemma-4-E2B-it_qualcomm_sm8750.litertlm" to 2877 * mb,
            ),
        )
        // web/tensor/intel/qualcomm 변형 전부 제외 → 범용 파일만 남는다
        assertEquals("gemma-4-E2B-it.litertlm", picked?.first)
    }

    @Test
    fun `oversized models score low on device fit`() {
        assertTrue(ModelScoring.sizeFit(4.0) > ModelScoring.sizeFit(14.0))
        assertEquals(0.0, ModelScoring.sizeFit(14.0), 0.001)
    }

    @Test
    fun `non chat repos are excluded`() {
        assertFalse(ModelScoring.isChatLlmRepo("litert-community/Qwen3-ASR-0.6B"))
        assertFalse(ModelScoring.isChatLlmRepo("litert-community/Qwen2.5-Coder-3B-Instruct"))
        assertFalse(ModelScoring.isChatLlmRepo("litert-community/embeddinggemma-300m"))
        assertTrue(ModelScoring.isChatLlmRepo("litert-community/Qwen3-4B-Instruct-2507"))
    }

    @Test
    fun `file picker avoids device-specific variants and prefers int4`() {
        val picked = ModelScoring.pickBestFile(
            listOf(
                "Gemma3-1B-IT_q4_ekv1280_sm8550.litertlm" to 500L,
                "Gemma3-1B-IT_q8_ekv1280_Google_Tensor_G5.litertlm" to 900L,
                "gemma3-1b-it-int4.litertlm" to 550L,
                "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm" to 560L,
                "README.md" to 1L,
            ),
        )
        assertEquals("gemma3-1b-it-int4.litertlm", picked?.first)
    }

    @Test
    fun `file picker returns null when only device variants exist`() {
        val picked = ModelScoring.pickBestFile(
            listOf("model.mediatek.mt6993.litertlm" to 100L),
        )
        assertNull(picked)
    }
}
