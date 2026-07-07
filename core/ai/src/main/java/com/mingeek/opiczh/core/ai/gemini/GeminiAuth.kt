package com.mingeek.opiczh.core.ai.gemini

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 복호화된 API 키의 메모리 캐시. 앱 시작 시 SettingsRepository.apiKey를
 * 구독해 채워지며, OkHttp 인터셉터(동기 컨텍스트)에서 읽는다.
 */
@Singleton
class ApiKeyHolder @Inject constructor() {
    @Volatile
    var current: String? = null
}

/**
 * 요청에 x-goog-api-key 헤더가 없으면 저장된 키를 채워 넣는다.
 * Google 생성형 AI 호스트에만 붙인다 — 이 클라이언트로 다른 호스트를 호출해도
 * API 키가 제3자에게 새지 않도록.
 */
class GeminiAuthInterceptor @Inject constructor(
    private val holder: ApiKeyHolder,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != GEMINI_HOST) return chain.proceed(request)
        if (request.header(HEADER) != null) return chain.proceed(request)
        val key = holder.current
        if (key.isNullOrBlank()) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(HEADER, key).build())
    }

    companion object {
        const val HEADER = "x-goog-api-key"
        private const val GEMINI_HOST = "generativelanguage.googleapis.com"
    }
}
