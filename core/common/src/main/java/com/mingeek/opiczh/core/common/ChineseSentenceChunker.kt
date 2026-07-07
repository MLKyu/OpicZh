package com.mingeek.opiczh.core.common

/**
 * 장문 중국어 텍스트를 TTS 엔진이 안전하게 처리할 수 있는 크기의 청크로 나눈다.
 *
 * Android TTS의 speak()는 입력 길이 제한(약 4,000자)이 있고, 한 번에 긴 발화를
 * 넘기면 엔진에 따라 무응답/오류가 발생한다. 문장 경계(。！？ 등)를 우선 지키고,
 * 한 문장이 한도를 넘으면 쉼표류로, 그래도 넘으면 강제 분할한다.
 */
object ChineseSentenceChunker {

    private val HARD_DELIMITERS = setOf('。', '！', '？', '；', '!', '?', ';', '\n', '…')
    private val SOFT_DELIMITERS = setOf('，', '、', ',', '：', ':')

    /**
     * @param maxChunkLength 청크 최대 길이. TTS 안전 여유를 위해 기본 300자.
     */
    fun chunk(text: String, maxChunkLength: Int = 300): List<String> {
        require(maxChunkLength > 0) { "maxChunkLength must be positive" }
        if (text.isBlank()) return emptyList()

        val chunks = mutableListOf<String>()
        val buffer = StringBuilder()

        fun flush() {
            val piece = buffer.toString().trim()
            if (piece.isNotEmpty()) chunks.add(piece)
            buffer.clear()
        }

        fun append(piece: String) {
            if (piece.isEmpty()) return
            if (buffer.isNotEmpty() && buffer.length + piece.length > maxChunkLength) flush()
            buffer.append(piece)
        }

        for (sentence in splitKeepingDelimiters(text, HARD_DELIMITERS)) {
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.length <= maxChunkLength) {
                append(trimmed)
                continue
            }

            // 문장 하나가 한도 초과: 쉼표류 → 강제 분할 순으로 쪼갠다.
            for (clause in splitKeepingDelimiters(trimmed, SOFT_DELIMITERS)) {
                val clauseTrimmed = clause.trim()
                if (clauseTrimmed.isEmpty()) continue
                if (clauseTrimmed.length <= maxChunkLength) {
                    append(clauseTrimmed)
                } else {
                    flush()
                    clauseTrimmed.chunked(maxChunkLength).forEach { chunks.add(it) }
                }
            }
        }
        flush()
        return chunks
    }

    /** 구분자를 앞 조각에 붙인 채로 분할한다. ("你好。我是…" → ["你好。", "我是…"]) */
    private fun splitKeepingDelimiters(text: String, delimiters: Set<Char>): List<String> {
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (ch in delimiters) {
                pieces.add(current.toString())
                current.clear()
            }
        }
        if (current.isNotEmpty()) pieces.add(current.toString())
        return pieces
    }
}
