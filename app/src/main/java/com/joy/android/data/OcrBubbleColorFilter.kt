package com.joy.android.data

import android.graphics.Bitmap
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 气泡底色辅助过滤。浅色 + 暗色对方灰泡均支持。
 *
 * 浅色：对方 #EEEEF0 · 我方绿 · 字 #161616
 * 暗色对方泡约 #2C2C2C，字偏白 —— 与嵌图深色底相近，左侧宁可先留，再交给字号/密集聚类。
 */
object OcrBubbleColorFilter {
    const val OTHER_BUBBLE_BG: Int = 0xFFEEEEF0.toInt()
    const val ME_BUBBLE_BG: Int = 0xFF9DF29F.toInt()
    const val CHAT_TEXT: Int = 0xFF161616.toInt()

    /** 暗色主题对方气泡常见灰 */
    private val OTHER_DARK_VARIANTS = intArrayOf(
        0xFF2C2C2C.toInt(),
        0xFF3A3A3A.toInt(),
        0xFF262626.toInt(),
        0xFF333333.toInt(),
        0xFF2A2A2A.toInt(),
    )

    private val ME_GREEN_VARIANTS = intArrayOf(
        0xFF9DF29F.toInt(),
        0xFF95EC69.toInt(),
        0xFF89D961.toInt(),
        0xFFA0E87A.toInt(),
        0xFF92E068.toInt(),
        0xFFB2F2B4.toInt(),
        0xFF07C160.toInt(),
    )

    const val BG_TOLERANCE = 70f
    const val ME_BG_TOLERANCE = 92f
    const val DARK_OTHER_TOLERANCE = 48f
    const val TEXT_EXCLUDE_DIST = 40f
    const val REJECT_DIST = 88f
    private const val OTHER_HIT_RATIO = 0.20f
    private const val ME_HIT_RATIO = 0.12f
    private const val ME_MIN_HITS = 2

    enum class Verdict { BUBBLE, NOT_BUBBLE, UNKNOWN }

    fun keepBubbleText(bitmap: Bitmap, lines: List<OcrRawLine>): List<OcrRawLine> {
        if (lines.isEmpty()) return lines
        val src = ensureArgb(bitmap)
        val kept = lines.filter { classify(src, it) != Verdict.NOT_BUBBLE }
        return if (kept.isEmpty()) lines else kept
    }

    /**
     * 用气泡底色判左右：绿泡 = 我，灰白/暗灰 = 对方。
     * 群聊长绿泡左缘会很靠左，几何判边易翻车，颜色优先。
     */
    fun detectSide(bitmap: Bitmap, line: OcrRawLine): OcrSide? {
        val samples = sampleInsideBubblePixels(bitmap, line)
        if (samples.isEmpty()) return null

        val hitMe = samples.count { matchesMeBubbleBg(it) || isGreenish(it) }
        val hitOther = samples.count {
            matchesOtherBubbleBg(it) || matchesDarkOtherBubble(it)
        }
        if (hitMe >= ME_MIN_HITS && hitMe >= hitOther) return OcrSide.ME
        if (hitOther >= 3 && hitOther > hitMe) return OcrSide.OTHER

        val med = medianArgb(samples)
        if (matchesMeBubbleBg(med) || isGreenish(med)) return OcrSide.ME
        if (matchesOtherBubbleBg(med) || matchesDarkOtherBubble(med)) return OcrSide.OTHER
        return null
    }

    fun classify(bitmap: Bitmap, line: OcrRawLine): Verdict {
        val samples = sampleInsideBubblePixels(bitmap, line)
        if (samples.isEmpty()) {
            return if (isRightish(line)) Verdict.UNKNOWN else Verdict.UNKNOWN
        }

        val hitMe = samples.count { matchesMeBubbleBg(it) }
        val hitOther = samples.count { matchesOtherBubbleBg(it) }
        val lightHits = samples.count { luminance(it) > 170f }
        val n = samples.size.toFloat()
        val lightRatio = lightHits / n

        if (hitMe / n >= ME_HIT_RATIO || hitMe >= ME_MIN_HITS) return Verdict.BUBBLE
        if (hitOther / n >= OTHER_HIT_RATIO) return Verdict.BUBBLE

        val med = medianArgb(samples)
        if (matchesMeBubbleBg(med) || isGreenish(med)) return Verdict.BUBBLE
        if (matchesOtherBubbleBg(med) || matchesDarkOtherBubble(med)) return Verdict.BUBBLE

        if (isRightish(line)) {
            if (hitMe >= 1 || nearestMeDist(med) <= ME_BG_TOLERANCE + 20f || isGreenish(med)) {
                return Verdict.BUBBLE
            }
            return if (clearlyDarkNonBubble(med) && hitMe == 0 && lightRatio < 0.08f) {
                Verdict.NOT_BUBBLE
            } else {
                Verdict.UNKNOWN
            }
        }

        // 左侧：暗色对方泡 = 深底+白字；嵌图也常深底+浅字 → 不要用底色硬杀
        // 有白字痕迹 → 当作可能气泡，交给字号/聚类
        if (lightRatio >= 0.10f) return Verdict.UNKNOWN
        if (matchesDarkOtherBubble(med)) return Verdict.BUBBLE
        // 几乎无浅色像素的纯深色块 → 更像图/贴图里的死黑底
        if (clearlyDarkNonBubble(med) && lightRatio < 0.06f) return Verdict.NOT_BUBBLE
        return Verdict.UNKNOWN
    }

    fun matchesWeChatBubbleBg(color: Int): Boolean =
        matchesOtherBubbleBg(color) || matchesMeBubbleBg(color) || matchesDarkOtherBubble(color)

    fun matchesOtherBubbleBg(color: Int): Boolean =
        colorDistance(color, OTHER_BUBBLE_BG) <= BG_TOLERANCE

    fun matchesDarkOtherBubble(color: Int): Boolean =
        OTHER_DARK_VARIANTS.any { colorDistance(color, it) <= DARK_OTHER_TOLERANCE }

    fun matchesMeBubbleBg(color: Int): Boolean {
        if (colorDistance(color, ME_BUBBLE_BG) <= ME_BG_TOLERANCE) return true
        return ME_GREEN_VARIANTS.any { colorDistance(color, it) <= ME_BG_TOLERANCE }
    }

    fun clearlyNotBubble(color: Int): Boolean {
        if (matchesWeChatBubbleBg(color) || isGreenish(color)) return false
        val nearest = min(nearestOtherDist(color), nearestMeDist(color))
        if (nearest <= BG_TOLERANCE) return false
        if (clearlyDarkNonBubble(color)) return true
        if (nearest >= REJECT_DIST + 20f) return true
        return false
    }

    fun clearlyDarkNonBubble(color: Int): Boolean {
        if (matchesMeBubbleBg(color) || matchesOtherBubbleBg(color) || isGreenish(color)) {
            return false
        }
        if (matchesDarkOtherBubble(color)) return false
        val lum = luminance(color)
        val nearest = min(nearestOtherDist(color), nearestMeDist(color))
        return lum < 90f && nearest >= REJECT_DIST
    }

    fun isGreenish(color: Int): Boolean {
        val r = red(color)
        val g = green(color)
        val b = blue(color)
        return g >= 120 && g > r + 12 && g > b + 12 && luminance(color) > 100f
    }

    fun colorDistance(a: Int, b: Int): Float {
        val dr = red(a) - red(b)
        val dg = green(a) - green(b)
        val db = blue(a) - blue(b)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    fun sampleInsideBubblePixels(bitmap: Bitmap, line: OcrRawLine): List<Int> {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 1 || h <= 1) return emptyList()

        var left = (line.left * w).toInt().coerceIn(0, w - 1)
        var right = (line.right * w).toInt().coerceIn(0, w - 1)
        val top = (line.top * h).toInt().coerceIn(0, h - 1)
        val bottom = (line.bottom * h).toInt().coerceIn(0, h - 1)
        if (right <= left || bottom <= top) return emptyList()

        val boxH = (bottom - top).coerceAtLeast(1)
        if (isRightish(line)) {
            val pad = (boxH * 0.5f).toInt().coerceIn(4, 32)
            right = (right + pad).coerceIn(0, w - 1)
            left = (left - pad / 4).coerceIn(0, w - 1)
        } else {
            val pad = (boxH * 0.35f).toInt().coerceIn(3, 24)
            left = (left - pad).coerceIn(0, w - 1)
        }

        val boxW = (right - left).coerceAtLeast(1)
        val xStep = (boxW / 8).coerceAtLeast(1)
        val yStep = (boxH / 4).coerceAtLeast(1)

        val samples = ArrayList<Int>(80)
        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                val c = bitmap.getPixel(x, y)
                // 浅色主题字是深色；暗色主题字是浅色——两种都排除「像字」的极端值时：
                // 只排除接近浅色主题深字，保留白字/绿底/灰底像素供判断
                if (colorDistance(c, CHAT_TEXT) >= TEXT_EXCLUDE_DIST) {
                    samples += c
                }
                x += xStep
            }
            y += yStep
        }
        return samples
    }

    fun medianArgb(colors: List<Int>): Int {
        if (colors.isEmpty()) return 0
        if (colors.size == 1) return colors[0]
        val rs = colors.map { red(it) }.sorted()
        val gs = colors.map { green(it) }.sorted()
        val bs = colors.map { blue(it) }.sorted()
        val mid = colors.size / 2
        return argb(rs[mid], gs[mid], bs[mid])
    }

    /** 我方长泡左缘可很靠左，但右缘仍贴右头像 */
    private fun isRightish(line: OcrRawLine): Boolean =
        line.cx >= 0.48f || line.right >= 0.68f

    private fun nearestMeDist(color: Int): Float =
        ME_GREEN_VARIANTS.minOf { colorDistance(color, it) }

    private fun nearestOtherDist(color: Int): Float =
        colorDistance(color, OTHER_BUBBLE_BG)

    private fun luminance(c: Int): Float =
        0.299f * red(c) + 0.587f * green(c) + 0.114f * blue(c)

    private fun red(c: Int): Int = (c shr 16) and 0xFF
    private fun green(c: Int): Int = (c shr 8) and 0xFF
    private fun blue(c: Int): Int = c and 0xFF
    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun ensureArgb(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        return bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
    }
}
