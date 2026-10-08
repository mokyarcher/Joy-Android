package com.joy.android.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃材质 Token（对齐 `苹果液态玻璃效果.txt`）。
 * 无真·跨窗口 backdrop blur 时，用 frostBoost 稍微抬高可见度，避免「无背景」。
 */
data class LiquidGlassSpec(
    /** 白玻璃基础透明度，文档建议 12%~20% */
    val glassAlpha: Float,
    /** 无背后模糊时的磨砂加厚（仅模拟） */
    val frostBoost: Float,
    /** 边缘描边透明度 15%~30% */
    val borderAlpha: Float,
    val borderWidth: Dp,
    /** 局部高光 20%~45% */
    val highlightAlpha: Float,
    /** 柔和阴影 */
    val shadowAlpha: Float,
    val shadowOffsetY: Dp,
    val shadowBlur: Dp,
    /** 按压缩放 */
    val pressScale: Float,
    /** 统一虚拟光源方向（左上 → 右下） */
    val lightAzimuthDeg: Float = -45f,
)

object LiquidGlassTokens {
    /** Joy 主球 / 悬浮 Medium */
    val Floating = LiquidGlassSpec(
        glassAlpha = 0.34f,
        frostBoost = 0.52f,
        borderAlpha = 0.38f,
        borderWidth = 1.dp,
        highlightAlpha = 0.36f,
        shadowAlpha = 0.22f,
        shadowOffsetY = 6.dp,
        shadowBlur = 22.dp,
        pressScale = 0.94f,
    )

    /** 副球 / 小圆形 */
    val Satellite = LiquidGlassSpec(
        glassAlpha = 0.32f,
        frostBoost = 0.54f,
        borderAlpha = 0.40f,
        borderWidth = 1.dp,
        highlightAlpha = 0.34f,
        shadowAlpha = 0.20f,
        shadowOffsetY = 5.dp,
        shadowBlur = 18.dp,
        pressScale = 0.93f,
    )

    val ContentWhite = Color.White.copy(alpha = 0.95f)
    val ContentWhiteMuted = Color.White.copy(alpha = 0.90f)
}
