package com.joy.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.groupBotRuntimeStore: DataStore<Preferences> by preferencesDataStore(
    name = "joy_group_bot_runtime",
)

/**
 * 群 Bot 运行时状态（按群隔离）：临时脾气、每日问候标记。
 */
class GroupBotRuntimeStore(private val context: Context) {
    private object Keys {
        val blob = stringPreferencesKey("runtime_json")
    }

    data class GroupRuntime(
        val temperId: String = "",
        val temperExpiresAt: Long = 0L,
        val temperChangedAt: Long = 0L,
        /** yyyy-MM-dd，当天已发过 8:30 问候 */
        val morningGreetingDay: String = "",
    )

    sealed class TemperApply {
        data class Ok(val temper: GroupBotTemper) : TemperApply()
        data class Cooldown(val remainSec: Int) : TemperApply()
    }

    suspend fun effectiveTemper(groupKey: String, fallback: GroupBotTemper): GroupBotTemper {
        val g = normalizeGroup(groupKey)
        val now = System.currentTimeMillis()
        val rt = get(g)
        if (rt.temperId.isBlank() || rt.temperExpiresAt <= now) {
            if (rt.temperId.isNotBlank() && rt.temperExpiresAt <= now) {
                // 过期清掉，避免脏数据
                put(g, rt.copy(temperId = "", temperExpiresAt = 0L))
            }
            return fallback
        }
        return GroupBotTemper.fromId(rt.temperId)
    }

    suspend fun trySetTemper(groupKey: String, temper: GroupBotTemper): TemperApply {
        val g = normalizeGroup(groupKey)
        val now = System.currentTimeMillis()
        val rt = get(g)
        val since = now - rt.temperChangedAt
        if (rt.temperChangedAt > 0L && since < TEMPER_CHANGE_COOLDOWN_MS) {
            val remain = ((TEMPER_CHANGE_COOLDOWN_MS - since + 999) / 1000L).toInt().coerceAtLeast(1)
            return TemperApply.Cooldown(remain)
        }
        put(
            g,
            rt.copy(
                temperId = temper.id,
                temperExpiresAt = now + TEMPER_TTL_MS,
                temperChangedAt = now,
            ),
        )
        return TemperApply.Ok(temper)
    }

    suspend fun clearTemper(groupKey: String): TemperApply {
        val g = normalizeGroup(groupKey)
        val now = System.currentTimeMillis()
        val rt = get(g)
        val since = now - rt.temperChangedAt
        if (rt.temperChangedAt > 0L && since < TEMPER_CHANGE_COOLDOWN_MS) {
            val remain = ((TEMPER_CHANGE_COOLDOWN_MS - since + 999) / 1000L).toInt().coerceAtLeast(1)
            return TemperApply.Cooldown(remain)
        }
        put(
            g,
            rt.copy(
                temperId = "",
                temperExpiresAt = 0L,
                temperChangedAt = now,
            ),
        )
        return TemperApply.Ok(GroupBotTemper.MILD) // 仅表示操作成功；实际回退到设置项
    }

    /** 是否处于每日问候窗口，且该群今天还没打过招呼 */
    suspend fun shouldSendMorningGreeting(groupKey: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val zone = ZoneId.systemDefault()
        val local = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
        val t = local.toLocalTime()
        if (t.isBefore(MORNING_START) || !t.isBefore(MORNING_END)) return false
        val day = local.toLocalDate().toString()
        val g = normalizeGroup(groupKey)
        return get(g).morningGreetingDay != day
    }

    suspend fun markMorningGreetingSent(groupKey: String, day: LocalDate = LocalDate.now()) {
        val g = normalizeGroup(groupKey)
        val rt = get(g)
        put(g, rt.copy(morningGreetingDay = day.toString()))
    }

    private suspend fun get(groupKey: String): GroupRuntime {
        val root = loadRoot()
        val o = root.optJSONObject(groupKey) ?: return GroupRuntime()
        return GroupRuntime(
            temperId = o.optString("temperId"),
            temperExpiresAt = o.optLong("temperExpiresAt"),
            temperChangedAt = o.optLong("temperChangedAt"),
            morningGreetingDay = o.optString("morningGreetingDay"),
        )
    }

    private suspend fun put(groupKey: String, rt: GroupRuntime) {
        val root = loadRoot()
        root.put(
            groupKey,
            JSONObject()
                .put("temperId", rt.temperId)
                .put("temperExpiresAt", rt.temperExpiresAt)
                .put("temperChangedAt", rt.temperChangedAt)
                .put("morningGreetingDay", rt.morningGreetingDay),
        )
        // 简单裁剪：最多保留 40 个群
        if (root.length() > 40) {
            val keys = root.keys().asSequence().toList()
            keys.take(root.length() - 40).forEach { root.remove(it) }
        }
        context.groupBotRuntimeStore.edit { it[Keys.blob] = root.toString() }
    }

    private suspend fun loadRoot(): JSONObject {
        val raw = context.groupBotRuntimeStore.data.map { it[Keys.blob] }.first()
        if (raw.isNullOrBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun normalizeGroup(groupKey: String): String =
        groupKey.trim().ifEmpty { "群聊" }

    companion object {
        /** 临时脾气维持 1 小时 */
        const val TEMPER_TTL_MS = 60 * 60 * 1000L
        /** 换脾气后 5 分钟内不可再换 */
        const val TEMPER_CHANGE_COOLDOWN_MS = 5 * 60 * 1000L
        val MORNING_START: LocalTime = LocalTime.of(8, 30)
        /** 半开区间 [8:30, 8:45) */
        val MORNING_END: LocalTime = LocalTime.of(8, 45)
    }
}
