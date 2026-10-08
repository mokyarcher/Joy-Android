package com.joy.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOcrHelperTest {

    @Test
    fun filtersTopChromeAndTimestamp() {
        assertTrue(OcrChatFormatter.isNoise(line("张三", left = 0.2f, top = 0.02f, right = 0.4f, bottom = 0.045f)))
        assertTrue(OcrChatFormatter.isNoise(line("昨天 12:30", left = 0.38f, top = 0.20f, right = 0.62f, bottom = 0.23f)))
        assertTrue(OcrChatFormatter.isNoise(line("上午 10:05", left = 0.40f, top = 0.35f, right = 0.60f, bottom = 0.38f)))
    }

    @Test
    fun filtersBottomTabsButKeepsRealWoMessage() {
        assertTrue(OcrChatFormatter.isNoise(line("通讯录", left = 0.25f, top = 0.93f, right = 0.40f, bottom = 0.97f)))
        assertTrue(OcrChatFormatter.isNoise(line("我", left = 0.78f, top = 0.93f, right = 0.88f, bottom = 0.97f)))
        // 聊天区里的短「我」不应被当成底栏噪声
        assertFalse(OcrChatFormatter.isNoise(line("我", left = 0.75f, top = 0.50f, right = 0.82f, bottom = 0.54f)))
    }

    @Test
    fun assignsSideByEdgeStickiness() {
        // 短回复靠右 → 我
        assertEquals(
            OcrSide.ME,
            OcrChatFormatter.assignSide(line("好的", left = 0.72f, top = 0.4f, right = 0.88f, bottom = 0.44f)),
        )
        // 短回复靠左 → 对方
        assertEquals(
            OcrSide.OTHER,
            OcrChatFormatter.assignSide(line("在吗", left = 0.14f, top = 0.3f, right = 0.28f, bottom = 0.34f)),
        )
        // 长句跨中间：右贴边 → 我
        assertEquals(
            OcrSide.ME,
            OcrChatFormatter.assignSide(
                line("晚上一起吃饭吧还是改天再说", left = 0.28f, top = 0.5f, right = 0.90f, bottom = 0.55f),
            ),
        )
        // 长句跨中间：左贴边 → 对方
        assertEquals(
            OcrSide.OTHER,
            OcrChatFormatter.assignSide(
                line("晚上一起吃饭吧还是改天再说", left = 0.12f, top = 0.5f, right = 0.72f, bottom = 0.55f),
            ),
        )
        // 群聊长绿泡：左缘可很靠左（向左撑开），右缘仍贴右 → 我（旧逻辑要求 left>=0.32 会翻成对方）
        assertEquals(
            OcrSide.ME,
            OcrChatFormatter.assignSide(
                line(
                    "这个图要留着 日后你儿子结婚的时候放出来",
                    left = 0.16f,
                    top = 0.55f,
                    right = 0.86f,
                    bottom = 0.62f,
                ),
            ),
        )
    }

    @Test
    fun groupChatKeepsMeBubblesAndDropsNicknames() {
        // 模拟群聊：对方昵称小字 + 对方泡 + 我短句 + 我长句（左缘靠左）
        val lines = listOf(
            line("喷射战士", left = 0.14f, top = 0.30f, right = 0.42f, bottom = 0.34f),
            line("我的妈呀", left = 0.72f, top = 0.40f, right = 0.88f, bottom = 0.44f),
            line(
                "这个图要留着 日后你儿子结婚的时候放出来",
                left = 0.16f,
                top = 0.50f,
                right = 0.86f,
                bottom = 0.58f,
            ),
            // 群昵称（更小字号）紧贴下方对方气泡
            line("胡亮", left = 0.14f, top = 0.64f, right = 0.26f, bottom = 0.665f),
            line("老朱这压力大的批爆", left = 0.14f, top = 0.68f, right = 0.58f, bottom = 0.72f),
            line("估计天天晚上又睡不着了吧", left = 0.14f, top = 0.76f, right = 0.62f, bottom = 0.80f),
        )
        val out = OcrChatFormatter.format(lines)
        assertTrue("expected 我 short: $out", out.contains("我：我的妈呀"))
        assertTrue("expected 我 long: $out", out.contains("我：这个图要留着"))
        assertFalse("long me must not be 对方: $out", out.contains("对方：这个图要留着"))
        assertTrue("expected other bubble: $out", out.contains("对方：老朱这压力大的批爆"))
        assertFalse("nickname alone: $out", out.contains("对方：胡亮"))
        assertFalse("nickname merged: $out", out.lines().any { it.contains("胡亮老朱") })
    }

    @Test
    fun cleanBubbleTextStripsPipePrefix() {
        assertEquals("胡亮老朱这压力大的批爆", OcrChatFormatter.cleanBubbleText("|胡亮老朱这压力大的批爆"))
        assertEquals("喷射战士", OcrChatFormatter.cleanBubbleText("  喷射战士  "))
    }

    @Test
    fun mergesWrappedBubbleButNotSeparateMessages() {
        val wrapped = listOf(
            line("今天下班要不要一起", left = 0.14f, top = 0.30f, right = 0.55f, bottom = 0.34f),
            line("去吃火锅", left = 0.14f, top = 0.345f, right = 0.32f, bottom = 0.385f),
        )
        assertEquals(
            "对方：今天下班要不要一起去吃火锅",
            OcrChatFormatter.format(wrapped),
        )

        val twoMessages = listOf(
            line("在吗", left = 0.14f, top = 0.30f, right = 0.28f, bottom = 0.34f),
            // 间隔明显大于行高 → 下一条消息
            line("晚上有空吗", left = 0.14f, top = 0.42f, right = 0.40f, bottom = 0.46f),
        )
        assertEquals(
            "对方：在吗\n对方：晚上有空吗",
            OcrChatFormatter.format(twoMessages),
        )
    }

    @Test
    fun mergesMeWrappedBubbleWhenSecondLineIsShorter() {
        // 右侧气泡内左对齐：第二行「别人」右缘更短，旧逻辑用右缘会对不齐
        val wrapped = listOf(
            line("自己喜欢当纯牛马就算了,还拉着", left = 0.42f, top = 0.55f, right = 0.88f, bottom = 0.59f),
            line("别人", left = 0.42f, top = 0.595f, right = 0.55f, bottom = 0.635f),
        )
        assertEquals(
            "我：自己喜欢当纯牛马就算了,还拉着别人",
            OcrChatFormatter.format(wrapped),
        )
    }

    @Test
    fun doesNotMergeAdjacentSeparateMeBubbles() {
        // 两条独立绿气泡：右缘都贴边，但左缘不同、行距更大
        val two = listOf(
            line("叫爹都没用", left = 0.62f, top = 0.40f, right = 0.88f, bottom = 0.44f),
            line("给脸了", left = 0.75f, top = 0.475f, right = 0.88f, bottom = 0.515f),
        )
        assertEquals(
            "我：叫爹都没用\n我：给脸了",
            OcrChatFormatter.format(two),
        )
    }

    @Test
    fun keepsBottomBubbleTailAndDropsGarbledTimestamp() {
        // 贴底、偏右的短尾字不应被底栏逻辑杀掉
        assertFalse(
            OcrChatFormatter.isNoise(
                line("妈 尝尝", left = 0.72f, top = 0.905f, right = 0.88f, bottom = 0.94f),
            ),
        )
        // 居中乱码时间条
        assertTrue(
            OcrChatFormatter.isNoise(
                line("BF 15:47", left = 0.38f, top = 0.62f, right = 0.62f, bottom = 0.65f),
            ),
        )
    }

    @Test
    fun mergesSameRowSplitFragments() {
        // 一行被拆成左右两块时拼回
        val split = listOf(
            line("这个十度炸弹我带回去 给我爸", left = 0.40f, top = 0.82f, right = 0.74f, bottom = 0.86f),
            line("妈 尝尝", left = 0.74f, top = 0.825f, right = 0.88f, bottom = 0.855f),
        )
        assertEquals(
            "我：这个十度炸弹我带回去 给我爸妈 尝尝",
            OcrChatFormatter.format(split),
        )
    }

    @Test
    fun doesNotMergeLeftAndRightBubblesOnSameRow() {
        val row = listOf(
            line("好像不行", left = 0.14f, top = 0.40f, right = 0.36f, bottom = 0.44f),
            line("好的", left = 0.72f, top = 0.405f, right = 0.88f, bottom = 0.435f),
        )
        assertEquals(
            "对方：好像不行\n我：好的",
            OcrChatFormatter.format(row),
        )
    }

    @Test
    fun bubbleBgMatchesWeChatLightTheme() {
        assertTrue(OcrBubbleColorFilter.matchesWeChatBubbleBg(OcrBubbleColorFilter.OTHER_BUBBLE_BG))
        assertTrue(OcrBubbleColorFilter.matchesWeChatBubbleBg(OcrBubbleColorFilter.ME_BUBBLE_BG))
        assertTrue(OcrBubbleColorFilter.matchesMeBubbleBg(0xFF95EC69.toInt()))
        assertTrue(OcrBubbleColorFilter.matchesWeChatBubbleBg(0xFFE8E8EA.toInt()))
        assertTrue(OcrBubbleColorFilter.matchesDarkOtherBubble(0xFF2C2C2C.toInt()))
        assertFalse(OcrBubbleColorFilter.matchesOtherBubbleBg(0xFF1E1E1E.toInt()))
        assertFalse(OcrBubbleColorFilter.matchesWeChatBubbleBg(0xFF000000.toInt()))
    }

    @Test
    fun clearlyNotBubbleRejectsDarkEditorBg() {
        // 纯黑远离暗色对方灰泡
        assertTrue(OcrBubbleColorFilter.clearlyDarkNonBubble(0xFF0A0A0A.toInt()))
        assertFalse(OcrBubbleColorFilter.clearlyNotBubble(OcrBubbleColorFilter.ME_BUBBLE_BG))
        assertFalse(OcrBubbleColorFilter.clearlyNotBubble(OcrBubbleColorFilter.OTHER_BUBBLE_BG))
        assertFalse(OcrBubbleColorFilter.clearlyNotBubble(0xFF2C2C2C.toInt()))
        assertFalse(OcrBubbleColorFilter.clearlyNotBubble(0xFFD0D0D4.toInt()))
    }

    @Test
    fun isGreenishDetectsMeBubbleColors() {
        assertTrue(OcrBubbleColorFilter.isGreenish(OcrBubbleColorFilter.ME_BUBBLE_BG))
        assertTrue(OcrBubbleColorFilter.isGreenish(0xFF95EC69.toInt()))
        assertFalse(OcrBubbleColorFilter.isGreenish(OcrBubbleColorFilter.OTHER_BUBBLE_BG))
        assertFalse(OcrBubbleColorFilter.isGreenish(0xFF1E1E1E.toInt()))
    }

    @Test
    fun keepsShortOtherBubblesAndDropsImageAndBareWo() {
        val lines = listOf(
            line("Steam就是这样的", left = 0.55f, top = 0.10f, right = 0.88f, bottom = 0.14f),
            // 短句框略矮（0.032 / 0.040 = 0.80），旧阈值会误杀
            line("好像不行", left = 0.14f, top = 0.18f, right = 0.34f, bottom = 0.212f),
            line("后复制到这个1.exe并点击校验", left = 0.16f, top = 0.26f, right = 0.58f, bottom = 0.284f),
            line("做好杯工具授权码点击校验", left = 0.16f, top = 0.288f, right = 0.56f, bottom = 0.312f),
            line("他不帮我做", left = 0.14f, top = 0.40f, right = 0.38f, bottom = 0.44f),
            line("烦死了", left = 0.14f, top = 0.48f, right = 0.28f, bottom = 0.512f),
            line("AI还是有短板的", left = 0.14f, top = 0.56f, right = 0.48f, bottom = 0.60f),
            line("这是原则问题啊", left = 0.58f, top = 0.68f, right = 0.88f, bottom = 0.72f),
            line("我", left = 0.80f, top = 0.93f, right = 0.90f, bottom = 0.97f),
        )
        val out = OcrChatFormatter.format(lines)
        assertTrue(out.contains("好像不行"))
        assertTrue(out.contains("烦死了"))
        assertTrue(out.contains("短板"))
        assertTrue(out.contains("他不帮我做"))
        assertFalse(out.contains("1.exe"))
        assertFalse(out.lines().any { it == "我：我" || it.trim() == "我" })
    }

    @Test
    fun keepsThreeLineChatWrapWithNormalFont() {
        // 同字号三行折行：密但不大概是图内字
        val wrapped = listOf(
            line("今天晚上要不要一起出去吃个火锅顺便", left = 0.14f, top = 0.30f, right = 0.58f, bottom = 0.34f),
            line("聊一下周末的安排如果可以的话", left = 0.14f, top = 0.345f, right = 0.55f, bottom = 0.385f),
            line("我就提前订位", left = 0.14f, top = 0.390f, right = 0.40f, bottom = 0.430f),
        )
        assertEquals(
            "对方：今天晚上要不要一起出去吃个火锅顺便聊一下周末的安排如果可以的话我就提前订位",
            OcrChatFormatter.format(wrapped),
        )
    }

    @Test
    fun dropsVoiceButtonParensAsJunk() {
        // 底栏左侧语音键声波 → OCR 成 ()，旧逻辑会变成「对方：()」
        assertTrue(
            OcrChatFormatter.isNoise(
                line("()", left = 0.02f, top = 0.93f, right = 0.10f, bottom = 0.97f),
            ),
        )
        assertTrue(
            OcrChatFormatter.isNoise(
                line("（）", left = 0.03f, top = 0.92f, right = 0.11f, bottom = 0.96f),
            ),
        )
        assertTrue(OcrChatFormatter.isSymbolOnlyNoise("()"))
        assertTrue(OcrChatFormatter.isSymbolOnlyNoise("··"))
        assertFalse(OcrChatFormatter.isSymbolOnlyNoise("（笑）"))
        assertFalse(OcrChatFormatter.isSymbolOnlyNoise("哈哈"))

        val out = OcrChatFormatter.format(
            listOf(
                line("哈哈，手速快", left = 0.55f, top = 0.70f, right = 0.88f, bottom = 0.74f),
                line("()", left = 0.02f, top = 0.93f, right = 0.10f, bottom = 0.97f),
            ),
        )
        assertEquals("我：哈哈，手速快", out)
        assertFalse(out.contains("()"))
    }

    private fun line(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): OcrRawLine = OcrRawLine(text = text, left = left, top = top, right = right, bottom = bottom)
}
