package com.mingeek.opiczh.core.ai.stt

import com.mingeek.opiczh.core.ai.ondevice.ModelStatus
import com.mingeek.opiczh.core.ai.ondevice.OnDeviceModelSpec

/**
 * 온디바이스 STT(음성 인식) 파일 카탈로그.
 *
 * LLM 카탈로그(OnDeviceModels.ALL)와 완전히 분리된 별도 목록이다 — 추천(ModelRecommender)·
 * 교체(reconcile)·활성 포인터의 대상이 아니고, 파일 존재 여부만으로 설치를 판정한다.
 * [OnDeviceModelSpec]을 재사용하는 이유: 다운로드 파이프라인(OnDeviceModelManager →
 * ModelDownloadWorker)이 url/fileName/displayName만 쓰는 범용 구조라서다.
 *
 * SenseVoice int8 (중/영/일/한/광둥어): 비스트리밍 인식이라 30초 초과 오디오는
 * Silero VAD로 발화 세그먼트를 나눠 인식한다 — 그래서 VAD 모델까지 한 세트다.
 * URL·용량은 실측값(2026-07, HF API/GitHub 릴리스 확인).
 */
object SttModels {

    val SENSE_VOICE = OnDeviceModelSpec(
        id = "stt-sensevoice-int8",
        displayName = "SenseVoice 음성 인식 모델",
        description = "중국어 특화 온디바이스 음성 인식 (중/영/일/한 지원). 무료·토큰 불필요.",
        url = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
        fileName = "sensevoice.int8.onnx",
        approxSizeMb = 239,
    )

    val SENSE_VOICE_TOKENS = OnDeviceModelSpec(
        id = "stt-sensevoice-tokens",
        displayName = "SenseVoice 토큰 사전",
        description = "SenseVoice 모델의 출력 토큰 사전 파일.",
        url = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt",
        fileName = "sensevoice.tokens.txt",
        approxSizeMb = 1,
    )

    val SILERO_VAD = OnDeviceModelSpec(
        id = "stt-silero-vad",
        displayName = "Silero VAD (발화 구간 감지)",
        description = "긴 녹음을 발화 단위로 잘라 인식 정확도를 지키는 보조 모델.",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        fileName = "silero_vad.onnx",
        approxSizeMb = 3,
    )

    /** 전부 있어야 STT가 동작한다 — 설치 판정은 세 파일 모두 존재 */
    val ALL = listOf(SENSE_VOICE, SENSE_VOICE_TOKENS, SILERO_VAD)

    /** 설정 화면 표기용 총 용량 */
    val TOTAL_APPROX_MB = ALL.sumOf { it.approxSizeMb }

    /**
     * 파일별 상태를 STT 세트 하나의 논리 상태로 합친다.
     * 우선순위: Failed > Downloading > Queued > 전부 Installed > NotInstalled.
     * Downloading 진행률은 파일 용량 가중 평균(Installed=100%로 계산) — 사실상
     * 대용량인 모델 파일의 진행률을 따라간다.
     */
    fun combineStatuses(statuses: List<Pair<OnDeviceModelSpec, ModelStatus>>): ModelStatus {
        require(statuses.isNotEmpty()) { "상태 목록이 비어 있습니다" }
        statuses.firstOrNull { it.second is ModelStatus.Failed }?.let { return it.second }

        if (statuses.any { it.second is ModelStatus.Downloading }) {
            val totalWeight = statuses.sumOf { it.first.approxSizeMb }
            val weighted = statuses.sumOf { (spec, status) ->
                val pct = when (status) {
                    is ModelStatus.Downloading -> status.progressPct
                    is ModelStatus.Installed -> 100
                    else -> 0
                }
                spec.approxSizeMb * pct
            }
            return ModelStatus.Downloading((weighted / totalWeight).coerceIn(0, 100))
        }

        if (statuses.any { it.second is ModelStatus.Queued }) return ModelStatus.Queued

        if (statuses.all { it.second is ModelStatus.Installed }) {
            return ModelStatus.Installed(
                statuses.sumOf { (it.second as ModelStatus.Installed).sizeBytes },
            )
        }
        return ModelStatus.NotInstalled
    }
}
