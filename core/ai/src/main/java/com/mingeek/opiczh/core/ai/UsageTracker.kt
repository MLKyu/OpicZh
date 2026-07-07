package com.mingeek.opiczh.core.ai

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ApiUsage(
    val requests: Int = 0,
    val promptTokens: Long = 0,
    val outputTokens: Long = 0,
)

/** 이번 앱 세션의 Gemini 사용량 (쿼터 감각 유지용, 메모리 집계) */
@Singleton
class UsageTracker @Inject constructor() {

    private val _usage = MutableStateFlow(ApiUsage())
    val usage: StateFlow<ApiUsage> = _usage

    fun record(promptTokens: Int?, outputTokens: Int?) {
        _usage.value = _usage.value.let {
            it.copy(
                requests = it.requests + 1,
                promptTokens = it.promptTokens + (promptTokens ?: 0),
                outputTokens = it.outputTokens + (outputTokens ?: 0),
            )
        }
    }
}
