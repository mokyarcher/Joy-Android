package com.joy.android.data

/**
 * 从解图文本里找「对方 @ Bot」触发句。
 * 支持：`对方：@昵称 …` / `对方·群友：@昵称 …`
 */
object GroupBotMention {
    data class Hit(
        val memberName: String,
        val triggerLine: String,
        val contextText: String,
        val fingerprint: String,
        /** 触发句在摘录中的行号，用于判断下方是否已有「我：」回复 */
        val lineIndex: Int = -1,
    )

    /**
     * @param isAnswered 已回复过的触发指纹（持久化记录）；命中则跳过找更新的 @
     */
    fun find(
        chatText: String,
        botNickname: String,
        requireAt: Boolean,
        maxContextLines: Int = 16,
        isAnswered: (fingerprint: String) -> Boolean = { false },
    ): Hit? {
        val nick = botNickname.trim()
        if (nick.isEmpty()) return null
        val lines = chatText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val candidates = mutableListOf<Hit>()

        // 1) 标准「对方 / 对方·昵称」行
        val otherLines = lines.mapIndexedNotNull { idx, line ->
            val parsed = parseOtherLine(line) ?: return@mapIndexedNotNull null
            Triple(idx, parsed.first, parsed.second)
        }
        for ((idx, member, body) in otherLines.asReversed()) {
            if (requireAt && !containsAtMention(body, nick)) continue
            if (!requireAt && body.isBlank()) continue
            candidates += hitFrom(lines, idx, member, body, maxContextLines, nick)
        }

        // 2) OCR 宽松（无「对方」前缀时）
        if (requireAt) {
            for (idx in lines.indices.reversed()) {
                val line = lines[idx]
                if (isMeLine(line)) continue
                if (parseOtherLine(line) != null) continue // 已在 structured 收过
                val body = stripRolePrefix(line)
                if (!containsAtMention(body, nick) && !containsAtMention(line, nick)) continue
                val member = guessMemberName(lines, idx)
                val trigger = body.ifBlank { line }.trim()
                candidates += hitFrom(lines, idx, member, trigger, maxContextLines, nick)
            }
        }

        // 去重：同指纹保留更早出现的那条（一个个从早回起）
        val deduped = LinkedHashMap<String, Hit>()
        for (hit in candidates.sortedBy { it.lineIndex }) {
            val prev = deduped[hit.fingerprint]
            if (prev == null || hit.lineIndex < prev.lineIndex) {
                deduped[hit.fingerprint] = hit
            }
        }
        // 屏上最后一条真「我：」之前的 @ 当历史（OCR 常丢中间回复、又把旧句认成新指纹 → 会复读）
        val lastMeIndex = lines.indexOfLast { isRealMeReply(it) }
        // 未回队列：只看「我：」之后；跳过屏上已接龙 / 账本已记；从早到晚一条条来
        val unanswered = deduped.values
            .filter { hit ->
                (lastMeIndex < 0 || hit.lineIndex > lastMeIndex) &&
                    !hasBotReplyAfter(lines, hit.lineIndex) &&
                    !isAnswered(hit.fingerprint)
            }
            .sortedBy { it.lineIndex }
        return unanswered.firstOrNull()
    }

    /** 该行是否在屏上最后一条真「我：」之前（历史区，不应再回） */
    fun isBeforeLastBotReply(lines: List<String>, lineIndex: Int): Boolean {
        if (lineIndex < 0) return false
        val lastMe = lines.indexOfLast { isRealMeReply(it) }
        return lastMe >= 0 && lineIndex < lastMe
    }

    /**
     * OCR 常把个别字认错（丁真→丁頁），指纹仍应视为同一条 @。
     */
    fun fingerprintsNearEqual(a: String, b: String): Boolean {
        val x = a.trim()
        val y = b.trim()
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        if (x.length >= 8 && y.length >= 8 && (x in y || y in x)) return true
        val maxLen = maxOf(x.length, y.length)
        if (maxLen < 5) return false
        val dist = levenshtein(x, y)
        val budget = when {
            maxLen <= 10 -> 1
            maxLen <= 20 -> 2
            else -> 3
        }
        return dist <= budget
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    /** 屏上所有 @ Bot 候选（含已回），供账本对齐 */
    fun listAtHits(
        chatText: String,
        botNickname: String,
        maxContextLines: Int = 16,
    ): List<Hit> {
        val nick = botNickname.trim()
        if (nick.isEmpty()) return emptyList()
        val lines = chatText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val candidates = mutableListOf<Hit>()
        val otherLines = lines.mapIndexedNotNull { idx, line ->
            val parsed = parseOtherLine(line) ?: return@mapIndexedNotNull null
            Triple(idx, parsed.first, parsed.second)
        }
        for ((idx, member, body) in otherLines) {
            if (!containsAtMention(body, nick)) continue
            candidates += hitFrom(lines, idx, member, body, maxContextLines, nick)
        }
        for (idx in lines.indices) {
            val line = lines[idx]
            if (isMeLine(line)) continue
            if (parseOtherLine(line) != null) continue
            val body = stripRolePrefix(line)
            if (!containsAtMention(body, nick) && !containsAtMention(line, nick)) continue
            val member = guessMemberName(lines, idx)
            candidates += hitFrom(lines, idx, member, body.ifBlank { line }.trim(), maxContextLines, nick)
        }
        val deduped = LinkedHashMap<String, Hit>()
        for (h in candidates.sortedBy { it.lineIndex }) {
            if (h.fingerprint !in deduped) deduped[h.fingerprint] = h
        }
        return deduped.values.sortedBy { it.lineIndex }
    }

    /**
     * 该 @ 下方若先出现另一条对方 @，说明中间没有我方回复。
     * 误把昵称打成「我：Moky」时，不能当成已回复。
     */
    fun hasBotReplyAfter(lines: List<String>, triggerLineIndex: Int): Boolean {
        if (triggerLineIndex < 0) return false
        for (i in (triggerLineIndex + 1) until lines.size) {
            val line = lines[i]
            if (isOtherAtLine(line)) return false
            if (isRealMeReply(line)) return true
        }
        return false
    }

    private fun isOtherAtLine(line: String): Boolean {
        val body = parseOtherLine(line)?.second ?: return false
        return '@' in body || '＠' in body
    }

    /** 真回复，不是把群昵称误标成「我：」 */
    private fun isRealMeReply(line: String): Boolean {
        if (!isMeLine(line)) return false
        val body = stripRolePrefix(line)
        if (body.length in 1..14 &&
            '@' !in body &&
            '＠' !in body &&
            isPlausibleGroupNick(body)
        ) {
            return false
        }
        return true
    }

    private fun hitFrom(
        lines: List<String>,
        lineIndex: Int,
        memberName: String,
        trigger: String,
        maxContextLines: Int,
        botNickname: String,
    ): Hit {
        val start = (lineIndex - maxContextLines + 1).coerceAtLeast(0)
        val context = lines.subList(start, lines.size).joinToString("\n")
        val member = memberName.trim().ifBlank { "群友" }
        val body = trigger.trim()
        return Hit(
            memberName = member,
            triggerLine = body,
            contextText = context,
            // 去重键只看归一化触发句，不绑成员名（OCR 时而「群友」时而昵称会双发）
            fingerprint = stableTriggerKey(botNickname, body),
            lineIndex = lineIndex,
        )
    }

    /**
     * 稳定触发指纹：去掉空白 / 全半角 @ 差异，避免同句 @ 被 OCR 成两种指纹而连回两次。
     */
    fun stableTriggerKey(botNickname: String, triggerLine: String): String {
        var body = triggerLine.trim().replace('\u3000', ' ')
        val nick = botNickname.trim()
        if (nick.isNotEmpty()) {
            body = body.replace(Regex("""[@＠]\s*${Regex.escape(nick)}"""), "@")
        } else {
            body = body.replace(Regex("""[@＠]\s*"""), "@")
        }
        body = body.replace(Regex("""\s+"""), "")
        return body.take(72)
    }

    private fun isMeLine(line: String): Boolean =
        line.startsWith("我：") || line.startsWith("我:")

    private fun stripRolePrefix(line: String): String {
        parseOtherLine(line)?.let { return it.second }
        return when {
            line.startsWith("我：") -> line.removePrefix("我：").trim()
            line.startsWith("我:") -> line.removePrefix("我:").trim()
            else -> line.trim()
        }
    }

    /** 上一行若是短昵称（OCR 常单独成行），用作群友名。 */
    private fun guessMemberName(lines: List<String>, atLineIndex: Int): String {
        parseOtherLine(lines[atLineIndex])?.first
            ?.takeIf { isPlausibleGroupNick(it) }
            ?.let { return it }
        val prev = lines.getOrNull(atLineIndex - 1)?.trim().orEmpty()
        if (prev.isEmpty() || isMeLine(prev)) return "群友"
        parseOtherLine(prev)?.first?.takeIf { isPlausibleGroupNick(it) }?.let { return it }
        val t = stripRolePrefix(prev)
        if (isPlausibleGroupNick(t) && '@' !in t && '＠' !in t) {
            return t
        }
        return "群友"
    }

    /**
     * 微信群昵称启发式：拒掉「是的/好的」等口语开头，以及带标点的句子碎片。
     * OCR/模型常把正文前两字误当成昵称（如 @是的）。
     */
    fun isPlausibleGroupNick(name: String): Boolean {
        val t = name.trim()
        if (t.isEmpty() || t == "群友") return false
        if (t.length !in 1..20) return false
        if (t.any { it in "，。！？、；：…,.!?;:\"\"''（）()【】[]" }) return false
        if ('@' in t || '＠' in t) return false
        val blocked = setOf(
            "是的", "好的", "对的", "不是", "没有", "可以", "不行", "嗯嗯", "哈哈",
            "啊啊", "哦哦", "行行", "呵呵", "嘿嘿", "嗯", "哦", "啊", "哈", "呵",
            "对", "行", "喂", "嗨", "微信", "对方", "群友", "豆姐", "助手",
        )
        if (t in blocked) return false
        if (blocked.any { it.length >= 2 && t.endsWith(it) }) return false
        // 「好像不行」「在忙了」是短气泡，不是群昵称
        if (t.length >= 3 && t.last() in "了吗呢吧呀的") return false
        if (t.length <= 2 && t.any { it in "了吗呢吧呀的啊哦嗯" }) return false
        // 纯标点/数字过短不像昵称
        if (t.length <= 2 && t.all { it.isDigit() || !it.isLetter() && it !in '\u4e00'..'\u9fff' }) {
            return false
        }
        return true
    }

    /** @return Pair(显示名, 正文) */
    fun parseOtherLine(line: String): Pair<String, String>? {
        val t = line.trim()
        when {
            t.startsWith("对方·") || t.startsWith("对方･") -> {
                val rest = t.removePrefix("对方·").removePrefix("对方･")
                val idx = rest.indexOf('：').let { if (it >= 0) it else rest.indexOf(':') }
                if (idx <= 0) return "群友" to rest
                val rawName = rest.substring(0, idx).trim()
                val name = if (isPlausibleGroupNick(rawName)) rawName else "群友"
                val body = rest.substring(idx + 1).trim()
                return name to body
            }
            t.startsWith("对方：") -> return "群友" to t.removePrefix("对方：").trim()
            t.startsWith("对方:") -> return "群友" to t.removePrefix("对方:").trim()
            else -> return null
        }
    }

    fun containsAtMention(text: String, botNickname: String): Boolean {
        val nick = botNickname.trim()
        if (nick.isEmpty() || text.isEmpty()) return false
        // 常见 OCR：@昵称 / @ 昵称 / ＠昵称
        val patterns = listOf(
            Regex("""[@＠]\s*${Regex.escape(nick)}"""),
            Regex("""[@＠]\s*${Regex.escape(nick.removeSuffix("号"))}"""),
        )
        return patterns.any { it.containsMatchIn(text) }
    }

    fun buildSystemPrompt(settings: JoySettings): String {
        val parts = mutableListOf<String>()
        parts += settings.groupBotPersona.trim().ifEmpty { JoySettings.DEFAULT_GROUP_BOT_PERSONA }
        if (settings.groupBotGender != GroupBotGender.UNSPECIFIED) {
            parts += "性别设定：${settings.groupBotGender.label}。"
        }
        parts += "脾气：${settings.groupBotTemper.label}。"
        if (settings.groupBotProfession.isNotBlank()) {
            parts += "身份/职业：${settings.groupBotProfession.trim()}。"
        }
        val nick = settings.groupBotNickname.trim()
        if (nick.isNotEmpty()) {
            parts += "你在群里的昵称是「$nick」，有人 @ 你时以这个身份回复。"
        }
        parts += "只输出一条可直接发送的短回复正文。" +
            "不要自己写@任何人（系统会处理）；不要用「是的」「好的」当@对象。" +
            "不要解释、不要多条。"
        // 微信表情：方括号码可直接发出；克制使用
        parts += "可用微信表情，格式如[微笑][捂脸][强][流泪][坏笑][哇]，不要用emoji符号。" +
            "多数回复不要带表情；只有情绪到位（好笑、心疼、得意、无语等）时才加，通常1个、最多2个；" +
            "必须文字为主，禁止只发表情或表情刷屏。"
        return parts.joinToString("")
    }

    /**
     * 尽量从摘录里解析真实触发者昵称（避免只剩「群友」导致无法强制 @）。
     */
    fun resolveMemberName(hit: Hit): String {
        val n = hit.memberName.trim()
        if (isPlausibleGroupNick(n)) return n

        val lines = hit.contextText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val triggerIdx = lines.indexOfLast { line ->
            val body = parseOtherLine(line)?.second ?: stripRolePrefix(line)
            val fp = stableTriggerKey("", body)
            fp == hit.fingerprint ||
                (hit.fingerprint.length >= 8 && (hit.fingerprint in fp || fp in hit.fingerprint)) ||
                line.contains(hit.triggerLine.take(16))
        }
        if (triggerIdx >= 0) {
            parseOtherLine(lines[triggerIdx])?.first
                ?.takeIf { isPlausibleGroupNick(it) }
                ?.let { return it }
            val prev = lines.getOrNull(triggerIdx - 1).orEmpty()
            if (prev.isNotEmpty() && !isMeLine(prev)) {
                parseOtherLine(prev)?.first?.takeIf { isPlausibleGroupNick(it) }?.let { return it }
                val t = stripRolePrefix(prev)
                if (isPlausibleGroupNick(t) && !t.startsWith("对方")) return t
            }
        }
        return "群友"
    }

    /**
     * 强制回复开头 @ 触发者。
     * - 昵称可信：剥掉错误 @，换成 `@昵称 正文`
     * - 昵称不可信：剥掉模型乱写的 @（如 @是的），不瞎加
     */
    fun forceAtTrigger(reply: String, memberName: String): String {
        var body = reply.trim()
        if (body.isEmpty()) return body
        // 无论是否认得人，先剥掉开头 @xxx（常是模型胡写）
        while (true) {
            val m = Regex("""^[@＠]\s*\S+\s*""").find(body) ?: break
            body = body.removeRange(m.range).trimStart()
        }
        val name = memberName.trim()
        if (!isPlausibleGroupNick(name)) return body
        return "@$name $body"
    }
}
