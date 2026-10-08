package com.joy.android.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object OverlayController {
    @Volatile
    var isRunning: Boolean = false
        internal set

    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun needsNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return !NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun start(context: Context) {
        val app = context.applicationContext
        if (!canDrawOverlays(app)) return
        ContextCompat.startForegroundService(
            app,
            Intent(app, JoyOverlayService::class.java).setAction(JoyOverlayService.ACTION_START),
        )
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        app.stopService(Intent(app, JoyOverlayService::class.java))
    }
}
