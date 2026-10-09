package com.joy.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupBotMentionTest {
    @Test
    fun findsAtMentionWithMemberName() {
        val chat = """
            会话对象：全是沙雕的群
            对方·胡亮：喷射战士
            我：我的妈呀
            对方·朱启超：@JoyBot 帮看看这图
        """.trimIndent()
        val hit = GroupBotMention.find(chat, "JoyBot", requireAt = true)
        assertNotNull(hit)
        assertEquals("朱启超", hit!!.memberName)
        assertTrue(hit.triggerLine.contains("@JoyBot"))
        assertTrue(hit.contextText.contains("喷射战士"))
    }

    @Test
    fun skipsWhenNoAtAndRequireAt() {
        val chat = """
            对方：在吗
            我：嗯
            对方：吃了没
        """.trimIndent()
        assertNull(GroupBotMention.find(chat, "JoyBot", requireAt = true))
    }

    @Test
    fun acceptsLastOtherWithoutAtWhenNotRequired() {
        val chat = """
            对方·小王：你好啊
        """.trimIndent()
        val hit = GroupBotMention.find(chat, "JoyBot", requireAt = false)
        assertNotNull(hit)
        assertEquals("小王", hit!!.memberName)
    }

    @Test
    fun atMentionToleratesFullwidthAndSpace() {
        assertTrue(GroupBotMention.containsAtMention("＠ JoyBot 在吗", "JoyBot"))
        assertTrue(GroupBotMention.containsAtMention("@JoyBot帮忙", "JoyBot"))
        assertFalse(GroupBotMention.containsAtMention("@别的号", "JoyBot"))
    }

    @Test
    fun stableTriggerKeyIgnoresWhitespaceAndMemberName() {
        val a = GroupBotMention.stableTriggerKey("豆姐", "@豆姐 是你 你终于回来了")
        val b = GroupBotMention.stableTriggerKey("豆姐", "＠ 豆姐  是你　你终于回来了")
        assertEquals(a, b)
        val hit1 = GroupBotMention.find("对方·Moky：@豆姐 下班了", "豆姐", requireAt = true)
        val hit2 = GroupBotMention.find("对方：@豆姐 下班了", "豆姐", requireAt = true)
        assertNotNull(hit1)
        assertNotNull(hit2)
        assertEquals(hit1!!.fingerprint, hit2!!.fingerprint)
    }

    @Test
    fun skipsOldAtWhenBotAlreadyRepliedBelow() {
        val chat = """
            对方·Moky：@豆姐 我来试试
            我：@Moky 来呗随便测
            对方·红师傅：看来没修好
        """.trimIndent()
        assertNull(GroupBotMention.find(chat, "豆姐", requireAt = true))
    }

    @Test
    fun skipsAnsweredFingerprintEvenWithoutMeLine() {
        val chat = """
            对方·Moky：@豆姐 歇着差不多了
            对方·红师傅：哈哈
        """.trimIndent()
        val first = GroupBotMention.find(chat, "豆姐", requireAt = true)
        assertNotNull(first)
        val again = GroupBotMention.find(chat, "豆姐", requireAt = true) { fp ->
            fp == first!!.fingerprint
        }
        assertNull(again)
    }

    @Test
    fun findsNewerAtWhenOldAlreadyAnsweredOnScreen() {
        val chat = """
            对方·Moky：@豆姐 旧的一句
            我：@Moky 旧的已经回过了哈
            对方·Moky：@豆姐 新的一句
        """.trimIndent()
        val hit = GroupBotMention.find(chat, "豆姐", requireAt = true)
        assertNotNull(hit)
        assertTrue(hit!!.triggerLine.contains("新的一句"))
    }

    @Test
    fun misclassifiedNickAsMeDoesNotCountAsBotReply() {
        // logcat：昵称误成「我：Moky」夹在两条 @ 之间，旧逻辑会判全部已回复
        // 未回队列从早到晚：先回「@豆姐你好」，下一轮再回「说话」
        val chat = """
            对方·红师傅：@豆姐你好,请出示你的健康码
            我：健廉码?姐这是绿码中的绿码
            对方·Moky：@豆姐你好
            我：Moky
            对方·Moky：@豆姐说话
        """.trimIndent()
        val hit = GroupBotMention.find(chat, "豆姐", requireAt = true)
        assertNotNull(hit)
        assertEquals("Moky", hit!!.memberName)
        assertTrue(hit.triggerLine.contains("你好"))
        val second = GroupBotMention.find(chat, "豆姐", requireAt = true) { fp ->
            fp == hit.fingerprint
        }
        assertNotNull(second)
        assertTrue(second!!.triggerLine.contains("说话"))
    }

    @Test
    fun unansweredQueueIsOldestFirst() {
        val chat = """
            对方·A：@豆姐 第一句
            对方·B：@豆姐 第二句
        """.trimIndent()
        val hit = GroupBotMention.find(chat, "豆姐", requireAt = true)
        assertNotNull(hit)
        assertTrue(hit!!.triggerLine.contains("第一句"))
    }

    @Test
    fun skipsHistoryAtAboveLastMeEvenIfOcrLostDirectReply() {
        // 实机：已回过「你是丁真」，OCR 丢了那条我：、又认成「丁頁」，不应再 claim
        val chat = """
            我：@Moky 红师傅,Moky 喊你测更新呢,赶紧出来冒个泡,别让姐一个人打
            对方·红师傳：@豆姐 你是丁頁
            对方：人起外号心
            对方·Moky：@豆姐 其其实丁真人挺好的
            我：@Moky 那你这么说,我勉强认了,丁真就丁真吧合
            对方·Moky：@豆姐除了玩玩新能源
            对方·红师傅：哈哈哈
            我：@Moky玩新能源怎么了,姐这叫有远见,你懂啥
            对方·Moky：@豆姐你知道瑞克五代吗?
            我：@Moky 瑞克王五代?姐孤陋寡闻了,你给料普一下呗~
        """.trimIndent()
        assertNull(GroupBotMention.find(chat, "豆姐", requireAt = true))
    }

    @Test
    fun fingerprintsNearEqualToleratesOneOcrTypo() {
        assertTrue(
            GroupBotMention.fingerprintsNearEqual("@你是丁真", "@你是丁頁"),
        )
        assertFalse(
            GroupBotMention.fingerprintsNearEqual("@除了玩玩新能源", "@你知道瑞克五代吗?"),
        )
    }

    @Test
    fun multiAtAfterLastMeStillOldestFirst() {
        val chat = """
            我：@Moky 先回过一条
            对方·A：@豆姐 第一句
            对方·B：@豆姐 第二句
        """.trimIndent()
        val hit = GroupBotMention.find(chat, "豆姐", requireAt = true)
        assertNotNull(hit)
        assertTrue(hit!!.triggerLine.contains("第一句"))
    }

    @Test
    fun multiAtDrainsOldestThenNextThenDone() {
        val chat = """
            对方·A：@豆姐 甲
            对方·B：@豆姐 乙
            对方·C：@豆姐 丙
        """.trimIndent()
        val answered = mutableSetOf<String>()
        val first = GroupBotMention.find(chat, "豆姐", requireAt = true) { it in answered }
        assertNotNull(first)
        assertTrue(first!!.triggerLine.contains("甲"))
        answered += first.fingerprint

        val second = GroupBotMention.find(chat, "豆姐", requireAt = true) { it in answered }
        assertNotNull(second)
        assertTrue(second!!.triggerLine.contains("乙"))
        answered += second.fingerprint

        val third = GroupBotMention.find(chat, "豆姐", requireAt = true) { it in answered }
        assertNotNull(third)
        assertTrue(third!!.triggerLine.contains("丙"))
        answered += third.fingerprint

        assertNull(GroupBotMention.find(chat, "豆姐", requireAt = true) { it in answered })
    }

    @Test
    fun listAtHitsKeepsScreenOrderForLedgerSync() {
        val chat = """
            对方·A：@豆姐 甲
            对方·B：@豆姐 乙
        """.trimIndent()
        val hits = GroupBotMention.listAtHits(chat, "豆姐")
        assertEquals(2, hits.size)
        assertTrue(hits[0].triggerLine.contains("甲"))
        assertTrue(hits[1].triggerLine.contains("乙"))
    }

    @Test
    fun realMeReplyStillSkipsOldAt() {
        val chat = """
            对方·Moky：@豆姐说话
            我：来啦来啦,姐这嘴就没停过,说吧今儿想唠点啥~
        """.trimIndent()
        assertNull(GroupBotMention.find(chat, "豆姐", requireAt = true))
    }

    @Test
    fun findsMemberNameFrom对方DotPrefix() {
        val chat = """
            对方·汤恒宇：@豆姐 你状态比昨天差多了
        """.trimIndent()
        val hit = GroupBotMention.find(chat, "豆姐", requireAt = true)
        assertNotNull(hit)
        assertEquals("汤恒宇", hit!!.memberName)
    }

    @Test
    fun forceAtTriggerUsesCallerNotSomeoneElse() {
        val out = GroupBotMention.forceAtTrigger("@Moky 哎哟凑合用呗", "汤恒宇")
        assertEquals("@汤恒宇 哎哟凑合用呗", out)
        assertEquals(
            "@汤恒宇 随便测",
            GroupBotMention.forceAtTrigger("随便测", "汤恒宇"),
        )
        // 认不出 / 假昵称：剥掉乱 @，不强加
        assertEquals("哈哈", GroupBotMention.forceAtTrigger("@Moky 哈哈", "群友"))
        assertEquals("哪有啦", GroupBotMention.forceAtTrigger("@是的 哪有啦", "是的"))
        assertEquals("哪有啦", GroupBotMention.forceAtTrigger("@是的 哪有啦", "群友"))
        assertFalse(GroupBotMention.isPlausibleGroupNick("是的"))
        assertFalse(GroupBotMention.isPlausibleGroupNick("好像不行"))
        assertTrue(GroupBotMention.isPlausibleGroupNick("汤恒宇"))
    }

    @Test
    fun resolveMemberNameFrom对方DotLine() {
        val hit = GroupBotMention.find(
            "对方·汤恒宇：@豆姐 状态差多了",
            "豆姐",
            requireAt = true,
        )
        assertNotNull(hit)
        assertEquals("汤恒宇", GroupBotMention.resolveMemberName(hit!!))
    }

    @Test
    fun ocrLooseFindsAtWithoutRolePrefix() {
        val chat = """
            对方：先聊着
            胡亮
            @JoyBot 帮看看
        """.trimIndent()
        val hit = GroupBotMention.find(chat, "JoyBot", requireAt = true)
        assertNotNull(hit)
        assertEquals("胡亮", hit!!.memberName)
        assertTrue(hit.triggerLine.contains("@JoyBot"))
    }

    @Test
    fun ocrLooseSkipsWhenMeReplyAlreadyBelow() {
        val chat = """
            胡亮
            @JoyBot 帮看看
            我：好的
        """.trimIndent()
        assertNull(GroupBotMention.find(chat, "JoyBot", requireAt = true))
    }

    @Test
    fun ocrFormattedOtherLineWithAt() {
        val chat = """
            对方：喷射战士
            我：嗯
            对方：@JoyBot 在吗
        """.trimIndent()
        val hit = GroupBotMention.find(chat, "JoyBot", requireAt = true)
        assertNotNull(hit)
        assertTrue(hit!!.triggerLine.contains("@JoyBot"))
    }

    @Test
    fun buildSystemPromptIncludesPersonaAndNick() {
        val s = JoySettings(
            groupBotNickname = "JoyBot",
            groupBotGender = GroupBotGender.FEMALE,
            groupBotTemper = GroupBotTemper.SHARP,
            groupBotProfession = "群助理",
            groupBotPersona = "你是毒舌群助理。",
        )
        val p = GroupBotMention.buildSystemPrompt(s)
        assertTrue(p.contains("毒舌群助理"))
        assertTrue(p.contains("JoyBot"))
        assertTrue(p.contains("女"))
        assertTrue(p.contains("毒舌"))
        assertTrue(p.contains("群助理"))
        assertTrue(p.contains("[微笑]"))
        assertTrue(p.contains("禁止只发表情"))
    }

    @Test
    fun visionGroupBotPromptKeepsMemberTag() {
        val p = ChatLlmClient.buildVisionExtractPrompt(
            VisionImagePolicy.IGNORE,
            groupBot = true,
        )
        assertTrue(p.contains("对方·成员昵称"))
        assertTrue(p.contains("@"))
    }
}
