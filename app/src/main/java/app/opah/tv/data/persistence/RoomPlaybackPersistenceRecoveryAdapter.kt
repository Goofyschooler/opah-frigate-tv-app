package app.opah.tv.data.persistence

import androidx.room.withTransaction
import app.opah.tv.playback.compatibility.PersistedPlaybackPersistenceRecoveryObligation
import app.opah.tv.playback.compatibility.PlaybackPersistenceRecoveryHandlingOutcome
import app.opah.tv.playback.compatibility.PlaybackPersistenceRecoveryPort
import app.opah.tv.playback.compatibility.PlaybackPersistenceRecoveryRegistrationOutcome as PortRegistrationOutcome
import app.opah.tv.playback.compatibility.PlaybackPersistenceRecoveryStartupEntry
import app.opah.tv.playback.compatibility.PlaybackPersistenceRecoveryToken
import app.opah.tv.playback.compatibility.UnresolvedStrategyPersistence

/**
 * Maps the Room outbox onto the compatibility boundary and atomically conjoins exact recovery
 * handling with exact outbox deletion.
 */
class RoomPlaybackPersistenceRecoveryAdapter(
    private val database: OpahDatabase,
    private val outbox: RoomPlaybackPersistenceRecoveryOutbox =
        RoomPlaybackPersistenceRecoveryOutbox(database),
) : PlaybackPersistenceRecoveryPort {
    override suspend fun register(
        request: UnresolvedStrategyPersistence,
    ): PortRegistrationOutcome = when (val outcome = outbox.register(request)) {
        is PlaybackPersistenceRecoveryRegistrationOutcome.Registered ->
            PortRegistrationOutcome.Registered(outcome.obligation.toPortToken())
        is PlaybackPersistenceRecoveryRegistrationOutcome.ExactReplay ->
            PortRegistrationOutcome.ExactReplay(outcome.obligation.toPortToken())
        is PlaybackPersistenceRecoveryRegistrationOutcome.Conflict ->
            PortRegistrationOutcome.Conflict
        is PlaybackPersistenceRecoveryRegistrationOutcome.Corrupt ->
            PortRegistrationOutcome.Corrupt
        is PlaybackPersistenceRecoveryRegistrationOutcome.CapacityReached ->
            PortRegistrationOutcome.CapacityReached
    }

    override suspend fun startupBatch(
        afterRowId: Long,
        limit: Int,
    ): List<PlaybackPersistenceRecoveryStartupEntry> =
        outbox.startupBatch(afterRowId = afterRowId, limit = limit).map { inspection ->
            when (inspection) {
                is PlaybackPersistenceRecoveryInspection.Pending ->
                    PlaybackPersistenceRecoveryStartupEntry.Pending(
                        inspection.obligation.toPortObligation(),
                    )
                is PlaybackPersistenceRecoveryInspection.Conflict ->
                    PlaybackPersistenceRecoveryStartupEntry.Conflict(inspection.rowId)
                is PlaybackPersistenceRecoveryInspection.Corrupt ->
                    PlaybackPersistenceRecoveryStartupEntry.Corrupt(inspection.rowId)
            }
        }

    override suspend fun handleAndResolveExact(
        token: PlaybackPersistenceRecoveryToken,
        handler: suspend (
            PersistedPlaybackPersistenceRecoveryObligation,
        ) -> PlaybackPersistenceRecoveryHandlingOutcome,
    ): PlaybackPersistenceRecoveryHandlingOutcome = try {
        database.withTransaction {
            val inspection = outbox.inspectAttemptKey(token.attemptKey)
            val pending = inspection as? PlaybackPersistenceRecoveryInspection.Pending
                ?: return@withTransaction PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
            if (pending.obligation.toPortToken() != token) {
                return@withTransaction PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
            }
            if (hasGlobalBlocker()) {
                return@withTransaction PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
            }

            when (val handled = handler(pending.obligation.toPortObligation())) {
                PlaybackPersistenceRecoveryHandlingOutcome.CONCLUSIVELY_HANDLED -> {
                    when (outbox.resolveExact(token.toRoomToken())) {
                        PlaybackPersistenceRecoveryResolutionOutcome.Resolved -> handled
                        PlaybackPersistenceRecoveryResolutionOutcome.AlreadyAbsent,
                        is PlaybackPersistenceRecoveryResolutionOutcome.Blocked,
                        is PlaybackPersistenceRecoveryResolutionOutcome.TokenMismatch,
                        -> throw RecoveryTransactionRollback(
                            PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED,
                        )
                    }
                }
                PlaybackPersistenceRecoveryHandlingOutcome.UNAVAILABLE,
                PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED,
                -> throw RecoveryTransactionRollback(handled)
            }
        }
    } catch (rollback: RecoveryTransactionRollback) {
        rollback.outcome
    }

    private suspend fun hasGlobalBlocker(): Boolean {
        if (
            database.localSchemaMetadata()
                .value(PLAYBACK_PERSISTENCE_RECOVERY_SATURATION_METADATA_KEY) != null ||
            database.localSchemaMetadata()
                .value(PLAYBACK_PERSISTENCE_PRIOR_EPOCH_SATURATION_METADATA_KEY) != null
        ) {
            return true
        }
        var cursor = 0L
        while (true) {
            val batch = outbox.startupBatch(
                afterRowId = cursor,
                limit = GLOBAL_BLOCKER_SCAN_BATCH_SIZE,
            )
            if (
                batch.any { inspection ->
                    inspection is PlaybackPersistenceRecoveryInspection.Conflict ||
                        inspection is PlaybackPersistenceRecoveryInspection.Corrupt
                }
            ) {
                return true
            }
            if (batch.isEmpty() || batch.size < GLOBAL_BLOCKER_SCAN_BATCH_SIZE) return false
            val nextCursor = batch.last().rowId
            if (nextCursor <= cursor) return true
            cursor = nextCursor
        }
    }

    private companion object {
        const val GLOBAL_BLOCKER_SCAN_BATCH_SIZE = 64
    }
}

private fun PersistedUnresolvedStrategyPersistence.toPortToken(): PlaybackPersistenceRecoveryToken =
    PlaybackPersistenceRecoveryToken(
        rowId = rowId,
        attemptKey = attemptKey,
        requestFingerprint = requestFingerprint,
    )

private fun PersistedUnresolvedStrategyPersistence.toPortObligation():
    PersistedPlaybackPersistenceRecoveryObligation =
    PersistedPlaybackPersistenceRecoveryObligation(
        token = toPortToken(),
        identityKey = identityKey,
        sourceScope = sourceScope,
        audioMode = audioMode,
        transportMode = transportMode,
        decoderMode = decoderMode,
        expectedRecordGeneration = expectedRecordGeneration,
        fallbackKnownGoodRecordGeneration = fallbackKnownGoodRecordGeneration,
        disposition = disposition,
        category = category,
        modelVersion = modelVersion,
    )

private fun PlaybackPersistenceRecoveryToken.toRoomToken():
    PlaybackPersistenceRecoveryResolutionToken =
    PlaybackPersistenceRecoveryResolutionToken(
        rowId = rowId,
        attemptKey = attemptKey,
        requestFingerprint = requestFingerprint,
    )

private class RecoveryTransactionRollback(
    val outcome: PlaybackPersistenceRecoveryHandlingOutcome,
) : RuntimeException()
