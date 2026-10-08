package com.joy.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRouterTest {
    @Test
    fun newPeerCreatesSessionWithoutOverwritingOld() {
        val old = ChatSession(id = "a", title = "张三", chatText = "对方：在吗")
        val snap = SessionSnapshot(activeId = "a", sessions = listOf(old))
        val out = SessionRouter.resolve(snap, "李四", "对方：吃了吗")
        assertEquals(SessionRouter.Kind.CREATE, out.kind)
        assertEquals("李四", out.session.title)
        assertEquals("对方：吃了吗", out.session.chatText)
        assertEquals(2, out.snapshot.sessions.size)
        val kept = out.snapshot.sessions.first { it.id == "a" }
        assertEquals("对方：在吗", kept.chatText)
        assertEquals(out.session.id, out.snapshot.activeId)
    }

    @Test
    fun knownPeerSwitchesBackAndMergesHistory() {
        val zhang = ChatSession(id = "a", title = "张三", chatText = "对方：在吗\n我：有空")
        val li = ChatSession(id = "b", title = "李四", chatText = "对方：吃了吗")
        val snap = SessionSnapshot(activeId = "b", sessions = listOf(li, zhang))
        val out = SessionRouter.resolve(
            snap,
            "张三",
            "我：有空\n对方：那晚上见",
        )
        assertEquals(SessionRouter.Kind.SWITCH, out.kind)
        assertEquals("a", out.snapshot.activeId)
        assertEquals("对方：在吗\n我：有空\n对方：那晚上见", out.session.chatText)
        assertEquals("对方：吃了吗", out.snapshot.sessions.first { it.id == "b" }.chatText)
    }

    @Test
    fun genericActiveRenamesInPlace() {
        val def = ChatSession(id = "d", title = "默认会话", chatText = "对方：嗨")
        val snap = SessionSnapshot(activeId = "d", sessions = listOf(def))
        val out = SessionRouter.resolve(snap, "moky", "对方：嗨\n我：在")
        assertEquals(SessionRouter.Kind.RENAME, out.kind)
        assertEquals("d", out.session.id)
        assertEquals("moky", out.session.title)
        assertEquals(1, out.snapshot.sessions.size)
    }

    @Test
    fun mergeSkipsDuplicateWindow() {
        val prev = "对方：A\n我：B\n对方：C"
        val incoming = "我：B\n对方：C"
        assertEquals(prev, SessionRouter.mergeChatLogs(prev, incoming))
    }
}
