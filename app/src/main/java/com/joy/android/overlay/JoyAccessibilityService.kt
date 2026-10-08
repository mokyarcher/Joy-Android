package com.joy.android.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 截屏（API 30+）+ 填入微信输入框 + 智驾点「发送」。
 */
class JoyAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef.set(this)
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED or
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 50
        }
        // isAccessibilityTool 只能在 xml 里声明（Android 13+ 完整读微信树需要）
        Log.i(TAG, "a11y connected sdk=${Build.VERSION.SDK_INT}")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instanceRef.get() === this) instanceRef.set(null)
        super.onDestroy()
    }

    suspend fun captureScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        return withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine { cont ->
                try {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        Executors.newSingleThreadExecutor(),
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                var soft: Bitmap? = null
                                try {
                                    val buffer = screenshot.hardwareBuffer
                                    val hw = Bitmap.wrapHardwareBuffer(
                                        buffer,
                                        screenshot.colorSpace,
                                    )
                                    soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                                    hw?.recycle()
                                    buffer.close()
                                } catch (_: Exception) {
                                    soft = null
                                }
                                if (cont.isActive) cont.resume(soft)
                            }

                            override fun onFailure(errorCode: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                    )
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    /**
     * @return Pair(成功?, 诊断信息)
     */
    suspend fun fillInput(text: String): Pair<Boolean, String> = withContext(Dispatchers.Main) {
        Log.i(TAG, "fillInput start len=${text.length} preview=${text.take(40)}")
        lastFillText = text
        dumpWindows()
        dumpBottomStrip()

        val wechatRootCount = wechatRoots().size
        val treeSealed = lastWechatTreeNodes <= 1
        Log.i(TAG, "treeSealed=$treeSealed nodes=$lastWechatTreeNodes")

        // 剪贴板必须前台写入（ColorOS）；写完后回到微信当前聊天页，勿按返回
        runCatching { ClipboardWriteActivity.writeForeground(this@JoyAccessibilityService, text) }
        delay(450)

        if (!treeSealed) {
            var usable = 0
            repeat(2) { attempt ->
                val edit = findChatInput()
                usable = lastUsableCount
                Log.i(TAG, "attempt=$attempt node=${describe(edit)}")
                if (edit != null && isUsableInputNode(edit) && writeAndVerify(edit, text)) {
                    Log.i(TAG, "fill OK via node")
                    return@withContext true to "ok-node"
                }
                delay(200L * (attempt + 1))
            }
            if (usable > 0) {
                // 有节点但写入失败，继续手势粘贴
            }
        }

        // 就在当前聊天页：底部输入框长按 → 点「粘贴」（禁止 Global Back，否则会退回会话列表）
        Log.i(TAG, "fallback: input-bar long-press paste")
        if (pasteViaInputBarGesture()) {
            Log.i(TAG, "fill OK via input-bar paste gesture")
            return@withContext true to "ok-gesture-paste"
        }

        val diag = if (treeSealed) {
            "微信屏蔽节点(tree=0)，已复制，请点输入框长按粘贴"
        } else {
            "wechatWin=$wechatRootCount usable=$lastUsableCount bottomEdit=$lastBottomEditCount"
        }
        Log.w(TAG, "fill FAILED $diag")
        false to diag
    }

    @Volatile
    private var lastUsableCount: Int = 0

    @Volatile
    private var lastBottomEditCount: Int = 0

    @Volatile
    private var lastWechatTreeNodes: Int = 0

    suspend fun clickSend(): Boolean = withContext(Dispatchers.Main) {
        delay(200)
        val send = findSendButton() ?: return@withContext false
        send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** 写入后读取节点文本，确认真的进去了。 */
    private suspend fun writeAndVerify(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!isUsableInputNode(node)) {
            Log.w(TAG, "skip unusable node ${describe(node)}")
            return false
        }
        val before = node.text?.toString().orEmpty()
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(100)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val setOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(TAG, "SET_TEXT returned=$setOk before='$before'")
        delay(150)
        node.refresh()
        if (textLooksFilled(node, text)) {
            Log.i(TAG, "verified after SET_TEXT text='${node.text}'")
            return true
        }

        runCatching { ClipboardWriteActivity.writeForeground(this, text) }
        delay(200)
        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "PASTE returned=$pasteOk")
        delay(150)
        node.refresh()
        if (textLooksFilled(node, text)) {
            Log.i(TAG, "verified after PASTE text='${node.text}'")
            return true
        }

        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(150)
        node.refresh()
        val ok = textLooksFilled(node, text)
        Log.i(TAG, "final verify ok=$ok text='${node.text}'")
        return ok
    }

    private fun textLooksFilled(node: AccessibilityNodeInfo, expected: String): Boolean {
        val now = node.text?.toString().orEmpty()
        if (now.isEmpty()) return false
        val exp = expected.trim()
        if (exp.isEmpty()) return false
        return now.contains(exp) ||
            exp.contains(now) ||
            now.take(12) == exp.take(12)
    }

    /** 过滤 ColorOS/微信返回的假节点（cls=null、bounds 全 0）。 */
    private fun isUsableInputNode(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() < 40 || r.height() < 20) return false
        if (node.className == null) return false
        return true
    }

    private fun describe(node: AccessibilityNodeInfo?): String {
        if (node == null) return "null"
        val r = Rect()
        node.getBoundsInScreen(r)
        return "pkg=${node.packageName} cls=${node.className} id=${node.viewIdResourceName} " +
            "edit=${node.isEditable} focus=${node.isFocused} text='${node.text}' bounds=$r"
    }

    private fun dumpWindows() {
        val wins = runCatching { windows }.getOrNull().orEmpty()
        Log.i(TAG, "windows=${wins.size} activePkg=${rootInActiveWindow?.packageName}")
        wins.forEachIndexed { i, w ->
            val root = runCatching { w.root }.getOrNull()
            Log.i(
                TAG,
                "win[$i] type=${w.type} pkg=${root?.packageName} title=${w.title} " +
                    "active=${w.isActive} focused=${w.isFocused}",
            )
        }
    }

    /** 打印微信窗口底部区域节点，方便对照真实输入框。 */
    private fun dumpBottomStrip() {
        val roots = wechatRoots()
        var total = 0
        for (root in roots) {
            val cc = root.childCount
            Log.i(TAG, "wechatRoot pkg=${root.packageName} childCount=$cc cls=${root.className}")
            total += countNodes(root)
        }
        lastWechatTreeNodes = total
        Log.i(TAG, "wechatTreeTotalNodes=$total")

        val (_, screenH) = screenSize()
        val yMin = (screenH * 0.70f).toInt()
        val list = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots) {
            walkAll(root) { n ->
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    // 先收集所有有效节点，底部优先打印
                    if (r.bottom >= yMin) {
                        list += AccessibilityNodeInfo.obtain(n)
                    }
                }
            }
        }
        // 若底部为空，打印全树前 25 个有效节点（诊断微信是否屏蔽）
        if (list.isEmpty()) {
            Log.w(TAG, "bottomStrip empty — dump any valid nodes")
            for (root in roots) {
                walkAll(root) { n ->
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0 && list.size < 25) {
                        list += AccessibilityNodeInfo.obtain(n)
                    }
                }
            }
        }
        Log.i(TAG, "bottomStrip count=${list.size}")
        var editCount = 0
        list.sortedByDescending { n ->
            val r = Rect(); n.getBoundsInScreen(r); r.bottom
        }.take(25).forEachIndexed { i, n ->
            val cls = n.className?.toString().orEmpty()
            if (cls.contains("EditText", true) || n.isEditable) editCount++
            Log.i(TAG, "bottom[$i] ${describe(n)}")
        }
        lastBottomEditCount = editCount
        Log.i(TAG, "bottomEditCount=$editCount")
    }

    private fun countNodes(node: AccessibilityNodeInfo): Int {
        var n = 1
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            n += countNodes(c)
            c.recycle()
        }
        return n
    }

    private fun walkAll(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        visit(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walkAll(child, visit)
            child.recycle()
        }
    }

    private fun findChatInput(): AccessibilityNodeInfo? {
        val roots = wechatRoots().ifEmpty { collectRoots() }
        val edits = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots) {
            for (id in WECHAT_INPUT_IDS) {
                runCatching {
                    root.findAccessibilityNodeInfosByViewId(id)?.forEach { edits += it }
                }
            }
            collectInputCandidates(root, edits)
            collectBottomFocusables(root, edits)
        }
        val usable = edits.filter { isUsableInputNode(it) }
        lastUsableCount = usable.size
        Log.i(TAG, "findChatInput raw=${edits.size} usable=${usable.size}")
        if (usable.isEmpty()) return null

        val wechat = usable.filter { it.packageName?.toString() == WECHAT_PKG }
        val pool = wechat.ifEmpty { usable }
        // 优先：可编辑 / EditText / 已聚焦
        pool.firstOrNull {
            it.isFocused && (it.isEditable || it.className?.contains("EditText") == true)
        }?.let { return it }
        pool.firstOrNull { it.isEditable }?.let { return it }
        pool.firstOrNull {
            it.className?.toString()?.contains("EditText", true) == true
        }?.let { return it }
        return pool.maxByOrNull { scoreInput(it) }
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        for (root in wechatRoots().ifEmpty { collectRoots() }) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (focused != null && isUsableInputNode(focused)) return focused
        }
        return null
    }

    private fun scoreInput(node: AccessibilityNodeInfo): Int {
        val r = Rect()
        node.getBoundsInScreen(r)
        val (_, screenH) = screenSize()
        var score = r.bottom
        if (node.packageName?.toString() == WECHAT_PKG) score += 20_000
        if (node.isFocused) score += 8_000
        if (node.isEditable) score += 3_000
        if (r.top < screenH * 0.55f) score -= 8_000 // 偏上：搜索/顶栏
        if (r.bottom > screenH * 0.82f) score += 5_000 // 贴底输入栏
        val w = r.width()
        if (w > screenSize().first * 0.35f) score += 2_000 // 输入框通常较宽
        return score
    }

    private fun wechatRoots(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val wins: List<AccessibilityWindowInfo> = try {
            windows ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        for (w in wins) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            if (root.packageName?.toString() == WECHAT_PKG) {
                out += root
            }
        }
        // active window 若是微信也加上
        rootInActiveWindow?.let { root ->
            if (root.packageName?.toString() == WECHAT_PKG && out.none { it == root }) {
                out += root
            }
        }
        return out
    }

    private fun collectRoots(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { out += it }
        val wins: List<AccessibilityWindowInfo> = try {
            windows ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        for (w in wins) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            // 跳过 Joy 自己的悬浮窗
            if (root.packageName?.toString() == packageName) continue
            if (out.none { it == root }) out += root
        }
        return out
    }

    private fun collectInputCandidates(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        if (isInputCandidate(node)) {
            out += AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectInputCandidates(child, out)
            child.recycle()
        }
    }

    private fun collectBottomFocusables(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        val (_, screenH) = screenSize()
        val threshold = (screenH * 0.78f).toInt()
        fun walk(n: AccessibilityNodeInfo) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.bottom >= threshold && r.height() in 24..280 && (n.isFocusable || n.isClickable || n.isEditable)) {
                val cls = n.className?.toString().orEmpty()
                // 排除明显按钮
                if (!cls.contains("Button", true) && !cls.contains("ImageButton", true)) {
                    out += AccessibilityNodeInfo.obtain(n)
                }
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c)
                c.recycle()
            }
        }
        walk(node)
    }

    private fun isInputCandidate(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled) return false
        // 不强制 isVisibleToUser：悬浮窗盖住时有的机型会判不可见
        val cls = node.className?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= 26) {
            node.hintText?.toString().orEmpty()
        } else {
            ""
        }
        val text = node.text?.toString().orEmpty()

        if (node.isEditable) return true
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return true
        if (cls.contains("EditText", true)) return true
        if (cls.contains("mmEditText", true)) return true
        if (cls.contains("FlexEditText", true)) return true
        if (cls.contains("cedit", true)) return true
        if (id.contains("input", true) || id.contains("edit", true)) return true
        if (desc.contains("输入") || hint.contains("输入")) return true
        // 空文本 + 可聚焦 + 底部区域，也可能是输入框
        if (node.isFocusable && text.isEmpty() && hint.isEmpty()) {
            val r = Rect()
            node.getBoundsInScreen(r)
            val (_, h) = screenSize()
            if (r.bottom > h * 0.85f && r.width() > screenSize().first * 0.3f) return true
        }
        return false
    }

    /**
     * 按你提供的两张截图（458×1024）像素标定：
     * - 长按：输入白框左侧空白。图2三角指向 ≈0.14W×0.945H（贴顶），
     *   实际长按取白框内更稳的中心 ≈0.15W×0.955H。
     * - 粘贴：黑条最左「粘贴」白字中心 ≈0.08W×0.923H。
     */
    private fun inputBarLongPressPoint(): Pair<Float, Float> {
        val (w, h) = screenSize()
        return (w * 0.15f) to (h * 0.955f)
    }

    private fun pasteButtonPoint(): Pair<Float, Float> {
        val (w, h) = screenSize()
        return (w * 0.08f) to (h * 0.923f)
    }

    /**
     * 就在当前聊天页操作（绝不 Global Back，否则会退到会话列表）：
     * 1) 长按底部输入框左侧空白 → 弹出「粘贴」黑条
     * 2) 点击「粘贴」
     *
     * 剪贴板已由 [fillInput] 写好，这里不再弹 ClipboardWriteActivity，避免打断当前聊天页。
     */
    private suspend fun pasteViaInputBarGesture(): Boolean {
        val (lx, ly) = inputBarLongPressPoint()
        val (px, py) = pasteButtonPoint()

        Log.i(TAG, "longPress input ($lx,$ly) — stay on current chat")
        if (!dispatchLongPress(lx, ly)) return false
        delay(550)

        val pasteNode = findPasteMenuNode()
        if (pasteNode != null) {
            Log.i(TAG, "click paste node ${describe(pasteNode)}")
            if (pasteNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }

        Log.i(TAG, "tap paste button ($px,$py)")
        val tapped = dispatchTap(px, py)
        delay(180)
        if (tapped) {
            // 略右再点一次，防首次偏左没点中
            dispatchTap(px + screenSize().first * 0.025f, py)
        }
        return tapped
    }

    private fun findPasteMenuNode(): AccessibilityNodeInfo? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        roots += wechatRoots()
        roots += collectRoots()
        runCatching { windows }.getOrNull().orEmpty().forEach { w ->
            runCatching { w.root }.getOrNull()?.let { roots += it }
        }
        for (root in roots) {
            findNodeByText(root, "粘贴")?.let { return it }
            findNodeByText(root, "Paste")?.let { return it }
        }
        return null
    }

    private var lastFillText: String = ""

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val list = root.findAccessibilityNodeInfosByText(text) ?: return null
        return list.firstOrNull { node ->
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            (t == text || d == text || t.contains(text)) && node.isClickable
        } ?: list.firstOrNull { it.isClickable }
    }

    private suspend fun dispatchTap(x: Float, y: Float): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 60)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!ok && cont.isActive) cont.resume(false)
        }

    private suspend fun dispatchLongPress(x: Float, y: Float): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 700)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!ok && cont.isActive) cont.resume(false)
        }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun findSendButton(): AccessibilityNodeInfo? {
        val roots = wechatRoots().ifEmpty { collectRoots() }
        val hits = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots) {
            collectSendLike(root, hits)
        }
        if (hits.isEmpty()) return null
        return hits.maxByOrNull { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            var s = r.bottom
            if (node.packageName?.toString() == WECHAT_PKG) s += 10_000
            s
        }
    }

    private fun collectSendLike(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        val label = buildString {
            append(node.text ?: "")
            append(node.contentDescription ?: "")
            append(node.viewIdResourceName ?: "")
        }
        if (node.isClickable && (
                label.contains("发送") ||
                    label.contains("Send", ignoreCase = true) ||
                    (node.viewIdResourceName?.contains("send", ignoreCase = true) == true)
                )
        ) {
            out += AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectSendLike(child, out)
            child.recycle()
        }
    }

    companion object {
        private const val TAG = "JoyFill"
        private const val WECHAT_PKG = "com.tencent.mm"

        /** 常见微信输入框 id（随版本变化，命中即用） */
        private val WECHAT_INPUT_IDS = listOf(
            "com.tencent.mm:id/input",
            "com.tencent.mm:id/bkk",
            "com.tencent.mm:id/chatting_content_et",
            "com.tencent.mm:id/oq",
            "com.tencent.mm:id/ok",
            "com.tencent.mm:id/aqe",
            "com.tencent.mm:id/aja",
        )

        private val instanceRef = AtomicReference<JoyAccessibilityService?>(null)

        val instance: JoyAccessibilityService?
            get() = instanceRef.get()

        fun isEnabled(context: Context): Boolean {
            val pkg = context.packageName
            val full = "$pkg/${JoyAccessibilityService::class.java.canonicalName}"
            val shortName = "$pkg/.overlay.JoyAccessibilityService"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any {
                it.equals(full, ignoreCase = true) ||
                    it.equals(shortName, ignoreCase = true) ||
                    it.contains("JoyAccessibilityService", ignoreCase = true)
            }
        }

        /** 设置里已勾选，但进程被杀导致服务实例未连上 */
        fun isGrantedButDisconnected(context: Context): Boolean =
            isEnabled(context) && instance == null

        fun settingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun copyToClipboard(context: Context, text: String) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("joy_reply", text))
        }

        fun postMain(block: () -> Unit) {
            Handler(Looper.getMainLooper()).post(block)
        }
    }
}
