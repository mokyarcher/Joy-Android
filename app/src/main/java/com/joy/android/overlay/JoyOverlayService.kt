package com.joy.android.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.joy.android.MainActivity
import com.joy.android.R
import com.joy.android.data.JoySettingsStore
import com.joy.android.data.SessionStore
import com.joy.android.ui.theme.JoyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 悬浮球：展开菜单 + 截屏解析 / 填入 / 三种模式。
 */
class JoyOverlayService : LifecycleService(), SavedStateRegistryOwner {
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var sessionStore: SessionStore

    private var clusterView: android.view.View? = null
    private var clusterParams: WindowManager.LayoutParams? = null
    private var dockAnimator: ValueAnimator? = null
    private lateinit var assistEngine: OverlayAssistEngine
    private var assistJob: Job? = null
    private var autoWatchJob: Job? = null
    /** 本次拖动是否从半隐贴边开始，松手时若未贴回则自动展开 */
    private var pulledFromDock = false

    private var ui by mutableStateOf(OverlayUiState())

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sessionStore = SessionStore(applicationContext)
        assistEngine = OverlayAssistEngine(applicationContext, sessionStore)
        OverlayController.isRunning = true
        ensureChannel()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        showCluster()
        refreshSessionTitle()
        observeOverlayTheme()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> refreshSessionTitle()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        autoWatchJob?.cancel()
        assistJob?.cancel()
        assistEngine.cancel()
        dockAnimator?.cancel()
        removeCluster()
        OverlayController.isRunning = false
        super.onDestroy()
    }

    private fun showCluster() {
        if (clusterView != null) return
        val params = baseOverlayParams(
            width = dp(OverlayClusterMetrics.windowW(expanded = false)),
            height = dp(OverlayClusterMetrics.windowH(expanded = false)),
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (
                resources.displayMetrics.widthPixels -
                    dp(OverlayClusterMetrics.windowW(expanded = false)) -
                    dp(OverlayClusterMetrics.EdgePadDp)
                ).coerceAtLeast(0)
            y = (resources.displayMetrics.heightPixels * 0.38f).toInt()
        }
        clusterParams = params
        val view = ComposeView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            setViewTreeLifecycleOwner(this@JoyOverlayService)
            setViewTreeSavedStateRegistryOwner(this@JoyOverlayService)
            setContent {
                JoyTheme {
                    OverlayCluster(
                        state = ui,
                        onToggle = { toggleExpanded() },
                        onRefresh = { runAssist(OverlayAssistAction.REFRESH_CONTEXT) },
                        onCycleMode = { cycleMode() },
                        onParse = { runAssist(OverlayAssistAction.PARSE_AND_REPLY) },
                        onHome = { openApp() },
                        onDragBy = { dx, dy -> dragBy(dx, dy) },
                        onDragEnd = { onClusterDragEnd() },
                    )
                }
            }
        }
        clusterView = view
        windowManager.addView(view, params)
        clampPosition(update = true)
    }

    private fun dragBy(dx: Float, dy: Float) {
        val params = clusterParams ?: return
        dockAnimator?.cancel()
        if (ui.edgeDock != OverlayEdgeDock.None) {
            pulledFromDock = true
            ui = ui.copy(edgeDock = OverlayEdgeDock.None)
        }
        params.x += dx.toInt()
        params.y += dy.toInt()
        clampPosition(update = true)
    }

    private fun onClusterDragEnd() {
        if (ui.expanded) {
            pulledFromDock = false
            val side = edgeSnapSide()
            if (side != OverlayEdgeDock.None) {
                // 展开态拖到可贴边位置 → 收起并半隐
                collapseAndDock(side)
            } else {
                clampAsExpandedSafeX(animate = true)
            }
            return
        }
        if (pulledFromDock) {
            pulledFromDock = false
            // 从半隐拉出后松手：若已离开贴边吸附区 → 直接展开
            if (edgeSnapSide() == OverlayEdgeDock.None) {
                expandNow()
                return
            }
        }
        maybeDockToNearestEdge(animate = true)
    }

    private fun toggleExpanded() {
        // 半隐贴边：一点/拉出即展开，省掉「先滑出再点开」
        if (!ui.expanded && ui.edgeDock != OverlayEdgeDock.None) {
            expandFromHalfHidden()
            return
        }
        val next = !ui.expanded
        if (next && ui.edgeDock != OverlayEdgeDock.None) {
            undockFromEdge(animate = false)
        }
        ui = ui.copy(expanded = next, edgeDock = OverlayEdgeDock.None)
        resizeClusterWindow(expanded = next)
        if (!next) {
            // 收起后若已贴边，自动半隐
            maybeDockToNearestEdge(animate = true)
        }
    }

    /** 半隐 → 滑出到安全位置并展开副球 */
    private fun expandFromHalfHidden() {
        undockFromEdge(animate = false)
        expandNow()
    }

    private fun expandNow() {
        if (ui.expanded) return
        ui = ui.copy(expanded = true, edgeDock = OverlayEdgeDock.None)
        resizeClusterWindow(expanded = true)
        clampPosition(update = true)
    }

    /**
     * 展开态贴边松手：先按当前展开几何滑到半隐位（不断窗），到位后再收起，避免顿挫。
     */
    private fun collapseAndDock(side: OverlayEdgeDock) {
        if (side == OverlayEdgeDock.None) return
        val params = clusterParams ?: return
        val joyDp = dp(OverlayClusterMetrics.JoyDp)
        val screenW = resources.displayMetrics.widthPixels
        // 仍用展开态 joy 偏移算目标，滑动过程不改窗口尺寸
        val joyOx = dp(OverlayClusterMetrics.joyOffsetX(expanded = true))
        val joyLeft = when (side) {
            OverlayEdgeDock.Left -> -joyDp / 2
            OverlayEdgeDock.Right -> screenW - joyDp / 2
            OverlayEdgeDock.None -> return
        }
        val targetX = joyLeft - joyOx
            animateOrSetX(targetX, animate = true) {
                finishCollapseAtDock(side)
            }
    }

    /** 球已到位后：同帧收起窗口并保持主球屏幕位置 */
    private fun finishCollapseAtDock(side: OverlayEdgeDock) {
        val params = clusterParams ?: return
        val view = clusterView ?: return
        val oldJoyOy = dp(OverlayClusterMetrics.joyOffsetY(true))
        val newJoyOy = dp(OverlayClusterMetrics.joyOffsetY(false))
        val joyY = params.y + oldJoyOy

        ui = ui.copy(expanded = false, edgeDock = side)
        params.width = dp(OverlayClusterMetrics.windowW(false))
        params.height = dp(OverlayClusterMetrics.windowH(false))
        params.y = joyY - newJoyOy
        params.x = dockedWindowX(side)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun edgeSnapSide(): OverlayEdgeDock {
        val params = clusterParams ?: return OverlayEdgeDock.None
        val expanded = ui.expanded
        val joyOx = dp(OverlayClusterMetrics.joyOffsetX(expanded))
        val joyDp = dp(OverlayClusterMetrics.JoyDp)
        val snap = dp(OverlayClusterMetrics.EdgeSnapDp)
        val screenW = resources.displayMetrics.widthPixels
        val joyCx = params.x + joyOx + joyDp / 2
        return when {
            joyCx <= snap -> OverlayEdgeDock.Left
            joyCx >= screenW - snap -> OverlayEdgeDock.Right
            else -> OverlayEdgeDock.None
        }
    }

    private fun cycleMode() {
        val next = ui.mode.next()
        ui = ui.copy(mode = next)
        val tip = when (next) {
            OverlayAssistMode.MANUAL -> "手动：点「解析」截屏→生成→填入（不发送）"
            OverlayAssistMode.AUTO -> "自动：看屏变动后生成并填入（不发送）"
            OverlayAssistMode.PILOT -> "智驾：看屏变动后生成、填入并尝试发送"
        }
        toast(tip)
        syncAutoWatch()
    }

    private fun runAssist(action: OverlayAssistAction) {
        if (assistJob?.isActive == true) {
            toast("正在处理…")
            return
        }
        if (ui.expanded) {
            ui = ui.copy(expanded = false, edgeDock = OverlayEdgeDock.None)
            resizeClusterWindow(expanded = false)
        }
        val mode = ui.mode
        assistJob = lifecycleScope.launch {
            ui = ui.copy(busyHint = "截屏")
            val result = try {
                assistEngine.run(
                    mode = mode,
                    action = action,
                    hideOverlay = {
                        withContext(Dispatchers.Main) {
                            clusterView?.visibility = View.INVISIBLE
                        }
                    },
                    showOverlay = {
                        withContext(Dispatchers.Main) {
                            clusterView?.visibility = View.VISIBLE
                        }
                    },
                    onPhase = { phase ->
                        withContext(Dispatchers.Main) {
                            ui = ui.copy(busyHint = phase)
                        }
                    },
                )
            } catch (e: Exception) {
                OverlayAssistResult(false, e.message ?: e.javaClass.simpleName)
            } finally {
                withContext(Dispatchers.Main) {
                    clusterView?.visibility = View.VISIBLE
                }
            }
            ui = ui.copy(busyHint = null)
            refreshSessionTitle()
            if (!OverlayAssistEngine.shouldQuietSkip(result.message, mode)) {
                toast(result.message)
            }
        }
    }

    private fun syncAutoWatch() {
        autoWatchJob?.cancel()
        val mode = ui.mode
        if (mode != OverlayAssistMode.AUTO && mode != OverlayAssistMode.PILOT) return
        autoWatchJob = lifecycleScope.launch {
            delay(1_200)
            while (isActive) {
                // 与手动解析共用 assistJob，避免两路并发各回一条
                if (assistJob?.isActive == true) {
                    delay(AUTO_WATCH_INTERVAL_MS)
                    continue
                }
                if (JoyAccessibilityService.instance == null) {
                    toast("自动/智驾需要开启 Joy 无障碍")
                    delay(AUTO_WATCH_INTERVAL_MS * 3)
                    continue
                }
                val watchMode = ui.mode
                if (watchMode != OverlayAssistMode.AUTO && watchMode != OverlayAssistMode.PILOT) {
                    break
                }
                assistJob = lifecycleScope.launch {
                    ui = ui.copy(busyHint = "截屏")
                    val result = try {
                        assistEngine.run(
                            mode = watchMode,
                            action = OverlayAssistAction.PARSE_AND_REPLY,
                            hideOverlay = {
                                withContext(Dispatchers.Main) {
                                    clusterView?.visibility = View.INVISIBLE
                                }
                            },
                            showOverlay = {
                                withContext(Dispatchers.Main) {
                                    clusterView?.visibility = View.VISIBLE
                                }
                            },
                            onPhase = { phase ->
                                withContext(Dispatchers.Main) {
                                    ui = ui.copy(busyHint = phase)
                                }
                            },
                        )
                    } catch (e: Exception) {
                        OverlayAssistResult(false, e.message ?: e.javaClass.simpleName)
                    } finally {
                        withContext(Dispatchers.Main) {
                            clusterView?.visibility = View.VISIBLE
                        }
                    }
                    ui = ui.copy(busyHint = null)
                    refreshSessionTitle()
                    if (result.ok && result.reply != null) {
                        toast(result.message)
                    } else if (
                        !result.ok &&
                        !OverlayAssistEngine.shouldQuietSkip(result.message, watchMode)
                    ) {
                        toast(result.message)
                    }
                }
                assistJob?.join()
                delay(AUTO_WATCH_INTERVAL_MS)
            }
        }
    }

    private fun resizeClusterWindow(expanded: Boolean) {
        val params = clusterParams ?: return
        val view = clusterView ?: return
        dockAnimator?.cancel()
        val newW = dp(OverlayClusterMetrics.windowW(expanded))
        val newH = dp(OverlayClusterMetrics.windowH(expanded))
        val oldJoyX = OverlayClusterMetrics.joyOffsetX(!expanded)
        val oldJoyY = OverlayClusterMetrics.joyOffsetY(!expanded)
        val newJoyX = OverlayClusterMetrics.joyOffsetX(expanded)
        val newJoyY = OverlayClusterMetrics.joyOffsetY(expanded)
        params.x = params.x + dp(oldJoyX) - dp(newJoyX)
        params.y = params.y + dp(oldJoyY) - dp(newJoyY)
        params.width = newW
        params.height = newH
        clampPosition(update = false)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * Y 按展开安全区钳制；未贴边时 X 允许拖到半隐吸附区。
     * 贴边：只钳制 Y，X 由 dock 位置决定。
     */
    private fun clampPosition(update: Boolean) {
        val params = clusterParams ?: return
        val view = clusterView ?: return
        val expanded = ui.expanded
        val docked = !expanded && ui.edgeDock != OverlayEdgeDock.None
        val joyOx = dp(OverlayClusterMetrics.joyOffsetX(expanded))
        val joyOy = dp(OverlayClusterMetrics.joyOffsetY(expanded))
        val joyDp = dp(OverlayClusterMetrics.JoyDp)
        val margin = dp(OverlayClusterMetrics.ScreenMarginDp)
        val expH = dp(OverlayClusterMetrics.ExpandedH)
        val expJoyOy = dp(OverlayClusterMetrics.joyOffsetY(true))
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        var joyX = params.x + joyOx
        var joyY = params.y + joyOy

        val minJoyY = margin + expJoyOy
        val maxJoyY = (screenH - margin - expH + expJoyOy).coerceAtLeast(minJoyY)
        joyY = joyY.coerceIn(minJoyY, maxJoyY)

        if (!docked) {
            // 展开/收缩都允许拖到半隐吸附区；展开松手再决定收起半隐或拉回安全 X
            val minJoyX = -joyDp / 2
            val maxJoyX = screenW - joyDp / 2
            joyX = joyX.coerceIn(minJoyX, maxJoyX)
        }

        params.x = joyX - joyOx
        params.y = joyY - joyOy

        if (update) {
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun maybeDockToNearestEdge(animate: Boolean) {
        if (ui.expanded) return
        val side = edgeSnapSide()
        if (side == OverlayEdgeDock.None) {
            // 未贴边：拉回展开安全 X，避免下次展开撞边
            ui = ui.copy(edgeDock = OverlayEdgeDock.None)
            clampAsExpandedSafeX(animate = animate)
            return
        }
        dockToEdge(side, animate = animate)
    }

    private fun dockToEdge(side: OverlayEdgeDock, animate: Boolean) {
        if (side == OverlayEdgeDock.None) return
        val targetX = dockedWindowX(side)
        ui = ui.copy(edgeDock = side)
        animateOrSetX(targetX, animate)
    }

    private fun undockFromEdge(animate: Boolean) {
        val side = ui.edgeDock
        if (side == OverlayEdgeDock.None) return
        ui = ui.copy(edgeDock = OverlayEdgeDock.None)
        val targetX = undockedWindowX(side)
        animateOrSetX(targetX, animate)
        // 再钳一下 Y / 展开安全
        clampPosition(update = !animate)
    }

    private fun clampAsExpandedSafeX(animate: Boolean) {
        val params = clusterParams ?: return
        val joyOx = dp(OverlayClusterMetrics.joyOffsetX(false))
        val margin = dp(OverlayClusterMetrics.ScreenMarginDp)
        val expW = dp(OverlayClusterMetrics.ExpandedW)
        val expJoyOx = dp(OverlayClusterMetrics.joyOffsetX(true))
        val screenW = resources.displayMetrics.widthPixels
        val minJoyX = margin + expJoyOx
        val maxJoyX = (screenW - margin - expW + expJoyOx).coerceAtLeast(minJoyX)
        val joyX = (params.x + joyOx).coerceIn(minJoyX, maxJoyX)
        animateOrSetX(joyX - joyOx, animate)
    }

    private fun dockedWindowX(side: OverlayEdgeDock): Int {
        val joyOx = dp(OverlayClusterMetrics.joyOffsetX(false))
        val joyDp = dp(OverlayClusterMetrics.JoyDp)
        val screenW = resources.displayMetrics.widthPixels
        // 主球中心贴屏幕左右缘 → 一半在屏外
        val joyLeft = when (side) {
            OverlayEdgeDock.Left -> -joyDp / 2
            OverlayEdgeDock.Right -> screenW - joyDp / 2
            OverlayEdgeDock.None -> 0
        }
        return joyLeft - joyOx
    }

    private fun undockedWindowX(side: OverlayEdgeDock): Int {
        val joyOx = dp(OverlayClusterMetrics.joyOffsetX(false))
        val joyDp = dp(OverlayClusterMetrics.JoyDp)
        val margin = dp(OverlayClusterMetrics.ScreenMarginDp)
        val screenW = resources.displayMetrics.widthPixels
        val joyLeft = when (side) {
            OverlayEdgeDock.Left -> margin
            OverlayEdgeDock.Right -> screenW - margin - joyDp
            OverlayEdgeDock.None -> 0
        }
        return joyLeft - joyOx
    }

    private fun animateOrSetX(
        targetX: Int,
        animate: Boolean,
        onEnd: (() -> Unit)? = null,
    ) {
        val params = clusterParams ?: return
        val view = clusterView ?: return
        dockAnimator?.cancel()
        if (!animate || params.x == targetX) {
            params.x = targetX
            runCatching { windowManager.updateViewLayout(view, params) }
            onEnd?.invoke()
            return
        }
        val start = params.x
        dockAnimator = ValueAnimator.ofInt(start, targetX).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, params) }
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    private var ended = false
                    override fun onAnimationEnd(animation: Animator) {
                        if (ended) return
                        ended = true
                        if (dockAnimator === this@apply) dockAnimator = null
                        onEnd?.invoke()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        ended = true
                        if (dockAnimator === this@apply) dockAnimator = null
                    }
                },
            )
            start()
        }
    }

    private fun observeOverlayTheme() {
        lifecycleScope.launch {
            JoySettingsStore(applicationContext).settings.collect { s ->
                ui = ui.copy(
                    coreColorArgb = s.overlayCoreColorArgb,
                    satelliteColorArgb = s.overlaySatelliteColorArgb,
                )
            }
        }
    }

    private fun refreshSessionTitle() {
        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) { sessionStore.read() }
            ui = ui.copy(sessionTitle = snap.active().title)
        }
    }

    private fun openApp() {
        if (ui.expanded) toggleExpanded()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun removeCluster() {
        clusterView?.let { runCatching { windowManager.removeView(it) } }
        clusterView = null
        clusterParams = null
    }

    private fun baseOverlayParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Joy 悬浮球",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持悬浮球在微信上方可用"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, JoyOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Joy 悬浮球运行中")
            .setContentText("点 Joy 展开：刷新 / 模式 / 解析 / 主页")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .addAction(0, "关闭悬浮球", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.joy.android.overlay.START"
        const val ACTION_STOP = "com.joy.android.overlay.STOP"
        const val ACTION_REFRESH = "com.joy.android.overlay.REFRESH"
        private const val CHANNEL_ID = "joy_overlay"
        private const val NOTIF_ID = 42
        private const val AUTO_WATCH_INTERVAL_MS = 2_800L
    }
}
