package app.opah.tv.data.persistence

import android.os.SystemClock
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import app.opah.tv.playback.compatibility.AudioCodec
import app.opah.tv.playback.compatibility.AudioMode
import app.opah.tv.playback.compatibility.CompatibilityIdentityKey
import app.opah.tv.playback.compatibility.DecoderImplementationEvidence
import app.opah.tv.playback.compatibility.DecoderMode
import app.opah.tv.playback.compatibility.FailureCategory
import app.opah.tv.playback.compatibility.ImplicatedStrategyFailure
import app.opah.tv.playback.compatibility.MAX_PERSISTENCE_WRITE_HORIZON_MILLIS
import app.opah.tv.playback.compatibility.PLAYBACK_COMPATIBILITY_MODEL_VERSION
import app.opah.tv.playback.compatibility.PersistablePlaybackStrategy
import app.opah.tv.playback.compatibility.PersistedPlaybackPersistenceRecoveryObligation
import app.opah.tv.playback.compatibility.PlaybackAttemptId
import app.opah.tv.playback.compatibility.PlaybackCompatibilityIdentity
import app.opah.tv.playback.compatibility.PlaybackDecoderEvidence
import app.opah.tv.playback.compatibility.PlaybackMediaMetadata
import app.opah.tv.playback.compatibility.PlaybackPurpose
import app.opah.tv.playback.compatibility.PlaybackPersistenceRecoveryHandlingOutcome
import app.opah.tv.playback.compatibility.PlaybackResourceClass
import app.opah.tv.playback.compatibility.PlaybackSuccessEvidence
import app.opah.tv.playback.compatibility.SanitizedDecoderName
import app.opah.tv.playback.compatibility.StoredPlaybackStrategy
import app.opah.tv.playback.compatibility.StrategyFailureRecordOutcome
import app.opah.tv.playback.compatibility.StrategyPersistenceDecisionContext
import app.opah.tv.playback.compatibility.StrategyPersistenceFinalization
import app.opah.tv.playback.compatibility.StrategyPersistenceFinalizationDisposition
import app.opah.tv.playback.compatibility.StrategyPersistenceFinalizationOutcome
import app.opah.tv.playback.compatibility.StrategyWriteCancellationOutcome
import app.opah.tv.playback.compatibility.StrategyWriteOutcome
import app.opah.tv.playback.compatibility.TransportMode
import app.opah.tv.playback.compatibility.UnresolvedPersistenceDisposition
import app.opah.tv.playback.compatibility.UnresolvedStrategyPersistence
import app.opah.tv.playback.compatibility.VerifiedPlaybackStrategyStore
import app.opah.tv.playback.compatibility.VerifiedStrategyWrite
import app.opah.tv.playback.compatibility.VideoCodec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

private const val FAILURE_INVALIDATION_THRESHOLD = 2
private const val MAX_FAILURE_COUNT = 63
private const val DEFAULT_IMPLICATED_FAILURE_WINDOW_MILLIS = 5 * 60 * 1_000L
private const val MAX_CANCELLATION_BARRIER_MILLIS = 24 * 60 * 60 * 1_000L
private const val GENERATION_AUTHORITY_METADATA_KEY = "playback_strategy_generation_authority_v1"
private const val MAX_PRIOR_EPOCH_PENDING_RECONCILIATIONS = 4_096

@Entity(
    tableName = "playback_strategy",
    indices = [Index(value = ["recordGeneration"], unique = true)],
)
data class PlaybackStrategyEntity(
    @PrimaryKey val identityKey: String,
    val profileScope: String,
    val cameraScope: String,
    val authorizationScope: String,
    val deviceScope: String,
    val osApiLevel: Int,
    val appCompatibilityRevision: Int,
    val serverApiGeneration: String,
    val streamConfigurationRevision: String,
    val purpose: String,
    val resourceClass: String,
    val identityModelVersion: Int,
    val recordGeneration: Long,
    val commitAttemptRowId: Long,
    val commitAttemptKey: String,
    val commitWriteFingerprint: String,
    val sourceScope: String,
    val videoCodec: String,
    val audioCodec: String?,
    val width: Int?,
    val height: Int?,
    val audioMode: String,
    val transportMode: String,
    val decoderMode: String,
    val firstFrameLatencyMillis: Long,
    val stablePlaybackDurationMillis: Long,
    val videoProgressEventCount: Int,
    val audioProgressEventCount: Int,
    val decoderImplementation: String,
    val sanitizedDecoderName: String?,
    val implicatedFailureCount: Int,
    val failureWindowStartedWallClockMillis: Long?,
    val invalidated: Boolean,
)

@Entity(
    tableName = "playback_strategy_write_attempt",
    indices = [
        Index(value = ["attemptKey"], unique = true),
        Index(value = ["identityKey"]),
        Index(value = ["recordGeneration"]),
    ],
)
data class PlaybackStrategyWriteAttemptEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val attemptKey: String,
    val identityKey: String?,
    val writeFingerprint: String?,
    val decisionFingerprint: String?,
    val finalizationFingerprint: String?,
    val status: String,
    val recordGeneration: Long?,
    val retainUntilElapsedMillis: Long,
    val retentionEpoch: String,
)

@Entity(
    tableName = "playback_strategy_pending",
    indices = [
        Index(value = ["attemptRowId"], unique = true),
        Index(value = ["identityKey"], unique = true),
        Index(value = ["recordGeneration"], unique = true),
    ],
)
data class PendingPlaybackStrategyEntity(
    @PrimaryKey val attemptKey: String,
    val attemptRowId: Long,
    val identityKey: String,
    val writeFingerprint: String,
    val decisionFingerprint: String,
    val profileScope: String,
    val cameraScope: String,
    val authorizationScope: String,
    val deviceScope: String,
    val osApiLevel: Int,
    val appCompatibilityRevision: Int,
    val serverApiGeneration: String,
    val streamConfigurationRevision: String,
    val purpose: String,
    val resourceClass: String,
    val identityModelVersion: Int,
    val recordGeneration: Long,
    val sourceScope: String,
    val videoCodec: String,
    val audioCodec: String?,
    val width: Int?,
    val height: Int?,
    val audioMode: String,
    val transportMode: String,
    val decoderMode: String,
    val firstFrameLatencyMillis: Long,
    val stablePlaybackDurationMillis: Long,
    val videoProgressEventCount: Int,
    val audioProgressEventCount: Int,
    val decoderImplementation: String,
    val sanitizedDecoderName: String?,
)

@Entity(
    tableName = "playback_strategy_reset_barrier",
    indices = [Index(value = ["resetEpoch", "retainUntilElapsedMillis"])],
)
data class PlaybackStrategyResetBarrierEntity(
    @PrimaryKey val identityKey: String,
    val resetAtElapsedMillis: Long,
    val retainUntilElapsedMillis: Long,
    val resetEpoch: String,
)

@Entity(tableName = "playback_strategy_generation")
data class PlaybackStrategyGenerationEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val lastGeneration: Long,
) {
    companion object {
        const val SINGLETON_ID: Int = 1
    }
}

@Dao
abstract class PlaybackStrategyDao {
    @Query("SELECT * FROM playback_strategy WHERE identityKey = :identityKey LIMIT 1")
    abstract suspend fun strategy(identityKey: String): PlaybackStrategyEntity?

    @Upsert
    abstract suspend fun upsertStrategy(entity: PlaybackStrategyEntity)

    @Query("DELETE FROM playback_strategy WHERE identityKey = :identityKey")
    abstract suspend fun deleteStrategy(identityKey: String): Int

    @Query(
        """
        UPDATE playback_strategy
        SET implicatedFailureCount = :failureCount,
            failureWindowStartedWallClockMillis = :failureWindowStartedWallClockMillis,
            invalidated = :invalidated
        WHERE identityKey = :identityKey AND recordGeneration = :recordGeneration
        """,
    )
    abstract suspend fun updateFailureState(
        identityKey: String,
        recordGeneration: Long,
        failureCount: Int,
        failureWindowStartedWallClockMillis: Long?,
        invalidated: Boolean,
    ): Int

    @Query("SELECT * FROM playback_strategy_pending WHERE attemptKey = :attemptKey LIMIT 1")
    abstract suspend fun pendingStrategy(attemptKey: String): PendingPlaybackStrategyEntity?

    @Query("SELECT * FROM playback_strategy_pending WHERE identityKey = :identityKey LIMIT 1")
    abstract suspend fun pendingStrategyForIdentity(
        identityKey: String,
    ): PendingPlaybackStrategyEntity?

    @Upsert
    abstract suspend fun upsertPendingStrategy(entity: PendingPlaybackStrategyEntity)

    @Query("DELETE FROM playback_strategy_pending WHERE attemptKey = :attemptKey")
    abstract suspend fun deletePendingStrategy(attemptKey: String): Int

    @Query(
        "DELETE FROM playback_strategy_pending WHERE identityKey = :identityKey",
    )
    abstract suspend fun deletePendingStrategiesForIdentity(identityKey: String): Int

    @Query(
        """
        UPDATE playback_strategy_write_attempt
        SET status = 'FAILED',
            recordGeneration = NULL,
            finalizationFingerprint = NULL,
            retainUntilElapsedMillis = :retainUntilElapsedMillis,
            retentionEpoch = :retentionEpoch
        WHERE identityKey = :identityKey
            AND status = 'COMMITTED_PENDING_RESOLUTION'
        """,
    )
    abstract suspend fun failPendingAttemptsForIdentity(
        identityKey: String,
        retainUntilElapsedMillis: Long,
        retentionEpoch: String,
    ): Int

    @Query("SELECT * FROM playback_strategy_write_attempt WHERE attemptKey = :attemptKey LIMIT 1")
    abstract suspend fun attempt(attemptKey: String): PlaybackStrategyWriteAttemptEntity?

    @Query("SELECT * FROM playback_strategy_write_attempt WHERE rowId = :rowId LIMIT 1")
    abstract suspend fun attemptByRowId(rowId: Long): PlaybackStrategyWriteAttemptEntity?

    @Query(
        """
        SELECT * FROM playback_strategy_write_attempt
        WHERE retentionEpoch != :currentEpoch
            AND status IN ('COMMITTED_PENDING_RESOLUTION', 'CANCELLED_PENDING_RESOLUTION')
        ORDER BY rowId ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun priorEpochPendingAttempts(
        currentEpoch: String,
        limit: Int,
    ): List<PlaybackStrategyWriteAttemptEntity>

    @Query(
        """
        SELECT * FROM playback_strategy
        WHERE commitAttemptKey = :attemptKey OR commitAttemptRowId = :attemptRowId
        ORDER BY recordGeneration DESC, identityKey DESC
        """,
    )
    abstract suspend fun strategiesReferencingAttempt(
        attemptKey: String,
        attemptRowId: Long,
    ): List<PlaybackStrategyEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM playback_strategy_write_attempt
            WHERE identityKey = :identityKey
                AND status = 'AMBIGUOUS_QUARANTINED'
                AND rowId >= :commitAttemptRowId
        )
        """,
    )
    abstract suspend fun hasBlockingAmbiguity(
        identityKey: String,
        commitAttemptRowId: Long,
    ): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAttempt(entity: PlaybackStrategyWriteAttemptEntity): Long

    @Query(
        """
        UPDATE playback_strategy_write_attempt
        SET identityKey = :identityKey,
            writeFingerprint = :writeFingerprint,
            decisionFingerprint = :decisionFingerprint,
            finalizationFingerprint = :finalizationFingerprint,
            status = :status,
            recordGeneration = :recordGeneration,
            retainUntilElapsedMillis = :retainUntilElapsedMillis,
            retentionEpoch = :retentionEpoch
        WHERE attemptKey = :attemptKey
        """,
    )
    abstract suspend fun updateAttempt(
        attemptKey: String,
        identityKey: String?,
        writeFingerprint: String?,
        decisionFingerprint: String?,
        finalizationFingerprint: String?,
        status: String,
        recordGeneration: Long?,
        retainUntilElapsedMillis: Long,
        retentionEpoch: String,
    ): Int

    @Query(
        "SELECT * FROM playback_strategy_reset_barrier WHERE identityKey = :identityKey LIMIT 1",
    )
    abstract suspend fun resetBarrier(identityKey: String): PlaybackStrategyResetBarrierEntity?

    @Upsert
    abstract suspend fun upsertResetBarrier(entity: PlaybackStrategyResetBarrierEntity)

    @Query(
        """
        DELETE FROM playback_strategy_reset_barrier
        WHERE identityKey IN (
            SELECT identityKey
            FROM playback_strategy_reset_barrier
            WHERE resetEpoch != :resetEpoch
                OR retainUntilElapsedMillis <= :elapsedRealtimeMillis
            ORDER BY resetAtElapsedMillis DESC, identityKey DESC
            LIMIT -1 OFFSET :retainedResetBarriers
        )
        """,
    )
    abstract suspend fun pruneResetBarriers(
        retainedResetBarriers: Int,
        elapsedRealtimeMillis: Long,
        resetEpoch: String,
    ): Int

    @Query(
        """
        DELETE FROM playback_strategy_write_attempt
        WHERE rowId IN (
            SELECT attempt.rowId
            FROM playback_strategy_write_attempt AS attempt
            LEFT JOIN playback_strategy AS strategy
                ON strategy.commitAttemptRowId = attempt.rowId
                AND strategy.commitAttemptKey = attempt.attemptKey
                AND strategy.commitWriteFingerprint = attempt.writeFingerprint
                AND strategy.identityKey = attempt.identityKey
                AND strategy.recordGeneration = attempt.recordGeneration
                AND strategy.invalidated = 0
            LEFT JOIN playback_strategy_pending AS pending_strategy
                ON pending_strategy.attemptRowId = attempt.rowId
                AND pending_strategy.attemptKey = attempt.attemptKey
                AND pending_strategy.writeFingerprint = attempt.writeFingerprint
                AND pending_strategy.decisionFingerprint = attempt.decisionFingerprint
                AND pending_strategy.identityKey = attempt.identityKey
                AND pending_strategy.recordGeneration = attempt.recordGeneration
            LEFT JOIN playback_strategy AS ambiguity_strategy
                ON ambiguity_strategy.identityKey = attempt.identityKey
                AND ambiguity_strategy.invalidated = 0
                AND attempt.status = 'AMBIGUOUS_QUARANTINED'
                AND ambiguity_strategy.commitAttemptRowId <= attempt.rowId
            WHERE ambiguity_strategy.identityKey IS NULL
                AND (
                    (
                        strategy.recordGeneration IS NULL
                        AND pending_strategy.recordGeneration IS NULL
                    )
                    OR attempt.status = 'FAILURE_RECORDED'
                )
                AND (
                    attempt.retentionEpoch != :retentionEpoch
                    OR attempt.retainUntilElapsedMillis <= :elapsedRealtimeMillis
                )
            ORDER BY attempt.rowId DESC
            LIMIT -1 OFFSET :retainedResolvedAttempts
        )
        """,
    )
    abstract suspend fun pruneResolvedAttempts(
        retainedResolvedAttempts: Int,
        elapsedRealtimeMillis: Long,
        retentionEpoch: String,
    ): Int

    @Query(
        """
        DELETE FROM playback_strategy
        WHERE identityKey IN (
            SELECT identityKey
            FROM playback_strategy
            ORDER BY recordGeneration DESC
            LIMIT -1 OFFSET :retainedStrategies
        )
        """,
    )
    abstract suspend fun pruneStrategies(retainedStrategies: Int): Int

    @Query(
        """
        DELETE FROM playback_strategy
        WHERE identityKey IN (
            SELECT identityKey
            FROM playback_strategy
            WHERE identityKey != :protectedIdentityKey
            ORDER BY recordGeneration DESC
            LIMIT -1 OFFSET :retainedOtherStrategies
        )
        """,
    )
    abstract suspend fun pruneStrategiesProtecting(
        protectedIdentityKey: String,
        retainedOtherStrategies: Int,
    ): Int

    @Query(
        """
        UPDATE playback_strategy
        SET invalidated = 1
        WHERE identityKey = :identityKey AND recordGeneration = :recordGeneration
        """,
    )
    abstract suspend fun invalidateStrategy(identityKey: String, recordGeneration: Long): Int

    @Query(
        "SELECT * FROM playback_strategy_generation WHERE singletonId = 1 LIMIT 1",
    )
    abstract suspend fun generation(): PlaybackStrategyGenerationEntity?

    @Query(
        """
        SELECT MAX(recordGeneration)
        FROM (
            SELECT recordGeneration FROM playback_strategy
            UNION ALL
            SELECT recordGeneration FROM playback_strategy_pending
            UNION ALL
            SELECT recordGeneration
            FROM playback_strategy_write_attempt
            WHERE recordGeneration IS NOT NULL
        )
        """,
    )
    abstract suspend fun maximumPersistedGeneration(): Long?

    @Upsert
    abstract suspend fun upsertGeneration(entity: PlaybackStrategyGenerationEntity)
}

fun interface PlaybackMonotonicClock {
    fun elapsedRealtimeMillis(): Long
}

fun interface PlaybackWallClock {
    fun currentTimeMillis(): Long
}

fun interface PlaybackPersistenceEpochSource {
    /** Returns one opaque value that is stable for the lifetime of this app process. */
    fun currentEpoch(): String
}

private object ProcessPlaybackPersistenceEpochSource : PlaybackPersistenceEpochSource {
    private val epoch = UUID.randomUUID().toString()

    override fun currentEpoch(): String = epoch
}

data class PlaybackStrategyRetentionPolicy(
    val retainedStrategies: Int = 512,
    val retainedResolvedAttempts: Int = 4_096,
    val retainedResetBarriers: Int = 512,
    val cancellationBarrierMillis: Long = MAX_PERSISTENCE_WRITE_HORIZON_MILLIS,
    val implicatedFailureWindowMillis: Long = DEFAULT_IMPLICATED_FAILURE_WINDOW_MILLIS,
) {
    init {
        require(retainedStrategies in 1..4_096)
        require(retainedResolvedAttempts in 1..32_768)
        require(retainedResetBarriers in 1..4_096)
        require(
            cancellationBarrierMillis in
                MAX_PERSISTENCE_WRITE_HORIZON_MILLIS..MAX_CANCELLATION_BARRIER_MILLIS,
        ) {
            "Cancellation barriers must cover every accepted persistence-write horizon"
        }
        require(implicatedFailureWindowMillis in 1_000L..86_400_000L)
    }
}

/** Room adapter; entities and transaction details never cross this package boundary. */
class RoomVerifiedPlaybackStrategyStore(
    private val database: OpahDatabase,
    private val clock: PlaybackMonotonicClock = PlaybackMonotonicClock(SystemClock::elapsedRealtime),
    private val failureClock: PlaybackWallClock = PlaybackWallClock(System::currentTimeMillis),
    private val retentionPolicy: PlaybackStrategyRetentionPolicy = PlaybackStrategyRetentionPolicy(),
    private val maximumPriorEpochPendingReconciliations: Int =
        MAX_PRIOR_EPOCH_PENDING_RECONCILIATIONS,
    epochSource: PlaybackPersistenceEpochSource = ProcessPlaybackPersistenceEpochSource,
) : VerifiedPlaybackStrategyStore {
    private val dao: PlaybackStrategyDao = database.playbackStrategyDao()
    private val recoveryDao: PlaybackPersistenceRecoveryDao =
        database.playbackPersistenceRecoveryDao()
    private val persistenceEpoch = epochSource.currentEpoch().also(::requireValidPersistenceEpoch)
    private val commitRelationStatuses = setOf(
        AttemptStatus.COMMITTED_PENDING_RESOLUTION,
        AttemptStatus.COMMITTED,
        AttemptStatus.QUARANTINED,
    )

    init {
        require(
            maximumPriorEpochPendingReconciliations in
                1..MAX_PRIOR_EPOCH_PENDING_RECONCILIATIONS,
        )
    }

    override suspend fun load(identity: PlaybackCompatibilityIdentity): StoredPlaybackStrategy? =
        database.withTransaction {
            val identityKey = PlaybackStrategyMapper.identityKey(identity)
            if (hasRecoveryBarrier(identityKey)) return@withTransaction null
            val entity = dao.strategy(identityKey) ?: return@withTransaction null
            if (entity.invalidated || entity.commitAttemptRowId <= 0L) return@withTransaction null
            val stored = PlaybackStrategyMapper.toStored(entity)
                ?.takeIf { it.record.identity == identity }
                ?: return@withTransaction null
            val provenance = dao.attemptByRowId(entity.commitAttemptRowId)
                ?: return@withTransaction null
            if (
                provenance.status != AttemptStatus.COMMITTED.name ||
                provenance.finalizationFingerprint
                    ?.let(PlaybackStrategyMapper::isDigestKey) != true ||
                !entity.hasExactCommitRelation(provenance) ||
                entity.commitWriteFingerprint != PlaybackStrategyMapper.writeFingerprint(stored.record)
            ) {
                return@withTransaction null
            }
            val references = referencedStrategies(entity.commitAttemptKey, provenance)
            val exactReferences = references.filter { reference ->
                reference.hasExactCommitRelation(provenance)
            }
            if (exactReferences.size != 1 || exactReferences.single().identityKey != identityKey) {
                return@withTransaction null
            }
            if (dao.hasBlockingAmbiguity(identityKey, entity.commitAttemptRowId)) {
                return@withTransaction null
            }
            stored
        }

    override suspend fun save(write: VerifiedStrategyWrite): StrategyWriteOutcome =
        database.withTransaction {
            val attemptKey = PlaybackStrategyMapper.attemptKey(write.attemptId)
            val identityKey = PlaybackStrategyMapper.identityKey(write.record.identity)
            val fingerprint = PlaybackStrategyMapper.writeFingerprint(write.record)
            val decisionFingerprint = PlaybackStrategyMapper.decisionFingerprint(write.record)
            if (hasRecoveryBarrier(identityKey, attemptKey)) {
                return@withTransaction StrategyWriteOutcome.WriteFailed
            }
            val existing = dao.attempt(attemptKey)
            val pending = dao.pendingStrategy(attemptKey)
            val identityPending = dao.pendingStrategyForIdentity(identityKey)
            if (identityPending != null && identityPending.attemptKey != attemptKey) {
                return@withTransaction StrategyWriteOutcome.WriteFailed
            }
            val references = referencedStrategies(attemptKey, existing)
            if (pending != null) {
                if (existing != null && pending.hasExactPendingRelation(existing)) {
                    return@withTransaction if (
                        existing.status == AttemptStatus.COMMITTED_PENDING_RESOLUTION.name &&
                        pending.identityKey == identityKey &&
                        pending.writeFingerprint == fingerprint &&
                        pending.decisionFingerprint == decisionFingerprint
                    ) {
                        StrategyWriteOutcome.DecisionRequired(pending.recordGeneration)
                    } else {
                        StrategyWriteOutcome.WriteFailed
                    }
                }
                val observedAt = clock.elapsedRealtimeMillis().coerceAtLeast(0L)
                markAmbiguousCorrelation(write.attemptId, existing, references, observedAt)
                return@withTransaction StrategyWriteOutcome.WriteFailed
            }
            if (references.isNotEmpty()) {
                val reference = existing?.let { attempt ->
                    references.filter { current -> current.hasExactCommitRelation(attempt) }
                        .singleOrNull()
                }
                val status = existing?.status?.let { name ->
                    AttemptStatus.entries.singleOrNull { it.name == name }
                }
                val historicalStatus = status.takeIf {
                    existing?.hasStructurallyValidCommittedShape() == true &&
                        it in setOf(AttemptStatus.COMMITTED, AttemptStatus.QUARANTINED)
                }
                val hasSameNamespaceClaim = existing != null && references.any { current ->
                    current.identityKey == existing.identityKey &&
                        current.recordGeneration == existing.recordGeneration
                }
                val exactInternalCommit = reference != null &&
                    status in commitRelationStatuses
                if (!exactInternalCommit) {
                    if (historicalStatus != null && !hasSameNamespaceClaim) {
                        val historicalAttempt = requireNotNull(existing)
                        val fieldsMatch = historicalAttempt.identityKey == identityKey &&
                            historicalAttempt.writeFingerprint == fingerprint &&
                            historicalAttempt.decisionFingerprint == decisionFingerprint &&
                            historicalAttempt.hasValidTerminalDecision()
                        return@withTransaction if (
                            historicalStatus == AttemptStatus.COMMITTED && fieldsMatch
                        ) {
                            StrategyWriteOutcome.FinalizedReplay(
                                StrategyPersistenceFinalizationOutcome.Committed(
                                    requireNotNull(historicalAttempt.recordGeneration),
                                ),
                            )
                        } else {
                            StrategyWriteOutcome.WriteFailed
                        }
                    }
                    val observedAt = clock.elapsedRealtimeMillis().coerceAtLeast(0L)
                    markAmbiguousCorrelation(write.attemptId, existing, references, observedAt)
                    return@withTransaction StrategyWriteOutcome.WriteFailed
                }
                val authoritativeReference = requireNotNull(reference)
                if (
                    authoritativeReference.identityKey != identityKey ||
                    authoritativeReference.commitWriteFingerprint != fingerprint ||
                    existing?.decisionFingerprint != decisionFingerprint
                ) {
                    return@withTransaction StrategyWriteOutcome.WriteFailed
                }
                return@withTransaction when (status) {
                    AttemptStatus.COMMITTED_PENDING_RESOLUTION ->
                        StrategyWriteOutcome.DecisionRequired(
                            authoritativeReference.recordGeneration,
                        )
                    AttemptStatus.COMMITTED -> if (existing?.hasValidTerminalDecision() == true) {
                        StrategyWriteOutcome.FinalizedReplay(
                            StrategyPersistenceFinalizationOutcome.Committed(
                                authoritativeReference.recordGeneration,
                            ),
                        )
                    } else {
                        StrategyWriteOutcome.WriteFailed
                    }
                    else -> StrategyWriteOutcome.WriteFailed
                }
            }
            when (existing?.status) {
                AttemptStatus.COMMITTED.name -> {
                    val fieldsMatch = existing.identityKey == identityKey &&
                        existing.writeFingerprint == fingerprint &&
                        existing.decisionFingerprint == decisionFingerprint &&
                        existing.hasValidTerminalDecision()
                    if (fieldsMatch && existing.hasStructurallyValidCommittedShape()) {
                        return@withTransaction StrategyWriteOutcome.FinalizedReplay(
                            StrategyPersistenceFinalizationOutcome.Committed(
                                requireNotNull(existing.recordGeneration),
                            ),
                        )
                    }
                    val observedAt = clock.elapsedRealtimeMillis().coerceAtLeast(0L)
                    markAmbiguousCorrelation(write.attemptId, existing, emptyList(), observedAt)
                    return@withTransaction StrategyWriteOutcome.WriteFailed
                }

                AttemptStatus.COMMITTED_PENDING_RESOLUTION.name -> {
                    val observedAt = clock.elapsedRealtimeMillis().coerceAtLeast(0L)
                    markAmbiguousCorrelation(write.attemptId, existing, emptyList(), observedAt)
                    return@withTransaction StrategyWriteOutcome.WriteFailed
                }

                AttemptStatus.CANCELLED_PENDING_RESOLUTION.name -> {
                    return@withTransaction if (
                        existing.hasExactCancellationShape(identityKey, decisionFingerprint) &&
                        existing.finalizationFingerprint == null
                    ) {
                        StrategyWriteOutcome.DecisionRequired(recordGeneration = null)
                    } else {
                        StrategyWriteOutcome.WriteFailed
                    }
                }

                AttemptStatus.CANCELLED.name -> {
                    return@withTransaction if (
                        existing.hasExactCancellationShape(identityKey, decisionFingerprint) &&
                        existing.hasValidTerminalDecision()
                    ) {
                        StrategyWriteOutcome.FinalizedReplay(
                            StrategyPersistenceFinalizationOutcome.Cancelled,
                        )
                    } else {
                        StrategyWriteOutcome.WriteFailed
                    }
                }

                AttemptStatus.TIMED_OUT.name -> return@withTransaction if (
                    existing.hasExactPrecommitShape(identityKey, decisionFingerprint)
                ) {
                    StrategyWriteOutcome.TimedOutBeforeCommit
                } else {
                    StrategyWriteOutcome.WriteFailed
                }

                AttemptStatus.QUARANTINED.name,
                AttemptStatus.FAILED.name,
                AttemptStatus.AMBIGUOUS_QUARANTINED.name,
                AttemptStatus.FAILURE_RECORDED.name,
                -> return@withTransaction StrategyWriteOutcome.WriteFailed

                else -> if (existing != null) {
                    return@withTransaction StrategyWriteOutcome.WriteFailed
                }
            }
            val firstAdmissionSample = clock.elapsedRealtimeMillis()
            val resetBarrier = dao.resetBarrier(identityKey)
            if (
                write.issuedAtElapsedMillis > firstAdmissionSample ||
                resetBarrier?.rejects(write, firstAdmissionSample) == true
            ) {
                rejectWriteBeforeCommit(
                    write,
                    identityKey,
                    fingerprint,
                    firstAdmissionSample,
                )
                return@withTransaction StrategyWriteOutcome.WriteFailed
            }
            if (firstAdmissionSample >= write.notAfterElapsedMillis) {
                upsertAttemptState(
                    write.attemptId,
                    identityKey,
                    fingerprint,
                    AttemptStatus.TIMED_OUT,
                    recordGeneration = null,
                    retainUntilElapsedMillis = barrierRetentionDeadline(firstAdmissionSample),
                    decisionFingerprint = decisionFingerprint,
                )
                pruneAttempts(firstAdmissionSample)
                return@withTransaction StrategyWriteOutcome.TimedOutBeforeCommit
            }
            val generation = allocateGeneration()
            if (generation == null) {
                val failedAt = clock.elapsedRealtimeMillis()
                upsertAttemptState(
                    write.attemptId,
                    identityKey,
                    fingerprint,
                    AttemptStatus.FAILED,
                    recordGeneration = null,
                    retainUntilElapsedMillis = barrierRetentionDeadline(failedAt),
                    decisionFingerprint = decisionFingerprint,
                )
                pruneAttempts(failedAt)
                return@withTransaction StrategyWriteOutcome.WriteFailed
            }
            val finalAdmissionSample = clock.elapsedRealtimeMillis()
            if (
                finalAdmissionSample < firstAdmissionSample ||
                finalAdmissionSample < write.issuedAtElapsedMillis ||
                resetBarrier?.rejects(write, finalAdmissionSample) == true
            ) {
                rejectWriteBeforeCommit(
                    write,
                    identityKey,
                    fingerprint,
                    finalAdmissionSample,
                )
                return@withTransaction StrategyWriteOutcome.WriteFailed
            }
            if (finalAdmissionSample >= write.notAfterElapsedMillis) {
                upsertAttemptState(
                    write.attemptId,
                    identityKey,
                    fingerprint,
                    AttemptStatus.TIMED_OUT,
                    recordGeneration = null,
                    retainUntilElapsedMillis = barrierRetentionDeadline(finalAdmissionSample),
                    decisionFingerprint = decisionFingerprint,
                )
                pruneAttempts(finalAdmissionSample)
                return@withTransaction StrategyWriteOutcome.TimedOutBeforeCommit
            }
            val commitAttemptRowId = upsertAttemptState(
                write.attemptId,
                identityKey,
                fingerprint,
                AttemptStatus.COMMITTED_PENDING_RESOLUTION,
                generation,
                retainUntilElapsedMillis = barrierRetentionDeadline(finalAdmissionSample),
                decisionFingerprint = decisionFingerprint,
            )
            dao.upsertPendingStrategy(
                PlaybackStrategyMapper.toPendingEntity(
                    write.record,
                    identityKey,
                    generation,
                    commitAttemptRowId,
                    attemptKey,
                    fingerprint,
                    decisionFingerprint,
                ),
            )
            pruneAttempts(finalAdmissionSample)
            StrategyWriteOutcome.DecisionRequired(generation)
        }

    override suspend fun cancel(
        context: StrategyPersistenceDecisionContext,
    ): StrategyWriteCancellationOutcome = database.withTransaction {
        val observedAt = clock.elapsedRealtimeMillis().coerceAtLeast(0L)
        val attemptId = context.attemptId
        val attemptKey = PlaybackStrategyMapper.attemptKey(attemptId)
        val identityKey = PlaybackStrategyMapper.identityKey(context.identity)
        val decisionFingerprint = PlaybackStrategyMapper.decisionFingerprint(context)
        if (hasRecoveryBarrier(identityKey, attemptKey)) {
            return@withTransaction StrategyWriteCancellationOutcome.ResolutionUnavailable
        }
        val existing = dao.attempt(attemptKey)
        val pending = dao.pendingStrategy(attemptKey)
        val references = referencedStrategies(attemptKey, existing)
        if (pending != null) {
            if (existing != null && pending.hasExactPendingRelation(existing)) {
                return@withTransaction if (
                    existing.status == AttemptStatus.COMMITTED_PENDING_RESOLUTION.name &&
                    pending.identityKey == identityKey &&
                    pending.decisionFingerprint == decisionFingerprint &&
                    PlaybackStrategyMapper.matches(
                        PlaybackStrategyMapper.toCommittedEntity(pending),
                        context,
                    )
                ) {
                    StrategyWriteCancellationOutcome.DecisionRequired(
                        pending.recordGeneration,
                    )
                } else {
                    StrategyWriteCancellationOutcome.ResolutionUnavailable
                }
            }
            markAmbiguousCorrelation(attemptId, existing, references, observedAt)
            return@withTransaction StrategyWriteCancellationOutcome.ResolutionUnavailable
        }
        if (references.isNotEmpty()) {
            val reference = existing?.let { attempt ->
                references.filter { current -> current.hasExactCommitRelation(attempt) }
                    .singleOrNull()
            }
            val status = existing?.status?.let { name ->
                AttemptStatus.entries.singleOrNull { it.name == name }
            }
            val historicalStatus = status.takeIf {
                existing?.hasStructurallyValidCommittedShape() == true &&
                    it in setOf(AttemptStatus.COMMITTED, AttemptStatus.QUARANTINED)
            }
            val hasSameNamespaceClaim = existing != null && references.any { current ->
                current.identityKey == existing.identityKey &&
                    current.recordGeneration == existing.recordGeneration
            }
            val exactCurrentCommit = reference != null &&
                status in commitRelationStatuses
            if (exactCurrentCommit) {
                val authoritativeReference = requireNotNull(reference)
                if (
                    existing?.decisionFingerprint != decisionFingerprint ||
                    !PlaybackStrategyMapper.matches(authoritativeReference, context)
                ) {
                    return@withTransaction StrategyWriteCancellationOutcome.ResolutionUnavailable
                }
                return@withTransaction when (status) {
                    AttemptStatus.COMMITTED_PENDING_RESOLUTION ->
                        StrategyWriteCancellationOutcome.DecisionRequired(
                            authoritativeReference.recordGeneration,
                        )
                    AttemptStatus.COMMITTED -> if (existing?.hasValidTerminalDecision() == true) {
                        StrategyWriteCancellationOutcome.FinalizedReplay(
                            StrategyPersistenceFinalizationOutcome.Committed(
                                authoritativeReference.recordGeneration,
                            ),
                        )
                    } else {
                        StrategyWriteCancellationOutcome.ResolutionUnavailable
                    }
                    else -> StrategyWriteCancellationOutcome.ResolutionUnavailable
                }
            }
            if (historicalStatus != null && !hasSameNamespaceClaim) {
                val historicalAttempt = requireNotNull(existing)
                val fieldsMatch = historicalAttempt.identityKey == identityKey &&
                    historicalAttempt.decisionFingerprint == decisionFingerprint &&
                    historicalAttempt.hasValidTerminalDecision()
                return@withTransaction if (
                    historicalStatus == AttemptStatus.COMMITTED && fieldsMatch
                ) {
                    StrategyWriteCancellationOutcome.FinalizedReplay(
                        StrategyPersistenceFinalizationOutcome.Committed(
                            requireNotNull(historicalAttempt.recordGeneration),
                        ),
                    )
                } else {
                    StrategyWriteCancellationOutcome.ResolutionUnavailable
                }
            }
            markAmbiguousCorrelation(attemptId, existing, references, observedAt)
            return@withTransaction StrategyWriteCancellationOutcome.ResolutionUnavailable
        }
        if (existing == null) {
            upsertAttemptState(
                attemptId,
                identityKey = identityKey,
                writeFingerprint = null,
                AttemptStatus.CANCELLED_PENDING_RESOLUTION,
                recordGeneration = null,
                retainUntilElapsedMillis = barrierRetentionDeadline(observedAt),
                decisionFingerprint = decisionFingerprint,
            )
            pruneAttempts(observedAt)
            return@withTransaction StrategyWriteCancellationOutcome.DecisionRequired(
                recordGeneration = null,
            )
        }
        val status = AttemptStatus.entries.singleOrNull { it.name == existing.status }
        if (status == AttemptStatus.AMBIGUOUS_QUARANTINED) {
            return@withTransaction StrategyWriteCancellationOutcome.ResolutionUnavailable
        }
        val exactCancellation = existing.hasExactCancellationShape(identityKey, decisionFingerprint)
        if (
            status == AttemptStatus.CANCELLED_PENDING_RESOLUTION &&
            exactCancellation &&
            existing.finalizationFingerprint == null
        ) {
            return@withTransaction StrategyWriteCancellationOutcome.DecisionRequired(
                recordGeneration = null,
            )
        }
        if (
            status == AttemptStatus.CANCELLED &&
            exactCancellation &&
            existing.hasValidTerminalDecision()
        ) {
            return@withTransaction StrategyWriteCancellationOutcome.FinalizedReplay(
                StrategyPersistenceFinalizationOutcome.Cancelled,
            )
        }
        val isExactResolvedPrecommit = existing.hasExactPrecommitShape(
            identityKey,
            decisionFingerprint,
        ) && status in setOf(AttemptStatus.TIMED_OUT, AttemptStatus.FAILED)
        if (!isExactResolvedPrecommit) {
            markAmbiguousCorrelation(attemptId, existing, emptyList(), observedAt)
            return@withTransaction StrategyWriteCancellationOutcome.ResolutionUnavailable
        }
        upsertAttemptState(
            attemptId = attemptId,
            identityKey = existing.identityKey,
            writeFingerprint = existing.writeFingerprint,
            status = AttemptStatus.CANCELLED_PENDING_RESOLUTION,
            recordGeneration = null,
            retainUntilElapsedMillis = barrierRetentionDeadline(observedAt),
            decisionFingerprint = existing.decisionFingerprint,
            finalizationFingerprint = null,
        )
        pruneAttempts(observedAt)
        StrategyWriteCancellationOutcome.DecisionRequired(
            recordGeneration = null,
        )
    }

    override suspend fun finalize(
        finalization: StrategyPersistenceFinalization,
    ): StrategyPersistenceFinalizationOutcome =
        database.withTransaction { finalizeInTransaction(finalization) }

    private suspend fun finalizeInTransaction(
        finalization: StrategyPersistenceFinalization,
    ): StrategyPersistenceFinalizationOutcome {
        val observedAt = clock.elapsedRealtimeMillis().coerceAtLeast(0L)
        val context = finalization.context
        val attemptKey = PlaybackStrategyMapper.attemptKey(context.attemptId)
        val identityKey = PlaybackStrategyMapper.identityKey(context.identity)
        val decisionFingerprint = PlaybackStrategyMapper.decisionFingerprint(context)
        val finalizationFingerprint = PlaybackStrategyMapper.finalizationFingerprint(finalization)
        val existing = dao.attempt(attemptKey)
            ?: return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
        val status = AttemptStatus.entries.singleOrNull { it.name == existing.status }
            ?: return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
        val pending = dao.pendingStrategy(attemptKey)
        val references = referencedStrategies(attemptKey, existing)
        val exactReferences = references.filter { reference ->
            reference.hasExactCommitRelation(existing)
        }
        val expectedGeneration = finalization.expectedRecordGeneration

        if (expectedGeneration != null) {
            if (pending != null) {
                if (!pending.hasExactPendingRelation(existing)) {
                    markAmbiguousCorrelation(context.attemptId, existing, references, observedAt)
                    return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
                }
                val pendingAsCommitted = PlaybackStrategyMapper.toCommittedEntity(pending)
                if (
                    status != AttemptStatus.COMMITTED_PENDING_RESOLUTION ||
                    pending.recordGeneration != expectedGeneration ||
                    pending.identityKey != identityKey ||
                    pending.decisionFingerprint != decisionFingerprint ||
                    !PlaybackStrategyMapper.matches(pendingAsCommitted, context)
                ) {
                    return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
                }
                val currentVisible = dao.strategy(identityKey)
                if (
                    currentVisible != null &&
                    currentVisible.recordGeneration >= pending.recordGeneration
                ) {
                    return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
                }
                val committedEntity = if (
                    finalization.disposition ==
                    StrategyPersistenceFinalizationDisposition.RECORD_IMPLICATED_FAILURE
                ) {
                    val failureObservedAt = failureClock.currentTimeMillis()
                    if (failureObservedAt < 0L) {
                        return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
                    }
                    val failureKey = PlaybackStrategyMapper.failureKey(context.attemptId)
                    if (dao.attempt(failureKey) != null) {
                        return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
                    }
                    val markerInserted = dao.insertAttempt(
                        PlaybackStrategyWriteAttemptEntity(
                            attemptKey = failureKey,
                            identityKey = identityKey,
                            writeFingerprint = null,
                            decisionFingerprint = null,
                            finalizationFingerprint = null,
                            status = AttemptStatus.FAILURE_RECORDED.name,
                            recordGeneration = expectedGeneration,
                            retainUntilElapsedMillis = failureMarkerRetentionDeadline(observedAt),
                            retentionEpoch = persistenceEpoch,
                        ),
                    )
                    if (markerInserted == -1L) {
                        return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
                    }
                    PlaybackStrategyMapper.toCommittedEntity(
                        pending = pending,
                        implicatedFailureCount = 1,
                        failureWindowStartedWallClockMillis = failureObservedAt,
                    )
                } else {
                    pendingAsCommitted
                }
                dao.upsertStrategy(committedEntity)
                upsertAttemptState(
                    attemptId = context.attemptId,
                    identityKey = existing.identityKey,
                    writeFingerprint = existing.writeFingerprint,
                    status = AttemptStatus.COMMITTED,
                    recordGeneration = expectedGeneration,
                    retainUntilElapsedMillis = barrierRetentionDeadline(observedAt),
                    decisionFingerprint = existing.decisionFingerprint,
                    finalizationFingerprint = finalizationFingerprint,
                )
                check(dao.deletePendingStrategy(attemptKey) == 1) {
                    "Pending playback strategy disappeared during finalization"
                }
                pruneStrategiesProtecting(identityKey)
                pruneAttempts(observedAt)
                return StrategyPersistenceFinalizationOutcome.Committed(expectedGeneration)
            }
            if (status == AttemptStatus.COMMITTED_PENDING_RESOLUTION) {
                markAmbiguousCorrelation(context.attemptId, existing, references, observedAt)
                return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
            }
            val exactReference = exactReferences.singleOrNull()
            if (exactReference != null) {
                if (
                    existing.recordGeneration != expectedGeneration ||
                    existing.identityKey != identityKey ||
                    existing.decisionFingerprint != decisionFingerprint ||
                    !PlaybackStrategyMapper.matches(exactReference, context)
                ) {
                    return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
                }
                if (status == AttemptStatus.COMMITTED) {
                    return if (
                        existing.finalizationFingerprint == finalizationFingerprint
                    ) {
                        StrategyPersistenceFinalizationOutcome.Committed(expectedGeneration)
                    } else {
                        StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
                    }
                }
                return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
            }

            val hasSameNamespaceClaim = references.any { reference ->
                reference.identityKey == existing.identityKey &&
                    reference.recordGeneration == existing.recordGeneration
            }
            val exactHistoricalCommit = status == AttemptStatus.COMMITTED &&
                existing.hasStructurallyValidCommittedShape() &&
                existing.recordGeneration == expectedGeneration &&
                existing.identityKey == identityKey &&
                existing.decisionFingerprint == decisionFingerprint &&
                existing.finalizationFingerprint == finalizationFingerprint &&
                !hasSameNamespaceClaim
            if (exactHistoricalCommit) {
                return StrategyPersistenceFinalizationOutcome.Committed(expectedGeneration)
            }
            if (references.isNotEmpty() || status == AttemptStatus.COMMITTED_PENDING_RESOLUTION) {
                markAmbiguousCorrelation(context.attemptId, existing, references, observedAt)
            }
            return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
        }

        if (pending != null) {
            if (!pending.hasExactPendingRelation(existing)) {
                markAmbiguousCorrelation(context.attemptId, existing, references, observedAt)
            }
            return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
        }
        if (
            existing.recordGeneration?.let { it > 0L } == true ||
            status in commitRelationStatuses
        ) {
            return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
        }
        if (exactReferences.isNotEmpty()) {
            return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
        }
        if (references.isNotEmpty()) {
            markAmbiguousCorrelation(context.attemptId, existing, references, observedAt)
            return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
        }
        if (
            !existing.hasExactCancellationShape(identityKey, decisionFingerprint) ||
            (status == AttemptStatus.CANCELLED_PENDING_RESOLUTION &&
                existing.finalizationFingerprint != null)
        ) {
            markAmbiguousCorrelation(context.attemptId, existing, emptyList(), observedAt)
            return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
        }
        if (status == AttemptStatus.CANCELLED) {
            return if (
                existing.finalizationFingerprint == finalizationFingerprint
            ) {
                StrategyPersistenceFinalizationOutcome.Cancelled
            } else {
                StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
            }
        }
        if (status != AttemptStatus.CANCELLED_PENDING_RESOLUTION) {
            return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
        }
        if (
            finalization.disposition ==
            StrategyPersistenceFinalizationDisposition.RECORD_IMPLICATED_FAILURE
        ) {
            val fallbackGeneration = context.fallbackKnownGoodRecordGeneration
                ?: return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
            val failure = finalization.toImplicatedFailure(fallbackGeneration)
            val failureOutcome = recordImplicatedFailureInTransaction(failure)
            if (
                failureOutcome == StrategyFailureRecordOutcome.IGNORED &&
                !hasExactFailureMarker(failure)
            ) {
                return StrategyPersistenceFinalizationOutcome.ResolutionUnavailable
            }
        }
        upsertAttemptState(
            attemptId = context.attemptId,
            identityKey = existing.identityKey,
            writeFingerprint = existing.writeFingerprint,
            status = AttemptStatus.CANCELLED,
            recordGeneration = null,
            retainUntilElapsedMillis = barrierRetentionDeadline(observedAt),
            decisionFingerprint = existing.decisionFingerprint,
            finalizationFingerprint = finalizationFingerprint,
        )
        pruneAttempts(observedAt)
        return StrategyPersistenceFinalizationOutcome.Cancelled
    }

    override suspend fun recordImplicatedFailure(
        failure: ImplicatedStrategyFailure,
    ): StrategyFailureRecordOutcome = database.withTransaction {
        recordImplicatedFailureInTransaction(failure)
    }

    private suspend fun recordImplicatedFailureInTransaction(
        failure: ImplicatedStrategyFailure,
    ): StrategyFailureRecordOutcome {
        val failureKey = PlaybackStrategyMapper.failureKey(failure.attemptId)
        val identityKey = PlaybackStrategyMapper.identityKey(failure.identity)
        if (hasRecoveryBarrier(identityKey, failureKey)) {
            return StrategyFailureRecordOutcome.IGNORED
        }
        if (dao.attempt(failureKey) != null) {
            return StrategyFailureRecordOutcome.IGNORED
        }
        val entity = dao.strategy(identityKey)
            ?.takeIf { PlaybackStrategyMapper.matches(it, failure) }
            ?: return StrategyFailureRecordOutcome.IGNORED
        if (entity.implicatedFailureCount !in 0..MAX_FAILURE_COUNT) {
            return StrategyFailureRecordOutcome.IGNORED
        }
        val observedAt = clock.elapsedRealtimeMillis()
        val failureObservedAt = failureClock.currentTimeMillis()
        if (failureObservedAt < 0L) {
            return StrategyFailureRecordOutcome.IGNORED
        }
        val existingWindowStart = entity.failureWindowStartedWallClockMillis
        val existingWindowIsValid = when (entity.implicatedFailureCount) {
            0 -> existingWindowStart == null
            else -> existingWindowStart != null && existingWindowStart >= 0L
        }
        if (!existingWindowIsValid) {
            return StrategyFailureRecordOutcome.IGNORED
        }
        val markerInserted = dao.insertAttempt(
            PlaybackStrategyWriteAttemptEntity(
                attemptKey = failureKey,
                identityKey = identityKey,
                writeFingerprint = null,
                decisionFingerprint = null,
                finalizationFingerprint = null,
                status = AttemptStatus.FAILURE_RECORDED.name,
                recordGeneration = failure.recordGeneration,
                retainUntilElapsedMillis = failureMarkerRetentionDeadline(observedAt),
                retentionEpoch = persistenceEpoch,
            ),
        )
        if (markerInserted == -1L) return StrategyFailureRecordOutcome.IGNORED
        if (entity.implicatedFailureCount >= FAILURE_INVALIDATION_THRESHOLD) {
            check(
                dao.updateFailureState(
                    identityKey = identityKey,
                    recordGeneration = failure.recordGeneration,
                    failureCount = entity.implicatedFailureCount,
                    failureWindowStartedWallClockMillis =
                        entity.failureWindowStartedWallClockMillis,
                    invalidated = true,
                ) == 1,
            ) { "Playback strategy disappeared during failure invalidation" }
            pruneAttempts(observedAt.coerceAtLeast(0L))
            return StrategyFailureRecordOutcome.INVALIDATED
        }
        val continuesFailureWindow = existingWindowStart != null &&
            failureObservedAt >= existingWindowStart &&
            failureObservedAt - existingWindowStart <= retentionPolicy.implicatedFailureWindowMillis
        val failureWindowStart = if (continuesFailureWindow) existingWindowStart else failureObservedAt
        val failureCount = when {
            !continuesFailureWindow -> 1
            entity.implicatedFailureCount >= MAX_FAILURE_COUNT -> MAX_FAILURE_COUNT
            else -> entity.implicatedFailureCount + 1
        }
        val invalidated = failureCount >= FAILURE_INVALIDATION_THRESHOLD
        check(
            dao.updateFailureState(
                identityKey,
                failure.recordGeneration,
                failureCount,
                failureWindowStart,
                invalidated,
            ) == 1,
        ) { "Playback strategy disappeared during failure correlation" }
        pruneAttempts(observedAt)
        return if (invalidated) {
            StrategyFailureRecordOutcome.INVALIDATED
        } else {
            StrategyFailureRecordOutcome.RECORDED
        }
    }

    override suspend fun quarantineUnresolvedWrite(
        request: UnresolvedStrategyPersistence,
    ): StrategyFailureRecordOutcome = database.withTransaction {
        val observedAt = clock.elapsedRealtimeMillis().coerceAtLeast(0L)
        val failureObservedAt = failureClock.currentTimeMillis().takeIf { it >= 0L } ?: 0L
        val attemptKey = PlaybackStrategyMapper.attemptKey(request.attemptId)
        val identityKey = PlaybackStrategyMapper.identityKey(request.identity)
        val decisionFingerprint = PlaybackStrategyMapper.decisionFingerprint(request)
        val recoveryFingerprint = PlaybackStrategyMapper.recoveryFingerprint(request)
        if (hasRecoveryBarrier(identityKey, attemptKey)) {
            return@withTransaction StrategyFailureRecordOutcome.IGNORED
        }
        val existing = dao.attempt(attemptKey)
        val pending = dao.pendingStrategy(attemptKey)
        val references = referencedStrategies(attemptKey, existing)
        val shouldInvalidateCommitted = request.disposition ==
            UnresolvedPersistenceDisposition.INVALIDATE_IMPLICATED_COMMIT
        if (pending != null) {
            if (existing == null || !pending.hasExactPendingRelation(existing)) {
                markAmbiguousCorrelation(request.attemptId, existing, references, observedAt)
                return@withTransaction StrategyFailureRecordOutcome.IGNORED
            }
            val requestMatchesPending =
                existing.status == AttemptStatus.COMMITTED_PENDING_RESOLUTION.name &&
                    pending.identityKey == identityKey &&
                    pending.decisionFingerprint == decisionFingerprint &&
                    (request.expectedRecordGeneration == null ||
                        request.expectedRecordGeneration == pending.recordGeneration) &&
                    PlaybackStrategyMapper.matchesDecision(
                        PlaybackStrategyMapper.toCommittedEntity(pending),
                        request,
                        pending.recordGeneration,
                    )
            if (!requestMatchesPending) {
                return@withTransaction StrategyFailureRecordOutcome.IGNORED
            }
            var pendingOutcome = StrategyFailureRecordOutcome.IGNORED
            if (shouldInvalidateCommitted) {
                val fallbackGeneration = request.fallbackKnownGoodRecordGeneration
                val fallback = fallbackGeneration?.let { dao.strategy(identityKey) }
                    ?.takeIf { strategy ->
                        PlaybackStrategyMapper.matchesDecision(
                            strategy,
                            request,
                            requireNotNull(fallbackGeneration),
                        )
                    }
                if (fallback != null && !fallback.invalidated) {
                    check(
                        dao.updateFailureState(
                            identityKey = identityKey,
                            recordGeneration = fallback.recordGeneration,
                            failureCount = MAX_FAILURE_COUNT,
                            failureWindowStartedWallClockMillis = failureObservedAt,
                            invalidated = true,
                        ) == 1,
                    ) { "Known-good strategy disappeared during pending-write quarantine" }
                    pendingOutcome = StrategyFailureRecordOutcome.INVALIDATED
                }
            } else {
                val currentVisible = dao.strategy(identityKey)
                if (
                    currentVisible != null &&
                    currentVisible.recordGeneration >= pending.recordGeneration
                ) {
                    // A valid database never exposes a newer generation while an older pending
                    // write still owns this identity. Refuse to overwrite that evidence and leave
                    // the pending row available for explicit recovery.
                    return@withTransaction StrategyFailureRecordOutcome.IGNORED
                }
                dao.upsertStrategy(PlaybackStrategyMapper.toCommittedEntity(pending))
            }
            upsertAttemptState(
                attemptId = request.attemptId,
                identityKey = existing.identityKey,
                writeFingerprint = existing.writeFingerprint,
                status = if (shouldInvalidateCommitted) {
                    AttemptStatus.QUARANTINED
                } else {
                    AttemptStatus.COMMITTED
                },
                recordGeneration = pending.recordGeneration,
                retainUntilElapsedMillis = barrierRetentionDeadline(observedAt),
                decisionFingerprint = existing.decisionFingerprint,
                finalizationFingerprint = recoveryFingerprint,
            )
            check(dao.deletePendingStrategy(attemptKey) == 1) {
                "Pending playback strategy disappeared during quarantine"
            }
            if (!shouldInvalidateCommitted) {
                pruneStrategiesProtecting(identityKey)
            }
            pruneAttempts(observedAt)
            return@withTransaction pendingOutcome
        }
        val reference = existing?.let { attempt ->
            references.filter { current -> current.hasExactCommitRelation(attempt) }
                .singleOrNull()
        }
        val existingStatus = existing?.status?.let { name ->
            AttemptStatus.entries.singleOrNull { it.name == name }
        }
        val historicalStatus = existingStatus.takeIf {
            existing?.hasStructurallyValidCommittedShape() == true &&
                it in setOf(AttemptStatus.COMMITTED, AttemptStatus.QUARANTINED)
        }
        val hasSameNamespaceClaim = existing != null && references.any { current ->
            current.identityKey == existing.identityKey &&
                current.recordGeneration == existing.recordGeneration
        }
        val hasOnlyForeignClaims = references.isNotEmpty() &&
            historicalStatus != null &&
            !hasSameNamespaceClaim
        val exactInternalCommit = reference != null &&
            existingStatus in commitRelationStatuses
        if (references.isNotEmpty() && !exactInternalCommit && !hasOnlyForeignClaims) {
            markAmbiguousCorrelation(request.attemptId, existing, references, observedAt)
            return@withTransaction StrategyFailureRecordOutcome.IGNORED
        }
        val requestMatchesReference = reference != null &&
            exactInternalCommit &&
            reference.identityKey == identityKey &&
            existing?.decisionFingerprint == decisionFingerprint &&
            (request.expectedRecordGeneration == null ||
                request.expectedRecordGeneration == reference.recordGeneration) &&
            PlaybackStrategyMapper.matchesDecision(
                reference,
                request,
                reference.recordGeneration,
            )
        if (exactInternalCommit && !requestMatchesReference) {
            return@withTransaction StrategyFailureRecordOutcome.IGNORED
        }
        if (requestMatchesReference) {
            val strategy = requireNotNull(reference)
            if (shouldInvalidateCommitted && !strategy.invalidated) {
                check(
                    dao.updateFailureState(
                        identityKey = identityKey,
                        recordGeneration = strategy.recordGeneration,
                        failureCount = MAX_FAILURE_COUNT,
                        failureWindowStartedWallClockMillis = failureObservedAt,
                        invalidated = true,
                    ) == 1,
                ) { "Playback strategy disappeared during unresolved-write quarantine" }
            }
            val resolvedStatus = when {
                shouldInvalidateCommitted -> AttemptStatus.QUARANTINED
                existingStatus == AttemptStatus.COMMITTED_PENDING_RESOLUTION ->
                    AttemptStatus.COMMITTED
                else -> requireNotNull(existingStatus)
            }
            val resolvedFinalizationFingerprint = when {
                resolvedStatus == AttemptStatus.COMMITTED &&
                    existingStatus == AttemptStatus.COMMITTED -> existing?.finalizationFingerprint
                resolvedStatus == AttemptStatus.QUARANTINED &&
                    existingStatus == AttemptStatus.QUARANTINED -> existing?.finalizationFingerprint
                else -> recoveryFingerprint
            }
            upsertAttemptState(
                attemptId = request.attemptId,
                identityKey = existing?.identityKey,
                writeFingerprint = existing?.writeFingerprint,
                status = resolvedStatus,
                recordGeneration = existing?.recordGeneration,
                retainUntilElapsedMillis = barrierRetentionDeadline(observedAt),
                decisionFingerprint = existing?.decisionFingerprint,
                finalizationFingerprint = resolvedFinalizationFingerprint,
            )
            pruneAttempts(observedAt)
            return@withTransaction if (shouldInvalidateCommitted && !strategy.invalidated) {
                StrategyFailureRecordOutcome.INVALIDATED
            } else {
                StrategyFailureRecordOutcome.IGNORED
            }
        }

        val historicalRequestMatches = historicalStatus != null &&
            existing?.identityKey == identityKey &&
            existing.decisionFingerprint == decisionFingerprint &&
            (request.expectedRecordGeneration == null ||
                request.expectedRecordGeneration == existing.recordGeneration)
        if (hasOnlyForeignClaims && !historicalRequestMatches) {
            return@withTransaction StrategyFailureRecordOutcome.IGNORED
        }
        if (historicalRequestMatches) {
            if (shouldInvalidateCommitted) {
                upsertAttemptState(
                    attemptId = request.attemptId,
                    identityKey = existing?.identityKey,
                    writeFingerprint = existing?.writeFingerprint,
                    status = AttemptStatus.QUARANTINED,
                    recordGeneration = existing?.recordGeneration,
                    retainUntilElapsedMillis = barrierRetentionDeadline(observedAt),
                    decisionFingerprint = existing?.decisionFingerprint,
                    finalizationFingerprint = recoveryFingerprint,
                )
                pruneAttempts(observedAt)
            }
            return@withTransaction StrategyFailureRecordOutcome.IGNORED
        }

        if (
            existing != null &&
            existingStatus in setOf(
                AttemptStatus.COMMITTED_PENDING_RESOLUTION,
                AttemptStatus.COMMITTED,
            )
        ) {
            markAmbiguousCorrelation(request.attemptId, existing, references, observedAt)
            return@withTransaction StrategyFailureRecordOutcome.IGNORED
        }

        val exactPrecommit = existing == null ||
            existing.hasExactPrecommitShape(identityKey, decisionFingerprint)
        if (!exactPrecommit || existingStatus == AttemptStatus.AMBIGUOUS_QUARANTINED) {
            if (existingStatus != AttemptStatus.AMBIGUOUS_QUARANTINED) {
                markAmbiguousCorrelation(request.attemptId, existing, references, observedAt)
            }
            return@withTransaction StrategyFailureRecordOutcome.IGNORED
        }

        var outcome = StrategyFailureRecordOutcome.IGNORED
        if (shouldInvalidateCommitted) {
            val fallbackGeneration = request.fallbackKnownGoodRecordGeneration
            val fallback = fallbackGeneration?.let { dao.strategy(identityKey) }
                ?.takeIf { strategy ->
                    PlaybackStrategyMapper.matchesDecision(
                        strategy,
                        request,
                        requireNotNull(fallbackGeneration),
                    )
                }
            if (fallback != null && !fallback.invalidated) {
                check(
                    dao.updateFailureState(
                        identityKey = identityKey,
                        recordGeneration = fallback.recordGeneration,
                        failureCount = MAX_FAILURE_COUNT,
                        failureWindowStartedWallClockMillis = failureObservedAt,
                        invalidated = true,
                    ) == 1,
                ) { "Known-good strategy disappeared during unresolved-write quarantine" }
                outcome = StrategyFailureRecordOutcome.INVALIDATED
            }
        }

        val resolvedStatus = when {
            existingStatus == AttemptStatus.QUARANTINED -> AttemptStatus.QUARANTINED
            shouldInvalidateCommitted -> AttemptStatus.QUARANTINED
            else -> AttemptStatus.CANCELLED
        }
        val resolvedFinalizationFingerprint = when {
            existingStatus == resolvedStatus && existing?.finalizationFingerprint != null ->
                existing.finalizationFingerprint
            else -> recoveryFingerprint
        }
        upsertAttemptState(
            attemptId = request.attemptId,
            identityKey = existing?.identityKey ?: identityKey,
            writeFingerprint = existing?.writeFingerprint,
            status = resolvedStatus,
            recordGeneration = null,
            retainUntilElapsedMillis = barrierRetentionDeadline(observedAt),
            decisionFingerprint = existing?.decisionFingerprint ?: decisionFingerprint,
            finalizationFingerprint = resolvedFinalizationFingerprint,
        )
        pruneAttempts(observedAt)
        outcome
    }

    /**
     * Applies one already-admitted, privacy-minimized recovery obligation without reconstructing
     * the original playback identity or attempt ID. The Room recovery adapter invokes this inside
     * the same transaction that verifies and removes the exact outbox row.
     */
    suspend fun recoverPersistedUnresolvedWrite(
        obligation: PersistedPlaybackPersistenceRecoveryObligation,
    ): PlaybackPersistenceRecoveryHandlingOutcome = database.withTransaction {
        recoverPersistedUnresolvedWriteInTransaction(obligation)
    }

    private suspend fun recoverPersistedUnresolvedWriteInTransaction(
        obligation: PersistedPlaybackPersistenceRecoveryObligation,
    ): PlaybackPersistenceRecoveryHandlingOutcome {
        if (!hasExactPersistedRecoveryObligation(obligation)) {
            return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
        }

        val attemptKey = obligation.token.attemptKey
        val identityKey = obligation.identityKey
        val expectedGeneration = obligation.expectedRecordGeneration
        val decisionFingerprint = PlaybackStrategyMapper.decisionFingerprint(obligation)
        val observedAt = clock.elapsedRealtimeMillis().coerceAtLeast(0L)
        val failureObservedAt = failureClock.currentTimeMillis().coerceAtLeast(0L)
        val shouldInvalidate = obligation.disposition ==
            UnresolvedPersistenceDisposition.INVALIDATE_IMPLICATED_COMMIT
        val existing = dao.attempt(attemptKey)
        val pending = dao.pendingStrategy(attemptKey)
        val references = referencedStrategies(attemptKey, existing)

        if (pending != null) {
            if (
                existing == null ||
                !pending.hasExactPendingRelation(existing) ||
                references.isNotEmpty() ||
                pending.identityKey != identityKey ||
                pending.decisionFingerprint != decisionFingerprint ||
                (expectedGeneration != null && expectedGeneration != pending.recordGeneration) ||
                !PlaybackStrategyMapper.matchesDecision(
                    PlaybackStrategyMapper.toCommittedEntity(pending),
                    obligation,
                    pending.recordGeneration,
                )
            ) {
                return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
            }

            val fallback = if (shouldInvalidate) {
                exactPersistedFallbackOrNull(obligation)
                    ?: if (obligation.fallbackKnownGoodRecordGeneration != null) {
                        val visible = dao.strategy(identityKey)
                        if (visible != null) {
                            return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                        }
                        null
                    } else {
                        null
                    }
            } else {
                null
            }

            if (shouldInvalidate) {
                fallback?.let { strategy ->
                    invalidatePersistedStrategy(strategy, failureObservedAt)
                }
            } else {
                val currentVisible = dao.strategy(identityKey)
                if (
                    currentVisible != null &&
                    currentVisible.recordGeneration >= pending.recordGeneration
                ) {
                    return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                }
                dao.upsertStrategy(PlaybackStrategyMapper.toCommittedEntity(pending))
            }

            check(
                dao.updateAttempt(
                    attemptKey = attemptKey,
                    identityKey = existing.identityKey,
                    writeFingerprint = existing.writeFingerprint,
                    decisionFingerprint = existing.decisionFingerprint,
                    finalizationFingerprint = obligation.token.requestFingerprint,
                    status = if (shouldInvalidate) {
                        AttemptStatus.QUARANTINED.name
                    } else {
                        AttemptStatus.COMMITTED.name
                    },
                    recordGeneration = pending.recordGeneration,
                    retainUntilElapsedMillis = barrierRetentionDeadline(observedAt),
                    retentionEpoch = persistenceEpoch,
                ) == 1,
            ) { "Pending playback recovery attempt disappeared" }
            check(dao.deletePendingStrategy(attemptKey) == 1) {
                "Pending playback recovery strategy disappeared"
            }
            if (!shouldInvalidate) {
                pruneStrategiesProtecting(identityKey)
            }
            pruneAttempts(observedAt)
            return PlaybackPersistenceRecoveryHandlingOutcome.CONCLUSIVELY_HANDLED
        }

        val status = existing?.status?.let { name ->
            AttemptStatus.entries.singleOrNull { it.name == name }
        }
        val exactReferences = existing?.let { attempt ->
            references.filter { reference -> reference.hasExactCommitRelation(attempt) }
        }.orEmpty()
        if (
            references.isNotEmpty() &&
            (exactReferences.size != references.size || exactReferences.size != 1)
        ) {
            return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
        }

        if (existing == null) {
            if (references.isNotEmpty() || expectedGeneration != null) {
                return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
            }
            val fallback = if (shouldInvalidate) {
                exactPersistedFallbackOrNull(obligation)
                    ?: if (obligation.fallbackKnownGoodRecordGeneration != null) {
                        val visible = dao.strategy(identityKey)
                        if (visible != null) {
                            return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                        }
                        null
                    } else {
                        null
                    }
            } else {
                null
            }
            fallback?.let { strategy -> invalidatePersistedStrategy(strategy, failureObservedAt) }
            val inserted = dao.insertAttempt(
                PlaybackStrategyWriteAttemptEntity(
                    attemptKey = attemptKey,
                    identityKey = identityKey,
                    writeFingerprint = null,
                    decisionFingerprint = decisionFingerprint,
                    finalizationFingerprint = obligation.token.requestFingerprint,
                    status = if (shouldInvalidate) {
                        AttemptStatus.QUARANTINED.name
                    } else {
                        AttemptStatus.CANCELLED.name
                    },
                    recordGeneration = null,
                    retainUntilElapsedMillis = barrierRetentionDeadline(observedAt),
                    retentionEpoch = persistenceEpoch,
                ),
            )
            check(inserted > 0L) { "Persisted playback recovery attempt was not admitted" }
            pruneAttempts(observedAt)
            return PlaybackPersistenceRecoveryHandlingOutcome.CONCLUSIVELY_HANDLED
        }

        if (
            existing.identityKey != identityKey ||
            existing.decisionFingerprint != decisionFingerprint ||
            !PlaybackStrategyMapper.isDigestKey(identityKey) ||
            !PlaybackStrategyMapper.isDigestKey(decisionFingerprint)
        ) {
            return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
        }

        when (status) {
            AttemptStatus.COMMITTED -> {
                if (
                    !existing.hasStructurallyValidCommittedShape() ||
                    !existing.hasValidTerminalDecision() ||
                    (expectedGeneration != null && existing.recordGeneration != expectedGeneration)
                ) {
                    return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                }
                val reference = exactReferences.singleOrNull()
                if (
                    reference != null &&
                    !PlaybackStrategyMapper.matchesDecision(
                        reference,
                        obligation,
                        requireNotNull(existing.recordGeneration),
                    )
                ) {
                    return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                }
                if (shouldInvalidate) {
                    val fallback = if (reference == null) {
                        exactPersistedFallbackOrNull(obligation)
                    } else {
                        null
                    }
                    if (
                        reference == null &&
                        obligation.fallbackKnownGoodRecordGeneration != null &&
                        fallback == null &&
                        dao.strategy(identityKey) != null
                    ) {
                        return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                    }
                    reference?.let { strategy ->
                        invalidatePersistedStrategy(strategy, failureObservedAt)
                    }
                    fallback?.let { strategy ->
                        invalidatePersistedStrategy(strategy, failureObservedAt)
                    }
                    check(
                        dao.updateAttempt(
                            attemptKey = attemptKey,
                            identityKey = existing.identityKey,
                            writeFingerprint = existing.writeFingerprint,
                            decisionFingerprint = existing.decisionFingerprint,
                            finalizationFingerprint = obligation.token.requestFingerprint,
                            status = AttemptStatus.QUARANTINED.name,
                            recordGeneration = existing.recordGeneration,
                            retainUntilElapsedMillis = barrierRetentionDeadline(observedAt),
                            retentionEpoch = persistenceEpoch,
                        ) == 1,
                    ) { "Committed playback recovery attempt disappeared" }
                    pruneAttempts(observedAt)
                }
                return PlaybackPersistenceRecoveryHandlingOutcome.CONCLUSIVELY_HANDLED
            }

            AttemptStatus.QUARANTINED -> {
                if (!shouldInvalidate || !existing.hasValidTerminalDecision()) {
                    return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                }
                if (existing.recordGeneration == null) {
                    if (
                        expectedGeneration != null ||
                        !existing.hasExactCancellationShape(identityKey, decisionFingerprint) ||
                        references.isNotEmpty()
                    ) {
                        return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                    }
                } else if (
                    !existing.hasStructurallyValidCommittedShape() ||
                    (expectedGeneration != null && existing.recordGeneration != expectedGeneration)
                ) {
                    return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                }

                val reference = exactReferences.singleOrNull()
                if (reference != null) {
                    if (
                        !PlaybackStrategyMapper.matchesDecision(
                            reference,
                            obligation,
                            requireNotNull(existing.recordGeneration),
                        )
                    ) {
                        return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                    }
                    invalidatePersistedStrategy(reference, failureObservedAt)
                } else {
                    val fallback = exactPersistedFallbackOrNull(obligation)
                    if (
                        obligation.fallbackKnownGoodRecordGeneration != null &&
                        fallback == null &&
                        dao.strategy(identityKey) != null
                    ) {
                        return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                    }
                    fallback?.let { strategy ->
                        invalidatePersistedStrategy(strategy, failureObservedAt)
                    }
                }
                pruneAttempts(observedAt)
                return PlaybackPersistenceRecoveryHandlingOutcome.CONCLUSIVELY_HANDLED
            }

            AttemptStatus.CANCELLED -> {
                if (
                    expectedGeneration != null ||
                    references.isNotEmpty() ||
                    !existing.hasExactCancellationShape(identityKey, decisionFingerprint) ||
                    !existing.hasValidTerminalDecision()
                ) {
                    return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                }
                if (shouldInvalidate) {
                    val fallback = exactPersistedFallbackOrNull(obligation)
                    if (
                        obligation.fallbackKnownGoodRecordGeneration != null &&
                        fallback == null &&
                        dao.strategy(identityKey) != null
                    ) {
                        return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                    }
                    fallback?.let { strategy ->
                        invalidatePersistedStrategy(strategy, failureObservedAt)
                    }
                    check(
                        dao.updateAttempt(
                            attemptKey = attemptKey,
                            identityKey = existing.identityKey,
                            writeFingerprint = existing.writeFingerprint,
                            decisionFingerprint = existing.decisionFingerprint,
                            finalizationFingerprint = obligation.token.requestFingerprint,
                            status = AttemptStatus.QUARANTINED.name,
                            recordGeneration = null,
                            retainUntilElapsedMillis = barrierRetentionDeadline(observedAt),
                            retentionEpoch = persistenceEpoch,
                        ) == 1,
                    ) { "Cancelled playback recovery attempt disappeared" }
                    pruneAttempts(observedAt)
                }
                return PlaybackPersistenceRecoveryHandlingOutcome.CONCLUSIVELY_HANDLED
            }

            AttemptStatus.CANCELLED_PENDING_RESOLUTION,
            AttemptStatus.TIMED_OUT,
            AttemptStatus.FAILED,
            -> {
                if (
                    expectedGeneration != null ||
                    references.isNotEmpty() ||
                    existing.finalizationFingerprint != null ||
                    !existing.hasExactCancellationShape(identityKey, decisionFingerprint)
                ) {
                    return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                }
                val fallback = if (shouldInvalidate) {
                    exactPersistedFallbackOrNull(obligation)
                        ?: if (obligation.fallbackKnownGoodRecordGeneration != null) {
                            val visible = dao.strategy(identityKey)
                            if (visible != null) {
                                return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                            }
                            null
                        } else {
                            null
                        }
                } else {
                    null
                }
                fallback?.let { strategy ->
                    invalidatePersistedStrategy(strategy, failureObservedAt)
                }
                check(
                    dao.updateAttempt(
                        attemptKey = attemptKey,
                        identityKey = identityKey,
                        writeFingerprint = existing.writeFingerprint,
                        decisionFingerprint = decisionFingerprint,
                        finalizationFingerprint = obligation.token.requestFingerprint,
                        status = if (shouldInvalidate) {
                            AttemptStatus.QUARANTINED.name
                        } else {
                            AttemptStatus.CANCELLED.name
                        },
                        recordGeneration = null,
                        retainUntilElapsedMillis = barrierRetentionDeadline(observedAt),
                        retentionEpoch = persistenceEpoch,
                    ) == 1,
                ) { "Precommit playback recovery attempt disappeared" }
                pruneAttempts(observedAt)
                return PlaybackPersistenceRecoveryHandlingOutcome.CONCLUSIVELY_HANDLED
            }

            AttemptStatus.COMMITTED_PENDING_RESOLUTION,
            AttemptStatus.AMBIGUOUS_QUARANTINED,
            AttemptStatus.FAILURE_RECORDED,
            null,
            -> return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
        }
    }

    /**
     * Fails closed over process death between durable phase one and reducer-owned phase two. A
     * staged verified strategy is discarded, never made visible; a staged cancellation becomes
     * terminal. Current-process work and any attempt protected by an outbox row are untouched.
     */
    suspend fun reconcilePriorEpochPendingPersistenceAttempts():
        PlaybackPersistenceRecoveryHandlingOutcome = database.withTransaction {
        reconcilePriorEpochPendingPersistenceAttemptsInTransaction()
    }

    private suspend fun reconcilePriorEpochPendingPersistenceAttemptsInTransaction():
        PlaybackPersistenceRecoveryHandlingOutcome {
        if (hasAnyRecoveryBlocker()) {
            return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
        }
        val priorAttempts = dao.priorEpochPendingAttempts(
            currentEpoch = persistenceEpoch,
            limit = maximumPriorEpochPendingReconciliations + 1,
        )
        if (priorAttempts.size > maximumPriorEpochPendingReconciliations) {
            database.localSchemaMetadata().upsert(
                LocalSchemaMetadataEntity(
                    key = PLAYBACK_PERSISTENCE_PRIOR_EPOCH_SATURATION_METADATA_KEY,
                    value = "1",
                ),
            )
            return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
        }

        // Validate the entire deterministic batch before changing any row. BLOCKED must never
        // commit a partial reconciliation when invoked inside the adapter's outer transaction.
        priorAttempts.forEach { attempt ->
            if (
                attempt.retentionEpoch == persistenceEpoch ||
                !isValidPersistenceEpoch(attempt.retentionEpoch)
            ) {
                return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
            }
            val status = AttemptStatus.entries.singleOrNull { it.name == attempt.status }
                ?: return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
            val pending = dao.pendingStrategy(attempt.attemptKey)
            val references = referencedStrategies(attempt.attemptKey, attempt)
            when (status) {
                AttemptStatus.COMMITTED_PENDING_RESOLUTION -> if (
                    pending == null ||
                    !pending.hasExactPendingRelation(attempt) ||
                    references.isNotEmpty()
                ) {
                    return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                }

                AttemptStatus.CANCELLED_PENDING_RESOLUTION -> if (
                    pending != null ||
                    references.isNotEmpty() ||
                    attempt.recordGeneration != null ||
                    attempt.identityKey?.let(PlaybackStrategyMapper::isDigestKey) != true ||
                    attempt.decisionFingerprint?.let(PlaybackStrategyMapper::isDigestKey) != true ||
                    attempt.writeFingerprint?.let(PlaybackStrategyMapper::isDigestKey) == false ||
                    attempt.finalizationFingerprint != null
                ) {
                    return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
                }

                else -> return PlaybackPersistenceRecoveryHandlingOutcome.BLOCKED
            }
        }

        val observedAt = clock.elapsedRealtimeMillis().coerceAtLeast(0L)
        priorAttempts.forEach { attempt ->
            val committedPending =
                attempt.status == AttemptStatus.COMMITTED_PENDING_RESOLUTION.name
            if (committedPending) {
                check(dao.deletePendingStrategy(attempt.attemptKey) == 1) {
                    "Prior-process pending playback strategy disappeared"
                }
            }
            check(
                dao.updateAttempt(
                    attemptKey = attempt.attemptKey,
                    identityKey = attempt.identityKey,
                    writeFingerprint = attempt.writeFingerprint,
                    decisionFingerprint = attempt.decisionFingerprint,
                    finalizationFingerprint =
                        PlaybackStrategyMapper.priorEpochRecoveryFingerprint(attempt),
                    status = if (committedPending) {
                        AttemptStatus.QUARANTINED.name
                    } else {
                        AttemptStatus.CANCELLED.name
                    },
                    recordGeneration = if (committedPending) attempt.recordGeneration else null,
                    retainUntilElapsedMillis = barrierRetentionDeadline(observedAt),
                    retentionEpoch = persistenceEpoch,
                ) == 1,
            ) { "Prior-process playback attempt disappeared" }
        }
        pruneAttempts(observedAt)
        return PlaybackPersistenceRecoveryHandlingOutcome.CONCLUSIVELY_HANDLED
    }

    override suspend fun reset(identity: PlaybackCompatibilityIdentity) {
        database.withTransaction {
            val identityKey = PlaybackStrategyMapper.identityKey(identity)
            // Reset cannot erase the evidence needed to finish an exact durable recovery.
            if (hasRecoveryBarrier(identityKey)) return@withTransaction
            val observedAt = clock.elapsedRealtimeMillis()
            val existingBarrier = dao.resetBarrier(identityKey)
            val resetAt = when {
                observedAt < 0L -> Long.MAX_VALUE
                existingBarrier == null || existingBarrier.resetEpoch != persistenceEpoch -> observedAt
                !existingBarrier.hasValidShape() -> observedAt
                else -> maxOf(existingBarrier.resetAtElapsedMillis, observedAt)
            }
            val retainUntil = if (resetAt == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                maxOf(
                    existingBarrier
                        ?.takeIf { it.resetEpoch == persistenceEpoch }
                        ?.retainUntilElapsedMillis
                        ?: 0L,
                    barrierRetentionDeadline(resetAt),
                )
            }
            dao.upsertResetBarrier(
                PlaybackStrategyResetBarrierEntity(
                    identityKey = identityKey,
                    resetAtElapsedMillis = resetAt,
                    retainUntilElapsedMillis = retainUntil,
                    resetEpoch = persistenceEpoch,
                ),
            )
            val safeObservedAt = observedAt.coerceAtLeast(0L)
            dao.failPendingAttemptsForIdentity(
                identityKey = identityKey,
                retainUntilElapsedMillis = barrierRetentionDeadline(safeObservedAt),
                retentionEpoch = persistenceEpoch,
            )
            dao.deletePendingStrategiesForIdentity(identityKey)
            dao.deleteStrategy(identityKey)
            pruneResetBarriers(safeObservedAt)
            pruneAttempts(safeObservedAt)
        }
    }

    private suspend fun allocateGeneration(): Long? {
        val metadata = database.localSchemaMetadata()
        val maximumPersisted = dao.maximumPersistedGeneration()
        val existingAuthority = dao.generation()
        val existingCheckpoint = metadata.value(GENERATION_AUTHORITY_METADATA_KEY)
        val authority = if (existingAuthority == null) {
            if (maximumPersisted != null || existingCheckpoint != null) return null
            PlaybackStrategyGenerationEntity(lastGeneration = 0L).also { initial ->
                dao.upsertGeneration(initial)
                metadata.upsert(
                    LocalSchemaMetadataEntity(
                        key = GENERATION_AUTHORITY_METADATA_KEY,
                        value = initial.lastGeneration.toString(),
                    ),
                )
            }
        } else {
            existingAuthority
        }
        val checkpoint = metadata.value(GENERATION_AUTHORITY_METADATA_KEY)
        val current = authority.lastGeneration
        if (
            current < 0L ||
            checkpoint != current.toString() ||
            (maximumPersisted != null && maximumPersisted > current) ||
            current == Long.MAX_VALUE
        ) {
            return null
        }
        val next = current + 1L
        dao.upsertGeneration(PlaybackStrategyGenerationEntity(lastGeneration = next))
        metadata.upsert(
            LocalSchemaMetadataEntity(
                key = GENERATION_AUTHORITY_METADATA_KEY,
                value = next.toString(),
            ),
        )
        return next
    }

    private suspend fun upsertAttemptState(
        attemptId: PlaybackAttemptId,
        identityKey: String?,
        writeFingerprint: String?,
        status: AttemptStatus,
        recordGeneration: Long?,
        retainUntilElapsedMillis: Long,
        decisionFingerprint: String? = null,
        finalizationFingerprint: String? = null,
    ): Long {
        val attemptKey = PlaybackStrategyMapper.attemptKey(attemptId)
        val inserted = dao.insertAttempt(
            PlaybackStrategyWriteAttemptEntity(
                attemptKey = attemptKey,
                identityKey = identityKey,
                writeFingerprint = writeFingerprint,
                decisionFingerprint = decisionFingerprint,
                finalizationFingerprint = finalizationFingerprint,
                status = status.name,
                recordGeneration = recordGeneration,
                retainUntilElapsedMillis = retainUntilElapsedMillis,
                retentionEpoch = persistenceEpoch,
            ),
        )
        if (inserted != -1L) return inserted
        check(
            dao.updateAttempt(
                attemptKey,
                identityKey,
                writeFingerprint,
                decisionFingerprint,
                finalizationFingerprint,
                status.name,
                recordGeneration,
                retainUntilElapsedMillis,
                persistenceEpoch,
            ) == 1,
        ) { "Playback write-attempt state disappeared during its transaction" }
        return requireNotNull(dao.attempt(attemptKey)).rowId
    }

    private suspend fun rejectWriteBeforeCommit(
        write: VerifiedStrategyWrite,
        identityKey: String,
        writeFingerprint: String,
        observedAt: Long,
    ) {
        val safeObservedAt = observedAt.coerceAtLeast(0L)
        upsertAttemptState(
            write.attemptId,
            identityKey,
            writeFingerprint,
            AttemptStatus.FAILED,
            recordGeneration = null,
            retainUntilElapsedMillis = barrierRetentionDeadline(safeObservedAt),
            decisionFingerprint = PlaybackStrategyMapper.decisionFingerprint(write.record),
        )
        pruneAttempts(safeObservedAt)
    }

    private suspend fun pruneAttempts(elapsedRealtimeMillis: Long) {
        dao.pruneResolvedAttempts(
            retentionPolicy.retainedResolvedAttempts,
            elapsedRealtimeMillis,
            persistenceEpoch,
        )
    }

    private suspend fun pruneStrategiesProtecting(identityKey: String) {
        dao.pruneStrategiesProtecting(
            protectedIdentityKey = identityKey,
            retainedOtherStrategies = retentionPolicy.retainedStrategies - 1,
        )
    }

    private suspend fun hasExactPersistedRecoveryObligation(
        obligation: PersistedPlaybackPersistenceRecoveryObligation,
    ): Boolean {
        val inspection = recoveryDao.obligation(obligation.token.attemptKey)
            ?.let(PlaybackPersistenceRecoveryMapper::inspect)
            as? PlaybackPersistenceRecoveryInspection.Pending
            ?: return false
        val persisted = inspection.obligation
        return persisted.rowId == obligation.token.rowId &&
            persisted.attemptKey == obligation.token.attemptKey &&
            persisted.requestFingerprint == obligation.token.requestFingerprint &&
            persisted.identityKey == obligation.identityKey &&
            persisted.sourceScope == obligation.sourceScope &&
            persisted.audioMode == obligation.audioMode &&
            persisted.transportMode == obligation.transportMode &&
            persisted.decoderMode == obligation.decoderMode &&
            persisted.expectedRecordGeneration == obligation.expectedRecordGeneration &&
            persisted.fallbackKnownGoodRecordGeneration ==
            obligation.fallbackKnownGoodRecordGeneration &&
            persisted.disposition == obligation.disposition &&
            persisted.category == obligation.category &&
            persisted.modelVersion == obligation.modelVersion
    }

    /** Null means either no fallback was requested or the exact generation is absent. */
    private suspend fun exactPersistedFallbackOrNull(
        obligation: PersistedPlaybackPersistenceRecoveryObligation,
    ): PlaybackStrategyEntity? {
        val generation = obligation.fallbackKnownGoodRecordGeneration ?: return null
        return dao.strategy(obligation.identityKey)?.takeIf { strategy ->
            PlaybackStrategyMapper.matchesDecision(strategy, obligation, generation)
        }
    }

    private suspend fun invalidatePersistedStrategy(
        strategy: PlaybackStrategyEntity,
        failureObservedAt: Long,
    ) {
        if (strategy.invalidated) return
        check(
            dao.updateFailureState(
                identityKey = strategy.identityKey,
                recordGeneration = strategy.recordGeneration,
                failureCount = MAX_FAILURE_COUNT,
                failureWindowStartedWallClockMillis = failureObservedAt,
                invalidated = true,
            ) == 1,
        ) { "Playback strategy disappeared during persisted recovery" }
    }

    private suspend fun hasAnyRecoveryBlocker(): Boolean =
        database.localSchemaMetadata()
            .value(PLAYBACK_PERSISTENCE_RECOVERY_SATURATION_METADATA_KEY) != null ||
            database.localSchemaMetadata()
                .value(PLAYBACK_PERSISTENCE_PRIOR_EPOCH_SATURATION_METADATA_KEY) != null ||
            recoveryDao.all().isNotEmpty()

    private suspend fun hasRecoveryBarrier(
        identityKey: String,
        attemptKey: String? = null,
    ): Boolean {
        if (
            database.localSchemaMetadata()
                .value(PLAYBACK_PERSISTENCE_RECOVERY_SATURATION_METADATA_KEY) != null ||
            database.localSchemaMetadata()
                .value(PLAYBACK_PERSISTENCE_PRIOR_EPOCH_SATURATION_METADATA_KEY) != null
        ) {
            return true
        }
        recoveryDao.all().forEach { entity ->
            when (val inspection = PlaybackPersistenceRecoveryMapper.inspect(entity)) {
                is PlaybackPersistenceRecoveryInspection.Corrupt -> return true
                is PlaybackPersistenceRecoveryInspection.Pending -> if (
                    inspection.obligation.identityKey == identityKey ||
                    inspection.obligation.attemptKey == attemptKey
                ) {
                    return true
                }
                // The conflicting request's identity is intentionally not retained, so a sticky
                // same-attempt semantic collision must block every identity rather than only the
                // first admitted request's identity.
                is PlaybackPersistenceRecoveryInspection.Conflict -> return true
            }
        }
        return false
    }

    private suspend fun pruneResetBarriers(elapsedRealtimeMillis: Long) {
        dao.pruneResetBarriers(
            retentionPolicy.retainedResetBarriers,
            elapsedRealtimeMillis,
            persistenceEpoch,
        )
    }

    private fun barrierRetentionDeadline(elapsedRealtimeMillis: Long): Long =
        retentionDeadline(elapsedRealtimeMillis, retentionPolicy.cancellationBarrierMillis)

    private fun failureMarkerRetentionDeadline(elapsedRealtimeMillis: Long): Long =
        retentionDeadline(elapsedRealtimeMillis, retentionPolicy.implicatedFailureWindowMillis)

    private fun retentionDeadline(elapsedRealtimeMillis: Long, durationMillis: Long): Long =
        if (elapsedRealtimeMillis > Long.MAX_VALUE - durationMillis) Long.MAX_VALUE
        else elapsedRealtimeMillis + durationMillis

    private fun requireValidPersistenceEpoch(value: String) {
        require(isValidPersistenceEpoch(value)) { "Persistence epoch must be a bounded opaque token" }
    }

    private fun isValidPersistenceEpoch(value: String): Boolean =
        value.length in 1..128 && value.all { character ->
            character.isLetterOrDigit() || character in ".:_-"
        }

    private fun PlaybackStrategyResetBarrierEntity.rejects(
        write: VerifiedStrategyWrite,
        admissionSample: Long,
    ): Boolean {
        if (!hasValidShape()) return true
        if (resetEpoch != persistenceEpoch) return false
        return admissionSample < resetAtElapsedMillis ||
            write.issuedAtElapsedMillis <= resetAtElapsedMillis
    }

    private fun PlaybackStrategyResetBarrierEntity.hasValidShape(): Boolean =
        resetAtElapsedMillis >= 0L &&
            retainUntilElapsedMillis >= resetAtElapsedMillis &&
            retainUntilElapsedMillis - resetAtElapsedMillis >=
            MAX_PERSISTENCE_WRITE_HORIZON_MILLIS &&
            isValidPersistenceEpoch(resetEpoch)

    private fun PlaybackStrategyWriteAttemptEntity.hasStructurallyValidCommittedShape(): Boolean =
        recordGeneration?.let { it > 0L } == true &&
            identityKey?.let(PlaybackStrategyMapper::isDigestKey) == true &&
            writeFingerprint?.let(PlaybackStrategyMapper::isDigestKey) == true &&
            decisionFingerprint?.let(PlaybackStrategyMapper::isDigestKey) == true

    private fun PlaybackStrategyWriteAttemptEntity.hasValidTerminalDecision(): Boolean =
        finalizationFingerprint?.let(PlaybackStrategyMapper::isDigestKey) == true

    private fun PlaybackStrategyWriteAttemptEntity.hasExactCancellationShape(
        expectedIdentityKey: String,
        expectedDecisionFingerprint: String,
    ): Boolean =
        recordGeneration == null &&
            identityKey == expectedIdentityKey &&
            decisionFingerprint == expectedDecisionFingerprint &&
            PlaybackStrategyMapper.isDigestKey(expectedIdentityKey) &&
            PlaybackStrategyMapper.isDigestKey(expectedDecisionFingerprint)

    private fun PlaybackStrategyWriteAttemptEntity.hasExactPrecommitShape(
        expectedIdentityKey: String,
        expectedDecisionFingerprint: String,
    ): Boolean = hasExactCancellationShape(expectedIdentityKey, expectedDecisionFingerprint)

    private fun PlaybackStrategyEntity.hasExactCommitRelation(
        attempt: PlaybackStrategyWriteAttemptEntity,
    ): Boolean =
        commitAttemptRowId == attempt.rowId &&
            PlaybackStrategyMapper.isDigestKey(commitAttemptKey) &&
            commitAttemptKey == attempt.attemptKey &&
            PlaybackStrategyMapper.isDigestKey(commitWriteFingerprint) &&
            identityKey == attempt.identityKey &&
            recordGeneration == attempt.recordGeneration &&
            commitWriteFingerprint == attempt.writeFingerprint &&
            PlaybackStrategyMapper.decisionFingerprint(this) == attempt.decisionFingerprint &&
            PlaybackStrategyMapper.recordFingerprint(this) == commitWriteFingerprint &&
            attempt.hasStructurallyValidCommittedShape() &&
            attempt.hasValidTerminalDecision()

    private fun PendingPlaybackStrategyEntity.hasExactPendingRelation(
        attempt: PlaybackStrategyWriteAttemptEntity,
    ): Boolean =
        attempt.status == AttemptStatus.COMMITTED_PENDING_RESOLUTION.name &&
            attempt.finalizationFingerprint == null &&
        attemptRowId == attempt.rowId &&
            PlaybackStrategyMapper.isDigestKey(attemptKey) &&
            attemptKey == attempt.attemptKey &&
            PlaybackStrategyMapper.isDigestKey(writeFingerprint) &&
            PlaybackStrategyMapper.isDigestKey(decisionFingerprint) &&
            identityKey == attempt.identityKey &&
            recordGeneration == attempt.recordGeneration &&
            writeFingerprint == attempt.writeFingerprint &&
            decisionFingerprint == attempt.decisionFingerprint &&
            PlaybackStrategyMapper.pendingRecordFingerprint(this) == writeFingerprint &&
            PlaybackStrategyMapper.decisionFingerprint(this) == decisionFingerprint &&
            attempt.hasStructurallyValidCommittedShape()

    private fun StrategyPersistenceFinalization.toImplicatedFailure(
        recordGeneration: Long,
    ): ImplicatedStrategyFailure = ImplicatedStrategyFailure(
        attemptId = context.attemptId,
        identity = context.identity,
        sourceScope = context.sourceScope,
        audioMode = context.audioMode,
        transportMode = context.transportMode,
        decoderMode = context.decoderMode,
        recordGeneration = recordGeneration,
        category = requireNotNull(category),
    )

    private suspend fun hasExactFailureMarker(failure: ImplicatedStrategyFailure): Boolean {
        val marker = dao.attempt(PlaybackStrategyMapper.failureKey(failure.attemptId)) ?: return false
        return marker.status == AttemptStatus.FAILURE_RECORDED.name &&
            marker.identityKey == PlaybackStrategyMapper.identityKey(failure.identity) &&
            marker.recordGeneration == failure.recordGeneration &&
            marker.writeFingerprint == null &&
            marker.decisionFingerprint == null &&
            marker.finalizationFingerprint == null
    }

    private suspend fun referencedStrategies(
        attemptKey: String,
        attempt: PlaybackStrategyWriteAttemptEntity?,
    ): List<PlaybackStrategyEntity> {
        val directReferences = dao.strategiesReferencingAttempt(
            attemptKey = attemptKey,
            attemptRowId = attempt?.rowId ?: 0L,
        )
        val generationReference = attempt
            ?.takeIf { current ->
                current.identityKey != null && current.recordGeneration?.let { it > 0L } == true
            }
            ?.let { current -> dao.strategy(requireNotNull(current.identityKey)) }
            ?.takeIf { strategy -> strategy.recordGeneration == attempt.recordGeneration }
        return (directReferences + listOfNotNull(generationReference))
            .distinctBy(PlaybackStrategyEntity::identityKey)
    }

    private suspend fun markAmbiguousCorrelation(
        attemptId: PlaybackAttemptId,
        attempt: PlaybackStrategyWriteAttemptEntity?,
        references: List<PlaybackStrategyEntity>,
        observedAt: Long,
    ) {
        val requestedAttemptKey = PlaybackStrategyMapper.attemptKey(attemptId)
        dao.deletePendingStrategy(requestedAttemptKey)
        val exactAnchor = attempt?.let { current ->
            references.filter { reference -> reference.hasExactCommitRelation(current) }
                .singleOrNull()
        }
        val directAnchor = references.filter { reference ->
            reference.commitAttemptKey == requestedAttemptKey ||
                (attempt != null && reference.commitAttemptRowId == attempt.rowId)
        }.singleOrNull()
        val anchor = exactAnchor ?: directAnchor
        listOfNotNull(attempt).forEach { existing ->
            check(
                dao.updateAttempt(
                    attemptKey = existing.attemptKey,
                    identityKey = anchor?.identityKey,
                    writeFingerprint = anchor?.commitWriteFingerprint,
                    decisionFingerprint = existing.decisionFingerprint,
                    finalizationFingerprint = existing.finalizationFingerprint,
                    status = AttemptStatus.AMBIGUOUS_QUARANTINED.name,
                    recordGeneration = anchor?.recordGeneration,
                    retainUntilElapsedMillis = barrierRetentionDeadline(observedAt),
                    retentionEpoch = persistenceEpoch,
                ) == 1,
            ) { "Playback write-attempt state disappeared during ambiguity quarantine" }
        }
        if (attempt?.attemptKey != requestedAttemptKey) {
            upsertAttemptState(
                attemptId = attemptId,
                identityKey = anchor?.identityKey,
                writeFingerprint = anchor?.commitWriteFingerprint,
                status = AttemptStatus.AMBIGUOUS_QUARANTINED,
                recordGeneration = anchor?.recordGeneration,
                retainUntilElapsedMillis = barrierRetentionDeadline(observedAt),
            )
        }
        pruneAttempts(observedAt)
    }

    private enum class AttemptStatus {
        COMMITTED_PENDING_RESOLUTION,
        CANCELLED_PENDING_RESOLUTION,
        COMMITTED,
        CANCELLED,
        TIMED_OUT,
        FAILED,
        QUARANTINED,
        AMBIGUOUS_QUARANTINED,
        FAILURE_RECORDED,
    }
}

private object PlaybackStrategyMapper {
    fun isDigestKey(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    fun identityKey(identity: PlaybackCompatibilityIdentity): String = digest(
        "identity-v${PLAYBACK_COMPATIBILITY_MODEL_VERSION}",
        identity.profileScope.value,
        identity.cameraScope.value,
        identity.authorizationScope.value,
        identity.deviceScope.value,
        identity.osApiLevel.toString(),
        identity.appCompatibilityRevision.toString(),
        identity.serverApiGeneration.value,
        identity.streamConfigurationRevision.value,
        identity.purpose.name,
        identity.resourceClass.name,
        identity.modelVersion.toString(),
    )

    fun attemptKey(attemptId: PlaybackAttemptId): String = digest(
        "attempt-v1",
        attemptId.sessionId.value,
        attemptId.ordinal.toString(),
    )

    fun failureKey(attemptId: PlaybackAttemptId): String = digest(
        "failure-v1",
        attemptId.sessionId.value,
        attemptId.ordinal.toString(),
    )

    fun writeFingerprint(record: PersistablePlaybackStrategy): String = digest(
        "write-v${record.modelVersion}",
        identityKey(record.identity),
        record.sourceScope.value,
        record.media.videoCodec.name,
        record.media.audioCodec?.name.orEmpty(),
        record.media.width?.toString().orEmpty(),
        record.media.height?.toString().orEmpty(),
        record.audioMode.name,
        record.transportMode.name,
        record.decoderMode.name,
        record.evidence.firstFrameLatencyMillis.toString(),
        record.evidence.stablePlaybackDurationMillis.toString(),
        record.evidence.videoProgressEventCount.toString(),
        record.evidence.audioProgressEventCount.toString(),
        record.evidence.decoderEvidence.implementation.name,
        record.evidence.decoderEvidence.sanitizedName?.value.orEmpty(),
    )

    fun decisionFingerprint(record: PersistablePlaybackStrategy): String = digest(
        "decision-v1",
        identityKey(record.identity),
        record.sourceScope.value,
        record.audioMode.name,
        record.transportMode.name,
        record.decoderMode.name,
    )

    fun decisionFingerprint(context: StrategyPersistenceDecisionContext): String = digest(
        "decision-v1",
        identityKey(context.identity),
        context.sourceScope.value,
        context.audioMode.name,
        context.transportMode.name,
        context.decoderMode.name,
    )

    fun decisionFingerprint(request: UnresolvedStrategyPersistence): String = digest(
        "decision-v1",
        identityKey(request.identity),
        request.sourceScope.value,
        request.audioMode.name,
        request.transportMode.name,
        request.decoderMode.name,
    )

    fun decisionFingerprint(
        obligation: PersistedPlaybackPersistenceRecoveryObligation,
    ): String = digest(
        "decision-v1",
        obligation.identityKey,
        obligation.sourceScope.value,
        obligation.audioMode.name,
        obligation.transportMode.name,
        obligation.decoderMode.name,
    )

    fun decisionFingerprint(entity: PlaybackStrategyEntity): String = digest(
        "decision-v1",
        entity.identityKey,
        entity.sourceScope,
        entity.audioMode,
        entity.transportMode,
        entity.decoderMode,
    )

    fun finalizationFingerprint(finalization: StrategyPersistenceFinalization): String = digest(
        "finalization-v1",
        attemptKey(finalization.context.attemptId),
        decisionFingerprint(finalization.context),
        finalization.expectedRecordGeneration?.toString().orEmpty(),
        finalization.context.fallbackKnownGoodRecordGeneration?.toString().orEmpty(),
        finalization.disposition.name,
        finalization.category?.name.orEmpty(),
    )

    fun recoveryFingerprint(request: UnresolvedStrategyPersistence): String = digest(
        "recovery-v1",
        attemptKey(request.attemptId),
        decisionFingerprint(request),
        request.expectedRecordGeneration?.toString().orEmpty(),
        request.fallbackKnownGoodRecordGeneration?.toString().orEmpty(),
        request.disposition.name,
        request.category?.name.orEmpty(),
    )

    fun priorEpochRecoveryFingerprint(
        attempt: PlaybackStrategyWriteAttemptEntity,
    ): String = digest(
        "prior-epoch-recovery-v1",
        attempt.attemptKey,
        attempt.identityKey.orEmpty(),
        attempt.writeFingerprint.orEmpty(),
        attempt.decisionFingerprint.orEmpty(),
        attempt.recordGeneration?.toString().orEmpty(),
        attempt.retentionEpoch,
    )

    fun toEntity(
        record: PersistablePlaybackStrategy,
        identityKey: String,
        generation: Long,
        commitAttemptRowId: Long,
        commitAttemptKey: String,
        commitWriteFingerprint: String,
    ): PlaybackStrategyEntity {
        require(commitAttemptRowId > 0L)
        require(isDigestKey(commitAttemptKey))
        require(isDigestKey(commitWriteFingerprint))
        require(writeFingerprint(record) == commitWriteFingerprint)
        val identity = record.identity
        val media = record.media
        val evidence = record.evidence
        return PlaybackStrategyEntity(
            identityKey = identityKey,
            profileScope = identity.profileScope.value,
            cameraScope = identity.cameraScope.value,
            authorizationScope = identity.authorizationScope.value,
            deviceScope = identity.deviceScope.value,
            osApiLevel = identity.osApiLevel,
            appCompatibilityRevision = identity.appCompatibilityRevision,
            serverApiGeneration = identity.serverApiGeneration.value,
            streamConfigurationRevision = identity.streamConfigurationRevision.value,
            purpose = identity.purpose.name,
            resourceClass = identity.resourceClass.name,
            identityModelVersion = identity.modelVersion,
            recordGeneration = generation,
            commitAttemptRowId = commitAttemptRowId,
            commitAttemptKey = commitAttemptKey,
            commitWriteFingerprint = commitWriteFingerprint,
            sourceScope = record.sourceScope.value,
            videoCodec = media.videoCodec.name,
            audioCodec = media.audioCodec?.name,
            width = media.width,
            height = media.height,
            audioMode = record.audioMode.name,
            transportMode = record.transportMode.name,
            decoderMode = record.decoderMode.name,
            firstFrameLatencyMillis = evidence.firstFrameLatencyMillis,
            stablePlaybackDurationMillis = evidence.stablePlaybackDurationMillis,
            videoProgressEventCount = evidence.videoProgressEventCount,
            audioProgressEventCount = evidence.audioProgressEventCount,
            decoderImplementation = evidence.decoderEvidence.implementation.name,
            sanitizedDecoderName = evidence.decoderEvidence.sanitizedName?.value,
            implicatedFailureCount = 0,
            failureWindowStartedWallClockMillis = null,
            invalidated = false,
        )
    }

    fun toPendingEntity(
        record: PersistablePlaybackStrategy,
        identityKey: String,
        generation: Long,
        attemptRowId: Long,
        attemptKey: String,
        writeFingerprint: String,
        decisionFingerprint: String,
    ): PendingPlaybackStrategyEntity {
        require(decisionFingerprint(record) == decisionFingerprint)
        val entity = toEntity(
            record = record,
            identityKey = identityKey,
            generation = generation,
            commitAttemptRowId = attemptRowId,
            commitAttemptKey = attemptKey,
            commitWriteFingerprint = writeFingerprint,
        )
        return PendingPlaybackStrategyEntity(
            attemptKey = attemptKey,
            attemptRowId = attemptRowId,
            identityKey = entity.identityKey,
            writeFingerprint = entity.commitWriteFingerprint,
            decisionFingerprint = decisionFingerprint,
            profileScope = entity.profileScope,
            cameraScope = entity.cameraScope,
            authorizationScope = entity.authorizationScope,
            deviceScope = entity.deviceScope,
            osApiLevel = entity.osApiLevel,
            appCompatibilityRevision = entity.appCompatibilityRevision,
            serverApiGeneration = entity.serverApiGeneration,
            streamConfigurationRevision = entity.streamConfigurationRevision,
            purpose = entity.purpose,
            resourceClass = entity.resourceClass,
            identityModelVersion = entity.identityModelVersion,
            recordGeneration = entity.recordGeneration,
            sourceScope = entity.sourceScope,
            videoCodec = entity.videoCodec,
            audioCodec = entity.audioCodec,
            width = entity.width,
            height = entity.height,
            audioMode = entity.audioMode,
            transportMode = entity.transportMode,
            decoderMode = entity.decoderMode,
            firstFrameLatencyMillis = entity.firstFrameLatencyMillis,
            stablePlaybackDurationMillis = entity.stablePlaybackDurationMillis,
            videoProgressEventCount = entity.videoProgressEventCount,
            audioProgressEventCount = entity.audioProgressEventCount,
            decoderImplementation = entity.decoderImplementation,
            sanitizedDecoderName = entity.sanitizedDecoderName,
        )
    }

    fun toCommittedEntity(
        pending: PendingPlaybackStrategyEntity,
        implicatedFailureCount: Int = 0,
        failureWindowStartedWallClockMillis: Long? = null,
        invalidated: Boolean = false,
    ): PlaybackStrategyEntity = PlaybackStrategyEntity(
        identityKey = pending.identityKey,
        profileScope = pending.profileScope,
        cameraScope = pending.cameraScope,
        authorizationScope = pending.authorizationScope,
        deviceScope = pending.deviceScope,
        osApiLevel = pending.osApiLevel,
        appCompatibilityRevision = pending.appCompatibilityRevision,
        serverApiGeneration = pending.serverApiGeneration,
        streamConfigurationRevision = pending.streamConfigurationRevision,
        purpose = pending.purpose,
        resourceClass = pending.resourceClass,
        identityModelVersion = pending.identityModelVersion,
        recordGeneration = pending.recordGeneration,
        commitAttemptRowId = pending.attemptRowId,
        commitAttemptKey = pending.attemptKey,
        commitWriteFingerprint = pending.writeFingerprint,
        sourceScope = pending.sourceScope,
        videoCodec = pending.videoCodec,
        audioCodec = pending.audioCodec,
        width = pending.width,
        height = pending.height,
        audioMode = pending.audioMode,
        transportMode = pending.transportMode,
        decoderMode = pending.decoderMode,
        firstFrameLatencyMillis = pending.firstFrameLatencyMillis,
        stablePlaybackDurationMillis = pending.stablePlaybackDurationMillis,
        videoProgressEventCount = pending.videoProgressEventCount,
        audioProgressEventCount = pending.audioProgressEventCount,
        decoderImplementation = pending.decoderImplementation,
        sanitizedDecoderName = pending.sanitizedDecoderName,
        implicatedFailureCount = implicatedFailureCount,
        failureWindowStartedWallClockMillis = failureWindowStartedWallClockMillis,
        invalidated = invalidated,
    )

    fun pendingRecordFingerprint(pending: PendingPlaybackStrategyEntity): String? =
        recordFingerprint(toCommittedEntity(pending))

    fun decisionFingerprint(pending: PendingPlaybackStrategyEntity): String = digest(
        "decision-v1",
        pending.identityKey,
        pending.sourceScope,
        pending.audioMode,
        pending.transportMode,
        pending.decoderMode,
    )

    fun toStored(entity: PlaybackStrategyEntity): StoredPlaybackStrategy? = runCatching {
        require(entity.identityModelVersion == PLAYBACK_COMPATIBILITY_MODEL_VERSION)
        require(entity.recordGeneration > 0)
        require(entity.commitAttemptRowId > 0)
        require(isDigestKey(entity.commitAttemptKey))
        require(isDigestKey(entity.commitWriteFingerprint))
        require(entity.implicatedFailureCount in 0..MAX_FAILURE_COUNT)
        require(entity.implicatedFailureCount < FAILURE_INVALIDATION_THRESHOLD)
        require(
            if (entity.implicatedFailureCount == 0) {
                entity.failureWindowStartedWallClockMillis == null
            } else {
                entity.failureWindowStartedWallClockMillis != null &&
                    entity.failureWindowStartedWallClockMillis >= 0L
            },
        )
        val identity = PlaybackCompatibilityIdentity(
            profileScope = CompatibilityIdentityKey.fromPersisted(entity.profileScope),
            cameraScope = CompatibilityIdentityKey.fromPersisted(entity.cameraScope),
            authorizationScope = CompatibilityIdentityKey.fromPersisted(entity.authorizationScope),
            deviceScope = CompatibilityIdentityKey.fromPersisted(entity.deviceScope),
            osApiLevel = entity.osApiLevel,
            appCompatibilityRevision = entity.appCompatibilityRevision,
            serverApiGeneration = CompatibilityIdentityKey.fromPersisted(entity.serverApiGeneration),
            streamConfigurationRevision = CompatibilityIdentityKey.fromPersisted(
                entity.streamConfigurationRevision,
            ),
            purpose = enumValue<PlaybackPurpose>(entity.purpose),
            resourceClass = enumValue<PlaybackResourceClass>(entity.resourceClass),
            modelVersion = entity.identityModelVersion,
        )
        require(identityKey(identity) == entity.identityKey)
        val decoderImplementation = enumValue<DecoderImplementationEvidence>(
            entity.decoderImplementation,
        )
        val decoderName = entity.sanitizedDecoderName?.let(SanitizedDecoderName::fromAdapter)
        val record = PersistablePlaybackStrategy(
                identity = identity,
                sourceScope = CompatibilityIdentityKey.fromPersisted(entity.sourceScope),
                media = PlaybackMediaMetadata(
                    videoCodec = enumValue<VideoCodec>(entity.videoCodec),
                    audioCodec = entity.audioCodec?.let(::enumValue),
                    width = entity.width,
                    height = entity.height,
                ),
                audioMode = enumValue<AudioMode>(entity.audioMode),
                transportMode = enumValue<TransportMode>(entity.transportMode),
                decoderMode = enumValue<DecoderMode>(entity.decoderMode),
                evidence = PlaybackSuccessEvidence(
                    firstFrameLatencyMillis = entity.firstFrameLatencyMillis,
                    stablePlaybackDurationMillis = entity.stablePlaybackDurationMillis,
                    videoProgressEventCount = entity.videoProgressEventCount,
                    audioProgressEventCount = entity.audioProgressEventCount,
                    decoderEvidence = PlaybackDecoderEvidence(decoderImplementation, decoderName),
                ),
                modelVersion = entity.identityModelVersion,
            )
        require(writeFingerprint(record) == entity.commitWriteFingerprint)
        StoredPlaybackStrategy(
            record = record,
            recordGeneration = entity.recordGeneration,
        )
    }.getOrNull()

    fun recordFingerprint(entity: PlaybackStrategyEntity): String? = runCatching {
        val identity = PlaybackCompatibilityIdentity(
            profileScope = CompatibilityIdentityKey.fromPersisted(entity.profileScope),
            cameraScope = CompatibilityIdentityKey.fromPersisted(entity.cameraScope),
            authorizationScope = CompatibilityIdentityKey.fromPersisted(entity.authorizationScope),
            deviceScope = CompatibilityIdentityKey.fromPersisted(entity.deviceScope),
            osApiLevel = entity.osApiLevel,
            appCompatibilityRevision = entity.appCompatibilityRevision,
            serverApiGeneration = CompatibilityIdentityKey.fromPersisted(entity.serverApiGeneration),
            streamConfigurationRevision = CompatibilityIdentityKey.fromPersisted(
                entity.streamConfigurationRevision,
            ),
            purpose = enumValue<PlaybackPurpose>(entity.purpose),
            resourceClass = enumValue<PlaybackResourceClass>(entity.resourceClass),
            modelVersion = entity.identityModelVersion,
        )
        require(identityKey(identity) == entity.identityKey)
        val decoderImplementation = enumValue<DecoderImplementationEvidence>(
            entity.decoderImplementation,
        )
        val decoderName = entity.sanitizedDecoderName?.let(SanitizedDecoderName::fromAdapter)
        writeFingerprint(
            PersistablePlaybackStrategy(
                identity = identity,
                sourceScope = CompatibilityIdentityKey.fromPersisted(entity.sourceScope),
                media = PlaybackMediaMetadata(
                    videoCodec = enumValue<VideoCodec>(entity.videoCodec),
                    audioCodec = entity.audioCodec?.let(::enumValue),
                    width = entity.width,
                    height = entity.height,
                ),
                audioMode = enumValue<AudioMode>(entity.audioMode),
                transportMode = enumValue<TransportMode>(entity.transportMode),
                decoderMode = enumValue<DecoderMode>(entity.decoderMode),
                evidence = PlaybackSuccessEvidence(
                    firstFrameLatencyMillis = entity.firstFrameLatencyMillis,
                    stablePlaybackDurationMillis = entity.stablePlaybackDurationMillis,
                    videoProgressEventCount = entity.videoProgressEventCount,
                    audioProgressEventCount = entity.audioProgressEventCount,
                    decoderEvidence = PlaybackDecoderEvidence(decoderImplementation, decoderName),
                ),
                modelVersion = entity.identityModelVersion,
            ),
        )
    }.getOrNull()

    fun matches(entity: PlaybackStrategyEntity, failure: ImplicatedStrategyFailure): Boolean =
        !entity.invalidated &&
            entity.recordGeneration == failure.recordGeneration &&
            entity.identityKey == identityKey(failure.identity) &&
            entity.sourceScope == failure.sourceScope.value &&
            entity.audioMode == failure.audioMode.name &&
            entity.transportMode == failure.transportMode.name &&
            entity.decoderMode == failure.decoderMode.name

    fun matches(
        entity: PlaybackStrategyEntity,
        context: StrategyPersistenceDecisionContext,
    ): Boolean =
        entity.identityKey == identityKey(context.identity) &&
            entity.sourceScope == context.sourceScope.value &&
            entity.audioMode == context.audioMode.name &&
            entity.transportMode == context.transportMode.name &&
            entity.decoderMode == context.decoderMode.name

    fun matchesDecision(
        entity: PlaybackStrategyEntity,
        request: UnresolvedStrategyPersistence,
        generation: Long,
    ): Boolean = entity.recordGeneration == generation &&
        entity.identityKey == identityKey(request.identity) &&
        entity.sourceScope == request.sourceScope.value &&
        entity.audioMode == request.audioMode.name &&
        entity.transportMode == request.transportMode.name &&
        entity.decoderMode == request.decoderMode.name

    fun matchesDecision(
        entity: PlaybackStrategyEntity,
        obligation: PersistedPlaybackPersistenceRecoveryObligation,
        generation: Long,
    ): Boolean = entity.recordGeneration == generation &&
        entity.identityKey == obligation.identityKey &&
        entity.sourceScope == obligation.sourceScope.value &&
        entity.audioMode == obligation.audioMode.name &&
        entity.transportMode == obligation.transportMode.name &&
        entity.decoderMode == obligation.decoderMode.name

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().single { it.name == value }

    private fun digest(vararg components: String): String {
        val canonical = ByteArrayOutputStream()
        DataOutputStream(canonical).use { output ->
            components.forEach { component ->
                val bytes = component.toByteArray(StandardCharsets.UTF_8)
                output.writeInt(bytes.size)
                output.write(bytes)
            }
        }
        val hex = "0123456789abcdef"
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .flatMap { byte ->
                val value = byte.toInt() and 0xff
                listOf(hex[value ushr 4], hex[value and 0x0f])
            }
            .joinToString(separator = "")
    }
}
