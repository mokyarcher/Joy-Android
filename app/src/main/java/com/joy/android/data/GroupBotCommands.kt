package com.joy.android.data

/**
 * 群 Bot 指令框架：仅在触发句命中明确指令时改写行为；普通闲聊返回 null。
 * 后续可在此加装更多 [Directive] 类型。
 */
object GroupBotCommands {

    sealed class Directive {
        /** 让 Bot 去 @ 指定群友（召唤 / 转达 / 代劳） */
        data class AtProxy(
            val targetNick: String,
            /** 便于 log / 调试 */
            val matchedBy: String,
        ) : Directive()

        /**
         * 设定「只对下达指令的人」的专属称呼。
         * 例：以后叫我爸爸 / 称呼我姐姐
         */
        data class SetAddress(
            val title: String,
            val matchedBy: String,
        ) : Directive()

        /** 清除该人对 Bot 的专属称呼 */
        data class ClearAddress(
            val matchedBy: String = "clear",
        ) : Directive()

        /** 临时换脾气（群级，有 TTL / 冷却） */
        data class SetTemper(
            val temper: GroupBotTemper,
            val matchedBy: String,
        ) : Directive()

        /** 恢复设置里的默认脾气 */
        data class ClearTemper(
            val matchedBy: String = "clear_temper",
        ) : Directive()

        /**
         * 原话代传：跟 X 说：正文 → 直接 `@X 正文`，不经模型改写。
         */
        data class RelaySay(
            val targetNick: String,
            val message: String,
            val matchedBy: String,
        ) : Directive()
    }

    /**
     * 从「对方 @ Bot」的触发句解析指令。
     * @return null = 普通聊天，仍 @ 触发者
     */
    fun parse(triggerLine: String, botNickname: String): Directive? {
        val bot = botNickname.trim()
        if (bot.isEmpty()) return null
        val raw = triggerLine.trim().replace('\u3000', ' ')
        if (raw.isEmpty()) return null

        // 去掉对本 Bot 的 @，避免把豆姐当成目标
        val body = stripBotMention(raw, bot).trim()
        if (body.isEmpty()) return null

        // 原话代传优先于「让他讲笑话」类代劳
        matchRelaySay(body, bot)?.let { return it }

        // 专属称呼（「叫我爸爸」勿被「叫他出来」误伤）
        matchClearAddress(body)?.let { return it }
        matchSetAddress(body)?.let { return it }

        // 换脾气
        matchClearTemper(body)?.let { return it }
        matchSetTemper(body)?.let { return it }

        val targets = extractAtTargets(body, bot)
        if (targets.isNotEmpty() && hasProxyIntent(body)) {
            return Directive.AtProxy(targets.first(), "explicit_at+intent")
        }
        targets.firstOrNull()?.let { target ->
            if (isShortProxyLine(body, target)) {
                return Directive.AtProxy(target, "explicit_at_short")
            }
        }

        // 无 @：让/叫/喊 + 昵称 + 动作
        matchVerbNickProxy(body, bot)?.let { return it }

        return null
    }

    /** 供回复落款：指令改写 @ 目标，否则仍用触发者 */
    fun resolveAtName(triggerLine: String, botNickname: String, triggerSender: String): String {
        val d = parse(triggerLine, botNickname)
        return when (d) {
            is Directive.AtProxy -> d.targetNick
            is Directive.RelaySay -> d.targetNick
            else -> triggerSender
        }
    }

    fun promptHint(directive: Directive?): String = when (directive) {
        is Directive.AtProxy ->
            "这是一条代劳指令：对方要你去 @「${directive.targetNick}」，并按原句意图对他说话" +
                "（出来聊/讲笑话/搭话等均可）。" +
                "只写一条短正文，开头不要写任何@（系统会自动加 @${directive.targetNick}）。" +
                "不要再 @ 下达指令的人。"
        is Directive.SetAddress ->
            "这是一条称呼设定：对方要求你以后叫他「${directive.title}」。" +
                "只写一条短确认正文，口头答应并示范一声「${directive.title}」即可；" +
                "不要写任何@（系统会自动加）。这个称呼只对他本人有效。"
        is Directive.ClearAddress ->
            "对方要你取消对他的特殊称呼。只写一条短确认正文，不要写任何@（系统会自动加）。"
        is Directive.SetTemper ->
            "对方让你暂时把脾气改成「${directive.temper.label}」（约一小时）。" +
                "只写一条短确认正文，不要写任何@（系统会自动加）。"
        is Directive.ClearTemper ->
            "对方让你恢复默认脾气。只写一条短确认正文，不要写任何@（系统会自动加）。"
        is Directive.RelaySay -> "" // 原话直发，不走模型
        null -> ""
    }

    /** 每日 8:30 问候 demo：按一年中的第几天轮换，保证「每天不一样」。 */
    fun morningGreetingLine(dayOfYear: Int = java.time.LocalDate.now().dayOfYear): String {
        val lines = listOf(
            "早呀各位，新的一天从摸鱼开始～",
            "早上好，姐先上线打个卡，你们慢慢醒。",
            "叮～今日份问候送达，记得喝水别光刷手机。",
            "哈喽哈喽，太阳都晒屁股了，群里有人吗？",
            "早啊，愿你们今天的bug比脾气少。",
            "晨报：姐醒了，群还在，世界暂时安全。",
            "早上好呀，今天也要元气满满（或至少假装有）。",
            "醒醒醒醒，新的一天，旧的班，先互相伤害一下。",
            "早，姐用一句废话打开今天：你们真勤快。",
            "上午好，摸鱼许可已盖章，可限量使用哦。",
            "早起的鸟儿有虫吃，早起的群友有姐陪聊。",
            "新的一天，新的摆烂姿势，早上好。",
            "滴，今日问候卡：笑一个，别板着脸上班。",
            "早呀，姐把好运分包发一下，先到先得。",
            "早上好，今天也请对键盘温柔一点。",
        )
        val idx = ((dayOfYear % lines.size) + lines.size) % lines.size
        return lines[idx]
    }

    /**
     * 已有专属称呼时的闲聊提示（只对触发者本人）。
     */
    fun addressChatHint(title: String, senderName: String): String {
        val t = title.trim()
        if (t.isEmpty()) return ""
        return "你对该群友「$senderName」的专属称呼是「$t」。" +
            "正文里要用「$t」来称呼他（自然带上即可，不要写成解释），" +
            "不要写任何@（系统会自动加）。"
    }

    /** 回复落款：`@昵称 称呼 正文`；无称呼则 `@昵称 正文` */
    fun forceAtWithAddress(reply: String, memberName: String, addressTitle: String): String {
        val base = GroupBotMention.forceAtTrigger(reply, memberName)
        val title = addressTitle.trim()
        if (title.isEmpty() || !GroupBotMention.isPlausibleGroupNick(memberName)) return base
        val name = memberName.trim()
        val prefix = "@$name "
        if (!base.startsWith(prefix)) return base
        val body = base.removePrefix(prefix).trimStart()
        // 已有称呼开头则不重复
        if (body.startsWith(title)) return base
        return "$prefix$title $body"
    }

    private fun stripBotMention(text: String, bot: String): String {
        var t = text
        val patterns = listOf(
            Regex("""[@＠]\s*${Regex.escape(bot)}"""),
            Regex("""[@＠]\s*${Regex.escape(bot.removeSuffix("号"))}"""),
        )
        for (p in patterns) {
            t = p.replace(t, " ")
        }
        return t.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * 抽出句中「要 @ 的别人」。
     * 支持：`@红师傅` / `@一下红师傅` / `＠ 红师傅就行了`
     */
    private fun extractAtTargets(body: String, bot: String): List<String> {
        val out = mutableListOf<String>()

        // 1) @一下XXX / 艾特一下XXX（OCR 常连写）
        val atOnce = Regex(
            """(?:[@＠]\s*一下|艾特一下)\s*([^\s@＠,，。！？、；：~～]{1,20}?)(?=$AT_STOP)""",
        )
        for (m in atOnce.findAll(body)) {
            addTarget(out, m.groupValues[1], bot)
        }

        // 2) 普通 @XXX（停在语气/指令边界，避免吞掉「就行了」）
        val plainAt = Regex(
            """[@＠]\s*(?!一下)([^\s@＠,，。！？、；：~～]{1,20}?)(?=$AT_STOP)""",
        )
        for (m in plainAt.findAll(body)) {
            addTarget(out, m.groupValues[1], bot)
        }

        return out
    }

    private fun addTarget(out: MutableList<String>, raw: String, bot: String) {
        val name = raw.trim().trimEnd('吧', '呀', '啊', '嘛', '呢', '啦')
        if (name.length < 2) return
        if (name.equals(bot, ignoreCase = true)) return
        if (name.equals(bot.removeSuffix("号"), ignoreCase = true)) return
        // OCR 截断「红师」：太短且像半截时仍要求 plausible；红师 length 2 可能过
        if (!GroupBotMention.isPlausibleGroupNick(name)) return
        if (name !in out) out += name
    }

    /**
     * 代劳意图：比「出来聊天」更宽——直接@、讲笑话、让他…、@一下…
     * 不含意图时即使句中出现别人名字也不改落款（防闲聊误伤）。
     */
    private fun hasProxyIntent(body: String): Boolean {
        val cues = listOf(
            // 召唤 / 出场
            "出来", "出来说", "出来聊", "来聊天", "来聊聊", "来搭话", "下水",
            "喊一下", "叫一下", "帮我叫", "帮我喊", "拉出来", "拉下水",
            // 明确要 Bot 去 @
            "去@", "去＠", "@一下", "＠一下", "艾特一下",
            "直接@", "直接＠", "只要@", "只要＠", "只@", "只＠",
            "就行了", "就好了", "就行", "别乱发", "不要拖泥带水",
            "执行力",
            // 代词指使
            "让他", "让她", "让它", "让TA", "让ta",
            "叫他", "叫她", "喊他", "喊她",
            // 任务类（对着第三人做）
            "讲个笑话", "讲笑话", "说个笑话", "讲个段子", "说个段子",
            "让他讲", "让她讲", "叫他讲", "叫她讲",
        )
        return cues.any { it in body }
    }

    private fun isShortProxyLine(body: String, target: String): Boolean {
        if (body.length > 32) return false
        // 昵称里已夹带「说得/讲个」→ 多半是闲聊误切，不能当「整句只 @ 某人」
        if (target.any { it in "说得讲" }) return false
        val stripped = body
            .replace(Regex("""[@＠]\s*一下\s*${Regex.escape(target)}"""), "")
            .replace(Regex("""[@＠]\s*${Regex.escape(target)}"""), "")
            .replace(" ", "")
        if (stripped.isEmpty()) {
            // 真·短指令：@红师傅 / @红师傅出来（出来已在 target 外）
            return target.length in 2..12
        }
        return hasProxyIntent(body) ||
            stripped in setOf("出来", "出来说", "来", "来聊", "来聊天", "下水", "就行", "就行了", "就好了")
    }

    private fun matchRelaySay(body: String, bot: String): Directive.RelaySay? {
        val patterns = listOf(
            // 跟红师傅说：晚上别迟到 / 跟红师傅讲：…
            Regex("""(?:跟|和|同)\s*([^\s@＠,，。！？、；：]{1,20}?)\s*(?:说|讲|讲一声|带话|带句话)\s*[:：]\s*(.+)"""),
            // 告诉红师傅：晚上别迟到
            Regex("""告诉\s*([^\s@＠,，。！？、；：]{1,20}?)\s*[:：]\s*(.+)"""),
            // 帮我跟红师傅说：…
            Regex("""帮我\s*(?:跟|和)\s*([^\s@＠,，。！？、；：]{1,20}?)\s*(?:说|讲)\s*[:：]\s*(.+)"""),
        )
        for (re in patterns) {
            val m = re.find(body) ?: continue
            val target = m.groupValues[1].trim().trimEnd('吧', '呀', '啊', '嘛', '呢')
            val msg = m.groupValues[2].trim().trimStart('「', '『', '"', '\'', '“')
                .trimEnd('」', '』', '"', '\'', '”')
            if (!GroupBotMention.isPlausibleGroupNick(target)) continue
            if (target.equals(bot, ignoreCase = true)) continue
            if (msg.length < 1 || msg.length > 200) continue
            // 拒「跟我说：」——那是对 Bot 自己说话
            if (target in setOf("我", "咱", "我们")) continue
            return Directive.RelaySay(target, msg, "relay_say")
        }
        return null
    }

    private fun matchClearTemper(body: String): Directive.ClearTemper? {
        val cues = listOf(
            "恢复脾气", "恢复默认脾气", "正常说话", "别毒舌了", "取消脾气", "脾气恢复",
        )
        if (cues.any { it in body }) return Directive.ClearTemper()
        return null
    }

    private fun matchSetTemper(body: String): Directive.SetTemper? {
        // 关键词 → 脾气（越具体越靠前）
        val rules = listOf(
            listOf("毒舌", "刻薄", "损一点", "损点") to GroupBotTemper.SHARP,
            listOf("冷淡", "高冷", "冰一点", "冷一点") to GroupBotTemper.DRY,
            listOf("活泼", "嗨一点", "开朗") to GroupBotTemper.LIVELY,
            listOf("暖心", "温柔点", "温柔一点", "贴心") to GroupBotTemper.WARM,
            listOf("温和", "平和", "正常点脾气") to GroupBotTemper.MILD,
        )
        val intent = listOf("换脾气", "改脾气", "脾气", "性格", "说话", "一点", "点")
        val hasIntent = intent.any { it in body } ||
            body.contains("换成") || body.contains("改成") || body.contains("用")
        for ((keys, temper) in rules) {
            if (keys.none { it in body }) continue
            // 「别毒舌了」走 Clear，不在这里
            if ("别" in body && temper == GroupBotTemper.SHARP && "毒舌" in body) continue
            // 需有换脾气意图，或短句几乎只有脾气词（「毒舌一点」）
            val shortPure = body.length <= 12 && keys.any { it in body }
            if (!hasIntent && !shortPure) continue
            return Directive.SetTemper(temper, "set_temper")
        }
        return null
    }

    private fun matchClearAddress(body: String): Directive.ClearAddress? {
        val cues = listOf(
            "别叫我", "不要叫我", "别再叫我", "取消称呼", "不用叫我", "别喊我", "不要喊我",
        )
        if (cues.none { it in body }) return null
        // 「别叫我出来」是动作不是清称呼
        if (Regex("""别(?:再)?(?:叫|喊)我\s*(?:出来|过来|一下)""").containsMatchIn(body)) return null
        return Directive.ClearAddress("clear_address")
    }

    private fun matchSetAddress(body: String): Directive.SetAddress? {
        val patterns = listOf(
            // 核心：叫我X / 喊我X / 称呼我X（前面可有「你以后都」等修饰）
            Regex(
                """(?:叫|喊|称呼)我(?:一声|为|做|成)?\s*([^\s@＠,，。！？、；：~～的]{1,8})""",
            ),
            // 称呼我为姐姐
            Regex(
                """称呼我(?:为|做|成)\s*([^\s@＠,，。！？、；：~～的]{1,8})""",
            ),
        )
        for (re in patterns) {
            val m = re.find(body) ?: continue
            val title = sanitizeAddressTitle(m.groupValues[1]) ?: continue
            return Directive.SetAddress(title, "set_address")
        }
        return null
    }

    /** 称呼白名单宽松：爸爸/老板/亲爱的…；拒动作词与垃圾。 */
    fun sanitizeAddressTitle(raw: String): String? {
        var t = raw.trim().trimEnd('吧', '呀', '啊', '嘛', '呢', '啦', '哈', '哦')
        t = t.trimStart('「', '『', '"', '\'', '“', '‘')
            .trimEnd('」', '』', '"', '\'', '”', '’')
        if (t.length !in 1..8) return null
        if (t in ADDRESS_BLOCKED) return null
        if (t.any { it in "，。！？、；：…,.!?;:@＠" }) return null
        // 纯动作 / 半截指令
        if (t in setOf("出来", "过来", "一下", "看看", "听听", "说话", "回复", "回答")) return null
        return t
    }

    private val ADDRESS_BLOCKED = setOf(
        "是的", "好的", "对的", "不是", "没有", "可以", "不行", "嗯嗯", "哈哈",
        "他", "她", "它", "你", "我", "咱", "谁", "啥", "什么",
    )

    private fun matchVerbNickProxy(body: String, bot: String): Directive.AtProxy? {
        val patterns = listOf(
            // 让红师傅出来 / 叫红师傅讲个笑话
            Regex(
                """(?:让|叫|喊|拉)(?:他|她|它|TA|ta)?\s*([^\s@＠,，。！？、；：]{1,20}?)\s*(?:出来|出来说|出来聊|来聊天|来聊聊|来搭话|下水|讲个笑话|讲笑话|说个笑话)""",
            ),
            // 帮我叫红师傅 / 去喊红师傅
            Regex(
                """(?:帮我|去|帮忙)\s*(?:叫|喊|拉|@|＠)\s*([^\s@＠,，。！？、；：]{1,20})""",
            ),
            // 直接@红师傅 / 只要@红师傅
            Regex(
                """(?:直接|只要|只)\s*[@＠]\s*([^\s@＠,，。！？、；：]{1,20}?)(?=$AT_STOP)""",
            ),
        )
        for (re in patterns) {
            val m = re.find(body) ?: continue
            val raw = m.groupValues[1].trim()
            if (!GroupBotMention.isPlausibleGroupNick(raw)) continue
            val name = raw.trimEnd('吧', '呀', '啊', '嘛', '呢', '啦')
            if (name in setOf("他", "她", "它", "TA", "ta")) continue
            if (name.equals(bot, ignoreCase = true)) continue
            if (!GroupBotMention.isPlausibleGroupNick(name)) continue
            if (name.length < 2) continue
            return Directive.AtProxy(name, "verb_nick_proxy")
        }
        return null
    }

    /**
     * @ 昵称结束边界：召唤词 / 指令词 / 空白 / 标点 / 句末。
     * 用字符串嵌入 Regex，避免「红师傅就行了」整段被当成昵称。
     */
    private const val AT_STOP =
        """出来|出来说|出来聊|来聊|来聊天|来聊聊|来搭话|下水|就行了|就好了|就行|就好|讲个|讲句|讲笑|说个|说得|说的|刚才|觉得|在吗|你好|让他|让她|让它|叫他|叫她|喊他|喊她|快|你|别|不要|别乱|直接|\s|$|[,，。！？、；：~～]"""
}
