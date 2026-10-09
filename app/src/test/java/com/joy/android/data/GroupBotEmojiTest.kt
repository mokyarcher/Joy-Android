package com.joy.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupBotEmojiTest {
    @Test
    fun keepsAllowedEmojiWithText() {
        val out = GroupBotEmoji.polishBody("哎哟这题考我？[偷笑] 你先把秤递过来")
        assertTrue(out.contains("[偷笑]"))
        assertTrue(out.contains("秤"))
    }

    @Test
    fun dropsUnknownBracketTokens() {
        val out = GroupBotEmoji.polishBody("哈哈[未知魔幻表情]可以")
        assertEquals("哈哈可以", out.replace(" ", ""))
        assertFalse(out.contains("["))
    }

    @Test
    fun capsAtTwoEmojis() {
        val out = GroupBotEmoji.polishBody("行吧[微笑][捂脸][强][哇]")
        val n = Regex("""\[[^]]+]""").findAll(out).count()
        assertEquals(2, n)
        assertTrue(out.contains("行吧"))
    }

    @Test
    fun rejectsEmojiOnly() {
        assertEquals("", GroupBotEmoji.polishBody("[微笑]"))
        assertEquals("", GroupBotEmoji.polishBody("[微笑][捂脸]"))
        assertEquals("", GroupBotEmoji.polishBody("  [强]  "))
    }
}
