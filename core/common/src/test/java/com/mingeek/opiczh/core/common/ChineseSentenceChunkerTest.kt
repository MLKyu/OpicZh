package com.mingeek.opiczh.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseSentenceChunkerTest {

    @Test
    fun `blank input returns empty list`() {
        assertEquals(emptyList<String>(), ChineseSentenceChunker.chunk(""))
        assertEquals(emptyList<String>(), ChineseSentenceChunker.chunk("   \n  "))
    }

    @Test
    fun `short text returns single chunk`() {
        val chunks = ChineseSentenceChunker.chunk("你好，我叫小明。")
        assertEquals(listOf("你好，我叫小明。"), chunks)
    }

    @Test
    fun `sentences are grouped up to max length`() {
        val text = "我家附近有一个公园。我每天早上去那里散步。空气很好，人也不多。"
        val chunks = ChineseSentenceChunker.chunk(text, maxChunkLength = 20)
        // 모든 청크가 한도 이내
        chunks.forEach { assertTrue("chunk too long: $it", it.length <= 20) }
        // 내용 유실 없음 (공백 제외)
        assertEquals(text.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
    }

    @Test
    fun `sentence boundaries are respected`() {
        val text = "第一句。第二句！第三句？"
        val chunks = ChineseSentenceChunker.chunk(text, maxChunkLength = 4)
        assertEquals(listOf("第一句。", "第二句！", "第三句？"), chunks)
    }

    @Test
    fun `overlong sentence splits on soft delimiters`() {
        val text = "我喜欢吃苹果，也喜欢吃香蕉，还喜欢吃葡萄。"
        val chunks = ChineseSentenceChunker.chunk(text, maxChunkLength = 10)
        chunks.forEach { assertTrue("chunk too long: $it", it.length <= 10) }
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `text without any punctuation is hard split`() {
        val text = "一".repeat(950)
        val chunks = ChineseSentenceChunker.chunk(text, maxChunkLength = 300)
        assertEquals(4, chunks.size)
        chunks.forEach { assertTrue(it.length <= 300) }
        assertEquals(950, chunks.sumOf { it.length })
    }

    @Test
    fun `very long mixed text loses no content`() {
        val paragraph = "我在一家家具公司工作。我们公司在首尔，有很多员工！你知道吗？" +
            "我每天九点上班，六点下班；有时候要加班，不过我觉得工作很有意思。\n" +
            "周末我喜欢去公园散步，也喜欢在家看电视剧，偶尔跟朋友一起吃饭喝咖啡。"
        val text = paragraph.repeat(30)
        val chunks = ChineseSentenceChunker.chunk(text, maxChunkLength = 300)
        chunks.forEach { assertTrue(it.length <= 300) }
        // 공백/줄바꿈 제외 전체 내용 보존
        val normalize = { s: String -> s.filterNot { it.isWhitespace() } }
        assertEquals(normalize(text), normalize(chunks.joinToString("")))
    }

    @Test
    fun `default max length is TTS safe`() {
        val text = ("这是一个很长的句子，".repeat(100) + "。").repeat(3)
        val chunks = ChineseSentenceChunker.chunk(text)
        chunks.forEach { assertTrue(it.length <= 300) }
    }
}
