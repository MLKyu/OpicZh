package com.mingeek.opiczh.core.ai.ondevice

/**
 * "한국어 기반 앱에서 OPIc 중국어 학습" 용도의 온디바이스 모델 점수화 (순수 로직).
 *
 * 모델의 임무: ① 간체 중국어 회화 상대·모범답안 생성(주 능력, 60%)
 *            ② 한국어로 교정 팁·문법 설명 작성(필수 능력, 40%)
 * 여기에 기기 적합성(S26 울트라, int4 파일 크기), 최신성, 인기, 접근성(게이트)을 가중한다.
 * 새 계열(예: Qwen4)이 나오면 휴리스틱이 모를 수 있으므로 최종 선택은 Gemini 판정이 보정한다.
 */
object ModelScoring {

    /** 계열별 언어 능력 프로파일 (0~10). zh=중국어, ko=한국어 */
    data class FamilyProfile(val family: String, val zh: Double, val ko: Double, val penalty: Double = 0.0)

    private val FAMILY_PROFILES = listOf(
        FamilyProfile("qwen3", zh = 10.0, ko = 8.0),
        FamilyProfile("qwen2.5", zh = 9.5, ko = 7.0),
        // R1 distill 계열은 사고 사슬(<think>)을 뱉어 회화 UX에 부적합
        FamilyProfile("deepseek", zh = 8.5, ko = 5.0, penalty = 2.0),
        FamilyProfile("gemma-3n", zh = 8.0, ko = 8.5),
        FamilyProfile("gemma", zh = 7.0, ko = 7.5),
        FamilyProfile("exaone", zh = 6.5, ko = 9.5),
        FamilyProfile("llama", zh = 6.0, ko = 5.5),
        FamilyProfile("phi", zh = 4.0, ko = 4.0),
        FamilyProfile("smollm", zh = 2.0, ko = 2.0),
    )

    private val UNKNOWN_PROFILE = FamilyProfile("unknown", zh = 5.0, ko = 5.0)

    /** LLM 채팅 용도가 아닌 리포지토리 제외 (ASR/임베딩/코딩 특화 등) */
    private val EXCLUDE_REPO = Regex(
        "(?i)(asr|embed|reranker|ocr|tts|whisper|guard|hammer|coder|function|-pt$|-pt-)",
    )

    /** 기기 특화/웹용 파일 변형 제외 */
    private val EXCLUDE_FILE = Regex(
        "(?i)(mt\\d{4}|sm\\d{4}|mediatek|qualcomm|tensor_g|_g5|web|f32)",
    )

    fun isChatLlmRepo(repoId: String): Boolean = !EXCLUDE_REPO.containsMatchIn(repoId)

    fun detectFamily(repoId: String): FamilyProfile {
        val lower = repoId.lowercase()
        return when {
            "qwen3" in lower -> profile("qwen3")
            "qwen2.5" in lower || "qwen2_5" in lower -> profile("qwen2.5")
            "deepseek" in lower -> profile("deepseek")
            "gemma-3n" in lower || "gemma3n" in lower -> profile("gemma-3n")
            "gemma" in lower -> profile("gemma")
            "exaone" in lower -> profile("exaone")
            "llama" in lower -> profile("llama")
            "phi" in lower -> profile("phi")
            "smollm" in lower -> profile("smollm")
            else -> UNKNOWN_PROFILE
        }
    }

    /** 이름에서 파라미터 수(B 단위) 추출: "4B"→4.0, "E2B"→2.0, "1.5B"→1.5, "270m"→0.27 */
    fun parseParamsB(name: String): Double? {
        val regex = Regex("(?i)(?:^|[-_/. ])e?(\\d+(?:\\.\\d+)?)([bm])(?=$|[-_/. ])")
        val match = regex.findAll(name).lastOrNull() ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return if (match.groupValues[2].equals("m", ignoreCase = true)) value / 1000.0 else value
    }

    /** S26 울트라(12GB+ RAM) 기준 크기 적합도: 3~5B가 품질/속도 스위트스팟 */
    fun sizeFit(paramsB: Double): Double = when {
        paramsB < 0.8 -> 0.3
        paramsB < 2.0 -> 0.6
        paramsB < 3.0 -> 0.85
        paramsB <= 5.0 -> 1.0
        paramsB <= 8.0 -> 0.8
        paramsB <= 9.0 -> 0.5
        else -> 0.0
    }

    data class ScoreInput(
        val repoId: String,
        val paramsB: Double,
        val gatedNeedsToken: Boolean,
        val hasHfToken: Boolean,
        val downloads: Long,
        val daysSinceUpdate: Long?,
        val isInstruct: Boolean,
    )

    fun score(input: ScoreInput): Double {
        val profile = detectFamily(input.repoId)
        // 중국어 60% / 한국어 40% — 이 앱의 이중언어 요구 반영
        val language = profile.zh * 0.6 + profile.ko * 0.4
        val recency = when {
            input.daysSinceUpdate == null -> 0.0
            input.daysSinceUpdate <= 120 -> 1.0
            input.daysSinceUpdate <= 365 -> 0.5
            else -> 0.0
        }
        val popularity = minOf(kotlin.math.log10(input.downloads + 1.0), 6.0) * 0.25
        val tokenPenalty = if (input.gatedNeedsToken && !input.hasHfToken) 1.5 else 0.0
        val instructBonus = if (input.isInstruct) 0.8 else 0.0

        return language * 0.8 +
            sizeFit(input.paramsB) * 3.0 +
            instructBonus +
            recency +
            popularity -
            tokenPenalty -
            profile.penalty
    }

    fun isInstructName(name: String): Boolean {
        val lower = name.lowercase()
        return "instruct" in lower || "-it" in lower || "_it" in lower || "chat" in lower
    }

    /**
     * 리포지토리의 .litertlm 파일 중 이 앱에 맞는 것 선택:
     * 기기코드 변형 제외 → int4/q4 선호 → (크기 알면) 작은 것, 모르면 이름 짧은 것
     */
    fun pickBestFile(files: List<Pair<String, Long?>>): Pair<String, Long?>? {
        val usable = files
            .filter { it.first.endsWith(".litertlm", ignoreCase = true) }
            .filterNot { EXCLUDE_FILE.containsMatchIn(it.first) }
        if (usable.isEmpty()) return null
        val int4 = usable.filter { "(?i)(int4|q4)".toRegex().containsMatchIn(it.first) }
        val pool = int4.ifEmpty { usable }
        return pool.sortedWith(
            compareBy({ it.second ?: Long.MAX_VALUE }, { it.first.length }),
        ).first()
    }

    private fun profile(family: String): FamilyProfile =
        FAMILY_PROFILES.first { it.family == family }
}
