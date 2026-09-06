package app.opah.tv.playback.media3

import app.opah.tv.playback.compatibility.PlaybackAttemptHandleToken
import app.opah.tv.playback.compatibility.PlaybackAttemptId
import app.opah.tv.playback.compatibility.PlaybackBackendReleaseOutcome
import java.util.LinkedHashSet
import kotlinx.coroutines.CompletableDeferred

/**
 * Small, platform-free state machine for the process Media3 slot.
 *
 * A reservation is installed before route redemption or player construction. Release marks the
 * attempt tombstoned before it asks an in-flight start to stop, so a late player can only be
 * discarded. Recent tombstones use a bounded insertion-order horizon; globally unique session IDs
 * make an evicted attempt non-replayable in normal operation without disabling long-running
 * Monitor/reconnect sessions. Only generation overflow is sticky and fail-closed.
 */
internal class Media3AttemptOwnership<Resource : Any>(
    private val maxTombstones: Int = DEFAULT_MAX_MEDIA3_ATTEMPT_TOMBSTONES,
) {
    init {
        require(maxTombstones > 0) { "Media3 tombstone capacity must be positive" }
    }

    private val lock = Any()
    private val tombstones = LinkedHashSet<PlaybackAttemptId>()
    private var saturated = false
    private var nextGeneration = 1L
    private var slot: Slot<Resource>? = null

    fun reserve(
        attemptId: PlaybackAttemptId,
        cancelStart: () -> Unit,
    ): Media3ReservationResult = synchronized(lock) {
        when {
            saturated -> Media3ReservationResult.RejectedSaturated
            attemptId in tombstones -> Media3ReservationResult.RejectedTombstoned
            slot != null -> Media3ReservationResult.RejectedOccupied
            nextGeneration == Long.MAX_VALUE -> {
                saturated = true
                Media3ReservationResult.RejectedSaturated
            }

            else -> {
                val reservation = Media3AttemptReservation(
                    attemptId = attemptId,
                    generation = nextGeneration++,
                    startSettled = CompletableDeferred(),
                )
                slot = Slot(
                    reservation = reservation,
                    phase = Media3OwnershipPhase.STARTING,
                    cancelStart = cancelStart,
                )
                Media3ReservationResult.Reserved(reservation)
            }
        }
    }

    /** Publishes a completely prepared player only if release has not already won the race. */
    fun publish(
        reservation: Media3AttemptReservation,
        resource: Resource,
        handle: PlaybackAttemptHandleToken,
    ): Media3PublishResult {
        val result = synchronized(lock) {
            val current = slot
            if (
                current == null ||
                current.reservation != reservation ||
                current.phase != Media3OwnershipPhase.STARTING ||
                current.releaseRequested ||
                reservation.attemptId in tombstones ||
                saturated
            ) {
                Media3PublishResult.DISCARD
            } else {
                current.phase = Media3OwnershipPhase.ACTIVE
                current.resource = resource
                current.handle = handle
                Media3PublishResult.PUBLISHED
            }
        }
        if (result == Media3PublishResult.PUBLISHED) {
            reservation.startSettled.complete(Unit)
        }
        return result
    }

    /** Completes a start which never published a player. Exact repeats are harmless. */
    fun abandon(reservation: Media3AttemptReservation) {
        synchronized(lock) {
            val current = slot
            if (
                current != null &&
                current.reservation == reservation &&
                current.phase == Media3OwnershipPhase.STARTING
            ) {
                slot = null
            }
        }
        reservation.startSettled.complete(Unit)
    }

    /**
     * Retains an unpublished player whose synchronous cleanup failed. This keeps the global slot
     * occupied and makes a subsequent exact release retry the same resource.
     */
    fun retainFailedDiscard(
        reservation: Media3AttemptReservation,
        resource: Resource,
        handle: PlaybackAttemptHandleToken,
    ) {
        synchronized(lock) {
            val current = slot
            if (
                current != null &&
                current.reservation == reservation &&
                current.phase == Media3OwnershipPhase.STARTING
            ) {
                current.phase = Media3OwnershipPhase.RELEASE_FAILED
                current.resource = resource
                current.handle = handle
                current.releaseRequested = true
                registerTombstoneLocked(reservation.attemptId)
            }
        }
        reservation.startSettled.complete(Unit)
    }

    /** Marks the attempt released before returning work that may touch the player. */
    fun beginRelease(attemptId: PlaybackAttemptId): Media3ReleasePlan<Resource> {
        var cancelStart: (() -> Unit)? = null
        val plan = synchronized(lock) {
            registerTombstoneLocked(attemptId)
            val current = slot
            if (current == null || current.reservation.attemptId != attemptId) {
                return@synchronized Media3ReleasePlan.AlreadyReleased
            }
            when (current.phase) {
                Media3OwnershipPhase.STARTING -> {
                    current.releaseRequested = true
                    cancelStart = current.cancelStart
                    Media3ReleasePlan.AwaitStart(current.reservation.startSettled)
                }

                Media3OwnershipPhase.ACTIVE,
                Media3OwnershipPhase.RELEASE_FAILED,
                -> {
                    val resource = checkNotNull(current.resource)
                    val completion = CompletableDeferred<PlaybackBackendReleaseOutcome>()
                    current.phase = Media3OwnershipPhase.RELEASING
                    current.releaseCompletion = completion
                    Media3ReleasePlan.Release(
                        reservation = current.reservation,
                        resource = resource,
                        completion = completion,
                    )
                }

                Media3OwnershipPhase.RELEASING -> Media3ReleasePlan.AwaitRelease(
                    checkNotNull(current.releaseCompletion),
                )
            }
        }
        // Never invoke foreign cancellation code while holding the ownership monitor.
        try {
            cancelStart?.invoke()
        } catch (_: RuntimeException) {
            // The tombstone and release request remain authoritative even if cancellation fails.
        }
        return plan
    }

    fun completeRelease(
        reservation: Media3AttemptReservation,
        completion: CompletableDeferred<PlaybackBackendReleaseOutcome>,
        outcome: PlaybackBackendReleaseOutcome,
    ) {
        synchronized(lock) {
            val current = slot
            if (
                current != null &&
                current.reservation == reservation &&
                current.phase == Media3OwnershipPhase.RELEASING &&
                current.releaseCompletion === completion
            ) {
                if (outcome == PlaybackBackendReleaseOutcome.RELEASED) {
                    slot = null
                } else {
                    current.phase = Media3OwnershipPhase.RELEASE_FAILED
                    current.releaseCompletion = null
                }
            }
        }
        completion.complete(outcome)
    }

    internal fun snapshotForTest(): Media3OwnershipSnapshot = synchronized(lock) {
        Media3OwnershipSnapshot(
            attemptId = slot?.reservation?.attemptId,
            phase = slot?.phase,
            tombstoneCount = tombstones.size,
            saturated = saturated,
        )
    }

    private fun registerTombstoneLocked(attemptId: PlaybackAttemptId) {
        if (attemptId in tombstones || saturated) return
        if (tombstones.size >= maxTombstones) {
            val protectedAttemptId = slot?.reservation?.attemptId
            val oldest = tombstones.iterator()
            var removed = false
            while (oldest.hasNext()) {
                if (oldest.next() != protectedAttemptId) {
                    oldest.remove()
                    removed = true
                    break
                }
            }
            if (!removed) return
        }
        tombstones += attemptId
    }

    private class Slot<Resource : Any>(
        val reservation: Media3AttemptReservation,
        var phase: Media3OwnershipPhase,
        val cancelStart: () -> Unit,
        var releaseRequested: Boolean = false,
        var resource: Resource? = null,
        var handle: PlaybackAttemptHandleToken? = null,
        var releaseCompletion: CompletableDeferred<PlaybackBackendReleaseOutcome>? = null,
    )
}

internal data class Media3AttemptReservation(
    val attemptId: PlaybackAttemptId,
    val generation: Long,
    internal val startSettled: CompletableDeferred<Unit>,
)

internal sealed interface Media3ReservationResult {
    data class Reserved(val reservation: Media3AttemptReservation) : Media3ReservationResult

    data object RejectedOccupied : Media3ReservationResult

    data object RejectedTombstoned : Media3ReservationResult

    data object RejectedSaturated : Media3ReservationResult
}

internal enum class Media3PublishResult {
    PUBLISHED,
    DISCARD,
}

internal sealed interface Media3ReleasePlan<out Resource : Any> {
    data object AlreadyReleased : Media3ReleasePlan<Nothing>

    data class AwaitStart(
        val settlement: CompletableDeferred<Unit>,
    ) : Media3ReleasePlan<Nothing>

    data class AwaitRelease(
        val completion: CompletableDeferred<PlaybackBackendReleaseOutcome>,
    ) : Media3ReleasePlan<Nothing>

    data class Release<Resource : Any>(
        val reservation: Media3AttemptReservation,
        val resource: Resource,
        val completion: CompletableDeferred<PlaybackBackendReleaseOutcome>,
    ) : Media3ReleasePlan<Resource>
}

internal enum class Media3OwnershipPhase {
    STARTING,
    ACTIVE,
    RELEASING,
    RELEASE_FAILED,
}

internal data class Media3OwnershipSnapshot(
    val attemptId: PlaybackAttemptId?,
    val phase: Media3OwnershipPhase?,
    val tombstoneCount: Int,
    val saturated: Boolean,
)

private const val DEFAULT_MAX_MEDIA3_ATTEMPT_TOMBSTONES = 4_096
