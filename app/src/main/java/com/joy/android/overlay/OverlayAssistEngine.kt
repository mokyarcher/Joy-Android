package com.joy.android.overlay

import android.content.Context
import android.graphics.Bitmap
import com.joy.android.data.ChatLlmClient
import com.joy.android.data.ChatSession
import com.joy.android.data.SessionRouter
import com.joy.android.data.ImageCaptureMode
import com.joy.android.data.ImageOcrHelper
import com.joy.android.data.ImageVisionEncoder
import com.joy.android.data.JoySettings
import com.joy.android.data.JoySettingsStore
import com.joy.android.data.SessionSnapshot
import com.joy.android.data.SessionStore
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
 */
class OverlayAssistEngine(
    private val app: Context,
    private val sessionStore: SessionStore = SessionStore(app),
    private val settingsStore: JoySettingsStore = JoySettingsStore(app),
    private val ocr: ImageOcrHelper = ImageOcrHelper(),
    private val client: ChatLlmClient = ChatLlmClient(),
) {
    private var lastScreenFingerprint: Int? = null
    private var lastErrorAtMs: Long = 0L

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
            if (action == OverlayAssistAction.PARSE_AND_REPLY &&
                (mode == OverlayAssistMode.AUTO || mode == OverlayAssistMode.PILOT) &&
                fingerprint == lastScreenFingerprint
            ) {
                bitmap.recycle()
                return@withContext OverlayAssistResult(true, "画面未变，跳过")
            }
            lastScreenFingerprint = fingerprint

            onPhase("解图")
            val settings = settingsStore.settings.first()
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
            // 填入前再次隐藏悬浮球，避免挡住/抢焦点
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

    /**
     * 自动/智驾轮询直接走 [run]；画面未变时会返回跳过。
     */
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
                    return "已生成·智驾填入失败($diag)"
                }
                delay(280)
                val sent = a11y.clickSend()
                return if (sent) "智驾：已填入并发送" else "已填入，未找到发送按钮"
            }
        }
    }

    private fun fingerprint(bitmap: Bitmap): Int {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        // 采样中下部聊天区，忽略状态栏/悬浮球边角
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
        private const val ERROR_COOLDOWN_MS = 8_000L
    }
}
