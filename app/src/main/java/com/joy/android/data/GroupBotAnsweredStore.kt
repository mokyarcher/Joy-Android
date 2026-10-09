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

private val Context.groupBotAnsweredStore: DataStore<Preferences> by preferencesDataStore(
    name = "joy_group_bot_answered",
)

/**
 * 群 Bot @ 回复标签账本（最近 [MAX] 条）。
 * - pending：已认领、正在回 / 待重试（失败会撤回应为未记录）
 * - done：已成功发出回复 → 不再复读
 */
class GroupBotAnsweredStore(private val context: Context) {
    private object Keys {
        val blob = stringPreferencesKey("answered_triggers_json")
    }

    enum class Status(val wire: String) {
        PENDING("pending"),
        DONE("done"),
        ;

        companion object {
            fun fromWire(s: String?): Status =
                entries.firstOrNull { it.wire == s } ?: DONE
        }
    }

    data class Tag(
        val fingerprint: String,
        val preview: String,
        val status: Status,
        val atMs: Long,
    )

    suspend fun containsFuzzy(fingerprint: String): Boolean {
        val fp = fingerprint.trim()
        if (fp.isEmpty()) return false
        return load().any { it.status == Status.DONE && matches(it.fingerprint, fp) }
    }

    /** 已成功回复的指纹（供 find 过滤复读） */
    suspend fun snapshotDoneFingerprints(): List<String> =
        load().filter { it.status == Status.DONE }.map { it.fingerprint }

    /** pending + done：找未回队列时都跳过（进行中的不双吃，已回的不复读） */
    suspend fun snapshotBlockedFingerprints(): List<String> =
        load().map { it.fingerprint }

    @Deprecated("用 snapshotDoneFingerprints", ReplaceWith("snapshotDoneFingerprints()"))
    suspend fun snapshotFingerprints(): List<String> = snapshotDoneFingerprints()

    /** 认领：标记 pending，防止并发双吃同一条 */
    suspend fun claim(fingerprint: String, triggerPreview: String = ""): Boolean {
        val fp = fingerprint.trim()
        if (fp.isEmpty()) return false
        val now = System.currentTimeMillis()
        val cur = load().toMutableList()
        val existing = cur.firstOrNull { matches(it.fingerprint, fp) }
        if (existing?.status == Status.DONE) return false
        cur.removeAll { matches(it.fingerprint, fp) }
        cur.add(0, Tag(fp, triggerPreview.trim().take(80), Status.PENDING, now))
        trim(cur, now)
        save(cur)
        return true
    }

    /** 成功发出后：pending → done */
    suspend fun markDone(fingerprint: String, triggerPreview: String = "") {
        val fp = fingerprint.trim()
        if (fp.isEmpty()) return
        val now = System.currentTimeMillis()
        val cur = load().toMutableList()
        cur.removeAll { matches(it.fingerprint, fp) }
        cur.add(0, Tag(fp, triggerPreview.trim().take(80), Status.DONE, now))
        trim(cur, now)
        save(cur)
    }

    /** 兼容旧调用：等同 markDone */
    suspend fun mark(fingerprint: String, triggerPreview: String = "") {
        markDone(fingerprint, triggerPreview)
    }

    /** 生成/发送失败：撤回应，允许稍后重试（有 @ 必须回） */
    suspend fun unmark(fingerprint: String) {
        val fp = fingerprint.trim()
        if (fp.isEmpty()) return
        val cur = load().toMutableList()
        if (!cur.removeAll { matches(it.fingerprint, fp) }) return
        save(cur)
    }

    /** 屏上已见「我：」回复时，把该 @ 补记为 done（对齐 OCR） */
    suspend fun markDoneIfKnown(fingerprint: String, triggerPreview: String = "") {
        markDone(fingerprint, triggerPreview)
    }

    suspend fun snapshotTags(): List<Tag> = load()

    private fun matches(stored: String, candidate: String): Boolean =
        GroupBotMention.fingerprintsNearEqual(stored, candidate)

    private fun trim(cur: MutableList<Tag>, now: Long) {
        while (cur.size > MAX) cur.removeAt(cur.lastIndex)
        val cutoff = now - TTL_MS
        cur.removeAll { it.atMs < cutoff }
    }

    private suspend fun load(): List<Tag> {
        val raw = context.groupBotAnsweredStore.data.map { it[Keys.blob] }.first()
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val fp = o.optString("fp").trim()
                    if (fp.isEmpty()) continue
                    // 旧数据无 status 字段 → 视为 done（已回复）
                    val status = if (o.has("status")) {
                        Status.fromWire(o.optString("status"))
                    } else {
                        Status.DONE
                    }
                    add(
                        Tag(
                            fingerprint = fp,
                            preview = o.optString("preview"),
                            status = status,
                            atMs = o.optLong("at", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun save(entries: List<Tag>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("fp", e.fingerprint)
                    .put("preview", e.preview)
                    .put("status", e.status.wire)
                    .put("at", e.atMs),
            )
        }
        context.groupBotAnsweredStore.edit { it[Keys.blob] = arr.toString() }
    }

    companion object {
        /** 只记最近 10 条 @ 标签 */
        const val MAX = 10
        private const val TTL_MS = 24 * 60 * 60_000L
    }
}
