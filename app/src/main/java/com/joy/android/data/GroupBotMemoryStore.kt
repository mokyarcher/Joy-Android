package com.joy.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.groupBotMemoryStore: DataStore<Preferences> by preferencesDataStore(
    name = "joy_group_bot_memory",
)

data class GroupBotPeerMemory(
    val groupKey: String,
    val memberKey: String,
    val memberName: String,
    /** 近期对话摘录（Bot 与该群友） */
    val recentTurns: String = "",
    /**
     * 该群友专属称呼（仅对他生效）：如「爸爸」「妈妈」「姐姐」。
     * 空 = 无特殊称呼。
     */
    val addressTitle: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val id: String get() = keyOf(groupKey, memberKey)

    companion object {
        fun keyOf(groupKey: String, memberKey: String): String =
            "${groupKey.trim()}::${memberKey.trim()}"
    }
}

/**
 * 群 Bot 分人记忆：按「群 × 群友」隔离上下文，避免串戏。
 */
class GroupBotMemoryStore(private val context: Context) {
    private object Keys {
        val blob = stringPreferencesKey("peer_memories_json")
    }

    suspend fun get(groupKey: String, memberKey: String): GroupBotPeerMemory? {
        val g = groupKey.trim().ifEmpty { "群聊" }
        val m = memberKey.trim().ifEmpty { "群友" }
        return listAll().firstOrNull { it.groupKey == g && it.memberKey == m }
    }

    suspend fun upsert(memory: GroupBotPeerMemory) {
        val g = memory.groupKey.trim().ifEmpty { "群聊" }
        val m = memory.memberKey.trim().ifEmpty { "群友" }
        val next = memory.copy(
            groupKey = g,
            memberKey = m,
            memberName = memory.memberName.trim().ifEmpty { m },
            updatedAt = System.currentTimeMillis(),
        )
        val others = listAll().filterNot { it.id == next.id }
        writeAll((listOf(next) + others).sortedByDescending { it.updatedAt }.take(MAX_PEERS))
    }

    suspend fun appendTurn(
        groupKey: String,
        memberKey: String,
        memberName: String,
        userLine: String,
        botLine: String,
        maxChars: Int = 1_800,
    ): GroupBotPeerMemory {
        val prev = get(groupKey, memberKey)
        val block = buildString {
            append("对方·").append(memberName.ifBlank { memberKey }).append("：").append(userLine.trim())
            append('\n')
            append("我：").append(botLine.trim())
        }
        val merged = listOfNotNull(prev?.recentTurns?.takeIf { it.isNotBlank() }, block)
            .joinToString("\n")
            .let { text ->
                if (text.length <= maxChars) text else text.takeLast(maxChars)
            }
        val mem = GroupBotPeerMemory(
            groupKey = groupKey,
            memberKey = memberKey,
            memberName = memberName.ifBlank { memberKey },
            recentTurns = merged,
            addressTitle = prev?.addressTitle.orEmpty(),
        )
        upsert(mem)
        return mem
    }

    /** 只改该群友的专属称呼；空字符串 = 清除。 */
    suspend fun setAddressTitle(
        groupKey: String,
        memberKey: String,
        memberName: String,
        title: String,
    ): GroupBotPeerMemory {
        val prev = get(groupKey, memberKey)
        val mem = GroupBotPeerMemory(
            groupKey = groupKey,
            memberKey = memberKey,
            memberName = memberName.ifBlank { prev?.memberName.orEmpty().ifBlank { memberKey } },
            recentTurns = prev?.recentTurns.orEmpty(),
            addressTitle = title.trim(),
        )
        upsert(mem)
        return mem
    }

    private suspend fun listAll(): List<GroupBotPeerMemory> {
        val raw = context.groupBotMemoryStore.data.map { it[Keys.blob] }.first()
        return decode(raw)
    }

    private suspend fun writeAll(list: List<GroupBotPeerMemory>) {
        context.groupBotMemoryStore.edit { prefs ->
            prefs[Keys.blob] = encode(list)
        }
    }

    companion object {
        private const val MAX_PEERS = 80

        fun encode(list: List<GroupBotPeerMemory>): String {
            val arr = JSONArray()
            list.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("groupKey", m.groupKey)
                        .put("memberKey", m.memberKey)
                        .put("memberName", m.memberName)
                        .put("recentTurns", m.recentTurns)
                        .put("addressTitle", m.addressTitle)
                        .put("updatedAt", m.updatedAt),
                )
            }
            return arr.toString()
        }

        fun decode(raw: String?): List<GroupBotPeerMemory> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(
                            GroupBotPeerMemory(
                                groupKey = o.optString("groupKey"),
                                memberKey = o.optString("memberKey"),
                                memberName = o.optString("memberName"),
                                recentTurns = o.optString("recentTurns"),
                                addressTitle = o.optString("addressTitle"),
                                updatedAt = o.optLong("updatedAt", 0L),
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
