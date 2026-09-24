package io.github.kaeferfreund.socketio

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Exponential backoff, a port of `contrib/backo2.ts` of `socket.io-client`:
 * `min × 2^attempts`, randomly shortened or lengthened by up to `jitter` of
 * itself, capped at `max`.
 */
internal class Backoff(
    var min: Duration,
    var max: Duration,
    jitter: Double,
    private val random: Random,
    private val factor: Double = 2.0,
) {
    var jitter: Double = if (jitter > 0 && jitter <= 1) jitter else 0.0
        set(value) {
            field = value
        }

    var attempts: Int = 0
        private set

    fun duration(): Duration {
        var ms = min.inWholeMilliseconds.toDouble() * factor.pow(attempts++)
        if (jitter > 0) {
            val rand = random.nextDouble()
            val deviation = floor(rand * jitter * ms)
            ms = if ((floor(rand * 10).toInt() and 1) == 0) ms - deviation else ms + deviation
        }
        // `Math.min(ms, max) | 0` truncates toward zero to a 32-bit integer.
        val capped = min(ms, max.inWholeMilliseconds.toDouble())
        return if (capped >= Int.MAX_VALUE) Int.MAX_VALUE.milliseconds else capped.toLong().milliseconds
    }

    fun reset() {
        attempts = 0
    }
}
