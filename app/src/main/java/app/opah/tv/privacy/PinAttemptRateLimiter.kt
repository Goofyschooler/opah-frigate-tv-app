package app.opah.tv.privacy

import kotlin.math.min

internal data class PersistedPinAttemptState(
    val failedAttempts: Int = 0,
    val nextAllowedWallClockMillis: Long = 0L,
)

internal sealed interface PinAttemptAdmission {
    data object Allowed : PinAttemptAdmission
    data class Cooldown(val remainingMillis: Long) : PinAttemptAdmission
    data object CorruptState : PinAttemptAdmission
}

/**
 * Process-local monotonic reinforcement for an encrypted persistent wall-clock cooldown.
 * Unlock state is intentionally outside this class and never persisted.
 */
internal class PinAttemptRateLimiter(
    private val policy: PinAttemptRatePolicy = PinAttemptRatePolicy(),
) {
    private val lock = Any()
    private var monotonicDeadlineMillis: Long = 0L

    fun restore(
        persisted: PersistedPinAttemptState,
        nowWallClockMillis: Long,
        nowMonotonicMillis: Long,
    ): PersistedPinAttemptState = synchronized(lock) {
        val safe = policy.sanitize(persisted, nowWallClockMillis)
        val remaining = (safe.nextAllowedWallClockMillis - nowWallClockMillis)
            .coerceIn(0L, policy.maximumCooldownMillis)
        monotonicDeadlineMillis = policy.saturatingAdd(nowMonotonicMillis, remaining)
        safe
    }

    fun admission(
        persisted: PersistedPinAttemptState,
        nowWallClockMillis: Long,
        nowMonotonicMillis: Long,
    ): PinAttemptAdmission = synchronized(lock) {
        if (!policy.isStructurallyValid(persisted) || nowWallClockMillis < 0L || nowMonotonicMillis < 0L) {
            return@synchronized PinAttemptAdmission.CorruptState
        }
        val wallRemaining = (persisted.nextAllowedWallClockMillis - nowWallClockMillis)
            .coerceIn(0L, policy.maximumCooldownMillis)
        val monotonicRemaining = (monotonicDeadlineMillis - nowMonotonicMillis)
            .coerceIn(0L, policy.maximumCooldownMillis)
        val remaining = maxOf(wallRemaining, monotonicRemaining)
        if (remaining > 0L) PinAttemptAdmission.Cooldown(remaining) else PinAttemptAdmission.Allowed
    }

    fun recordFailure(
        persisted: PersistedPinAttemptState,
        nowWallClockMillis: Long,
        nowMonotonicMillis: Long,
    ): PersistedPinAttemptState = synchronized(lock) {
        val safe = policy.sanitize(persisted, nowWallClockMillis)
        val failures = min(safe.failedAttempts + 1, policy.maximumFailureCount)
        val delay = policy.delayFor(failures)
        monotonicDeadlineMillis = policy.saturatingAdd(nowMonotonicMillis, delay)
        PersistedPinAttemptState(
            failedAttempts = failures,
            nextAllowedWallClockMillis = policy.saturatingAdd(nowWallClockMillis, delay),
        )
    }

    fun recordSuccess(): PersistedPinAttemptState = synchronized(lock) {
        monotonicDeadlineMillis = 0L
        PersistedPinAttemptState()
    }

    fun lockNow() = synchronized(lock) {
        // Unlock proof owners clear their in-memory proof. Cooldown evidence is intentionally kept.
        Unit
    }
}

internal data class PinAttemptRatePolicy(
    val earlyFailureDelayMillis: Long = 500L,
    val exponentialBaseDelayMillis: Long = 2_000L,
    val exponentialStartsAtFailure: Int = 3,
    val maximumCooldownMillis: Long = 15 * 60_000L,
    val maximumFailureCount: Int = 32,
) {
    init {
        require(earlyFailureDelayMillis > 0L)
        require(exponentialBaseDelayMillis >= earlyFailureDelayMillis)
        require(exponentialStartsAtFailure >= 2)
        require(maximumCooldownMillis >= exponentialBaseDelayMillis)
        require(maximumFailureCount >= exponentialStartsAtFailure)
    }

    fun delayFor(failedAttempts: Int): Long {
        if (failedAttempts < exponentialStartsAtFailure) return earlyFailureDelayMillis
        val shift = (failedAttempts - exponentialStartsAtFailure).coerceIn(0, 30)
        val multiplier = 1L shl shift
        return if (exponentialBaseDelayMillis > maximumCooldownMillis / multiplier) {
            maximumCooldownMillis
        } else {
            min(maximumCooldownMillis, exponentialBaseDelayMillis * multiplier)
        }
    }

    fun isStructurallyValid(state: PersistedPinAttemptState): Boolean =
        state.failedAttempts in 0..maximumFailureCount && state.nextAllowedWallClockMillis >= 0L

    fun sanitize(
        state: PersistedPinAttemptState,
        nowWallClockMillis: Long,
    ): PersistedPinAttemptState {
        val safeNow = nowWallClockMillis.coerceAtLeast(0L)
        val failures = state.failedAttempts.coerceIn(0, maximumFailureCount)
        val deadline = state.nextAllowedWallClockMillis.coerceIn(
            0L,
            saturatingAdd(safeNow, maximumCooldownMillis),
        )
        return PersistedPinAttemptState(failures, deadline)
    }

    fun saturatingAdd(first: Long, second: Long): Long = when {
        first < 0L || second < 0L -> 0L
        first > Long.MAX_VALUE - second -> Long.MAX_VALUE
        else -> first + second
    }
}
