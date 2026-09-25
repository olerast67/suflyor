package com.olerast.suflyor.diag

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory diagnostic journal shown in the app; everything the test run needs to report lands here. */
object DiagLog {
    private const val MAX_LINES = 600
    private val lines = ArrayDeque<String>()
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var notifyPosted = false

    fun i(message: String) = add(message)

    fun e(message: String, t: Throwable? = null) =
        add("ОШИБКА: $message" + (t?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    @Synchronized
    private fun add(message: String) {
        val line = "${time.format(Date())}  $message"
        Log.i("Suflyor", line)
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        if (!notifyPosted) {
            notifyPosted = true
            main.postDelayed({
                synchronized(this) { notifyPosted = false }
                listeners.toList().forEach { it() }
            }, 200)
        }
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        main.post { listeners.toList().forEach { it() } }
    }

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }
}
