package com.hermes.android.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last uncaught exception so the next launch can show it.
 *
 * A debug build that dies on a phone leaves nothing behind: the trace lives in
 * `logcat` for a few seconds, and without a USB cable the only report available
 * is "闪退". This writes the trace into app storage, and [com.hermes.android.MainActivity]
 * puts it on screen instead of the chat UI until it is dismissed.
 */
object CrashLog {
    private const val FILE_NAME = "last-crash.txt"

    /** Chains onto whatever handler is installed, so the process still dies normally. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { sink ->
            PrintWriter(sink).use { error.printStackTrace(it) }
        }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val body = buildString {
            appendLine("时间: $stamp")
            appendLine("线程: ${thread.name}")
            appendLine()
            append(trace)
        }
        runCatching { File(context.filesDir, FILE_NAME).writeText(body) }
    }

    /** The stored trace, or null when the last run ended cleanly. */
    fun pending(context: Context): String? =
        runCatching { File(context.filesDir, FILE_NAME) }
            .getOrNull()
            ?.takeIf { it.isFile }
            ?.readText()
            ?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
