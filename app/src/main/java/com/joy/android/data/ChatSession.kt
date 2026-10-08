package com.joy.android.data

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个微信对象/话题对应一条会话：独立上下文与最近候选。
 */
data class ChatSession(
    val id: String,
    val title: String,
    val chatText: String = "",
    val candidates: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun preview(maxLen: Int = 40): String {
        val line = chatText.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.isEmpty()) return "暂无上下文"
        return if (line.length <= maxLen) line else line.take(maxLen) + "…"
    }

    companion object {
        fun new(title: String = "新会话"): ChatSession =
            ChatSession(
                id = UUID.randomUUID().toString(),
                title = title.trim().ifEmpty { "新会话" },
            )
    }
}

data class SessionSnapshot(
    val activeId: String,
    val sessions: List<ChatSession>,
) {
    fun active(): ChatSession =
        sessions.firstOrNull { it.id == activeId }
            ?: sessions.firstOrNull()
            ?: ChatSession.new("默认会话")

    fun sortedByRecent(): List<ChatSession> =
        sessions.sortedByDescending { it.updatedAt }

    companion object {
        fun bootstrap(): SessionSnapshot {
            val first = ChatSession.new("默认会话")
            return SessionSnapshot(activeId = first.id, sessions = listOf(first))
        }

        fun fromJson(raw: String?): SessionSnapshot {
            if (raw.isNullOrBlank()) return bootstrap()
            return try {
                decode(raw)
            } catch (_: Exception) {
                bootstrap()
            }
        }

        fun decode(raw: String): SessionSnapshot {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("sessions") ?: JSONArray()
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    if (id.isEmpty()) continue
                    val candArr = o.optJSONArray("candidates") ?: JSONArray()
                    val candidates = buildList {
                        for (j in 0 until candArr.length()) {
                            val c = candArr.optString(j).trim()
                            if (c.isNotEmpty()) add(c)
                        }
                    }
                    add(
                        ChatSession(
                            id = id,
                            title = o.optString("title").ifBlank { "未命名" },
                            chatText = o.optString("chatText"),
                            candidates = candidates,
                            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                        ),
                    )
                }
            }
            if (list.isEmpty()) return bootstrap()
            val active = root.optString("activeId").trim()
            val activeId = if (list.any { it.id == active }) active else list.first().id
            return SessionSnapshot(activeId = activeId, sessions = list)
        }

        fun encode(snapshot: SessionSnapshot): String {
            val arr = JSONArray()
            for (s in snapshot.sessions) {
                val cands = JSONArray()
                s.candidates.forEach { cands.put(it) }
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("title", s.title)
                        .put("chatText", s.chatText)
                        .put("candidates", cands)
                        .put("updatedAt", s.updatedAt),
                )
            }
            return JSONObject()
                .put("activeId", snapshot.activeId)
                .put("sessions", arr)
                .toString()
        }
    }
}
