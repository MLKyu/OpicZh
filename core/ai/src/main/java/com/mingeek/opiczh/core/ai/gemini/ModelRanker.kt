package com.mingeek.opiczh.core.ai.gemini

/**
 * models.list 결과를 "좋은 모델부터" 서열화한다. 사용자가 모델을 고르지 않는 대신,
 * 이 순서대로 쓰다가 한도가 찬 모델은 건너뛰며 자동 전환한다.
 *
 * 점수 규칙 (검증 가능한 휴리스틱):
 * 1. 세대(버전 숫자)가 높을수록 우선 — 3.5 > 3.1 > 2.5
 * 2. 같은 세대에서는 pro > flash > flash-lite
 * 3. 같은 급에서는 정식판 > 버전 스냅샷(-001 등) > preview/exp
 * 채점·전사에 쓸 수 없는 특화 변형(tts/image/live/audio/embedding)은 제외한다.
 */
object ModelRanker {

    // flash-lite를 flash보다 먼저 — 교대는 순서대로 매칭되므로 뒤에 두면 flash+접미사 "lite"로 오인한다
    private val TEXT_MODEL_PATTERN = Regex("""^gemini-(\d+(?:\.\d+)?)-(pro|flash-lite|flash)(?:-(.+))?$""")
    private val EXCLUDED_VARIANTS = listOf("tts", "image", "live", "audio", "embedding", "veo")

    /** 텍스트 생성 체인에 넣을 수 있는 모델만 점수순으로 반환 */
    fun rank(modelIds: List<String>): List<String> =
        modelIds.distinct()
            .mapNotNull { id -> score(id)?.let { id to it } }
            .sortedByDescending { (_, score) -> score }
            .map { (id, _) -> id }

    /** 체인 부적합 모델은 null */
    fun score(modelId: String): Double? {
        val match = TEXT_MODEL_PATTERN.matchEntire(modelId) ?: return null
        val version = match.groupValues[1].toDoubleOrNull() ?: return null
        val tier = when (match.groupValues[2]) {
            "pro" -> 3.0
            "flash" -> 2.0
            else -> 1.0 // flash-lite
        }
        val suffix = match.groupValues[3]
        if (EXCLUDED_VARIANTS.any { suffix.contains(it) }) return null
        val stability = when {
            suffix.isEmpty() -> 0.6
            suffix.contains("preview") || suffix.contains("exp") -> 0.0
            else -> 0.3
        }
        return version * 10 + tier + stability
    }
}
