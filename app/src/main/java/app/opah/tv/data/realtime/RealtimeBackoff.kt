package app.opah.tv.data.realtime

import kotlin.math.pow

data class RealtimeBackoffPolicy(
    val baseDelayMillis: Long = 1_000,
    val maximumDelayMillis: Long = 60_000,
) {
    init {
        require(baseDelayMillis > 0)
        require(maximumDelayMillis >= baseDelayMillis)
    }
}

/** Pure capped exponential full-jitter calculation. */
fun calculateRealtimeBackoffMillis(
    consecutiveFailure: Int,
    jitterUnit: Double,
    policy: RealtimeBackoffPolicy = RealtimeBackoffPolicy(),
): Long {
    val safeFailure = consecutiveFailure.coerceAtLeast(1)
    val exponent = (safeFailure - 1).coerceAtMost(MAX_BACKOFF_EXPONENT)
    val exponential = policy.baseDelayMillis.toDouble() * 2.0.pow(exponent)
    val cap = exponential.coerceAtMost(policy.maximumDelayMillis.toDouble()).toLong()
    val boundedJitter = when {
        jitterUnit.isNaN() -> 0.0
        else -> jitterUnit.coerceIn(0.0, 1.0)
    }
    return (cap.toDouble() * boundedJitter).toLong().coerceIn(0L, cap)
}

private const val MAX_BACKOFF_EXPONENT = 62
