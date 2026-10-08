package com.joy.android.data

/**
 * 按顶栏昵称把识图结果落到会话库：新对象建档，已有对象切回去并接上历史。
 */
object SessionRouter {
    enum class Kind { SAME, SWITCH, CREATE, RENAME }

    data class Result(
        val snapshot: SessionSnapshot,
        val session: ChatSession,
        val kind: Kind,
    )

    fun resolve(
        snap: SessionSnapshot,
        peerName: String?,
        incomingChat: String,
        now: Long = System.currentTimeMillis(),
    ): Result {
        val chat = incomingChat.trim()
        val name = ChatPeerName.sanitize(peerName)
        val active = snap.active()

        if (name == null) {
            val updated = active.copy(chatText = mergeChatLogs(active.chatText, chat), updatedAt = now)
            return Result(replaceActive(snap, updated), updated, Kind.SAME)
        }

        val hit = snap.sessions
            .filter { titlesMatch(it.title, name) }
            .maxByOrNull { it.updatedAt }

        if (hit != null) {
            val merged = mergeChatLogs(hit.chatText, chat)
            val updated = hit.copy(title = name, chatText = merged, updatedAt = now)
            val list = snap.sessions.map { if (it.id == updated.id) updated else it }
            val kind = if (hit.id == active.id) Kind.SAME else Kind.SWITCH
            return Result(SessionSnapshot(activeId = updated.id, sessions = list), updated, kind)
        }

        if (isGenericTitle(active.title)) {
            val updated = active.copy(
                title = name,
                chatText = mergeChatLogs(active.chatText, chat),
                updatedAt = now,
            )
            return Result(replaceActive(snap, updated), updated, Kind.RENAME)
        }

        val created = ChatSession.new(name).copy(chatText = chat, updatedAt = now)
        val next = SessionSnapshot(
            activeId = created.id,
            sessions = listOf(created) + snap.sessions,
        )
        return Result(next, created, Kind.CREATE)
    }

    fun titlesMatch(a: String, b: String): Boolean =
        a.trim().equals(b.trim(), ignoreCase = true)

    fun isGenericTitle(title: String): Boolean =
        title.trim() in GENERIC_TITLES

    /**
     * 把新截屏窗口接到已有记录后面：找最长「旧后缀 = 新前缀」重叠，避免整段覆盖。
     */
    fun mergeChatLogs(previous: String, incoming: String, maxLines: Int = MAX_LINES): String {
        val prev = previous.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val next = incoming.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (prev.isEmpty()) return next.joinToString("\n")
        if (next.isEmpty()) return prev.joinToString("\n")
        if (containsSublist(prev, next)) return prev.joinToString("\n")

        var overlap = minOf(prev.size, next.size)
        while (overlap > 0 && prev.takeLast(overlap) != next.take(overlap)) {
            overlap--
        }
        val merged = if (overlap == 0) prev + next else prev + next.drop(overlap)
        return merged.takeLast(maxLines).joinToString("\n")
    }

    private fun replaceActive(snap: SessionSnapshot, updated: ChatSession): SessionSnapshot {
        val list = snap.sessions.map { if (it.id == updated.id) updated else it }
        return SessionSnapshot(activeId = updated.id, sessions = list)
    }

    private fun containsSublist(hay: List<String>, needle: List<String>): Boolean {
        if (needle.size > hay.size) return false
        val lastStart = hay.size - needle.size
        for (i in 0..lastStart) {
            if (hay.subList(i, i + needle.size) == needle) return true
        }
        return false
    }

    private val GENERIC_TITLES = setOf("默认会话", "新会话", "未命名", "会话")
    const val MAX_LINES = 80
}
