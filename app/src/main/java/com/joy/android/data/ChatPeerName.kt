package com.joy.android.data

/**
 * 识图结果：聊天摘录 + 可选顶栏会话对象昵称。
 */
data class ChatExtractResult(
    val chatText: String,
    val peerName: String? = null,
)

/**
 * 从微信顶栏抠出聊天对象名（手机居中标题 / 电脑窗左上角昵称）。
 */
object OcrPeerNameExtractor {
    fun extract(rawLines: List<OcrRawLine>): String? {
        val topLines = rawLines.filter { it.top <= TOP_BAND || it.bottom <= TOP_BAND_BOTTOM }
        if (topLines.isEmpty()) return null

        data class Cand(val name: String, val line: OcrRawLine, val score: Float)

        val cands = topLines.mapNotNull { line ->
            val name = ChatPeerName.sanitize(line.text) ?: return@mapNotNull null
            Cand(name, line, score(line, name))
        }
        return cands
            .filter { it.score >= MIN_SCORE }
            .maxByOrNull { it.score }
            ?.name
    }

    private fun score(line: OcrRawLine, name: String): Float {
        var s = 0f
        // 电脑微信：标题在聊天区左上
        if (line.left <= 0.42f) s += 3.2f
        // 手机微信：标题多在顶栏中部
        if (line.cx in 0.22f..0.78f) s += 1.6f
        if (line.top <= 0.06f) s += 2.0f else if (line.top <= 0.10f) s += 1.0f
        when (name.length) {
            in 2..8 -> s += 1.4f
            1 -> s -= 0.8f
            in 9..14 -> s += 0.3f
            else -> s -= 1.5f
        }
        // 过宽的整行不像昵称（状态栏拼串）
        if (line.right - line.left > 0.55f) s -= 2.0f
        return s
    }

    private const val TOP_BAND = 0.11f
    private const val TOP_BAND_BOTTOM = 0.13f
    private const val MIN_SCORE = 2.5f
}

object ChatPeerName {
    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var t = raw.trim()
            .trim('"', '\'', '“', '”', '‘', '’')
            .replace(Regex("""\s+"""), " ")
        // 会话对象：陈佳佳
        t = t.removePrefix("会话对象：").removePrefix("会话对象:").trim()
        // 陈佳佳(3) / 陈佳佳（12）/ 陈佳佳...
        t = t.replace(Regex("""[（(]\d+[）)]$"""), "")
        t = t.replace(Regex("""[.…]+$"""), "").trim()
        if (t.isEmpty() || t.length > 20) return null
        if (t in BLOCKED || t.lowercase() in BLOCKED_LOWER) return null
        if (DIGIT_ONLY.matches(t)) return null
        if (CLOCK.matches(t)) return null
        if (looksLikeSentence(t)) return null
        // 至少有一个中文或字母
        if (!t.any { it in '\u4e00'..'\u9fff' || it.isLetter() }) return null
        return t
    }

    private fun looksLikeSentence(text: String): Boolean {
        if (text.length >= 16) return true
        if (text.any { it in "，。！？、；：…,.!?;:" }) return true
        return false
    }

    private val DIGIT_ONLY = Regex("""^\d+$""")
    private val CLOCK = Regex("""^\d{1,2}\s*[:：]\s*\d{2}$""")
    private val BLOCKED = setOf(
        "微信", "WeChat", "搜索", "通讯录", "发现", "详情",
        "切换到键盘", "表情", "更多功能", "视频通话", "语音通话",
        "对方", "我", "昨天", "今天", "前天", "刚刚",
    )
    private val BLOCKED_LOWER = setOf("wechat", "search")
}
