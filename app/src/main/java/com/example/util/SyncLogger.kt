package com.example.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SyncLogger {
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val allowedTags = setOf("WEBDAV", "BACKUP", "SYNC", "RESTORE", "LOCAL_BACKUP")

    fun log(tag: String, message: String) {
        val uppercaseTag = tag.uppercase()
        // Only log synchronization and backup related entries as requested by user
        if (!allowedTags.contains(uppercaseTag) && !uppercaseTag.contains("SYNC") && !uppercaseTag.contains("BACKUP")) {
            return
        }
        val time = dateFormat.format(Date())
        val entry = "[$time] [$uppercaseTag] $message"
        val current = _logs.value.toMutableList()
        current.add(entry)
        if (current.size > 200) {
            current.removeAt(0)
        }
        _logs.value = current
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun getAllLogsText(): String {
        return _logs.value.joinToString("\n")
    }
}
