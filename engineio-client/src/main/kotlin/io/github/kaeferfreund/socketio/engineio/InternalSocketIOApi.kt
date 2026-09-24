package io.github.kaeferfreund.socketio.engineio

/**
 * Marks declarations that are public only so the library's own modules can
 * share them. They may change in any release without notice.
 */
@RequiresOptIn(
    message = "Internal socket.io-client-kotlin API; it may change without notice.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class InternalSocketIOApi
