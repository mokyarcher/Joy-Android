package com.joy.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * 微信截图 OCR：气泡底色校验（去嵌图字）+ 左右贴边分角色。
 */
class ImageOcrHelper {
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    fun recognize(context: Context, uri: Uri): ChatExtractResult {
        val prepared = loadImage(context, uri)
            ?: throw IllegalStateException("无法读取图片")
        return recognizePrepared(prepared, retainGroupNicknames = false)
    }

    /**
     * @param retainGroupNicknames 群 Bot 为 true：保留气泡上方昵称，格式化为「对方·昵称：」
     */
    fun recognizeBitmap(
        bitmap: Bitmap,
        retainGroupNicknames: Boolean = false,
    ): ChatExtractResult {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("无法转换截屏格式")
        }
        return recognizePrepared(
            PreparedImage(
                image = InputImage.fromBitmap(argb, 0),
                bitmap = argb,
                width = argb.width,
                height = argb.height,
            ),
            retainGroupNicknames = retainGroupNicknames,
        )
    }

    private fun recognizePrepared(
        prepared: PreparedImage,
        retainGroupNicknames: Boolean,
    ): ChatExtractResult {
        val result = Tasks.await(recognizer.process(prepared.image), 60, TimeUnit.SECONDS)
        val raw = extractLines(result, prepared.width, prepared.height)
        val peerName = OcrPeerNameExtractor.extract(raw)
        val onBubbles = OcrBubbleColorFilter.keepBubbleText(prepared.bitmap, raw)
        val formatted = OcrChatFormatter.format(
            onBubbles,
            prepared.bitmap,
            retainGroupNicknames = retainGroupNicknames,
        )
        if (formatted.isBlank()) {
            throw IllegalStateException("未识别到聊天内容，请换一张更清晰的截图")
        }
        return ChatExtractResult(chatText = formatted, peerName = peerName)
    }

    private fun extractLines(result: Text, imageWidth: Int, imageHeight: Int): List<OcrRawLine> {
        val w = imageWidth.coerceAtLeast(1).toFloat()
        val h = imageHeight.coerceAtLeast(1).toFloat()
        val out = mutableListOf<OcrRawLine>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val text = line.text.trim()
                if (text.isEmpty()) continue
                val box = line.boundingBox ?: continue
                out += OcrRawLine(
                    text = text,
                    left = box.left / w,
                    top = box.top / h,
                    right = box.right / w,
                    bottom = box.bottom / h,
                )
            }
        }
        return out
    }

    private fun loadImage(context: Context, uri: Uri): PreparedImage? {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            val decoded = BitmapFactory.decodeStream(stream) ?: return null
            val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) {
                decoded
            } else {
                decoded.copy(Bitmap.Config.ARGB_8888, false) ?: decoded
            }
            PreparedImage(
                image = InputImage.fromBitmap(bitmap, 0),
                bitmap = bitmap,
                width = bitmap.width,
                height = bitmap.height,
            )
        }
    }

    private data class PreparedImage(
        val image: InputImage,
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
    )
}

/** 归一化坐标的 OCR 行（0～1），便于单测。 */
data class OcrRawLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
    val height: Float get() = (bottom - top).coerceAtLeast(0.008f)
}

enum class OcrSide { ME, OTHER }

/**
 * 噪声过滤 + 字号主筛 + 密集聚类 + 左右角色 + 同气泡合并。
 * [bitmap] 有则优先用绿/灰泡底色判「我/对方」，避免群聊长绿泡被几何误判。
 */
object OcrChatFormatter {
    fun format(
        rawLines: List<OcrRawLine>,
        bitmap: Bitmap? = null,
        retainGroupNicknames: Boolean = false,
    ): String {
        val afterChrome = rawLines.filterNot { isNoise(it, retainGroupNicknames) }
        val afterFont = dropSmallFontLines(afterChrome, retainGroupNicknames)
        val kept = dropImageTextClusters(afterFont)
        if (kept.isEmpty()) return ""

        val src = bitmap?.let { ensureArgb(it) }
        val tagged = kept
            .map { line -> TaggedLine(line, assignSide(line, src)) }
            .sortedWith(compareBy({ it.line.cy }, { it.line.left }))

        if (retainGroupNicknames) {
            dumpGroupOcrPipeline(rawLines, afterChrome, afterFont, kept, tagged)
            return formatWithGroupNicknames(tagged)
        }

        val withoutNicks = dropGroupNicknames(tagged)
        val merged = mergeBubbles(withoutNicks)
        return merged
            .map { (side, text) -> side to cleanBubbleText(text) }
            .filterNot { (_, text) -> isJunkMessage(text) }
            .joinToString("\n") { (side, text) ->
                val label = if (side == OcrSide.ME) "我" else "对方"
                "$label：$text"
            }
    }

    /**
     * 群 Bot：气泡上方小字当昵称，输出「对方·昵称：正文」，便于 @ 对的人。
     */
    private fun formatWithGroupNicknames(tagged: List<TaggedLine>): String {
        if (tagged.isEmpty()) return ""
        val chatH = resolveChatFontHeight(tagged.map { it.line })
        val out = mutableListOf<String>()
        var i = 0
        while (i < tagged.size) {
            val t = tagged[i]
            if (t.side == OcrSide.ME) {
                val bubble = collectBubble(tagged, i)
                val text = cleanBubbleText(bubble.text)
                if (!isJunkMessage(text)) out += "我：$text"
                i = bubble.nextIndex
                continue
            }
            val text = cleanBubbleText(t.line.text)
            val nextOtherIdx = nextOtherIndex(tagged, i + 1)
            val nextOther = nextOtherIdx.takeIf { it >= 0 }?.let { tagged[it] }
            val geoReason = if (nextOther == null) {
                "no_next_other"
            } else {
                nicknameAboveReason(t.line, nextOther.line, chatH, text)
            }
            if (nextOther != null && geoReason == "ok") {
                val bubble = collectBubble(tagged, nextOtherIdx)
                val body = cleanBubbleText(bubble.text)
                if (!isJunkMessage(body)) {
                    dbg("geoNick OK '$text' -> '${body.take(28)}'")
                    out += "对方·$text：$body"
                } else {
                    // 正文被判垃圾时不能吞掉昵称行，回退成普通对方行
                    dbg("geoNick body junk, keep nick line '$text'")
                    if (!isJunkMessage(text)) out += "对方：$text"
                }
                i = bubble.nextIndex
            } else {
                if (nextOther != null) {
                    dbg(
                        "geoNick skip '$text' vs '${cleanBubbleText(nextOther.line.text).take(24)}' ($geoReason)",
                    )
                }
                val bubble = collectBubble(tagged, i)
                val body = cleanBubbleText(bubble.text)
                if (!isJunkMessage(body)) out += "对方：$body"
                i = bubble.nextIndex
            }
        }
        val locked = lockNickFromLineAbove(out)
        dbg("formatted ${locked.size} lines")
        locked.forEachIndexed { idx, line -> dbg("chat[$idx] $line") }
        return locked.joinToString("\n")
    }

    /**
     * 微信群固定结构：对方气泡正上方那一行短字 = 发送人昵称。
     * 几何没挂上时，用阅读顺序把「对方：昵称」+「对方：正文」锁成「对方·昵称：正文」。
     */
    internal fun lockNickFromLineAbove(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (line in lines) {
            val cur = GroupBotMention.parseOtherLine(line)
            val prevLine = out.lastOrNull().orEmpty()
            val prevOther = GroupBotMention.parseOtherLine(prevLine)
            val prevMeBody = when {
                prevLine.startsWith("我：") -> prevLine.removePrefix("我：").trim()
                prevLine.startsWith("我:") -> prevLine.removePrefix("我:").trim()
                else -> ""
            }
            val maybeNick = when {
                prevOther != null && prevOther.first == "群友" -> prevOther.second.trim()
                prevOther != null && GroupBotMention.isPlausibleGroupNick(prevOther.first) &&
                    prevOther.second.isBlank() -> prevOther.first
                prevMeBody.isNotEmpty() -> prevMeBody
                else -> ""
            }
            val canLock = cur != null &&
                cur.first == "群友" &&
                GroupBotMention.isPlausibleGroupNick(maybeNick) &&
                maybeNick.length in 1..14 &&
                !looksLikeChatSentence(maybeNick) &&
                '@' !in maybeNick &&
                '＠' !in maybeNick &&
                cur.second.isNotBlank() &&
                cur.second != maybeNick &&
                (looksLikeChatSentence(cur.second) ||
                    '@' in cur.second ||
                    '＠' in cur.second ||
                    cur.second.length >= maybeNick.length)
            if (canLock) {
                out.removeAt(out.lastIndex)
                val merged = "对方·$maybeNick：${cur!!.second}"
                dbg("lockNick above '$maybeNick' -> '${cur.second.take(28)}'")
                out += merged
            } else {
                out += line
            }
        }
        return out
    }

    private data class BubbleChunk(val text: String, val nextIndex: Int)

    private fun collectBubble(tagged: List<TaggedLine>, start: Int): BubbleChunk {
        if (start >= tagged.size) return BubbleChunk("", start)
        val side = tagged[start].side
        val parts = mutableListOf(tagged[start].line.text)
        var last = tagged[start].line
        var i = start + 1
        while (i < tagged.size) {
            val n = tagged[i]
            if (!sameBubble(last, side, n.line, n.side)) break
            parts += n.line.text
            last = n.line
            i++
        }
        return BubbleChunk(parts.joinToString(""), i)
    }

    /** 去掉 OCR 把头像边线认成的 | 等前缀 */
    internal fun cleanBubbleText(text: String): String =
        text.trim().trimStart('|', '丨', '｜', ' ', '\t')

    /**
     * 群聊对方昵称在气泡上方、字更小；勿并进正文。
     */
    internal fun dropGroupNicknames(tagged: List<TaggedLine>): List<TaggedLine> {
        if (tagged.size < 2) return tagged
        val chatH = resolveChatFontHeight(tagged.map { it.line })
        return tagged.filterIndexed { i, t ->
            if (t.side != OcrSide.OTHER) return@filterIndexed true
            val text = cleanBubbleText(t.line.text)
            if (text.length > 14) return@filterIndexed true
            if (t.line.height >= chatH * 0.88f) return@filterIndexed true
            if (looksLikeChatSentence(text)) return@filterIndexed true
            val next = tagged.getOrNull(i + 1) ?: return@filterIndexed true
            if (next.side != OcrSide.OTHER) return@filterIndexed true
            // true = keep；认出昵称则丢掉
            !isNicknameAboveBubble(t.line, next.line, chatH, text)
        }
    }

    private fun ensureArgb(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        return bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
    }

    private fun isJunkMessage(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        // 底栏 Tab「我」或残缺单字
        if (t == "我" || t == "微信" || t == "通讯录" || t == "发现") return true
        // 语音键声波 / 空表情等被认成「()」「（）」等纯符号
        if (isSymbolOnlyNoise(t)) return true
        return false
    }

    /** 无汉字/字母/数字：多半是 UI 图标误识，不是真实气泡文案 */
    internal fun isSymbolOnlyNoise(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (t.length > 8) return false
        return t.none { ch ->
            ch.isLetterOrDigit() || ch in '\u4e00'..'\u9fff'
        }
    }

    /**
     * 以「我」侧字号为基准丢掉嵌图小字。
     * 短气泡 OCR 框常略矮，不可与嵌图小字一刀切。
     */
    fun dropSmallFontLines(
        lines: List<OcrRawLine>,
        retainGroupNicknames: Boolean = false,
    ): List<OcrRawLine> {
        if (lines.size < 2) return lines
        val ref = resolveChatFontHeight(lines)
        if (ref < 0.012f) return lines

        return lines.filter { line ->
            val ratio = line.height / ref
            val leftish = line.cx < 0.52f && line.right < 0.72f
            val text = cleanBubbleText(line.text)
            val shortChat = text.length <= 10
            when {
                // 群 Bot：气泡上方小字昵称必须留下，不能当嵌图丢掉
                retainGroupNicknames && leftish && shortChat &&
                    ratio >= FONT_NICK_KEEP &&
                    !looksLikeChatSentence(text) &&
                    GroupBotMention.isPlausibleGroupNick(text) -> true
                // 群 Bot：含 @ 的对方短泡（「@豆姐你好」）框常略矮，勿当嵌图
                retainGroupNicknames && leftish &&
                    ratio >= FONT_NICK_KEEP &&
                    ('@' in text || '＠' in text) -> true
                // 短句（「好像不行」「烦死了」）框高略矮也保留
                leftish && shortChat && ratio >= FONT_SHORT_KEEP -> true
                // 左侧明显更小 → 嵌图
                leftish && ratio < FONT_DROP_LEFT -> false
                !leftish && ratio < FONT_DROP_RIGHT -> false
                else -> true
            }
        }
    }

    /**
     * 丢掉嵌图/截图里的密排小字：多行挤在一起且字号偏小（或行数很多）。
     * 正常气泡连发行距更大，不会进同一密集聚类。
     */
    fun dropImageTextClusters(lines: List<OcrRawLine>): List<OcrRawLine> {
        if (lines.size < 2) return lines

        val indexed = lines.mapIndexed { i, line -> i to line }
            .sortedWith(compareBy({ it.second.cy }, { it.second.left }))
        val chatFontH = resolveChatFontHeight(lines)
        val dropIndices = mutableSetOf<Int>()

        var start = 0
        fun flush(endExclusive: Int) {
            if (endExclusive - start < 2) return
            val cluster = indexed.subList(start, endExclusive).map { it.second }
            if (isImageLikeCluster(cluster, chatFontH)) {
                for (j in start until endExclusive) {
                    dropIndices += indexed[j].first
                }
            }
        }

        for (i in 1 until indexed.size) {
            val prev = indexed[i - 1].second
            val next = indexed[i].second
            val soFar = indexed.subList(start, i).map { it.second }
            if (!belongsToDenseCluster(prev, next, soFar, chatFontH)) {
                flush(i)
                start = i
            }
        }
        flush(indexed.size)

        if (dropIndices.isEmpty()) return lines
        return lines.filterIndexed { i, _ -> i !in dropIndices }
    }

    /** 优先用右侧「我」的字号当聊天基准，避免嵌图小字拉低参考值。 */
    fun resolveChatFontHeight(lines: List<OcrRawLine>): Float {
        val meHeights = lines
            .filter { it.cx >= 0.50f || it.right >= 0.68f }
            .map { it.height }
        if (meHeights.size >= 2) return chatFontRef(meHeights)
        if (meHeights.size == 1) {
            val all = chatFontRef(lines.map { it.height })
            return max(meHeights[0], all)
        }
        return chatFontRef(lines.map { it.height })
    }

    fun isNoise(line: OcrRawLine, retainGroupNicknames: Boolean = false): Boolean {
        val text = line.text.trim()
        if (text.isEmpty()) return true

        // 「()」「··」等：语音键/图标误识（长度 2+ 旧逻辑漏掉了）
        if (isSymbolOnlyNoise(text)) return true

        // 顶栏：状态栏 / 会话标题。群 Bot 要保留聊天区顶部的对方昵称。
        if (line.bottom <= TOP_CHROME_MAX) return true
        // 返回箭头
        if (line.top <= 0.12f && line.left <= 0.12f && text.length <= 2) return true
        // 居中群标题「豆姐调教专用(3)」
        if (line.cy <= TOP_SOFT_MAX && line.cx in 0.28f..0.72f && GROUP_TITLE_BADGE.containsMatchIn(text)) {
            return true
        }
        if (line.cy <= TOP_SOFT_MAX && text.length <= 14 && !looksLikeChatSentence(text)) {
            val keepAsGroupNick = retainGroupNicknames &&
                line.left <= 0.42f &&
                line.bottom > TOP_CHROME_MAX &&
                GroupBotMention.isPlausibleGroupNick(cleanBubbleText(text))
            if (!keepAsGroupNick) return true
        }

        // 底栏左侧语音按钮区：声波常被认成 ()，贴左且靠底
        if (line.top >= BOTTOM_VOICE_MIN && line.cx <= 0.22f &&
            text.length <= 6 && !looksLikeChatSentence(text)
        ) {
            return true
        }

        // 底栏：只清 Tab / 居中输入占位；贴左右的短串可能是气泡尾字，保留
        if (line.top >= BOTTOM_CHROME_MIN) {
            if (BOTTOM_TAB_LABELS.contains(text) || BOTTOM_TAB_LABELS.contains(text.lowercase())) {
                return true
            }
            val centered = line.cx in 0.28f..0.72f
            if (centered && text.length <= 10 && !looksLikeChatSentence(text)) {
                return true
            }
        }

        // 底栏 Tab 残字「我」
        if (text == "我" && line.top >= 0.82f) return true

        if (UI_NOISE.contains(text) || UI_NOISE.contains(text.lowercase())) return true
        if (isTimestampLike(text)) return true

        // 居中时间条（含「昨天 12:30」、OCR 把「昨天」认成 BF 等）
        if (line.cx in 0.28f..0.72f && text.length <= 18 && isLooseTimeLabel(text)) {
            return true
        }

        return false
    }

    /** 贴左短串：群聊气泡上方昵称，不在绿/灰泡里。 */
    internal fun isLeftGroupNickname(line: OcrRawLine): Boolean {
        if (line.right > 0.40f) return false
        if (line.cx > 0.32f) return false
        val t = cleanBubbleText(line.text)
        if (t.length !in 1..14) return false
        if (looksLikeChatSentence(t)) return false
        return GroupBotMention.isPlausibleGroupNick(t)
    }

    fun assignSide(line: OcrRawLine, bitmap: Bitmap? = null): OcrSide {
        // 群昵称在头像旁、不在泡上；绿底采样常误吸成「我」
        if (isLeftGroupNickname(line)) return OcrSide.OTHER

        // 绿泡 / 灰泡底色优先（群聊长「我」泡左缘很靠左时几何会翻车）
        if (bitmap != null) {
            OcrBubbleColorFilter.detectSide(bitmap, line)?.let { return it }
        }

        val leftStick = line.left
        val rightStick = 1f - line.right
        val margin = 0.06f

        // 右缘更贴右 → 我；左缘更贴左 → 对方
        when {
            rightStick + margin < leftStick -> return OcrSide.ME
            leftStick + margin < rightStick -> return OcrSide.OTHER
        }

        // 微信：我方气泡从右头像向左撑开——长句左缘可 <0.25，但右缘仍贴右
        // 旧逻辑要求 left>=0.32 才会判「我」，群聊长绿泡必翻成「对方」
        when {
            line.right >= 0.76f -> return OcrSide.ME
            line.left <= 0.24f && line.right <= 0.72f -> return OcrSide.OTHER
            line.cx >= 0.52f -> return OcrSide.ME
            else -> return OcrSide.OTHER
        }
    }

    private fun mergeBubbles(tagged: List<TaggedLine>): List<Pair<OcrSide, String>> {
        if (tagged.isEmpty()) return emptyList()

        val out = mutableListOf<Pair<OcrSide, String>>()
        var curSide = tagged[0].side
        var parts = mutableListOf(tagged[0].line.text)
        var last = tagged[0].line

        fun flush() {
            val body = parts.joinToString("").trim()
            if (body.isNotEmpty()) out += curSide to body
            parts = mutableListOf()
        }

        for (i in 1 until tagged.size) {
            val next = tagged[i]
            if (sameBubble(last, curSide, next.line, next.side)) {
                parts += next.line.text
                last = next.line
            } else {
                flush()
                curSide = next.side
                parts += next.line.text
                last = next.line
            }
        }
        flush()
        return out
    }

    private fun sameBubble(
        prev: OcrRawLine,
        prevSide: OcrSide,
        next: OcrRawLine,
        nextSide: OcrSide,
    ): Boolean {
        // 同一视觉行被拆成左右两段（如「给我爸」+「妈 尝尝」）
        if (sameVisualRow(prev, next)) return true

        val gap = next.top - prev.bottom
        val lineH = max(prev.height, next.height)
        // 气泡内折行间距远小于两条独立气泡；允许轻微重叠（OCR 框抖动）
        if (gap > lineH * Y_GAP_FACTOR) return false

        // 微信气泡内文字左对齐：折行后短行右缘会缩进，不能靠右缘判断「我」
        val leftDelta = abs(prev.left - next.left)
        val leftAligned = leftDelta <= EDGE_ALIGN_MAX
        val contained =
            next.left >= prev.left - EDGE_ALIGN_MAX &&
                next.right <= prev.right + EDGE_ALIGN_MAX

        if (prevSide != nextSide) {
            // 折行短句框偏中时常被判错边；左缘对齐/落入上行且行距很近时跟上行
            return (leftAligned || (contained && !endsSentence(prev.text))) &&
                gap <= lineH * Y_GAP_SOFT
        }

        // 群昵称（小字号短串）紧贴下方对方气泡时，不要并进正文
        if (
            prevSide == OcrSide.OTHER &&
            looksLikeNicknameAbove(prev, next)
        ) {
            return false
        }

        if (leftAligned) return true

        // 左缘略抖：下一行水平落在上一行内，且上行不像句末 → 多半是折行
        if (contained && !endsSentence(prev.text) && gap <= lineH * Y_GAP_SOFT) {
            return true
        }

        return false
    }

    /** 纵向重叠且水平紧挨：OCR 常把一行拆成相邻两块。 */
    private fun sameVisualRow(prev: OcrRawLine, next: OcrRawLine): Boolean {
        val overlap = minOf(prev.bottom, next.bottom) - maxOf(prev.top, next.top)
        val minH = minOf(prev.height, next.height)
        if (overlap < minH * 0.45f) return false
        if (abs(prev.cy - next.cy) > minH * 0.55f) return false

        val ordered = if (prev.left <= next.left) prev to next else next to prev
        val (leftBox, rightBox) = ordered
        val hGap = rightBox.left - leftBox.right
        // 允许轻微重叠或很小空隙；空隙过大则是并排无关块
        return hGap <= ROW_H_GAP_MAX && hGap >= -0.04f
    }

    private fun belongsToDenseCluster(
        prev: OcrRawLine,
        next: OcrRawLine,
        clusterSoFar: List<OcrRawLine>,
        chatFontH: Float,
    ): Boolean {
        val gap = next.top - prev.bottom
        if (gap > chatFontH * CLUSTER_JOIN_GAP) return false

        // 正常聊天字号不要和嵌图小字并成一簇（避免误吞「好像不行」）
        val prevFull = prev.height >= chatFontH * 0.90f
        val nextFull = next.height >= chatFontH * 0.90f
        if (prevFull != nextFull) return false

        val cLeft = clusterSoFar.minOf { it.left }
        val cRight = clusterSoFar.maxOf { it.right }
        return next.left <= cRight + CLUSTER_X_PAD && next.right >= cLeft - CLUSTER_X_PAD
    }

    private fun isImageLikeCluster(cluster: List<OcrRawLine>, chatFontH: Float): Boolean {
        if (cluster.size < 2) return false

        val cMedH = median(cluster.map { it.height })
        val gaps = (1 until cluster.size).map { i ->
            cluster[i].top - cluster[i - 1].bottom
        }
        val avgGap = gaps.average().toFloat()
        val tight = avgGap <= cMedH * DENSE_GAP_FACTOR
        if (!tight) return false

        val smallFont = cMedH <= chatFontH * SMALL_FONT_RATIO
        // 密排+偏小：两行起即可丢（嵌图常只漏出两三行）
        if (smallFont) return true
        // 密排且行很多：即使字号接近也当图内正文
        return cluster.size >= LARGE_IMAGE_CLUSTER
    }

    /** 参考聊天字号：取偏大分位，避免图内密字把中位数拉低。 */
    private fun chatFontRef(heights: List<Float>): Float {
        if (heights.isEmpty()) return 0.02f
        val sorted = heights.sorted()
        val idx = ((sorted.size - 1) * 0.75f).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0.02f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2f
        } else {
            sorted[mid]
        }
    }

    private fun endsSentence(text: String): Boolean {
        val t = text.trimEnd()
        if (t.isEmpty()) return false
        return t.last() in "。！？…!?；;"
    }

    private fun looksLikeChatSentence(text: String): Boolean {
        if (text.length >= 8) return true
        if (text.any { it in "，。！？、；：…,.!?;:" }) return true
        return false
    }

    private fun nextOtherIndex(tagged: List<TaggedLine>, from: Int): Int {
        for (j in from until tagged.size) {
            if (tagged[j].side == OcrSide.ME) return -1
            return j
        }
        return -1
    }

    /**
     * 群聊：昵称在灰泡左上方、更短，水平贴气泡左缘。
     * 不强制「下一行字更高」——短 @ 句 OCR 框常和昵称差不多高。
     */
    internal fun isNicknameAboveBubble(
        nick: OcrRawLine,
        bubble: OcrRawLine,
        chatH: Float,
        nickText: String = cleanBubbleText(nick.text),
    ): Boolean = nicknameAboveReason(nick, bubble, chatH, nickText) == "ok"

    internal fun nicknameAboveReason(
        nick: OcrRawLine,
        bubble: OcrRawLine,
        chatH: Float,
        nickText: String = cleanBubbleText(nick.text),
    ): String {
        if (nick.cx >= 0.48f) return "nick_too_right cx=${fmt(nick.cx)}"
        if (nickText.length !in 1..14) return "nick_len=${nickText.length}"
        if (looksLikeChatSentence(nickText)) return "nick_looks_sentence"
        if (!GroupBotMention.isPlausibleGroupNick(nickText)) return "nick_not_plausible"
        if (nick.height > chatH * 0.98f && nickText.length >= 6) return "nick_font_too_big"
        val gap = bubble.top - nick.bottom
        val maxGap = max(nick.height * 2.4f, 0.045f)
        if (gap < -0.012f || gap > maxGap) return "gap=${fmt(gap)} max=${fmt(maxGap)}"
        if (nick.left > bubble.left + 0.10f) return "nick_left_not_align"
        if (nick.cx > bubble.cx + 0.06f) return "nick_cx_not_align"
        val body = cleanBubbleText(bubble.text)
        if (body.isEmpty() || body == nickText) return "body_empty_or_same"
        return "ok"
    }

    /** 群聊：上方昵称字更小、更短，紧贴下方气泡 */
    private fun looksLikeNicknameAbove(prev: OcrRawLine, next: OcrRawLine): Boolean {
        val chatH = max(next.height, prev.height)
        return isNicknameAboveBubble(prev, next, chatH)
    }

    private fun isTimestampLike(text: String): Boolean {
        if (PURE_CLOCK.matches(text)) return true
        if (TIMESTAMP_FULL.matches(text)) return true
        return false
    }

    private fun isLooseTimeLabel(text: String): Boolean {
        if (isTimestampLike(text)) return true
        if (LOOSE_TIME.matches(text)) return true
        if (GARBLED_TIME.matches(text)) return true
        return text in setOf("刚刚", "昨天", "前天", "星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
    }

    data class TaggedLine(val line: OcrRawLine, val side: OcrSide)

    private fun dumpGroupOcrPipeline(
        raw: List<OcrRawLine>,
        afterChrome: List<OcrRawLine>,
        afterFont: List<OcrRawLine>,
        kept: List<OcrRawLine>,
        tagged: List<TaggedLine>,
    ) {
        dbg("=== group OCR raw=${raw.size} chrome=${afterChrome.size} font=${afterFont.size} kept=${kept.size} ===")
        raw.take(80).forEachIndexed { i, line ->
            dbg("raw[$i] ${box(line)}")
        }
        raw.filter { it !in afterChrome }.forEach {
            dbg("drop chrome ${box(it)}")
        }
        afterChrome.filter { it !in afterFont }.forEach {
            dbg("drop smallFont ${box(it)}")
        }
        afterFont.filter { it !in kept }.forEach {
            dbg("drop cluster ${box(it)}")
        }
        tagged.forEachIndexed { i, t ->
            dbg("keep[$i] ${t.side} ${box(t.line)}")
        }
    }

    private fun dbg(msg: String) {
        try {
            Log.i(TAG, msg)
        } catch (_: Throwable) {
            // JVM 单测没有 logcat
        }
    }

    private fun box(line: OcrRawLine): String {
        val t = line.text.replace('\n', ' ').take(32)
        return "'$t' L=${fmt(line.left)} T=${fmt(line.top)} R=${fmt(line.right)} B=${fmt(line.bottom)} h=${fmt(line.height)}"
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.3f", v)

    private const val TOP_CHROME_MAX = 0.055f
    private const val TOP_SOFT_MAX = 0.10f
    /** 真正输入条更靠下；过早裁切会丢掉贴底气泡尾字 */
    private const val BOTTOM_CHROME_MIN = 0.92f
    /** 语音按钮略高于输入条中线，单独按左下角清 */
    private const val BOTTOM_VOICE_MIN = 0.88f
    /** 同气泡折行：行距通常小于 1 倍行高；独立气泡更大 */
    private const val Y_GAP_FACTOR = 1.0f
    /** 左缘略抖时的更紧行距，配合「水平落在上行内」兜底 */
    private const val Y_GAP_SOFT = 0.55f
    private const val EDGE_ALIGN_MAX = 0.08f
    private const val ROW_H_GAP_MAX = 0.06f
    /** 左侧低于「我」侧字号×该比例 → 当嵌图小字丢掉 */
    private const val FONT_DROP_LEFT = 0.75f
    /** 短句框略矮时的保底比例 */
    private const val FONT_SHORT_KEEP = 0.65f
    /** 群昵称可比聊天字小很多，仍要保留 */
    private const val FONT_NICK_KEEP = 0.32f
    /** 右侧仅极端碎渣才丢 */
    private const val FONT_DROP_RIGHT = 0.62f
    /** 行数很多时，即使字号接近聊天字也当图内正文 */
    private const val LARGE_IMAGE_CLUSTER = 4
    /** 相对聊天字号：超过则视为气泡间距，不并入密簇 */
    private const val CLUSTER_JOIN_GAP = 0.75f
    /** 簇内平均行距 ≤ 该比例×簇内行高 → 密排 */
    private const val DENSE_GAP_FACTOR = 0.55f
    /** 簇内字号 ≤ 聊天基准×该比例 → 偏小 */
    private const val SMALL_FONT_RATIO = 0.90f
    private const val CLUSTER_X_PAD = 0.06f

    private val PURE_CLOCK = Regex("""^[\d\s:：.]+$""")
    private val TIMESTAMP_FULL = Regex(
        """^(昨天|前天|今天|星期[一二三四五六日天]|周[一二三四五六日天])?\s*(上午|下午|早上|晚上|凌晨)?\s*\d{1,2}\s*[:：]\s*\d{2}$""",
    )
    private val LOOSE_TIME = Regex(
        """^((昨天|前天|今天)\s*)?(上午|下午|早上|晚上|凌晨)?\s*\d{1,2}\s*[:：]\s*\d{2}$|^星期[一二三四五六日天]$|^周[一二三四五六日天]$""",
    )
    /** 「昨天」被认成 BF/Zt 等时：短前缀 + 时刻 */
    private val GARBLED_TIME = Regex(
        """^[\p{L}\p{N}]{0,4}\s*\d{1,2}\s*[:：]\s*\d{2}$""",
    )

    private val BOTTOM_TAB_LABELS = setOf(
        "微信", "WeChat", "wechat", "通讯录", "发现", "我",
        "搜索", "search", "相册", "拍摄", "按住说话",
    )

    private const val TAG = "JoyOcr"
    private val GROUP_TITLE_BADGE = Regex("""\(\d+\)\s*$""")

    private val UI_NOISE = setOf(
        "微信", "WeChat", "wechat", "搜索", "search",
        "通讯录", "发现", "详情", "···", "...", "…",
        "切换到键盘", "表情", "更多功能",
    )
}
