package com.example.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object ClipboardAutoClearManager {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null

    fun onCopied(context: Context, copiedText: String) {
        val prefs = context.applicationContext.getSharedPreferences("cartino_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("clipboard_auto_clear_enabled", true)
        val seconds = prefs.getInt("clipboard_auto_clear_seconds", 30)
        job?.cancel()
        if (!enabled || seconds <= 0 || copiedText.isBlank()) return
        job = scope.launch {
            delay(seconds * 1000L)
            runCatching {
                val clipboard = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (current == copiedText) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        clipboard.clearPrimaryClip()
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                }
            }
        }
    }
}
