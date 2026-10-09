package com.joy.android.overlay

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.joy.android.data.AppMode
import com.joy.android.data.ChatLlmClient
import com.joy.android.data.ChatSession
import com.joy.android.data.GroupBotAnsweredStore
import com.joy.android.data.GroupBotCommands
import com.joy.android.data.GroupBotEmoji
import com.joy.android.data.GroupBotMemoryStore
import com.joy.android.data.GroupBotMention
import com.joy.android.data.GroupBotRuntimeStore
import java.time.LocalDate
import com.joy.android.data.ImageCaptureMode
import com.joy.android.data.ImageOcrHelper
import com.joy.android.data.ImageVisionEncoder
import com.joy.android.data.JoySettings
import com.joy.android.data.JoySettingsStore
import com.joy.android.data.SessionRouter
import com.joy.android.data.SessionSnapshot
import com.joy.android.data.SessionStore
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class OverlayAssistAction {
    /** 只识图更新会话上下文（刷新） */
    REFRESH_CONTEXT,
    /** 识图 + 生成 + 按模式投递 */
    PARSE_AND_REPLY,
}

data class OverlayAssistResult(
    val ok: Boolean,
    val message: String,
    val reply: String? = null,
)

/**
 * 悬浮球能力核心：截屏 → OCR/视觉 → 生成 → 填入/发送/剪贴板。
 * 群 Bot 模式额外：@ 门闩、Bot 人设、分人记忆、冷却。
 */
class OverlayAssistEngine(
    private val app: Context,
    private val sessionStore: SessionStore = SessionStore(app),
    private val settingsStore: JoySettingsStore = JoySettingsStore(app),
    private val botMemoryStore: GroupBotMemoryStore = GroupBotMemoryStore(app),
    private val answeredStore: GroupBotAnsweredStore = GroupBotAnsweredStore(app),
    private val runtimeStore: GroupBotRuntimeStore = GroupBotRuntimeStore(app),
    private val ocr: ImageOcrHelper = ImageOcrHelper(),
    private val client: ChatLlmClient = ChatLlmClient(),
) {
    private var lastScreenFingerprint: Int? = null
    private var lastErrorAtMs: Long = 0L
    private var lastBotReplyAtMs: Long = 0L
    private var lastBotTriggerFp: String? = null
    /** 回复世代：发送前校验，过期结果直接丢弃防复读 */
    private var replyGeneration: Long = 0L
    private var activeTriggerFp: String? = null
    /** 近期已处理的 @ 指纹，防止并发/OCR 抖动连回两次 */
    private val recentBotTriggers = LinkedHashMap<String, Long>()
    private val botGate = Mutex()

    fun cancel() {
        client.cancelActive()
    }

    /**
     * @param onPhase 两字阶段回调，供主球展示：截屏 / 解图 / 思考 / 填入 / 发送
     */
    suspend fun run(
        mode: OverlayAssistMode,
        action: OverlayAssistAction,
        hideOverlay: suspend () -> Unit,
        showOverlay: suspend () -> Unit,
        onPhase: suspend (String) -> Unit = {},
    ): OverlayAssistResult = withContext(Dispatchers.Default) {
        if (System.currentTimeMillis() - lastErrorAtMs < ERROR_COOLDOWN_MS) {
            return@withContext OverlayAssistResult(false, "刚失败过，稍后再试（冷却中）")
        }
        try {
            onPhase("截屏")
            hideOverlay()
            delay(180)
            val bitmap = try {
                val allowAsk = mode == OverlayAssistMode.MANUAL
                OverlayScreenCapture.capture(app, allowAskPermission = allowAsk)
            } finally {
                showOverlay()
            }
            val fingerprint = fingerprint(bitmap)
            onPhase("解图")
            val settings = settingsStore.settings.first()
            // 群 Bot：画面未变也可能有未回 @（失败重试 / 多条排队），不能因画面指纹跳过
            if (action == OverlayAssistAction.PARSE_AND_REPLY &&
                (mode == OverlayAssistMode.AUTO || mode == OverlayAssistMode.PILOT) &&
                fingerprint == lastScreenFingerprint &&
                settings.appMode != AppMode.GROUP_BOT
            ) {
                bitmap.recycle()
                return@withContext OverlayAssistResult(true, "画面未变，跳过")
            }
            lastScreenFingerprint = fingerprint

            // 群 Bot：本地 OCR 筛「未回 @」——不调视觉 API
            if (settings.appMode == AppMode.GROUP_BOT &&
                action == OverlayAssistAction.PARSE_AND_REPLY
            ) {
                val ocrExtracted = try {
                    // 保留群昵称 →「对方·汤恒宇：」才能正确 @ 回去
                    ocr.recognizeBitmap(bitmap, retainGroupNicknames = true)
                } finally {
                    bitmap.recycle()
                }
                val ocrText = ocrExtracted.chatText.trim()
                Log.i(GROUP_TAG, "ocr done peer='${ocrExtracted.peerName}' lines=${ocrText.lines().size}")
                ocrText.lines().forEachIndexed { i, line ->
                    Log.i(GROUP_TAG, "ocrChat[$i] $line")
                }
                if (ocrText.isEmpty()) {
                    return@withContext OverlayAssistResult(false, "OCR 未识别到聊天内容")
                }
                return@withContext runGroupBotReply(
                    mode = mode,
                    settings = settings,
                    groupTitle = ocrExtracted.peerName?.trim().orEmpty().ifBlank { "群聊" },
                    chatText = ocrText,
                    onPhase = onPhase,
                    hideOverlay = hideOverlay,
                    showOverlay = showOverlay,
                )
            }

            val extracted = extractChat(settings, bitmap)
            bitmap.recycle()

            val incoming = extracted.chatText.trim()
            if (incoming.isEmpty()) {
                return@withContext OverlayAssistResult(false, "未识别到聊天内容")
            }

            val routed = SessionRouter.resolve(
                snap = sessionStore.read(),
                peerName = extracted.peerName,
                incomingChat = incoming,
            )
            var nextSession = routed.session
            sessionStore.write(routed.snapshot)

            if (action == OverlayAssistAction.REFRESH_CONTEXT) {
                val tip = when (routed.kind) {
                    SessionRouter.Kind.CREATE -> "已新建会话「${nextSession.title}」"
                    SessionRouter.Kind.SWITCH -> "已切回会话「${nextSession.title}」"
                    SessionRouter.Kind.RENAME -> "已保存为会话「${nextSession.title}」"
                    SessionRouter.Kind.SAME -> "已刷新 · 会话「${nextSession.title}」"
                }
                return@withContext OverlayAssistResult(true, tip)
            }

            onPhase("思考")
            val replies = client.generateReplies(settings, nextSession.chatText)
            val reply = replies.firstOrNull()?.trim().orEmpty()
            if (reply.isEmpty()) {
                nextSession = nextSession.copy(candidates = replies)
                persistSession(routed.snapshot, nextSession)
                return@withContext OverlayAssistResult(false, "模型未返回可用回复")
            }
            nextSession = nextSession.copy(candidates = replies)
            persistSession(routed.snapshot, nextSession)

            onPhase(if (mode == OverlayAssistMode.PILOT) "发送" else "填入")
            hideOverlay()
            delay(280)
            val deliver = try {
                deliverReply(mode, reply)
            } finally {
                showOverlay()
            }
            OverlayAssistResult(true, deliver, reply)
        } catch (e: Exception) {
            lastErrorAtMs = System.currentTimeMillis()
            OverlayAssistResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun runGroupBotReply(
        mode: OverlayAssistMode,
        settings: JoySettings,
        groupTitle: String,
        chatText: String,
        onPhase: suspend (String) -> Unit,
        hideOverlay: suspend () -> Unit,
        showOverlay: suspend () -> Unit,
    ): OverlayAssistResult {
        val nick = settings.groupBotNickname.trim()
        if (nick.isEmpty()) {
            return OverlayAssistResult(false, "请先设置 Bot 微信昵称")
        }

        // 屏上已接龙、或落在最后一条「我：」之前的历史 @ → 补记 done（防 OCR 变体复读）
        val lines = chatText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (h in GroupBotMention.listAtHits(chatText, nick)) {
            if (GroupBotMention.hasBotReplyAfter(lines, h.lineIndex) ||
                GroupBotMention.isBeforeLastBotReply(lines, h.lineIndex)
            ) {
                answeredStore.markDone(h.fingerprint, h.triggerLine)
            }
        }
        val blockedFps = answeredStore.snapshotBlockedFingerprints()
        // 未回队列：从早到晚一条条回；pending/done / 内存进行中的都跳过
        val hit = GroupBotMention.find(
            chatText = chatText,
            botNickname = nick,
            requireAt = settings.groupBotRequireAt,
            isAnswered = { fp ->
                recentBotTriggers.keys.any { matchesTriggerFp(it, fp) } ||
                    blockedFps.any { matchesTriggerFp(it, fp) }
            },
        )
        if (hit == null) {
            // demo：智驾/自动在 8:30–8:45 且今日未打过招呼 → 发一条每日问候
            if (mode == OverlayAssistMode.AUTO || mode == OverlayAssistMode.PILOT) {
                tryMorningGreeting(
                    mode = mode,
                    settings = settings,
                    groupTitle = groupTitle,
                    onPhase = onPhase,
                    hideOverlay = hideOverlay,
                    showOverlay = showOverlay,
                )?.let { return it }
            }
            Log.i(GROUP_TAG, "no unanswered @$nick (requireAt=${settings.groupBotRequireAt})")
            return if (mode == OverlayAssistMode.MANUAL) {
                OverlayAssistResult(
                    false,
                    if (settings.groupBotRequireAt) {
                        "没有新的 @$nick（旧的 @ 已回复过或屏上已有我方回复）"
                    } else {
                        "未识别到对方气泡"
                    },
                )
            } else {
                OverlayAssistResult(true, SKIP_SAME_TRIGGER)
            }
        }

        val triggerFp = hit.fingerprint
        val now = System.currentTimeMillis()
        val cooldownMs = settings.groupBotCooldownSec.coerceIn(0, 600) * 1000L

        // 认领：内存门闩 + 账本 pending（成功才 done，失败 unmark 可重试）
        val claimGen = botGate.withLock {
            pruneRecentTriggers(now)
            if (triggerFp == lastBotTriggerFp ||
                recentBotTriggers.keys.any { matchesTriggerFp(it, triggerFp) }
            ) {
                return@withLock -1L
            }
            if (cooldownMs > 0 && now - lastBotReplyAtMs < cooldownMs) {
                return@withLock -1L
            }
            lastBotTriggerFp = triggerFp
            lastBotReplyAtMs = now
            recentBotTriggers[triggerFp] = now
            replyGeneration += 1
            activeTriggerFp = triggerFp
            replyGeneration
        }
        if (claimGen < 0L) {
            return if (mode == OverlayAssistMode.MANUAL) {
                OverlayAssistResult(false, "仍是上次那条 @ 或冷却中，勿重复解析")
            } else {
                OverlayAssistResult(true, SKIP_SAME_TRIGGER)
            }
        }
        if (!answeredStore.claim(triggerFp, hit.triggerLine)) {
            // 已是 done：撤掉刚占的内存门闩，避免堵死后续 @
            releaseBotTrigger(triggerFp)
            return if (mode == OverlayAssistMode.MANUAL) {
                OverlayAssistResult(false, "该 @ 已回复过")
            } else {
                OverlayAssistResult(true, SKIP_SAME_TRIGGER)
            }
        }
        Log.i(GROUP_TAG, "claim fp='${triggerFp.take(40)}' gen=$claimGen queueOldestFirst")

        val ocrName = GroupBotMention.resolveMemberName(hit)
        // 无障碍认人优先：OCR 容易把邻座昵称贴到这条 @ 上
        val a11yNick = JoyAccessibilityService.instance?.findAtSenderNickname(nick)?.trim().orEmpty()
        val triggerSender = when {
            GroupBotMention.isPlausibleGroupNick(a11yNick) -> a11yNick
            else -> ocrName
        }
        // 指令框架：代劳 @ / 专属称呼 / 换脾气 / 原话代传等
        val directive = GroupBotCommands.parse(hit.triggerLine, nick)
        val atName = GroupBotCommands.resolveAtName(hit.triggerLine, nick, triggerSender)
        val memberKey = triggerSender.ifBlank { "群友" }

        // —— 原话代传：不经模型，直接 @目标 + 原文 ——
        if (directive is GroupBotCommands.Directive.RelaySay) {
            val reply = "@${directive.targetNick} ${directive.message}".trim()
            Log.i(GROUP_TAG, "relay -> $reply")
            return finishGroupBotReply(
                mode = mode,
                groupTitle = groupTitle,
                memberKey = memberKey,
                triggerSender = triggerSender,
                triggerLine = hit.triggerLine,
                triggerFp = triggerFp,
                claimGen = claimGen,
                reply = reply,
                onPhase = onPhase,
                hideOverlay = hideOverlay,
                showOverlay = showOverlay,
                label = "relay",
            )
        }

        // 称呼落库
        when (val d = directive) {
            is GroupBotCommands.Directive.SetAddress -> {
                if (GroupBotMention.isPlausibleGroupNick(triggerSender)) {
                    botMemoryStore.setAddressTitle(groupTitle, memberKey, triggerSender, d.title)
                }
            }
            is GroupBotCommands.Directive.ClearAddress -> {
                botMemoryStore.setAddressTitle(groupTitle, memberKey, triggerSender, "")
            }
            else -> Unit
        }

        // 换脾气（群级 1h；5 分钟冷却）——固定短回，不改写
        when (val d = directive) {
            is GroupBotCommands.Directive.SetTemper -> {
                when (val apply = runtimeStore.trySetTemper(groupTitle, d.temper)) {
                    is GroupBotRuntimeStore.TemperApply.Ok -> {
                        val body = "好嘞，姐先按「${d.temper.label}」跟你们唠一小时，想提前改再说一声～"
                        val reply = GroupBotMention.forceAtTrigger(body, triggerSender)
                        return finishGroupBotReply(
                            mode, groupTitle, memberKey, triggerSender, hit.triggerLine,
                            triggerFp, claimGen, reply, onPhase, hideOverlay, showOverlay, "temper",
                        )
                    }
                    is GroupBotRuntimeStore.TemperApply.Cooldown -> {
                        val body = "脾气刚换过，再等${apply.remainSec}分钟才能改哈～"
                        val reply = GroupBotMention.forceAtTrigger(body, triggerSender)
                        return finishGroupBotReply(
                            mode, groupTitle, memberKey, triggerSender, hit.triggerLine,
                            triggerFp, claimGen, reply, onPhase, hideOverlay, showOverlay, "temper_cd",
                        )
                    }
                }
            }
            is GroupBotCommands.Directive.ClearTemper -> {
                when (val apply = runtimeStore.clearTemper(groupTitle)) {
                    is GroupBotRuntimeStore.TemperApply.Ok -> {
                        val body = "行，脾气切回默认设置啦～"
                        val reply = GroupBotMention.forceAtTrigger(body, triggerSender)
                        return finishGroupBotReply(
                            mode, groupTitle, memberKey, triggerSender, hit.triggerLine,
                            triggerFp, claimGen, reply, onPhase, hideOverlay, showOverlay, "temper_clear",
                        )
                    }
                    is GroupBotRuntimeStore.TemperApply.Cooldown -> {
                        val body = "脾气刚动过，再等${apply.remainSec}分钟才能改哈～"
                        val reply = GroupBotMention.forceAtTrigger(body, triggerSender)
                        return finishGroupBotReply(
                            mode, groupTitle, memberKey, triggerSender, hit.triggerLine,
                            triggerFp, claimGen, reply, onPhase, hideOverlay, showOverlay, "temper_cd",
                        )
                    }
                }
            }
            else -> Unit
        }

        val memory = botMemoryStore.get(groupTitle, memberKey)
        val history = memory?.recentTurns.orEmpty()
        val addressForReply = when (directive) {
            is GroupBotCommands.Directive.AtProxy,
            is GroupBotCommands.Directive.RelaySay,
            is GroupBotCommands.Directive.SetTemper,
            is GroupBotCommands.Directive.ClearTemper,
            is GroupBotCommands.Directive.ClearAddress,
            -> ""
            is GroupBotCommands.Directive.SetAddress -> directive.title
            null -> memory?.addressTitle.orEmpty()
        }
        val effectiveTemper = runtimeStore.effectiveTemper(groupTitle, settings.groupBotTemper)
        val promptSettings = settings.copy(groupBotTemper = effectiveTemper)
        Log.i(
            GROUP_TAG,
            "sender ocr='$ocrName' a11y='$a11yNick' triggerSender='$triggerSender' " +
                "replyAt='$atName' address='$addressForReply' temper=${effectiveTemper.label} " +
                "directive=${directive?.javaClass?.simpleName ?: "none"} " +
                "trigger='${hit.triggerLine}'",
        )
        val userPrompt = buildString {
            append("下面是微信群聊摘录（「我」= Bot「").append(nick).append("」）。\n")
            if (history.isNotBlank()) {
                append("与「").append(triggerSender).append("」的近期记忆：\n")
                append(history.trim())
                append("\n\n")
            }
            append("当前屏幕上下文：\n")
            append(hit.contextText.trim())
            append("\n\n")
            val cmdHint = GroupBotCommands.promptHint(directive)
            if (cmdHint.isNotEmpty()) {
                append("刚刚是「").append(triggerSender).append("」@ 了你。")
                append(cmdHint)
                append("\n原句：\n")
            } else {
                val addrHint = GroupBotCommands.addressChatHint(addressForReply, triggerSender)
                if (addrHint.isNotEmpty()) {
                    append("刚刚是「").append(triggerSender).append("」@ 了你。")
                    append(addrHint)
                    append("\n原句：\n")
                } else if (GroupBotMention.isPlausibleGroupNick(atName)) {
                    append("刚刚是「").append(atName).append("」@ 了你。")
                    append("只针对他这句写一条短正文，不要写任何@（系统会自动加 @")
                        .append(atName).append("）：\n")
                } else {
                    append("有人 @ 了你。只写一条短正文，不要写任何@（昵称识别不稳时宁可不@）：\n")
                }
            }
            append(hit.triggerLine)
        }

        onPhase("思考")
        val replies = try {
            client.generateReplies(
                settings = promptSettings,
                chatText = hit.contextText,
                systemPromptOverride = GroupBotMention.buildSystemPrompt(promptSettings),
                userPromptOverride = userPrompt,
            )
        } catch (e: Exception) {
            releaseBotTrigger(triggerFp)
            answeredStore.unmark(triggerFp)
            throw e
        }
        val rawReply = replies.firstOrNull()?.trim().orEmpty()
        if (rawReply.isEmpty()) {
            releaseBotTrigger(triggerFp)
            answeredStore.unmark(triggerFp)
            return OverlayAssistResult(false, "模型未返回可用回复")
        }
        // 微信 [微笑] 等：白名单 + 限量 + 禁止纯表情
        val polished = GroupBotEmoji.polishBody(rawReply)
        if (polished.isEmpty()) {
            releaseBotTrigger(triggerFp)
            answeredStore.unmark(triggerFp)
            return OverlayAssistResult(false, "模型未返回可用回复")
        }
        val reply = GroupBotCommands.forceAtWithAddress(polished, atName, addressForReply)
        return finishGroupBotReply(
            mode, groupTitle, memberKey, triggerSender, hit.triggerLine,
            triggerFp, claimGen, reply, onPhase, hideOverlay, showOverlay, "chat",
        )
    }

    private suspend fun finishGroupBotReply(
        mode: OverlayAssistMode,
        groupTitle: String,
        memberKey: String,
        triggerSender: String,
        triggerLine: String,
        triggerFp: String,
        claimGen: Long,
        reply: String,
        onPhase: suspend (String) -> Unit,
        hideOverlay: suspend () -> Unit,
        showOverlay: suspend () -> Unit,
        label: String,
    ): OverlayAssistResult {
        // 过期结果：未发出前丢弃，撤 pending 以便稍后重试（有 @ 必须回）
        val stale = botGate.withLock {
            claimGen != replyGeneration || activeTriggerFp != triggerFp
        }
        if (stale) {
            Log.i(GROUP_TAG, "stale drop label=$label gen=$claimGen fp='${triggerFp.take(32)}'")
            answeredStore.unmark(triggerFp)
            releaseBotTrigger(triggerFp)
            return OverlayAssistResult(true, "过期回复已丢弃（防复读）", null)
        }
        onPhase(if (mode == OverlayAssistMode.PILOT) "发送" else "填入")
        hideOverlay()
        delay(280)
        val deliver = try {
            deliverReply(mode, reply)
        } finally {
            showOverlay()
        }
        // 一旦填入/发送成功就必须记 done，即使世代已推进——否则会复读
        answeredStore.markDone(triggerFp, triggerLine)
        botMemoryStore.appendTurn(
            groupKey = groupTitle,
            memberKey = memberKey,
            memberName = triggerSender,
            userLine = triggerLine,
            botLine = reply,
        )
        Log.i(GROUP_TAG, "done label=$label deliver=$deliver reply='${reply.take(40)}'")
        return OverlayAssistResult(true, "Bot·$memberKey：$deliver", reply)
    }

    /**
     * 每日问候 demo：本地 8:30–8:45，该群今天还没发过，且处于自动/智驾。
     * 无 @ 也会发；一天一次。
     */
    private suspend fun tryMorningGreeting(
        mode: OverlayAssistMode,
        settings: JoySettings,
        groupTitle: String,
        onPhase: suspend (String) -> Unit,
        hideOverlay: suspend () -> Unit,
        showOverlay: suspend () -> Unit,
    ): OverlayAssistResult? {
        if (!runtimeStore.shouldSendMorningGreeting(groupTitle)) return null
        val day = LocalDate.now().toString()
        val fp = "morning:$day:${groupTitle.trim().ifEmpty { "群聊" }}"
        val now = System.currentTimeMillis()
        val claimed = botGate.withLock {
            pruneRecentTriggers(now)
            if (recentBotTriggers.keys.any { matchesTriggerFp(it, fp) }) return@withLock false
            lastBotTriggerFp = fp
            lastBotReplyAtMs = now
            recentBotTriggers[fp] = now
            true
        }
        if (!claimed) return null
        // 先占「今日已问候」，避免并发双发；发送失败也不重复打扰
        runtimeStore.markMorningGreetingSent(groupTitle)
        val reply = GroupBotCommands.morningGreetingLine()
        Log.i(GROUP_TAG, "morning greeting group='$groupTitle' reply='$reply'")
        onPhase(if (mode == OverlayAssistMode.PILOT) "发送" else "填入")
        hideOverlay()
        delay(280)
        val deliver = try {
            deliverReply(mode, reply)
        } finally {
            showOverlay()
        }
        return OverlayAssistResult(true, "Bot·晨间问候：$deliver", reply)
    }

    private fun pruneRecentTriggers(now: Long) {
        val expireBefore = now - RECENT_TRIGGER_TTL_MS
        val it = recentBotTriggers.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value < expireBefore) it.remove()
        }
        while (recentBotTriggers.size > RECENT_TRIGGER_MAX) {
            val oldest = recentBotTriggers.keys.firstOrNull() ?: break
            recentBotTriggers.remove(oldest)
        }
    }

    private fun releaseBotTrigger(fp: String) {
        // 失败时允许重试（持久化记录在 mark 之后；生成失败时尽量撤出口袋缓存）
        if (lastBotTriggerFp == fp) lastBotTriggerFp = null
        recentBotTriggers.remove(fp)
    }

    private fun matchesTriggerFp(a: String, b: String): Boolean =
        GroupBotMention.fingerprintsNearEqual(a, b)

    private fun extractChat(settings: JoySettings, bitmap: Bitmap) =
        when (settings.imageCaptureMode) {
            ImageCaptureMode.OCR -> ocr.recognizeBitmap(bitmap)
            ImageCaptureMode.VISION -> {
                val dataUrl = ImageVisionEncoder.toDataUrl(bitmap, recycleOriginal = false)
                client.extractChatFromImage(settings, dataUrl)
            }
        }

    private suspend fun persistSession(snap: SessionSnapshot, updated: ChatSession) {
        val list = snap.sessions.map { if (it.id == updated.id) updated else it }
        sessionStore.write(SessionSnapshot(activeId = updated.id, sessions = list))
    }

    private suspend fun deliverReply(mode: OverlayAssistMode, reply: String): String {
        val a11y = JoyAccessibilityService.instance
        if (a11y == null) {
            runCatching { ClipboardWriteActivity.writeForeground(app, reply) }
            return "已生成并复制（请在设置里开启 Joy 无障碍以自动填入）"
        }
        val (filled, diag) = a11y.fillInput(reply)
        when (mode) {
            OverlayAssistMode.MANUAL, OverlayAssistMode.AUTO -> {
                if (filled) return "已生成并填入输入框"
                runCatching { ClipboardWriteActivity.writeForeground(app, reply) }
                return "已生成·填入失败($diag)·可长按输入框粘贴"
            }
            OverlayAssistMode.PILOT -> {
                if (!filled) {
                    runCatching { ClipboardWriteActivity.writeForeground(app, reply) }
                    return "已生成·智驾填入失败($diag)·请确认在聊天页且已开无障碍"
                }
                // 等微信刷出「发送」；clickSend 只点一次，发出后不再点底栏
                delay(450)
                val sent = a11y.clickSend()
                return if (sent) {
                    "智驾：已填入并发送"
                } else {
                    "已填入，发送未点到（请保持在聊天页，发送钮在右下）"
                }
            }
        }
    }

    private fun fingerprint(bitmap: Bitmap): Int {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val x0 = w / 8
        val x1 = w - w / 8
        val y0 = h / 5
        val y1 = h - h / 10
        var hash = 17
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                hash = 31 * hash + (bitmap.getPixel(x, y) and 0x00F0F0F0)
                x += maxOf(8, (x1 - x0) / 24)
            }
            y += maxOf(8, (y1 - y0) / 20)
        }
        hash = 31 * hash + min(w, 4096)
        hash = 31 * hash + min(h, 4096)
        return hash
    }

    companion object {
        private const val GROUP_TAG = "JoyGroupBot"
        private const val ERROR_COOLDOWN_MS = 8_000L
        private const val RECENT_TRIGGER_TTL_MS = 10 * 60_000L
        private const val RECENT_TRIGGER_MAX = 32
        const val SKIP_NO_MENTION = "未@，跳过"
        const val SKIP_COOLDOWN = "冷却中，跳过"
        const val SKIP_SAME_TRIGGER = "已处理过该@，跳过"

        fun shouldQuietSkip(message: String, mode: OverlayAssistMode): Boolean {
            // 手动解析始终 toast，方便排查
            if (mode == OverlayAssistMode.MANUAL) return false
            return message == "画面未变，跳过" ||
                message == SKIP_NO_MENTION ||
                message == SKIP_COOLDOWN ||
                message == SKIP_SAME_TRIGGER
        }
    }
}
