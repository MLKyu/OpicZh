package com.mingeek.opiczh.core.ai.ondevice

import com.mingeek.opiczh.core.ai.LlmRequest
import com.mingeek.opiczh.core.ai.gemini.GeminiEngine
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.getOrNull
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
private data class HfModelDto(
    val modelId: String? = null,
    val id: String? = null,
    /** false | "auto" | "manual" — 타입이 섞여 있어 JsonElement로 받는다 */
    val gated: JsonElement? = null,
    val downloads: Long? = null,
    val likes: Int? = null,
    val lastModified: String? = null,
    val siblings: List<HfSiblingDto>? = null,
) {
    val repoId: String get() = modelId ?: id ?: ""
    val needsToken: Boolean
        get() = when (val g = gated) {
            null -> false
            is JsonPrimitive -> g.booleanOrNull != false
            else -> true
        }
}

@Serializable
private data class HfSiblingDto(val rfilename: String, val size: Long? = null)

@Serializable
private data class JudgeVerdict(val bestRepoId: String, val reasonKo: String)

/**
 * "한국어 UI 앱에서 OPIc 중국어 학습"에 가장 적합한 온디바이스 모델 추천기.
 *
 * 1) HuggingFace API에서 LiteRT(.litertlm) 모델을 실시간 발견 — URL/파일명/용량/게이팅은
 *    항상 사실 데이터에서 나온다 (LLM 환각 방지)
 * 2) [ModelScoring]으로 중국어(60%)+한국어(40%) 이중언어·크기·최신성 점수화
 * 3) 온라인이면 Gemini가 상위 후보 중 최종 선택 + 한국어 추천 사유 작성
 *    (새 모델 계열이 나와도 판정이 흡수) — 오프라인이면 규칙 기반 폴백
 */
@Singleton
class ModelRecommender @Inject constructor(
    private val gemini: GeminiEngine,
) {

    // Gemini 클라이언트와 분리된 무인증 클라이언트 (키 유출 방지)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private data class Candidate(
        val repoId: String,
        val fileName: String,
        val fileSizeBytes: Long?,
        val paramsB: Double,
        val downloads: Long,
        val daysSinceUpdate: Long?,
        val score: Double,
    ) {
        val url: String get() = "https://huggingface.co/$repoId/resolve/main/$fileName"
        val approxSizeMb: Int
            get() = fileSizeBytes?.let { (it / 1_000_000).toInt() }
                ?: (paramsB * 620).toInt() // int4 대략치
    }

    suspend fun recommend(): AppResult<RecommendationRecord> =
        withContext(Dispatchers.IO) {
            try {
                val repos = fetchRepoList()
                if (repos.isEmpty()) {
                    return@withContext AppResult.failure(
                        AppError.Network("HuggingFace에서 모델 목록을 가져오지 못했습니다"),
                    )
                }

                val candidates = repos.mapNotNull { toCandidate(it) }
                if (candidates.isEmpty()) {
                    return@withContext AppResult.failure(
                        AppError.Unknown("조건에 맞는 .litertlm 모델을 찾지 못했습니다"),
                    )
                }

                val top = candidates.sortedByDescending { it.score }.take(5)
                    .mapNotNull { enrichWithDetail(it) }
                    .filter { (it.fileSizeBytes ?: 0) <= MAX_FILE_BYTES }
                if (top.isEmpty()) {
                    return@withContext AppResult.failure(
                        AppError.Unknown("기기에 적합한 크기의 모델을 찾지 못했습니다"),
                    )
                }

                val judged = judgeWithGemini(top)
                val (chosen, reason, decidedBy) = judged
                    ?: Triple(top.first(), heuristicReason(top.first()), "규칙 기반")

                AppResult.success(
                    RecommendationRecord(
                        spec = chosen.toSpec(),
                        reasonKo = reason,
                        decidedBy = decidedBy,
                        checkedAtEpochMs = System.currentTimeMillis(),
                        candidatesConsidered = candidates.size,
                    ),
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                AppResult.failure(AppError.Network("모델 추천 실패: ${t.message}"))
            }
        }

    // --- HuggingFace 조회 ---

    private fun fetchRepoList(): List<HfModelDto> {
        val community = fetchJsonList(
            "https://huggingface.co/api/models?author=litert-community&full=true&limit=300",
        )
        val googleLitert = fetchJsonList(
            "https://huggingface.co/api/models?author=google&search=litert&full=true&limit=50",
        )
        return community + googleLitert
    }

    private fun fetchJsonList(url: String): List<HfModelDto> {
        val request = Request.Builder().url(url).build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                json.decodeFromString<List<HfModelDto>>(response.body?.string().orEmpty())
            }
        }.getOrDefault(emptyList())
    }

    /** 상위 후보만 상세 조회(blobs=true)해 정확한 파일 크기 확보 */
    private fun enrichWithDetail(candidate: Candidate): Candidate? {
        if (candidate.fileSizeBytes != null) return candidate
        val request = Request.Builder()
            .url("https://huggingface.co/api/models/${candidate.repoId}?blobs=true")
            .build()
        val detail = runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                json.decodeFromString<HfModelDto>(response.body?.string().orEmpty())
            }
        }.getOrNull() ?: return candidate

        val size = detail.siblings.orEmpty()
            .firstOrNull { it.rfilename == candidate.fileName }
            ?.size
        return candidate.copy(fileSizeBytes = size)
    }

    private fun toCandidate(dto: HfModelDto): Candidate? {
        val repoId = dto.repoId
        if (repoId.isBlank() || !ModelScoring.isChatLlmRepo(repoId)) return null
        // 게이트(라이선스 동의·토큰 필요) 모델은 무조건 제외 — 이 앱은 HF 토큰을 쓰지 않는다.
        // litert-community의 최상위 모델(Qwen3·Gemma 4 등)이 전부 토큰 불필요라 손해가 없다.
        if (dto.needsToken) return null

        val files = dto.siblings.orEmpty().map { it.rfilename to it.size }
        val picked = ModelScoring.pickBestFile(files) ?: return null

        val paramsB = ModelScoring.parseParamsB(repoId)
            ?: ModelScoring.parseParamsB(picked.first)
            ?: return null
        if (paramsB > 9.0) return null

        val daysSinceUpdate = dto.lastModified?.let { iso ->
            runCatching {
                ChronoUnit.DAYS.between(Instant.parse(iso), Instant.now())
            }.getOrNull()
        }

        val score = ModelScoring.score(
            ModelScoring.ScoreInput(
                repoId = repoId,
                paramsB = paramsB,
                downloads = dto.downloads ?: 0,
                daysSinceUpdate = daysSinceUpdate,
                isInstruct = ModelScoring.isInstructName(repoId),
            ),
        )

        return Candidate(
            repoId = repoId,
            fileName = picked.first,
            fileSizeBytes = picked.second,
            paramsB = paramsB,
            downloads = dto.downloads ?: 0,
            daysSinceUpdate = daysSinceUpdate,
            score = score,
        )
    }

    // --- Gemini 최종 판정 ---

    private suspend fun judgeWithGemini(top: List<Candidate>): Triple<Candidate, String, String>? {
        if (!gemini.isReady() || top.size < 2) return null

        val table = top.joinToString("\n") { c ->
            "- ${c.repoId} | 약 ${c.paramsB}B | 파일 ${c.approxSizeMb}MB | " +
                "다운로드 ${c.downloads}회 | ${c.daysSinceUpdate ?: "?"}일 전 갱신 | 무료·공개"
        }
        val schema = buildJsonObject {
            put("type", "OBJECT")
            putJsonObject("properties") {
                putJsonObject("bestRepoId") {
                    put("type", "STRING")
                    putJsonArray("enum") { top.forEach { add(it.repoId) } }
                }
                putJsonObject("reasonKo") { put("type", "STRING") }
            }
            putJsonArray("required") { add("bestRepoId"); add("reasonKo") }
        }
        val prompt = """
            아래는 Android 온디바이스(.litertlm, LiteRT-LM) LLM 후보 목록입니다.

            [사용 맥락]
            - 한국어 UI 앱에서 한국인 학습자가 OPIc "중국어" 시험을 준비한다
            - 모델 임무: ① 간체 중국어로 자연스러운 회화 상대·모범답안 생성(가장 중요)
                        ② 한국어로 문법 교정 팁·설명 작성(필수) ③ JSON 등 지시 이행
            - 기기: 갤럭시 S26 울트라 (RAM 12GB+), int4 양자화 실행
            - 비용: 온디바이스라 추론 과금은 없음. 다운로드 접근성이 중요 — 무료·공개 모델을 우선한다.
            - 선택 기준: (무료/공개 우선) → 중국어 품질 > 한국어 품질 > 크기 적합성(3~5B 스위트스팟) > 최신성

            [후보]
            $table

            위 후보 중 최적 모델 하나를 고르고, 사용자에게 보여줄 추천 사유를
            한국어 2~3문장으로 작성하세요 (중국어·한국어 능력과 기기 적합성, 무료 여부를 언급).
        """.trimIndent()

        val reply = gemini.generate(
            LlmRequest(
                parts = listOf(com.mingeek.opiczh.core.ai.LlmPart.Text(prompt)),
                responseJsonSchema = schema,
                temperature = 0.2f,
            ),
        ).getOrNull() ?: return null

        val verdict = runCatching {
            json.decodeFromString<JudgeVerdict>(reply.text.trim())
        }.getOrNull() ?: return null

        val chosen = top.firstOrNull { it.repoId == verdict.bestRepoId } ?: return null
        return Triple(chosen, verdict.reasonKo, "AI 판정")
    }

    private fun heuristicReason(c: Candidate): String {
        val family = ModelScoring.detectFamily(c.repoId)
        return "${c.repoId.substringAfter('/')}는 중국어 성능(${family.zh}/10)과 한국어 설명 능력(${family.ko}/10)이 " +
            "현재 후보 중 가장 균형 잡힌 ${c.paramsB}B 모델로, ${c.approxSizeMb}MB(무료·토큰 불필요)로 " +
            "S26 울트라에서 쾌적하게 구동됩니다."
    }

    private fun Candidate.toSpec(): OnDeviceModelSpec = OnDeviceModelSpec(
        id = repoId.substringAfter('/').lowercase(),
        displayName = repoId.substringAfter('/'),
        description = "AI 추천 · 무료·토큰 불필요 · $repoId",
        url = url,
        fileName = fileName,
        approxSizeMb = approxSizeMb,
    )

    private companion object {
        const val MAX_FILE_BYTES = 8L * 1024 * 1024 * 1024
    }
}
