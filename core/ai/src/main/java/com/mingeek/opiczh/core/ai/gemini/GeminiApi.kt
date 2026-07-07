package com.mingeek.opiczh.core.ai.gemini

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface GeminiApi {

    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Body body: GenerateContentRequest,
        /** null이면 인터셉터가 저장된 키를 채운다. 키 검증 시에만 명시적으로 전달. */
        @Header(GeminiAuthInterceptor.HEADER) apiKeyOverride: String? = null,
    ): Response<GenerateContentResponse>

    @GET("v1beta/models")
    suspend fun listModels(
        @Query("pageSize") pageSize: Int = 200,
        @Header(GeminiAuthInterceptor.HEADER) apiKeyOverride: String? = null,
    ): Response<ListModelsResponse>
}
