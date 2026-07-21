package com.mingeek.opiczh.core.ai.stt

import com.mingeek.opiczh.core.ai.ondevice.ModelStatus
import com.mingeek.opiczh.core.ai.ondevice.OnDeviceModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SttModelsTest {

    @Test
    fun `전부 설치되면 Installed에 합계 바이트`() {
        val combined = SttModels.combineStatuses(
            listOf(
                SttModels.SENSE_VOICE to ModelStatus.Installed(239_000_000L),
                SttModels.SENSE_VOICE_TOKENS to ModelStatus.Installed(300_000L),
                SttModels.SILERO_VAD to ModelStatus.Installed(2_200_000L),
            ),
        )
        assertEquals(ModelStatus.Installed(241_500_000L), combined)
    }

    @Test
    fun `하나라도 실패면 Failed가 다운로드 중보다 우선`() {
        val combined = SttModels.combineStatuses(
            listOf(
                SttModels.SENSE_VOICE to ModelStatus.Downloading(70),
                SttModels.SENSE_VOICE_TOKENS to ModelStatus.Failed("404"),
                SttModels.SILERO_VAD to ModelStatus.Installed(2_200_000L),
            ),
        )
        assertEquals(ModelStatus.Failed("404"), combined)
    }

    @Test
    fun `다운로드 중이면 용량 가중 진행률`() {
        val combined = SttModels.combineStatuses(
            listOf(
                SttModels.SENSE_VOICE to ModelStatus.Downloading(50),
                SttModels.SENSE_VOICE_TOKENS to ModelStatus.Installed(300_000L),
                SttModels.SILERO_VAD to ModelStatus.NotInstalled,
            ),
        )
        // (239*50 + 1*100 + 3*0) / 243 = 49.58 → 49
        assertEquals(ModelStatus.Downloading(49), combined)
    }

    @Test
    fun `다운로드 없이 대기만 있으면 Queued`() {
        val combined = SttModels.combineStatuses(
            listOf(
                SttModels.SENSE_VOICE to ModelStatus.Queued,
                SttModels.SENSE_VOICE_TOKENS to ModelStatus.NotInstalled,
                SttModels.SILERO_VAD to ModelStatus.Installed(2_200_000L),
            ),
        )
        assertEquals(ModelStatus.Queued, combined)
    }

    @Test
    fun `일부만 설치된 상태는 NotInstalled`() {
        val combined = SttModels.combineStatuses(
            listOf(
                SttModels.SENSE_VOICE to ModelStatus.Installed(239_000_000L),
                SttModels.SENSE_VOICE_TOKENS to ModelStatus.NotInstalled,
                SttModels.SILERO_VAD to ModelStatus.NotInstalled,
            ),
        )
        assertEquals(ModelStatus.NotInstalled, combined)
    }

    @Test
    fun `spec id와 파일명은 유일하고 LLM 카탈로그와 충돌하지 않는다`() {
        val ids = SttModels.ALL.map { it.id }
        val fileNames = SttModels.ALL.map { it.fileName }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(fileNames.size, fileNames.toSet().size)

        val llmIds = OnDeviceModels.ALL.map { it.id }.toSet()
        val llmFiles = OnDeviceModels.ALL.map { it.fileName }.toSet()
        assertTrue(ids.none { it in llmIds })
        assertTrue(fileNames.none { it in llmFiles })
    }

    @Test
    fun `총 용량 표기는 spec 합계`() {
        assertEquals(243, SttModels.TOTAL_APPROX_MB)
    }
}
