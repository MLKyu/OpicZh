package com.mingeek.opiczh.core.ai.gemini

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiDtoParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    @Test
    fun `parses real-shaped generateContent response`() {
        val fixture = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [{"text": "你好！我是你的中文老师。"}],
                    "role": "model"
                  },
                  "finishReason": "STOP",
                  "index": 0,
                  "safetyRatings": []
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 12,
                "candidatesTokenCount": 34,
                "totalTokenCount": 46
              },
              "modelVersion": "gemini-3.5-flash"
            }
        """.trimIndent()

        val parsed = json.decodeFromString<GenerateContentResponse>(fixture)
        assertEquals("你好！我是你的中文老师。", parsed.candidates?.first()?.content?.parts?.first()?.text)
        assertEquals("STOP", parsed.candidates?.first()?.finishReason)
        assertEquals(12, parsed.usageMetadata?.promptTokenCount)
        assertEquals(34, parsed.usageMetadata?.candidatesTokenCount)
    }

    @Test
    fun `request omits null fields`() {
        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = "안녕")), role = "user")),
        )
        val encoded = json.encodeToString(GenerateContentRequest.serializer(), request)
        assertFalse(encoded.contains("systemInstruction"))
        assertFalse(encoded.contains("generationConfig"))
        assertFalse(encoded.contains("inlineData"))
        assertTrue(encoded.contains("\"text\":\"안녕\""))
    }

    @Test
    fun `parses error body`() {
        val fixture = """
            {
              "error": {
                "code": 400,
                "message": "API key not valid. Please pass a valid API key.",
                "status": "INVALID_ARGUMENT",
                "details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo"}]
              }
            }
        """.trimIndent()
        val parsed = json.decodeFromString<GeminiErrorResponse>(fixture)
        assertEquals(400, parsed.error?.code)
        assertEquals("INVALID_ARGUMENT", parsed.error?.status)
        assertTrue(parsed.error?.message.orEmpty().contains("API key"))
    }

    @Test
    fun `parses models list and filters generateContent`() {
        val fixture = """
            {
              "models": [
                {
                  "name": "models/gemini-3.5-flash",
                  "displayName": "Gemini 3.5 Flash",
                  "supportedGenerationMethods": ["generateContent", "countTokens"]
                },
                {
                  "name": "models/embedding-001",
                  "displayName": "Embedding 001",
                  "supportedGenerationMethods": ["embedContent"]
                }
              ]
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ListModelsResponse>(fixture)
        val generative = parsed.models.orEmpty()
            .filter { it.supportedGenerationMethods.orEmpty().contains("generateContent") }
        assertEquals(1, generative.size)
        assertEquals("models/gemini-3.5-flash", generative.first().name)
    }
}
