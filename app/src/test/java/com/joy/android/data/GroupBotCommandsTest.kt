package com.joy.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupBotCommandsTest {

    @Test
    fun parsesExplicitAtWithSummon() {
        val d = GroupBotCommands.parse("@豆姐 @红师傅 让他出来聊天", "豆姐")
        assertTrue(d is GroupBotCommands.Directive.AtProxy)
        assertEquals("红师傅", (d as GroupBotCommands.Directive.AtProxy).targetNick)
    }

    @Test
    fun parsesAtSomeoneComeOutShort() {
        val d = GroupBotCommands.parse("@豆姐 @红师傅出来", "豆姐")
        assertTrue(d is GroupBotCommands.Directive.AtProxy)
        assertEquals("红师傅", (d as GroupBotCommands.Directive.AtProxy).targetNick)
    }

    @Test
    fun parsesVerbNickWithoutAt() {
        val d = GroupBotCommands.parse("@豆姐 让红师傅出来聊天", "豆姐")
        assertTrue(d is GroupBotCommands.Directive.AtProxy)
        assertEquals("红师傅", (d as GroupBotCommands.Directive.AtProxy).targetNick)
    }

    @Test
    fun parsesCallSomeoneOut() {
        val d = GroupBotCommands.parse("@豆姐 叫Moky出来聊聊", "豆姐")
        assertTrue(d is GroupBotCommands.Directive.AtProxy)
        assertEquals("Moky", (d as GroupBotCommands.Directive.AtProxy).targetNick)
    }

    @Test
    fun parsesHelpCall() {
        val d = GroupBotCommands.parse("@豆姐 帮我喊汤恒宇", "豆姐")
        assertTrue(d is GroupBotCommands.Directive.AtProxy)
        assertEquals("汤恒宇", (d as GroupBotCommands.Directive.AtProxy).targetNick)
    }

    @Test
    fun normalChatDoesNotTrigger() {
        assertNull(GroupBotCommands.parse("@豆姐 你好呀在吗", "豆姐"))
        assertNull(GroupBotCommands.parse("@豆姐 今天吃啥", "豆姐"))
        assertNull(GroupBotCommands.parse("@豆姐 红师傅刚才说得对", "豆姐"))
        assertNull(GroupBotCommands.parse("@豆姐 我觉得可以", "豆姐"))
        assertNull(GroupBotCommands.parse("@豆姐 你好出来了吗", "豆姐"))
        assertNull(GroupBotCommands.parse("@豆姐 他出来了没", "豆姐"))
    }

    @Test
    fun doesNotTreatBotAsTarget() {
        assertNull(GroupBotCommands.parse("@豆姐 出来聊天", "豆姐"))
        assertNull(GroupBotCommands.parse("@豆姐 让豆姐出来", "豆姐"))
    }

    @Test
    fun rejectsImplausibleNick() {
        assertNull(GroupBotCommands.parse("@豆姐 @是的 出来聊天", "豆姐"))
        assertNull(GroupBotCommands.parse("@豆姐 让好的出来", "豆姐"))
    }

    @Test
    fun resolveAtNameUsesProxyOnlyWhenCommand() {
        assertEquals(
            "红师傅",
            GroupBotCommands.resolveAtName("@豆姐 让红师傅出来", "豆姐", "Moky"),
        )
        assertEquals(
            "Moky",
            GroupBotCommands.resolveAtName("@豆姐 下午好", "豆姐", "Moky"),
        )
    }

    @Test
    fun forceAtStillWorksWithProxyTarget() {
        val reply = GroupBotMention.forceAtTrigger("你出来唠两句呗", "红师傅")
        assertEquals("@红师傅 你出来唠两句呗", reply)
    }

    @Test
    fun parsesDirectAtJustDoIt() {
        // log:「直接@红师傅就行了不要拖泥带水」
        val d = GroupBotCommands.parse(
            "@豆姐你别乱发 直接@红师傅就行了不要拖泥带水",
            "豆姐",
        )
        assertTrue(d is GroupBotCommands.Directive.AtProxy)
        assertEquals("红师傅", (d as GroupBotCommands.Directive.AtProxy).targetNick)
    }

    @Test
    fun parsesAtOnceWithJokeTask() {
        val d = GroupBotCommands.parse(
            "@豆姐 @一下红师傅让他讲个笑话我俩听下",
            "豆姐",
        )
        assertTrue(d is GroupBotCommands.Directive.AtProxy)
        assertEquals("红师傅", (d as GroupBotCommands.Directive.AtProxy).targetNick)
    }

    @Test
    fun parsesDoubleAtJokeFromScreenshot() {
        val d = GroupBotCommands.parse(
            "@豆姐 @红师傅 让他讲个笑话 笑死你",
            "豆姐",
        )
        assertTrue(d is GroupBotCommands.Directive.AtProxy)
        assertEquals("红师傅", (d as GroupBotCommands.Directive.AtProxy).targetNick)
    }

    @Test
    fun parsesAtOnceGluedNoSpace() {
        // OCR 常连写：@豆姐@一下红师傅…
        val d = GroupBotCommands.parse(
            "@豆姐@一下红师傅让他讲个笑话",
            "豆姐",
        )
        assertTrue(d is GroupBotCommands.Directive.AtProxy)
        assertEquals("红师傅", (d as GroupBotCommands.Directive.AtProxy).targetNick)
    }

    @Test
    fun mentioningSomeoneInChatIsNotProxy() {
        assertNull(GroupBotCommands.parse("@豆姐 @红师傅说得对", "豆姐"))
        assertNull(GroupBotCommands.parse("@豆姐 @红师傅 你好", "豆姐"))
    }

    @Test
    fun parsesSetAddressDadMomSister() {
        val dad = GroupBotCommands.parse("@豆姐 你以后都叫我爸爸", "豆姐")
        assertTrue(dad is GroupBotCommands.Directive.SetAddress)
        assertEquals("爸爸", (dad as GroupBotCommands.Directive.SetAddress).title)

        val mom = GroupBotCommands.parse("@豆姐 从今以后叫我妈妈", "豆姐")
        assertEquals("妈妈", (mom as GroupBotCommands.Directive.SetAddress).title)

        val sis = GroupBotCommands.parse("@豆姐 称呼我为姐姐", "豆姐")
        assertEquals("姐姐", (sis as GroupBotCommands.Directive.SetAddress).title)

        val bro = GroupBotCommands.parse("@豆姐 叫我哥哥", "豆姐")
        assertEquals("哥哥", (bro as GroupBotCommands.Directive.SetAddress).title)
    }

    @Test
    fun parsesCustomAddressTitle() {
        val d = GroupBotCommands.parse("@豆姐 以后叫我老板", "豆姐")
        assertTrue(d is GroupBotCommands.Directive.SetAddress)
        assertEquals("老板", (d as GroupBotCommands.Directive.SetAddress).title)
    }

    @Test
    fun parsesClearAddress() {
        assertTrue(
            GroupBotCommands.parse("@豆姐 别叫我爸爸了", "豆姐")
                is GroupBotCommands.Directive.ClearAddress,
        )
        assertTrue(
            GroupBotCommands.parse("@豆姐 取消称呼", "豆姐")
                is GroupBotCommands.Directive.ClearAddress,
        )
    }

    @Test
    fun setAddressDoesNotStealCallHimOut() {
        // 「叫红师傅出来」是代劳，不是「叫我」
        val d = GroupBotCommands.parse("@豆姐 叫红师傅出来聊天", "豆姐")
        assertTrue(d is GroupBotCommands.Directive.AtProxy)
        assertEquals("红师傅", (d as GroupBotCommands.Directive.AtProxy).targetNick)
    }

    @Test
    fun callMeComeOutIsNotAddress() {
        assertNull(GroupBotCommands.parse("@豆姐 叫我出来", "豆姐"))
    }

    @Test
    fun forceAtWithAddressInsertsTitle() {
        assertEquals(
            "@Moky 爸爸 咋啦今天",
            GroupBotCommands.forceAtWithAddress("咋啦今天", "Moky", "爸爸"),
        )
        assertEquals(
            "@Moky 爸爸 好的",
            GroupBotCommands.forceAtWithAddress("爸爸 好的", "Moky", "爸爸"),
        )
        assertEquals(
            "@Moky 你好",
            GroupBotCommands.forceAtWithAddress("你好", "Moky", ""),
        )
    }

    @Test
    fun resolveAtNameKeepsSenderForAddressCommand() {
        assertEquals(
            "Moky",
            GroupBotCommands.resolveAtName("@豆姐 叫我爸爸", "豆姐", "Moky"),
        )
    }

    @Test
    fun parsesRelaySayVerbatim() {
        val d = GroupBotCommands.parse("@豆姐 跟红师傅说：晚上别迟到", "豆姐")
        assertTrue(d is GroupBotCommands.Directive.RelaySay)
        val r = d as GroupBotCommands.Directive.RelaySay
        assertEquals("红师傅", r.targetNick)
        assertEquals("晚上别迟到", r.message)
    }

    @Test
    fun parsesRelayTellColon() {
        val d = GroupBotCommands.parse("@豆姐 告诉Moky：开会推迟了", "豆姐")
        assertTrue(d is GroupBotCommands.Directive.RelaySay)
        assertEquals("Moky", (d as GroupBotCommands.Directive.RelaySay).targetNick)
        assertEquals("开会推迟了", d.message)
    }

    @Test
    fun parsesSetTemperSharp() {
        val d = GroupBotCommands.parse("@豆姐 毒舌一点", "豆姐")
        assertTrue(d is GroupBotCommands.Directive.SetTemper)
        assertEquals(GroupBotTemper.SHARP, (d as GroupBotCommands.Directive.SetTemper).temper)
    }

    @Test
    fun parsesClearTemper() {
        assertTrue(
            GroupBotCommands.parse("@豆姐 正常说话", "豆姐")
                is GroupBotCommands.Directive.ClearTemper,
        )
        assertTrue(
            GroupBotCommands.parse("@豆姐 恢复脾气", "豆姐")
                is GroupBotCommands.Directive.ClearTemper,
        )
    }

    @Test
    fun morningGreetingChangesByDay() {
        val a = GroupBotCommands.morningGreetingLine(1)
        val b = GroupBotCommands.morningGreetingLine(2)
        assertTrue(a.isNotBlank())
        assertTrue(b.isNotBlank())
        assertTrue(a != b || GroupBotCommands.morningGreetingLine(16) != a)
    }

    @Test
    fun relayBeatsAtProxyWhenColonSay() {
        // 「跟红师傅说：讲个笑话」应原话代传，而不是代劳改写
        val d = GroupBotCommands.parse("@豆姐 跟红师傅说：讲个笑话给我听", "豆姐")
        assertTrue(d is GroupBotCommands.Directive.RelaySay)
        assertEquals("讲个笑话给我听", (d as GroupBotCommands.Directive.RelaySay).message)
    }
}
