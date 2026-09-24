package io.github.kaeferfreund.socketio.android

import android.os.SystemClock
import android.util.Log
import androidx.tracing.Trace
import io.github.kaeferfreund.socketio.SocketTracer
import io.github.kaeferfreund.socketio.engineio.LogLevel
import io.github.kaeferfreund.socketio.engineio.SocketLogger
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit

/**
 * Monotonic time from `SystemClock.elapsedRealtimeNanos()`, which keeps
 * counting while the device sleeps. Heartbeat deadlines measured with it
 * expire during Doze exactly as they do on the server, so a connection that
 * silently died while sleeping is detected on the first emit after wake-up.
 * (`System.nanoTime()` stops in deep sleep.)
 */
public object ElapsedRealtimeTimeSource : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
    override fun read(): Long = SystemClock.elapsedRealtimeNanos()
}

/**
 * Logs to logcat under [tag], gated by `Log.isLoggable`: disabled levels cost
 * nothing. Enable debug output with `adb shell setprop log.tag.SocketIO DEBUG`.
 */
public class AndroidLogger(
    private val tag: String = "SocketIO",
) : SocketLogger {
    override fun isLoggable(level: LogLevel): Boolean = Log.isLoggable(tag, priority(level))

    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
        error: Throwable?,
    ) {
        val text = "[$tag] $message"
        when (level) {
            LogLevel.VERBOSE -> Log.v(this.tag, text, error)
            LogLevel.DEBUG -> Log.d(this.tag, text, error)
            LogLevel.INFO -> Log.i(this.tag, text, error)
            LogLevel.WARN -> Log.w(this.tag, text, error)
            LogLevel.ERROR -> Log.e(this.tag, text, error)
        }
    }

    private fun priority(level: LogLevel): Int =
        when (level) {
            LogLevel.VERBOSE -> Log.VERBOSE
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
}

/** Emits connect, upgrade and acknowledgement sections to Perfetto through `androidx.tracing`. */
public object AndroidTracer : SocketTracer {
    override fun beginAsyncSection(
        name: String,
        cookie: Int,
    ) {
        Trace.beginAsyncSection(name.take(MAX_SECTION_NAME), cookie)
    }

    override fun endAsyncSection(
        name: String,
        cookie: Int,
    ) {
        Trace.endAsyncSection(name.take(MAX_SECTION_NAME), cookie)
    }

    /** `Trace` section names are limited to 127 characters. */
    private const val MAX_SECTION_NAME = 127
}
