package com.joy.android.overlay

import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joy.android.ui.glass.LiquidGlassCircle
import kotlinx.coroutines.delay
import com.joy.android.ui.glass.LiquidGlassTokens
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 贴右侧展开：副球只在上 / 左上 / 左下 / 下，右侧不占位，主球可贴屏幕右缘。
 */
internal object OverlayClusterMetrics {
    /** 主球略大于副球 */
    const val JoyDp = 54
    const val SatDp = 48
    const val RadiusDp = 70
    /**
     * 外圈留白：副球半径外的阴影 + 防裁切余量。
     * 太小会在展开瞬间被窗口矩形裁切，出现「边线」感。
     */
    const val PadDp = 44
    /** 贴右缘留白（含主球阴影） */
    const val EdgePadDp = 16
    /** 相对屏幕边缘的安全边距（按展开占地约束主球活动范围） */
    const val ScreenMarginDp = 14
    /**
     * 收缩态：主球中心距左右边缘小于此值时，松手吸附半隐。
     */
    const val EdgeSnapDp = 40
    const val CollapsedDp = JoyDp + PadDp

    /** 展开宽：左侧扇形 + 主球贴右 */
    val ExpandedW: Int
        get() = PadDp + RadiusDp + SatDp / 2 + JoyDp / 2 + EdgePadDp

    /** 展开高：上下都有副球 */
    val ExpandedH: Int
        get() = 2 * (PadDp + RadiusDp + SatDp / 2)

    fun windowW(expanded: Boolean): Int =
        if (expanded) ExpandedW else CollapsedDp

    fun windowH(expanded: Boolean): Int =
        if (expanded) ExpandedH else CollapsedDp

    fun joyOffsetX(expanded: Boolean): Int =
        if (expanded) {
            ExpandedW - EdgePadDp - JoyDp
        } else {
            (CollapsedDp - JoyDp) / 2
        }

    fun joyOffsetY(expanded: Boolean): Int =
        if (expanded) {
            (ExpandedH - JoyDp) / 2
        } else {
            (CollapsedDp - JoyDp) / 2
        }

    fun joyCenterX(expanded: Boolean): Float =
        joyOffsetX(expanded) + JoyDp / 2f

    fun joyCenterY(expanded: Boolean): Float =
        joyOffsetY(expanded) + JoyDp / 2f
}

@Composable
fun OverlayCluster(
    state: OverlayUiState,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onCycleMode: () -> Unit,
    onParse: () -> Unit,
    onHome: () -> Unit,
    onDragBy: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val expandProgress by animateFloatAsState(
        targetValue = if (state.expanded) 1f else 0f,
        // 文档：展开 320~420ms；仍避免弹性过冲顶出窗口
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "expandProgress",
    )

    val joySize = OverlayClusterMetrics.JoyDp.dp
    val satSize = OverlayClusterMetrics.SatDp.dp
    val fullRadius = OverlayClusterMetrics.RadiusDp.dp
    val boxW = OverlayClusterMetrics.windowW(state.expanded).dp
    val boxH = OverlayClusterMetrics.windowH(state.expanded).dp
    val joyX = OverlayClusterMetrics.joyOffsetX(state.expanded).dp
    val joyY = OverlayClusterMetrics.joyOffsetY(state.expanded).dp
    val centerX = OverlayClusterMetrics.joyCenterX(expanded = true).dp
    val centerY = OverlayClusterMetrics.joyCenterY(expanded = true).dp

    Box(
        modifier = Modifier
            .size(width = boxW, height = boxH)
            .background(Color.Transparent),
    ) {
        if (state.expanded && expandProgress > 0.01f) {
            val satTint = Color(state.satelliteColorArgb)
            val satellites = listOf(
                SatelliteSpec("刷新", Icons.Outlined.Refresh, -90f, tint = satTint, onClick = onRefresh),
                SatelliteSpec(
                    label = state.mode.shortLabel,
                    icon = Icons.Outlined.Tune,
                    angleDeg = -150f,
                    tint = blendSatelliteWithMode(satTint, state.mode),
                    onClick = onCycleMode,
                ),
                SatelliteSpec("解析", Icons.Outlined.AutoAwesome, 150f, tint = satTint, onClick = onParse),
                SatelliteSpec("主页", Icons.Outlined.Home, 90f, tint = satTint, onClick = onHome),
            )
            val animRadius = fullRadius * expandProgress
            satellites.forEach { spec ->
                val (x, y) = polarOffset(
                    centerX = centerX,
                    centerY = centerY,
                    radius = animRadius,
                    angleDeg = spec.angleDeg,
                    itemSize = satSize,
                )
                GlassSatelliteButton(
                    label = spec.label,
                    icon = spec.icon,
                    x = x,
                    y = y,
                    size = satSize,
                    tint = spec.tint,
                    progress = expandProgress,
                    onClick = spec.onClick,
                )
            }
        }

        JoyCoreButton(
            x = joyX,
            y = joyY,
            size = joySize,
            busyPhase = state.busyHint,
            coreColor = Color(state.coreColorArgb),
            onClick = onToggle,
            onDragBy = onDragBy,
            onDragEnd = onDragEnd,
        )
    }
}

private data class SatelliteSpec(
    val label: String,
    val icon: ImageVector,
    val angleDeg: Float,
    val tint: Color = Color.Transparent,
    val onClick: () -> Unit,
)

/** 极坐标：0°=右，90°=下；返回子球左上角 offset。 */
private fun polarOffset(
    centerX: Dp,
    centerY: Dp,
    radius: Dp,
    angleDeg: Float,
    itemSize: Dp,
): Pair<Dp, Dp> {
    val rad = Math.toRadians(angleDeg.toDouble())
    val cx = centerX.value + radius.value * cos(rad).toFloat()
    val cy = centerY.value + radius.value * sin(rad).toFloat()
    val half = itemSize.value / 2f
    return (cx - half).roundToInt().dp to (cy - half).roundToInt().dp
}

@Composable
private fun JoyCoreButton(
    x: Dp,
    y: Dp,
    size: Dp,
    busyPhase: String?,
    coreColor: Color,
    onClick: () -> Unit,
    onDragBy: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val busy = busyPhase != null
    var dragging by remember { mutableStateOf(false) }
    val dragProgress by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
        label = "joyDrag",
    )

    val infinite = rememberInfiniteTransition(label = "joyBusy")
    val pulseAnim by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(780, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "joyPulse",
    )
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "joySpin",
    )
    val glowAnim by infinite.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(780, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "joyGlow",
    )
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(busy) {
        if (!busy) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        val started = SystemClock.elapsedRealtime()
        while (true) {
            elapsedMs = SystemClock.elapsedRealtime() - started
            delay(100)
        }
    }

    val pulse = if (busy) pulseAnim else 1f
    val glow = if (busy) glowAnim else 0.55f
    val ringPad = 8.dp
    val ringSize = size + ringPad * 2
    val phase = busyPhase?.take(2).orEmpty()
    val scale = (if (busy) pulse else 1f) * (1f + 0.06f * dragProgress)
    val ambient = if (busy) {
        coreColor.copy(alpha = 0.55f + glow * 0.35f)
    } else {
        darken(coreColor, 0.16f * dragProgress)
    }

    Box(
        modifier = Modifier
            .offset(x - ringPad, y - ringPad)
            .size(ringSize),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            Canvas(
                modifier = Modifier
                    .size(ringSize)
                    .graphicsLayer { rotationZ = spin },
            ) {
                val stroke = Stroke(
                    width = 3.2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                val inset = stroke.width / 2f + 1.dp.toPx()
                val diam = min(this.size.width, this.size.height) - inset * 2f
                val topLeft = Offset(
                    (this.size.width - diam) / 2f,
                    (this.size.height - diam) / 2f,
                )
                val arcSize = Size(diam, diam)
                // 主弧 + 短尾，转起来像在「扫」
                drawArc(
                    color = coreColor.copy(alpha = glow),
                    startAngle = -90f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
                drawArc(
                    color = coreColor.copy(alpha = glow * 0.45f),
                    startAngle = 50f,
                    sweepAngle = 48f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
        }

        LiquidGlassCircle(
            size = size,
            spec = LiquidGlassTokens.Floating,
            ambientTint = ambient,
            modifier = Modifier
                .scale(scale)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            onDragEnd()
                        },
                        onDragCancel = {
                            dragging = false
                            onDragEnd()
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        onDragBy(dragAmount.x, dragAmount.y)
                    }
                },
            onClick = onClick,
        ) {
            if (busy) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = phase,
                        color = LiquidGlassTokens.ContentWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Text(
                        text = formatBallElapsed(elapsedMs),
                        color = LiquidGlassTokens.ContentWhite.copy(alpha = 0.92f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            } else {
                Text(
                    text = "Joy",
                    color = LiquidGlassTokens.ContentWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 主球内紧凑计时：3.1s / 1:05 */
private fun formatBallElapsed(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0) / 1000.0
    return if (totalSec < 60) {
        String.format("%.1fs", totalSec)
    } else {
        val m = (totalSec / 60).toInt()
        val s = (totalSec % 60).toInt()
        String.format("%d:%02d", m, s)
    }
}

@Composable
private fun GlassSatelliteButton(
    label: String,
    icon: ImageVector,
    x: Dp,
    y: Dp,
    size: Dp,
    tint: Color,
    progress: Float,
    onClick: () -> Unit,
) {
    val scale = 0.35f + 0.65f * progress
    val p = progress.coerceIn(0f, 1f)

    LiquidGlassCircle(
        size = size,
        spec = LiquidGlassTokens.Satellite,
        ambientTint = tint,
        modifier = Modifier
            .offset(x, y)
            .scale(scale)
            .alpha(p),
        onClick = onClick,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = LiquidGlassTokens.ContentWhite,
                modifier = Modifier.size(size * 0.40f),
            )
            Spacer(modifier = Modifier.size(2.dp))
            Text(
                text = label,
                color = LiquidGlassTokens.ContentWhiteMuted,
                fontWeight = FontWeight.Medium,
                fontSize = 8.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                lineHeight = 9.sp,
            )
        }
    }
}

private fun modeGlassTint(mode: OverlayAssistMode): Color = when (mode) {
    OverlayAssistMode.MANUAL -> Color.Transparent
    OverlayAssistMode.AUTO -> Color(0xFF26A69A)
    OverlayAssistMode.PILOT -> Color(0xFFAB47BC)
}

private fun blendSatelliteWithMode(base: Color, mode: OverlayAssistMode): Color {
    val modeTint = modeGlassTint(mode)
    if (modeTint.alpha < 0.01f) return base
    if (base.alpha < 0.01f) return modeTint
    return Color(
        red = base.red * 0.55f + modeTint.red * 0.45f,
        green = base.green * 0.55f + modeTint.green * 0.45f,
        blue = base.blue * 0.55f + modeTint.blue * 0.45f,
        alpha = 1f,
    )
}

private fun darken(color: Color, amount: Float): Color {
    val a = amount.coerceIn(0f, 1f)
    val k = 1f - 0.22f * a
    return color.copy(red = color.red * k, green = color.green * k, blue = color.blue * k)
}
