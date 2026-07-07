package com.mingeek.opiczh.core.ai.gemini

import com.mingeek.opiczh.core.common.AppError
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException

/** Gemini HTTP 오류/예외를 AppError로 변환한다. */
object GeminiErrorMapper {

    fun fromResponse(code: Int, errorBody: String?, json: Json): AppError {
        val parsed = errorBody
            ?.let { body -> runCatching { json.decodeFromString<GeminiErrorResponse>(body) }.getOrNull() }
            ?.error
        val message = parsed?.message
        val status = parsed?.status
        return when {
            code == 429 || status == "RESOURCE_EXHAUSTED" -> AppError.RateLimited()
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
}
