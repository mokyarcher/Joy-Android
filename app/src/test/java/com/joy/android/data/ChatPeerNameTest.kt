package com.joy.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPeerNameTest {
    @Test
    fun sanitizeKeepsRealNames() {
        assertEquals("陈佳佳", ChatPeerName.sanitize("陈佳佳"))
        assertEquals("陈佳佳", ChatPeerName.sanitize("陈佳佳(3)"))
        assertEquals("陈佳佳", ChatPeerName.sanitize("会话对象：陈佳佳"))
    }

    @Test
    fun sanitizeRejectsUiChrome() {
        assertNull(ChatPeerName.sanitize("微信"))
        assertNull(ChatPeerName.sanitize("21:51"))
        assertNull(ChatPeerName.sanitize("水 确实不错，有九寨沟那个感觉了"))
    }

    @Test
    fun ocrExtractorPrefersPcTopLeftTitle() {
        val lines = listOf(
            OcrRawLine("陈佳佳", left = 0.05f, top = 0.02f, right = 0.18f, bottom = 0.05f),
            OcrRawLine("渐变蓝绿", left = 0.18f, top = 0.40f, right = 0.40f, bottom = 0.44f),
            OcrRawLine("水 确实不错", left = 0.55f, top = 0.55f, right = 0.88f, bottom = 0.59f),
        )
        assertEquals("陈佳佳", OcrPeerNameExtractor.extract(lines))
    }

    @Test
    fun ocrExtractorAcceptsMobileCenteredTitle() {
        val lines = listOf(
            OcrRawLine("小王", left = 0.38f, top = 0.03f, right = 0.55f, bottom = 0.06f),
            OcrRawLine("在吗", left = 0.14f, top = 0.30f, right = 0.28f, bottom = 0.34f),
        )
        assertEquals("小王", OcrPeerNameExtractor.extract(lines))
    }

    @Test
    fun visionParseReadsPeerThenChat() {
        val raw = """
            会话对象：陈佳佳
            对方：渐变蓝绿
            我：水 确实不错
        """.trimIndent()
        val out = ChatLlmClient.parseVisionExtract(raw)
        assertEquals("陈佳佳", out.peerName)
        assertTrue(out.chatText.contains("对方：渐变蓝绿"))
        assertTrue(out.chatText.contains("我：水 确实不错"))
    }
}
