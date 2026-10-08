package com.joy.android.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 无障碍截屏优先；手动模式下可申请 MediaProjection 拍一帧。
 * 注意：录屏 Intent 只能 getMediaProjection 一次，用完即清。
 */
object OverlayScreenCapture {
    @Volatile
    private var projectionResultCode: Int = 0

    @Volatile
    private var projectionData: Intent? = null

    @Volatile
    var pendingBitmap: CompletableDeferred<Bitmap?>? = null

    fun hasProjectionPermission(): Boolean =
        projectionData != null && projectionResultCode != 0

    fun storeProjectionPermission(resultCode: Int, data: Intent) {
        projectionResultCode = resultCode
        projectionData = Intent(data)
    }

    private fun clearProjectionPermission() {
        projectionResultCode = 0
        projectionData = null
    }

    /**
     * @param allowAskPermission 为 false 时（自动/智驾轮询）不弹录屏授权页，避免反复拉起 Activity 闪退。
     */
    suspend fun capture(context: Context, allowAskPermission: Boolean = true): Bitmap {
        val a11y = JoyAccessibilityService.instance
        if (a11y != null) {
            runCatching { a11y.captureScreenshot() }.getOrNull()?.let { return it }
        }
        if (hasProjectionPermission()) {
            runCatching {
                captureWithStoredProjection(context.applicationContext)
            }.getOrNull()?.let { return it }
            clearProjectionPermission()
        }
        if (!allowAskPermission) {
            throw IllegalStateException("自动/智驾请先开启 Joy 无障碍以截屏")
        }
        return requestProjectionAndCapture(context.applicationContext)
            ?: throw IllegalStateException(
                "无法截屏：请开启 Joy 无障碍（推荐），或允许屏幕录制权限",
            )
    }

    private suspend fun requestProjectionAndCapture(app: Context): Bitmap? {
        // 已有等待中的请求则复用，避免连点开多个透明页
        pendingBitmap?.let { existing ->
            if (!existing.isCompleted) {
                return withTimeoutOrNull(45_000L) { existing.await() }
            }
        }
        val deferred = CompletableDeferred<Bitmap?>()
        pendingBitmap = deferred
        try {
            app.startActivity(
                Intent(app, OverlayProjectionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        } catch (e: Exception) {
            pendingBitmap = null
            throw IllegalStateException("无法打开录屏授权：${e.message}")
        }
        return withTimeoutOrNull(45_000L) { deferred.await() }.also {
            if (pendingBitmap === deferred) pendingBitmap = null
        }
    }

    private suspend fun captureWithStoredProjection(app: Context): Bitmap? {
        val code = projectionResultCode
        val data = projectionData ?: return null
        clearProjectionPermission()
        return withContext(Dispatchers.IO) {
            val mgr = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = runCatching { mgr.getMediaProjection(code, data) }.getOrNull()
                ?: return@withContext null
            captureOneFrame(app, projection)
        }
    }

    internal suspend fun captureOneFrame(app: Context, projection: MediaProjection): Bitmap? =
        withContext(Dispatchers.Main) {
            val metrics = DisplayMetrics()
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            val density = metrics.densityDpi
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            var display: VirtualDisplay? = null
            val deferred = CompletableDeferred<Bitmap?>()
            val handler = Handler(Looper.getMainLooper())
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    projection.registerCallback(
                        object : MediaProjection.Callback() {
                            override fun onStop() = Unit
                        },
                        handler,
                    )
                }
                display = projection.createVirtualDisplay(
                    "joy-capture",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    handler,
                )
                reader.setOnImageAvailableListener({
                    if (deferred.isCompleted) return@setOnImageAvailableListener
                    val image = runCatching { reader.acquireLatestImage() }.getOrNull()
                        ?: return@setOnImageAvailableListener
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer.duplicate()
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * width
                        val bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride.coerceAtLeast(1),
                            height,
                            Bitmap.Config.ARGB_8888,
                        )
                        buffer.rewind()
                        bitmap.copyPixelsFromBuffer(buffer)
                        val cropped = if (bitmap.width == width) {
                            bitmap
                        } else {
                            Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
                                if (it !== bitmap) bitmap.recycle()
                            }
                        }
                        deferred.complete(cropped)
                    } catch (_: Exception) {
                        deferred.complete(null)
                    } finally {
                        image.close()
                    }
                }, handler)
                withTimeoutOrNull(5_000L) { deferred.await() }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { display?.release() }
                runCatching { reader.close() }
                runCatching { projection.stop() }
            }
        }
}

/**
 * 一次性申请 MediaProjection；授权后拍一帧回传 [OverlayScreenCapture.pendingBitmap]。
 */
class OverlayProjectionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ)
        } catch (e: Exception) {
            OverlayScreenCapture.pendingBitmap?.complete(null)
            OverlayScreenCapture.pendingBitmap = null
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val pending = OverlayScreenCapture.pendingBitmap
        if (requestCode != REQ || resultCode != RESULT_OK || data == null) {
            pending?.complete(null)
            OverlayScreenCapture.pendingBitmap = null
            finish()
            return
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = runCatching { mgr.getMediaProjection(resultCode, data) }.getOrNull()
        if (projection == null) {
            pending?.complete(null)
            OverlayScreenCapture.pendingBitmap = null
            finish()
            return
        }
        // 本次 Intent 已消费，不要再 store 复用（二次 getMediaProjection 会崩）
        Thread {
            try {
                val bmp = runBlocking {
                    OverlayScreenCapture.captureOneFrame(applicationContext, projection)
                }
                pending?.complete(bmp)
            } catch (_: Exception) {
                pending?.complete(null)
            } finally {
                OverlayScreenCapture.pendingBitmap = null
                runOnUiThread { finish() }
            }
        }.start()
    }

    companion object {
        private const val REQ = 0xC001
    }
}
