package com.example.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    data class LogEntry(
        val timestamp: String,
        val level: String,
        val tag: String,
        val message: String,
        val exceptionMessage: String? = null
    )

    fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
        addEntry("DEBUG", tag, message)
    }

    fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
        addEntry("INFO", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        android.util.Log.w(tag, message, throwable)
        addEntry("WARN", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        android.util.Log.e(tag, message, throwable)
        addEntry("ERROR", tag, message, throwable)
    }

    private fun addEntry(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val exceptionMessage = throwable?.let { "${it.javaClass.simpleName}: ${it.message}\n${it.stackTrace.take(3).joinToString("\n")}" }
        val entry = LogEntry(time, level, tag, message, exceptionMessage)
        val currentList = _logs.value.toMutableList()
        currentList.add(0, entry)
        if (currentList.size > 50) {
            currentList.removeAt(currentList.size - 1)
        }
        _logs.value = currentList
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
