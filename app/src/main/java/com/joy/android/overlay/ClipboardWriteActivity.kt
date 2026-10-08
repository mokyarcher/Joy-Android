package com.joy.android.overlay

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ColorOS 等会拦截后台写剪贴板；用短暂前台透明 Activity 写入。
 */
class ClipboardWriteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        try {
            if (text.isNotEmpty()) {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("joy_reply", text))
                Log.i(TAG, "clipboard written in foreground, len=${text.length}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "clipboard write failed: ${e.message}")
        } finally {
            pending?.complete(Unit)
            pending = null
            finish()
        }
    }

    companion object {
        private const val TAG = "JoyFill"
        const val EXTRA_TEXT = "text"

        @Volatile
        private var pending: CompletableDeferred<Unit>? = null

        suspend fun writeForeground(context: Context, text: String) {
            val deferred = CompletableDeferred<Unit>()
            pending = deferred
            context.applicationContext.startActivity(
                Intent(context.applicationContext, ClipboardWriteActivity::class.java)
                    .putExtra(EXTRA_TEXT, text)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                    ),
            )
            withTimeoutOrNull(3_000L) { deferred.await() }
            if (pending === deferred) pending = null
        }
    }
}
