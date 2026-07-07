package com.mingeek.opiczh.core.ai.gemini

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Gemini REST API(v1beta) DTO.
 * encodeDefaults=false 설정과 널 기본값 조합으로, 채워진 필드만 직렬화된다.
 */

@Serializable
data class GenerateContentRequest(
    val contents: List<Content>,
    val systemInstruction: Content? = null,
    val generationConfig: GenerationConfig? = null,
)

@Serializable
data class Content(
    val parts: List<Part>,
    val role: String? = null,
)

@Serializable
data class Part(
    val text: String? = null,
    val inlineData: Blob? = null,
)

@Serializable
data class Blob(
    val mimeType: String,
    val data: String,
)

@Serializable
data class GenerationConfig(
    val temperature: Float? = null,
    val maxOutputTokens: Int? = null,
    val responseMimeType: String? = null,
    val responseSchema: JsonObject? = null,
    val responseModalities: List<String>? = null,
    val speechConfig: SpeechConfig? = null,
)

@Serializable
data class SpeechConfig(
    val voiceConfig: VoiceConfig? = null,
)

@Serializable
data class VoiceConfig(
    val prebuiltVoiceConfig: PrebuiltVoiceConfig? = null,
)

@Serializable
data class PrebuiltVoiceConfig(
    val voiceName: String,
)

@Serializable
data class GenerateContentResponse(
    val candidates: List<Candidate>? = null,
    val usageMetadata: UsageMetadata? = null,
    val promptFeedback: PromptFeedback? = null,
)

@Serializable
data class Candidate(
    val content: Content? = null,
    val finishReason: String? = null,
)

@Serializable
data class UsageMetadata(
    val promptTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    val totalTokenCount: Int? = null,
)

@Serializable
data class PromptFeedback(
    val blockReason: String? = null,
)

@Serializable
data class ListModelsResponse(
    val models: List<GeminiModelDto>? = null,
    val nextPageToken: String? = null,
)

@Serializable
data class GeminiModelDto(
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val supportedGenerationMethods: List<String>? = null,
)

@Serializable
data class GeminiErrorResponse(
    val error: GeminiErrorDto? = null,
)

@Serializable
data class GeminiErrorDto(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
)
