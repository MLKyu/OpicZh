package com.mingeek.opiczh.core.common

/** 앱 전역 오류 분류. 모든 계층이 이 타입으로 실패를 전달한다. */
sealed class AppError {
    data object ApiKeyMissing : AppError()
    data class ApiKeyInvalid(val detail: String? = null) : AppError()
    data class Network(val detail: String? = null) : AppError()
    data class RateLimited(val retryAfterSec: Int? = null) : AppError()
    data class Server(val code: Int, val detail: String? = null) : AppError()
    data class BadRequest(val detail: String? = null) : AppError()
    data class Parsing(val detail: String? = null) : AppError()
    data class OnDeviceUnavailable(val detail: String? = null) : AppError()
    data class Audio(val detail: String? = null) : AppError()
    data class Unknown(val detail: String? = null) : AppError()

    /** 사용자에게 그대로 보여줄 수 있는 한국어 메시지 */
    fun userMessageKo(): String = when (this) {
        ApiKeyMissing -> "Gemini API 키가 등록되지 않았습니다. 설정에서 키를 등록해 주세요."
        is ApiKeyInvalid -> "API 키가 유효하지 않습니다. 키를 다시 확인해 주세요."
        is Network -> "네트워크에 연결할 수 없습니다. 연결 상태를 확인해 주세요."
        is RateLimited -> when {
            retryAfterSec == null -> "요청 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."
            retryAfterSec >= 3600 ->
                "오늘 무료 사용량을 모두 썼습니다. 한도는 한국 시간 오후 4~5시경 초기화됩니다."
            retryAfterSec >= 60 ->
                "요청 한도를 초과했습니다. 약 ${retryAfterSec / 60}분 후 다시 시도해 주세요."
            else -> "요청 한도를 초과했습니다. 약 ${retryAfterSec}초 후 다시 시도해 주세요."
        }
        is Server -> "Gemini 서버 오류가 발생했습니다. (코드 $code)"
        is BadRequest -> "요청이 거부되었습니다." + (detail?.let { " ($it)" } ?: "")
        is Parsing -> "AI 응답을 해석하지 못했습니다. 다시 시도해 주세요."
        is OnDeviceUnavailable ->
            detail ?: "온디바이스 AI가 준비되지 않았습니다. 설정에서 모델을 다운로드하거나 내장 Nano를 준비해 주세요."
        is Audio -> "오디오 처리 중 문제가 발생했습니다." + (detail?.let { " ($it)" } ?: "")
        is Unknown -> "알 수 없는 오류가 발생했습니다." + (detail?.let { " ($it)" } ?: "")
    }

    val isRetryable: Boolean
        get() = when (this) {
            is Network, is RateLimited, is Server -> true
            else -> false
        }
}

/** 예외 대신 사용하는 결과 타입 */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>

    companion object {
        fun <T> success(value: T): AppResult<T> = Success(value)
        fun failure(error: AppError): AppResult<Nothing> = Failure(error)
    }
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
    is AppResult.Success -> transform(value)
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(value)
    return this
}

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value

fun <T> AppResult<T>.errorOrNull(): AppError? = (this as? AppResult.Failure)?.error
