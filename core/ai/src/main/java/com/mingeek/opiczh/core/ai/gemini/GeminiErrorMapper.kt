package com.mingeek.opiczh.core.ai.gemini

import com.mingeek.opiczh.core.common.AppError
import java.io.IOException
import kotlin.math.ceil
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Gemini HTTP 오류/예외를 AppError로 변환한다. */
object GeminiErrorMapper {

    /**
     * @param retryAfterHeaderSec HTTP `Retry-After` 헤더 값(초). 바디의 RetryInfo가 우선.
     */
    fun fromResponse(
        code: Int,
        errorBody: String?,
        json: Json,
        retryAfterHeaderSec: Int? = null,
    ): AppError {
        val parsed = errorBody
            ?.let { body -> runCatching { json.decodeFromString<GeminiErrorResponse>(body) }.getOrNull() }
            ?.error
        val message = parsed?.message
        val status = parsed?.status
        return when {
            code == 429 || status == "RESOURCE_EXHAUSTED" ->
                AppError.RateLimited(
                    retryAfterSec = retryDelaySecFrom(parsed) ?: retryAfterHeaderSec,
                )
            code == 401 || code == 403 ||
                status == "UNAUTHENTICATED" || status == "PERMISSION_DENIED" ->
                AppError.ApiKeyInvalid(message)
            code == 400 && message?.contains("API key", ignoreCase = true) == true ->
                AppError.ApiKeyInvalid(message)
            code == 400 || code == 404 -> AppError.BadRequest(message ?: "HTTP $code")
            code >= 500 -> AppError.Server(code, message)
            else -> AppError.Unknown("HTTP $code ${message.orEmpty()}".trim())
        }
    }

    fun fromThrowable(t: Throwable): AppError = when (t) {
        is IOException -> AppError.Network(t.message)
        is SerializationException -> AppError.Parsing(t.message)
        else -> AppError.Unknown(t.message)
    }

    /**
     * 429 바디의 google.rpc.RetryInfo에서 재시도 대기시간을 꺼낸다.
     * 형식: details[{"@type":".../google.rpc.RetryInfo","retryDelay":"27s"}] ("3.5s"도 가능)
     */
    private fun retryDelaySecFrom(error: GeminiErrorDto?): Int? =
        error?.details.orEmpty()
            .firstOrNull { detail ->
                (detail["@type"] as? JsonPrimitive)?.content?.endsWith("RetryInfo") == true
            }
            ?.let { info -> parseDurationSec(info) }

    private fun parseDurationSec(retryInfo: JsonObject): Int? {
        val raw = (retryInfo["retryDelay"] as? JsonPrimitive)?.content ?: return null
        val seconds = raw.trim().removeSuffix("s").toDoubleOrNull() ?: return null
        if (seconds < 0) return null
        return ceil(seconds).toInt()
    }
}
