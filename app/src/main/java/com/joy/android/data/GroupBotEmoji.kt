package com.joy.android.data

/**
 * 微信方括号表情：模型可偶尔写入 `[微笑]`，发送后微信会渲染。
 * 本类做轻量收尾：白名单、限频、禁止纯表情。
 */
object GroupBotEmoji {
    private val TOKEN = Regex("""\[[^\[\]]{1,8}]""")

    /** 常用微信表情（够用即可，未收录的方括号会被去掉，防乱码） */
    val ALLOWED = setOf(
        "微笑", "撇嘴", "色", "发呆", "得意", "流泪", "害羞", "闭嘴", "睡", "大哭",
        "尴尬", "发怒", "调皮", "呲牙", "惊讶", "难过", "囧", "抓狂", "吐", "偷笑",
        "愉快", "白眼", "傲慢", "困", "惊恐", "流汗", "憨笑", "悠闲", "奋斗", "咒骂",
        "疑问", "嘘", "晕", "衰", "敲打", "再见", "擦汗", "抠鼻", "鼓掌", "坏笑",
        "左哼哼", "右哼哼", "哈欠", "鄙视", "委屈", "快哭了", "阴险", "亲亲", "吓",
        "可怜", "西瓜", "啤酒", "咖啡", "猪头", "玫瑰", "爱心", "心碎", "蛋糕", "月亮",
        "太阳", "拥抱", "强", "弱", "握手", "胜利", "抱拳", "OK", "跳跳", "发抖",
        "转圈", "嘿哈", "捂脸", "奸笑", "机智", "皱眉", "耶", "吃瓜", "加油", "汗",
        "天啊", "Emm", "社会社会", "旺柴", "好的", "打脸", "哇", "翻白眼", "666",
        "让我看看", "叹气", "苦涩", "裂开", "笑脸", "生病", "脸红", "破涕为笑", "恐惧",
        "失望", "无语", "嘿", "加油加油",
    )

    private const val MAX_PER_REPLY = 2

    /**
     * 收尾模型输出（尚未加 @）：
     * - 只保留白名单表情
     * - 每条最多 [MAX_PER_REPLY] 个
     * - 去掉纯表情（必须留有汉字/字母/数字）
     */
    fun polishBody(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return text

        val kept = mutableListOf<String>()
        text = TOKEN.replace(text) { m ->
            val inner = m.value.removeSurrounding("[", "]").trim()
            if (inner !in ALLOWED) return@replace ""
            if (kept.size >= MAX_PER_REPLY) return@replace ""
            kept += inner
            "[$inner]"
        }

        text = text.replace(Regex("""[ \t]{2,}"""), " ").trim()
        // 纯表情 / 表情+空白 → 去掉表情，避免只发 [微笑]
        val withoutEmoji = TOKEN.replace(text, "").trim()
        if (withoutEmoji.isEmpty() || withoutEmoji.none { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' }) {
            return withoutEmoji
        }
        return text.trim()
    }
}
