package app.opah.tv.playback.media3

import app.opah.tv.playback.compatibility.PlaybackSnapshotCancellationOutcome
import app.opah.tv.playback.compatibility.PlaybackSnapshotPresentationId
import app.opah.tv.playback.compatibility.PlaybackSnapshotRenderOutcome
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import kotlinx.coroutines.CompletableDeferred

/**
 * Opaque process-local capability for cancelling the presenter that created a snapshot.
 * Implementations must be bounded, non-blocking, cancellation-cooperative, and idempotent for an
 * exact ID. Acknowledgment is valid only after the presentation is no longer visible.
 */
internal fun interface Media3SnapshotCancellationOwner {
    suspend fun cancel(
        presentationId: PlaybackSnapshotPresentationId,
    ): PlaybackSnapshotCancellationOutcome
}

/** Bounded process-local ownership and tombstones for snapshot presentation side effects. */
internal class Media3SnapshotOwnership(
    private val maximumActivePresentations: Int = DEFAULT_MAX_ACTIVE_SNAPSHOTS,
    private val maximumRecentTombstones: Int = DEFAULT_MAX_SNAPSHOT_TOMBSTONES,
) {
    init {
        require(maximumActivePresentations > 0)
        require(maximumRecentTombstones > maximumActivePresentations) {
            "Snapshot tombstones must cover active presentations plus cancellation churn"
        }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<PlaybackSnapshotPresentationId, Entry>()
    private val tombstones = LinkedHashSet<PlaybackSnapshotPresentationId>()

    /** Called synchronously before the presenter or any other suspending boundary. */
    fun reserve(
        presentationId: PlaybackSnapshotPresentationId,
        cancellationOwner: Media3SnapshotCancellationOwner,
    ): Media3SnapshotReservationResult = synchronized(lock) {
            when {
                presentationId in entries -> Media3SnapshotReservationResult.REJECTED_DUPLICATE
                presentationId in tombstones -> Media3SnapshotReservationResult.REJECTED_TOMBSTONED
                entries.size >= maximumActivePresentations ->
                    Media3SnapshotReservationResult.REJECTED_CAPACITY

                else -> {
                    entries[presentationId] = Entry(
                        phase = Media3SnapshotPhase.PRESENTING,
                        cancellationOwner = cancellationOwner,
                    )
                    Media3SnapshotReservationResult.RESERVED
                }
            }
        }

    fun completePresentation(
        presentationId: PlaybackSnapshotPresentationId,
        outcome: PlaybackSnapshotRenderOutcome,
    ): Media3SnapshotPresentationDisposition {
        var cancellationToAcknowledge: CompletableDeferred<PlaybackSnapshotCancellationOutcome>? = null
        val disposition = synchronized(lock) {
            val entry = entries[presentationId]
                ?: return@synchronized Media3SnapshotPresentationDisposition.CANCELLED
            when (entry.phase) {
                Media3SnapshotPhase.PRESENTING -> {
                    if (outcome.retainsPresentationOwnership) {
                        entry.phase = Media3SnapshotPhase.RETAINED
                    } else {
                        entries.remove(presentationId)
                    }
                    if (outcome is PlaybackSnapshotRenderOutcome.CancellationRequired) {
                        Media3SnapshotPresentationDisposition.CANCELLATION_REQUIRED
                    } else {
                        Media3SnapshotPresentationDisposition.ACCEPT
                    }
                }

                Media3SnapshotPhase.CANCELLING,
                Media3SnapshotPhase.CANCEL_FAILED,
                -> {
                    if (outcome.retainsPresentationOwnership) {
                        Media3SnapshotPresentationDisposition.CANCELLATION_REQUIRED
                    } else {
                        entries.remove(presentationId)
                        cancellationToAcknowledge = entry.cancellation
                        Media3SnapshotPresentationDisposition.CANCELLED
                    }
                }

                Media3SnapshotPhase.RETAINED -> Media3SnapshotPresentationDisposition.ACCEPT
            }
        }
        cancellationToAcknowledge?.complete(PlaybackSnapshotCancellationOutcome.ACKNOWLEDGED)
        return disposition
    }

    /** Tombstones the exact ID synchronously before returning any plan that can suspend. */
    fun beginCancellation(
        presentationId: PlaybackSnapshotPresentationId,
    ): Media3SnapshotCancellationPlan {
        val plan = synchronized(lock) {
            registerTombstoneLocked(presentationId)
            val entry = entries[presentationId]
                ?: return@synchronized Media3SnapshotCancellationPlan.AlreadyAcknowledged
            when (entry.phase) {
                Media3SnapshotPhase.CANCELLING -> Media3SnapshotCancellationPlan.Await(
                    checkNotNull(entry.cancellation),
                )

                Media3SnapshotPhase.PRESENTING,
                Media3SnapshotPhase.RETAINED,
                Media3SnapshotPhase.CANCEL_FAILED,
                -> {
                    val completion = CompletableDeferred<PlaybackSnapshotCancellationOutcome>()
                    entry.phase = Media3SnapshotPhase.CANCELLING
                    entry.cancellation = completion
                    Media3SnapshotCancellationPlan.Delegate(
                        completion = completion,
                        cancellationOwner = entry.cancellationOwner,
                    )
                }
            }
        }
        return plan
    }

    fun completeCancellation(
        presentationId: PlaybackSnapshotPresentationId,
        delegation: Media3SnapshotCancellationPlan.Delegate,
        delegatedOutcome: PlaybackSnapshotCancellationOutcome,
    ): PlaybackSnapshotCancellationOutcome {
        var completionIsAuthoritative = false
        val authoritative = synchronized(lock) {
            val entry = entries[presentationId]
            if (entry == null) {
                completionIsAuthoritative = true
                PlaybackSnapshotCancellationOutcome.ACKNOWLEDGED
            } else if (
                entry.phase != Media3SnapshotPhase.CANCELLING ||
                entry.cancellation !== delegation.completion ||
                entry.cancellationOwner !== delegation.cancellationOwner
            ) {
                PlaybackSnapshotCancellationOutcome.FAILED
            } else if (delegatedOutcome == PlaybackSnapshotCancellationOutcome.ACKNOWLEDGED) {
                completionIsAuthoritative = true
                entries.remove(presentationId)
                PlaybackSnapshotCancellationOutcome.ACKNOWLEDGED
            } else {
                completionIsAuthoritative = true
                entry.phase = Media3SnapshotPhase.CANCEL_FAILED
                entry.cancellation = null
                PlaybackSnapshotCancellationOutcome.FAILED
            }
        }
        if (completionIsAuthoritative) delegation.completion.complete(authoritative)
        return authoritative
    }

    internal fun snapshotForTest(): Media3SnapshotOwnershipSnapshot = synchronized(lock) {
        Media3SnapshotOwnershipSnapshot(
            activeIds = entries.keys.toList(),
            phases = entries.mapValues { it.value.phase },
            tombstoneIds = tombstones.toList(),
        )
    }

    private fun registerTombstoneLocked(presentationId: PlaybackSnapshotPresentationId) {
        if (presentationId in tombstones) return
        if (tombstones.size >= maximumRecentTombstones) {
            val protectedIds = entries.keys
            val iterator = tombstones.iterator()
            while (iterator.hasNext()) {
                if (iterator.next() !in protectedIds) {
                    iterator.remove()
                    break
                }
            }
        }
        if (tombstones.size < maximumRecentTombstones) {
            tombstones += presentationId
        }
    }

    private class Entry(
        var phase: Media3SnapshotPhase,
        val cancellationOwner: Media3SnapshotCancellationOwner,
        var cancellation: CompletableDeferred<PlaybackSnapshotCancellationOutcome>? = null,
    )
}

internal enum class Media3SnapshotReservationResult {
    RESERVED,
    REJECTED_TOMBSTONED,
    REJECTED_DUPLICATE,
    REJECTED_CAPACITY,
}

internal enum class Media3SnapshotPresentationDisposition {
    ACCEPT,
    CANCELLED,
    CANCELLATION_REQUIRED,
}

internal sealed interface Media3SnapshotCancellationPlan {
    data object AlreadyAcknowledged : Media3SnapshotCancellationPlan

    data class Await(
        val completion: CompletableDeferred<PlaybackSnapshotCancellationOutcome>,
    ) : Media3SnapshotCancellationPlan

    data class Delegate(
        val completion: CompletableDeferred<PlaybackSnapshotCancellationOutcome>,
        val cancellationOwner: Media3SnapshotCancellationOwner,
    ) : Media3SnapshotCancellationPlan
}

internal enum class Media3SnapshotPhase {
    PRESENTING,
    RETAINED,
    CANCELLING,
    CANCEL_FAILED,
}

internal data class Media3SnapshotOwnershipSnapshot(
    val activeIds: List<PlaybackSnapshotPresentationId>,
    val phases: Map<PlaybackSnapshotPresentationId, Media3SnapshotPhase>,
    val tombstoneIds: List<PlaybackSnapshotPresentationId>,
)

private const val DEFAULT_MAX_ACTIVE_SNAPSHOTS = 32
private const val DEFAULT_MAX_SNAPSHOT_TOMBSTONES = 4_096

private val PlaybackSnapshotRenderOutcome.retainsPresentationOwnership: Boolean
    get() = this is PlaybackSnapshotRenderOutcome.Rendered ||
        this is PlaybackSnapshotRenderOutcome.CancellationRequired
