package com.joy.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionTest {
    @Test
    fun previewUsesFirstNonBlankLine() {
        val s = ChatSession(
            id = "1",
            title = "小王",
            chatText = "\n对方：在吗\n我：有空",
        )
        assertEquals("对方：在吗", s.preview())
    }

    @Test
    fun bootstrapHasActiveSession() {
        val snap = SessionSnapshot.bootstrap()
        assertEquals(1, snap.sessions.size)
        assertEquals(snap.sessions.first().id, snap.activeId)
        assertEquals("默认会话", snap.active().title)
    }

    @Test
    fun normalizeDropsDuplicatesAndCapsCount() {
        val many = (1..50).map { i ->
            ChatSession(
                id = "id-$i",
                title = "s$i",
                updatedAt = i.toLong(),
            )
        }
        val snap = SessionStore.normalize(
            SessionSnapshot(activeId = "id-50", sessions = many + many.take(3)),
        )
        assertEquals(SessionStore.MAX_SESSIONS, snap.sessions.size)
        assertEquals("id-50", snap.activeId)
        assertTrue(snap.sessions.first().updatedAt >= snap.sessions.last().updatedAt)
    }
}
