package com.joy.android.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 圆形液态玻璃表面（文档 MVP：半透明 + 环境色 + 边缘高光 + 柔和阴影 + Pressed）。
 *
 * 说明：TYPE_APPLICATION_OVERLAY 无法做「圆形裁切的跨窗口 backdrop blur」，
 * 因此用 frostBoost 磨砂层模拟柔化背景；有环境 tint 时做色彩融合。
 */
@Composable
fun LiquidGlassCircle(
    size: Dp,
    spec: LiquidGlassSpec = LiquidGlassTokens.Floating,
    ambientTint: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) spec.pressScale else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "liquidGlassPress",
    )
    val density = LocalDensity.current
    val shadowBlurPx = with(density) { spec.shadowBlur.toPx() }
    val shadowYPx = with(density) { spec.shadowOffsetY.toPx() }
    val borderPx = with(density) { spec.borderWidth.toPx() }

    val hasTint = ambientTint.alpha > 0.01f
    val glassFill = remember(spec, ambientTint) {
        blendGlassFill(spec, ambientTint, hasTint)
    }
    val glassFillBottom = remember(spec, ambientTint) {
        blendGlassFillBottom(spec, ambientTint, hasTint)
    }

    Box(
        modifier = modifier
            .size(size)
            .scale(pressScale),
        contentAlignment = Alignment.Center,
    ) {
        // 06 Shadow：极软接触影（圆形，避免方块感）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val r = min(this.size.width, this.size.height) / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Black.copy(alpha = spec.shadowAlpha),
                                0.55f to Color.Black.copy(alpha = spec.shadowAlpha * 0.35f),
                                1.0f to Color.Transparent,
                            ),
                            center = Offset(this.size.width / 2f, this.size.height / 2f + shadowYPx * 0.35f),
                            radius = r + shadowBlurPx * 0.35f,
                        ),
                        radius = r * 0.92f,
                        center = Offset(this.size.width / 2f, this.size.height / 2f + shadowYPx),
                    )
                },
        )

        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .drawBehind {
                    val w = this.size.width
                    val h = this.size.height
                    val cx = w / 2f
                    val cy = h / 2f
                    val r = min(w, h) / 2f

                    // 02+03 Frost + Glass Tint（扁平，非立体球）
                    drawCircle(
                        brush = Brush.verticalGradient(
                            colors = listOf(glassFill, glassFillBottom),
                        ),
                    )

                    // 08 内部反射：左上 → 右下极淡光泽
                    val lightRad = Math.toRadians(spec.lightAzimuthDeg.toDouble())
                    val lx = cos(lightRad).toFloat()
                    val ly = sin(lightRad).toFloat()
                    drawCircle(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0.0f to Color.White.copy(alpha = spec.highlightAlpha * 0.55f),
                                0.42f to Color.White.copy(alpha = 0.04f),
                                1.0f to Color.Transparent,
                            ),
                            start = Offset(cx + lx * r * 0.85f, cy + ly * r * 0.85f),
                            end = Offset(cx - lx * r * 0.9f, cy - ly * r * 0.9f),
                        ),
                    )

                    // 05 Specular：边缘高光（上/左上亮，底暗）— sweep，不是球心闪光
                    drawCircle(
                        brush = Brush.sweepGradient(
                            colorStops = arrayOf(
                                0.00f to Color.White.copy(alpha = spec.highlightAlpha * 0.35f),
                                0.12f to Color.White.copy(alpha = spec.highlightAlpha),
                                0.28f to Color.White.copy(alpha = spec.borderAlpha * 0.7f),
                                0.50f to Color.White.copy(alpha = spec.borderAlpha * 0.25f),
                                0.72f to Color.White.copy(alpha = spec.borderAlpha * 0.45f),
                                0.90f to Color.White.copy(alpha = spec.highlightAlpha * 0.85f),
                                1.00f to Color.White.copy(alpha = spec.highlightAlpha * 0.35f),
                            ),
                            center = Offset(cx, cy),
                        ),
                        radius = r - borderPx * 0.5f,
                        style = Stroke(width = borderPx * 1.15f),
                    )

                    // 外轮廓极淡，浅底上仍能辨认玻璃边界
                    drawCircle(
                        color = Color(0xFF6B7788).copy(alpha = 0.16f),
                        radius = r - 0.4f,
                        style = Stroke(width = borderPx * 0.7f),
                    )
                }
                .then(
                    if (onClick != null) {
                        Modifier.pointerInput(enabled) {
                            detectTapGestures(
                                onPress = {
                                    if (!enabled) return@detectTapGestures
                                    pressed = true
                                    try {
                                        awaitRelease()
                                    } finally {
                                        pressed = false
                                    }
                                },
                                onTap = { if (enabled) onClick() },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

private fun blendGlassFill(
    spec: LiquidGlassSpec,
    tint: Color,
    hasTint: Boolean,
): Color {
    val a = (spec.glassAlpha + spec.frostBoost).coerceIn(0.48f, 0.92f)
    return if (hasTint) {
        Color(
            red = (0.58f * 0.62f + tint.red * 0.38f).coerceIn(0f, 1f),
            green = (0.68f * 0.62f + tint.green * 0.38f).coerceIn(0f, 1f),
            blue = (0.82f * 0.62f + tint.blue * 0.38f).coerceIn(0f, 1f),
            alpha = a,
        )
    } else {
        Color(0xFFA8B4C2).copy(alpha = a)
    }
}

private fun blendGlassFillBottom(
    spec: LiquidGlassSpec,
    tint: Color,
    hasTint: Boolean,
): Color {
    val a = (spec.glassAlpha + spec.frostBoost * 0.96f).coerceIn(0.46f, 0.90f)
    return if (hasTint) {
        Color(
            red = (0.40f * 0.58f + tint.red * 0.42f).coerceIn(0f, 1f),
            green = (0.52f * 0.58f + tint.green * 0.42f).coerceIn(0f, 1f),
            blue = (0.68f * 0.58f + tint.blue * 0.42f).coerceIn(0f, 1f),
            alpha = a,
        )
    } else {
        Color(0xFF7E8C9C).copy(alpha = a)
    }
}
