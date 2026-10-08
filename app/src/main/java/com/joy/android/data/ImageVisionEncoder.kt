package com.joy.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 把相册图压成适合 Vision API 的 data URL（JPEG base64）。
 * 长边与质量刻意偏小：微信气泡字仍可读，上传/推理明显更快。
 */
object ImageVisionEncoder {
    /** 聊天气泡字号大，960 足够；再大主要拖上传与首 token */
    private const val MAX_LONG_EDGE = 960
    private const val JPEG_QUALITY = 72

    fun toDataUrl(context: Context, uri: Uri): String {
        val original = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: throw IllegalStateException("无法读取图片")

        return toDataUrl(original, recycleOriginal = true)
    }

    fun toDataUrl(bitmap: Bitmap, recycleOriginal: Boolean = false): String {
        val scaled = scaleDown(bitmap, MAX_LONG_EDGE)
        if (scaled !== bitmap && recycleOriginal) {
            bitmap.recycle()
        }

        val bytes = ByteArrayOutputStream().use { out ->
            val ok = scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (!ok) {
                if (scaled !== bitmap) scaled.recycle()
                throw IllegalStateException("图片压缩失败")
            }
            out.toByteArray()
        }
        if (scaled !== bitmap) {
            scaled.recycle()
        } else if (recycleOriginal) {
            bitmap.recycle()
        }

        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    private fun scaleDown(src: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= maxLongEdge) return src
        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
