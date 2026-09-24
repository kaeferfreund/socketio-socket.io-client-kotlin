package io.github.kaeferfreund.socketio.engineio

/** Log levels, lowest first. */
public enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

/**
 * Logging hook. The core logs nothing unless a logger is configured; the
 * Android module supplies one backed by `android.util.Log`.
 *
 * Messages are built lazily: [isLoggable] is checked first, so disabled
 * logging costs no string formatting.
 */
public interface SocketLogger {
    /** Whether messages of [level] should be produced at all. */
    public fun isLoggable(level: LogLevel): Boolean

    /** Writes one message. [tag] names the component, for example `"engine"`. */
    public fun log(
        level: LogLevel,
        tag: String,
        message: String,
        error: Throwable? = null,
    )

    public companion object {
        /** Discards everything. */
        public val NONE: SocketLogger =
            object : SocketLogger {
                override fun isLoggable(level: LogLevel): Boolean = false

                override fun log(
                    level: LogLevel,
                    tag: String,
                    message: String,
                    error: Throwable?,
                ) = Unit
            }

        /** Prints to standard error; useful in JVM tools and tests. */
        public fun console(minimum: LogLevel = LogLevel.DEBUG): SocketLogger =
            object : SocketLogger {
                override fun isLoggable(level: LogLevel): Boolean = level >= minimum

                override fun log(
                    level: LogLevel,
                    tag: String,
                    message: String,
                    error: Throwable?,
                ) {
                    System.err.println("[socket.io:$tag] $level $message" + (error?.let { " ($it)" } ?: ""))
                }
            }
    }
}

/** Logs [message] lazily. */
@InternalSocketIOApi
public inline fun SocketLogger.debug(
    tag: String,
    message: () -> String,
) {
    if (isLoggable(LogLevel.DEBUG)) log(LogLevel.DEBUG, tag, message())
}
