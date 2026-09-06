package app.opah.tv.data.persistence

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import app.opah.tv.playback.compatibility.AudioMode
import app.opah.tv.playback.compatibility.CompatibilityIdentityKey
import app.opah.tv.playback.compatibility.DecoderMode
import app.opah.tv.playback.compatibility.FailureCategory
import app.opah.tv.playback.compatibility.PLAYBACK_COMPATIBILITY_MODEL_VERSION
import app.opah.tv.playback.compatibility.PlaybackAttemptId
import app.opah.tv.playback.compatibility.PlaybackCompatibilityIdentity
import app.opah.tv.playback.compatibility.TransportMode
import app.opah.tv.playback.compatibility.UnresolvedPersistenceDisposition
import app.opah.tv.playback.compatibility.UnresolvedStrategyPersistence
import app.opah.tv.playback.compatibility.implicatesSavedStrategy
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * A durable, privacy-minimized description of persistence work that survived its original
 * callback. It deliberately cannot recreate a playback route, session, credential, media item,
 * or process-local resource capability.
 */
data class PersistedUnresolvedStrategyPersistence(
    val rowId: Long,
    val attemptKey: String,
    val requestFingerprint: String,
    val identityKey: String,
    val sourceScope: CompatibilityIdentityKey,
    val audioMode: AudioMode,
    val transportMode: TransportMode,
    val decoderMode: DecoderMode,
    val expectedRecordGeneration: Long?,
    val fallbackKnownGoodRecordGeneration: Long?,
    val disposition: UnresolvedPersistenceDisposition,
    val category: FailureCategory?,
    val modelVersion: Int,
) {
    init {
        require(rowId > 0L) { "Recovery-obligation row ID must be positive" }
        requireRecoveryDigest(attemptKey, "Recovery attempt key")
        requireRecoveryDigest(requestFingerprint, "Recovery request fingerprint")
        requireRecoveryDigest(identityKey, "Recovery identity key")
        require(expectedRecordGeneration == null || expectedRecordGeneration > 0L)
        require(
            fallbackKnownGoodRecordGeneration == null ||
                fallbackKnownGoodRecordGeneration > 0L,
        )
        require(modelVersion == PLAYBACK_COMPATIBILITY_MODEL_VERSION) {
            "Unsupported playback recovery model version"
        }
        require(
            disposition != UnresolvedPersistenceDisposition.INVALIDATE_IMPLICATED_COMMIT ||
                category?.implicatesSavedStrategy == true,
        ) { "Invalidating recovery requires implicated strategy evidence" }
        require(
            disposition != UnresolvedPersistenceDisposition.PRESERVE_VERIFIED_COMMIT ||
                category == null,
        ) { "Neutral recovery cannot carry failure evidence" }
    }

    fun resolutionToken(): PlaybackPersistenceRecoveryResolutionToken =
        PlaybackPersistenceRecoveryResolutionToken(
            rowId = rowId,
            attemptKey = attemptKey,
            requestFingerprint = requestFingerprint,
        )
}

data class PlaybackPersistenceRecoveryResolutionToken(
    val rowId: Long,
    val attemptKey: String,
    val requestFingerprint: String,
) {
    init {
        require(rowId > 0L) { "Recovery resolution row ID must be positive" }
        requireRecoveryDigest(attemptKey, "Recovery resolution attempt key")
        requireRecoveryDigest(requestFingerprint, "Recovery resolution fingerprint")
    }
}

sealed interface PlaybackPersistenceRecoveryInspection {
    val rowId: Long
    /** Null when local corruption means the stored value is not a safe digest to expose. */
    val attemptKey: String?

    data class Pending(
        val obligation: PersistedUnresolvedStrategyPersistence,
    ) : PlaybackPersistenceRecoveryInspection {
        override val rowId: Long = obligation.rowId
        override val attemptKey: String = obligation.attemptKey
    }

    /** A sticky same-attempt semantic collision. Automatic recovery must not resolve this row. */
    data class Conflict(
        val obligation: PersistedUnresolvedStrategyPersistence,
        val conflictingRequestFingerprint: String,
    ) : PlaybackPersistenceRecoveryInspection {
        override val rowId: Long = obligation.rowId
        override val attemptKey: String = obligation.attemptKey
    }

    /** A fail-closed row whose semantic fingerprint or allowlisted fields cannot be validated. */
    data class Corrupt(
        override val rowId: Long,
        override val attemptKey: String?,
    ) : PlaybackPersistenceRecoveryInspection
}

sealed interface PlaybackPersistenceRecoveryRegistrationOutcome {
    data class Registered(
        val obligation: PersistedUnresolvedStrategyPersistence,
    ) : PlaybackPersistenceRecoveryRegistrationOutcome

    data class ExactReplay(
        val obligation: PersistedUnresolvedStrategyPersistence,
    ) : PlaybackPersistenceRecoveryRegistrationOutcome

    data class Conflict(
        val blocker: PlaybackPersistenceRecoveryInspection.Conflict,
    ) : PlaybackPersistenceRecoveryRegistrationOutcome

    data class Corrupt(
        val blocker: PlaybackPersistenceRecoveryInspection.Corrupt,
    ) : PlaybackPersistenceRecoveryRegistrationOutcome

    data class CapacityReached(
        val maximumOutstandingObligations: Int,
    ) : PlaybackPersistenceRecoveryRegistrationOutcome
}

sealed interface PlaybackPersistenceRecoveryResolutionOutcome {
    data object Resolved : PlaybackPersistenceRecoveryResolutionOutcome

    data object AlreadyAbsent : PlaybackPersistenceRecoveryResolutionOutcome

    data class TokenMismatch(
        val current: PlaybackPersistenceRecoveryInspection,
    ) : PlaybackPersistenceRecoveryResolutionOutcome

    data class Blocked(
        val current: PlaybackPersistenceRecoveryInspection,
    ) : PlaybackPersistenceRecoveryResolutionOutcome
}

enum class PlaybackPersistenceRecoveryBarrier {
    CLEAR,
    BLOCKED_FOR_IDENTITY,
    GLOBAL_CORRUPTION,
}

data class PlaybackPersistenceRecoveryPolicy(
    val maximumOutstandingObligations: Int = MAX_OUTSTANDING_RECOVERY_OBLIGATIONS,
    val startupBatchSize: Int = DEFAULT_RECOVERY_STARTUP_BATCH_SIZE,
) {
    init {
        require(maximumOutstandingObligations in 1..MAX_OUTSTANDING_RECOVERY_OBLIGATIONS)
        require(startupBatchSize in 1..MAX_OUTSTANDING_RECOVERY_OBLIGATIONS)
    }
}

const val MAX_OUTSTANDING_RECOVERY_OBLIGATIONS: Int = 512
private const val DEFAULT_RECOVERY_STARTUP_BATCH_SIZE: Int = 64
internal const val PLAYBACK_PERSISTENCE_RECOVERY_SATURATION_METADATA_KEY =
    "playback_persistence_recovery_saturated_v1"
internal const val PLAYBACK_PERSISTENCE_PRIOR_EPOCH_SATURATION_METADATA_KEY =
    "playback_persistence_prior_epoch_saturated_v1"

@Entity(
    tableName = "playback_strategy_recovery_obligation",
    indices = [
        Index(value = ["attemptKey"], unique = true),
        Index(value = ["identityKey", "state", "rowId"]),
    ],
)
data class PlaybackPersistenceRecoveryObligationEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val attemptKey: String,
    val requestFingerprint: String,
    val identityKey: String,
    val sourceScope: String,
    val audioMode: String,
    val transportMode: String,
    val decoderMode: String,
    val expectedRecordGeneration: Long?,
    val fallbackKnownGoodRecordGeneration: Long?,
    val disposition: String,
    val category: String?,
    val modelVersion: Int,
    val state: String,
    val conflictingRequestFingerprint: String?,
)

@Dao
abstract class PlaybackPersistenceRecoveryDao {
    @Query(
        "SELECT * FROM playback_strategy_recovery_obligation " +
            "WHERE attemptKey = :attemptKey LIMIT 1",
    )
    abstract suspend fun obligation(attemptKey: String): PlaybackPersistenceRecoveryObligationEntity?

    @Query(
        "SELECT * FROM playback_strategy_recovery_obligation " +
            "WHERE rowId > :afterRowId ORDER BY rowId ASC LIMIT :limit",
    )
    abstract suspend fun startupBatch(
        afterRowId: Long,
        limit: Int,
    ): List<PlaybackPersistenceRecoveryObligationEntity>

    @Query("SELECT * FROM playback_strategy_recovery_obligation ORDER BY rowId ASC")
    abstract suspend fun all(): List<PlaybackPersistenceRecoveryObligationEntity>

    @Query("SELECT COUNT(*) FROM playback_strategy_recovery_obligation")
    abstract suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(entity: PlaybackPersistenceRecoveryObligationEntity): Long

    /** Changes only conflict metadata. The first admitted request's semantics are immutable. */
    @Query(
        """
        UPDATE playback_strategy_recovery_obligation
        SET state = 'CONFLICT',
            conflictingRequestFingerprint = CASE
                WHEN conflictingRequestFingerprint IS NULL
                    THEN :conflictingRequestFingerprint
                ELSE conflictingRequestFingerprint
            END
        WHERE attemptKey = :attemptKey
            AND state IN ('PENDING', 'CONFLICT')
        """,
    )
    abstract suspend fun markConflict(
        attemptKey: String,
        conflictingRequestFingerprint: String,
    ): Int

    @Query(
        """
        DELETE FROM playback_strategy_recovery_obligation
        WHERE rowId = :rowId
            AND attemptKey = :attemptKey
            AND requestFingerprint = :requestFingerprint
            AND state = 'PENDING'
            AND conflictingRequestFingerprint IS NULL
        """,
    )
    abstract suspend fun deleteExactPending(
        rowId: Long,
        attemptKey: String,
        requestFingerprint: String,
    ): Int
}

/**
 * Process-death-durable outbox for reducer-owned persistence recovery decisions.
 *
 * Registration, conflict marking, capacity admission, inspection, and exact resolution are all
 * transactional. No pruning or eviction API exists: a row leaves only after a validated pending
 * obligation has been conclusively handled.
 */
class RoomPlaybackPersistenceRecoveryOutbox(
    private val database: OpahDatabase,
    private val policy: PlaybackPersistenceRecoveryPolicy = PlaybackPersistenceRecoveryPolicy(),
) {
    private val dao = database.playbackPersistenceRecoveryDao()
    private val metadata = database.localSchemaMetadata()

    suspend fun register(
        request: UnresolvedStrategyPersistence,
    ): PlaybackPersistenceRecoveryRegistrationOutcome {
        val candidate = PlaybackPersistenceRecoveryMapper.toEntity(request)
        return database.withTransaction {
            val existing = dao.obligation(candidate.attemptKey)
            if (existing != null) {
                return@withTransaction registerAgainstExisting(existing, candidate)
            }
            if (
                metadata.value(PLAYBACK_PERSISTENCE_RECOVERY_SATURATION_METADATA_KEY) != null ||
                metadata.value(PLAYBACK_PERSISTENCE_PRIOR_EPOCH_SATURATION_METADATA_KEY) != null
            ) {
                return@withTransaction PlaybackPersistenceRecoveryRegistrationOutcome.CapacityReached(
                    policy.maximumOutstandingObligations,
                )
            }
            if (dao.count() >= policy.maximumOutstandingObligations) {
                // The rejected request cannot be represented without violating the bounded outbox.
                // Persist a sticky global barrier so process death cannot turn capacity exhaustion
                // into permission to load or save potentially implicated strategies.
                metadata.upsert(
                    LocalSchemaMetadataEntity(
                        key = PLAYBACK_PERSISTENCE_RECOVERY_SATURATION_METADATA_KEY,
                        value = "1",
                    ),
                )
                return@withTransaction PlaybackPersistenceRecoveryRegistrationOutcome.CapacityReached(
                    policy.maximumOutstandingObligations,
                )
            }

            val insertedRowId = dao.insert(candidate)
            val admitted = if (insertedRowId > 0L) {
                requireNotNull(dao.obligation(candidate.attemptKey))
            } else {
                // A second connection or process may have won the unique-attempt admission race.
                requireNotNull(dao.obligation(candidate.attemptKey))
            }
            if (insertedRowId <= 0L) {
                registerAgainstExisting(admitted, candidate)
            } else {
                when (val inspection = PlaybackPersistenceRecoveryMapper.inspect(admitted)) {
                    is PlaybackPersistenceRecoveryInspection.Pending ->
                        PlaybackPersistenceRecoveryRegistrationOutcome.Registered(
                            inspection.obligation,
                        )
                    is PlaybackPersistenceRecoveryInspection.Conflict ->
                        PlaybackPersistenceRecoveryRegistrationOutcome.Conflict(inspection)
                    is PlaybackPersistenceRecoveryInspection.Corrupt ->
                        PlaybackPersistenceRecoveryRegistrationOutcome.Corrupt(inspection)
                }
            }
        }
    }

    suspend fun startupBatch(
        afterRowId: Long = 0L,
        limit: Int = policy.startupBatchSize,
    ): List<PlaybackPersistenceRecoveryInspection> {
        require(afterRowId >= 0L) { "Startup cursor must not be negative" }
        require(limit in 1..MAX_OUTSTANDING_RECOVERY_OBLIGATIONS)
        return database.withTransaction {
            dao.startupBatch(afterRowId, limit).map(PlaybackPersistenceRecoveryMapper::inspect)
        }
    }

    suspend fun inspect(
        request: UnresolvedStrategyPersistence,
    ): PlaybackPersistenceRecoveryInspection? {
        val attemptKey = PlaybackPersistenceRecoveryMapper.attemptKey(request.attemptId)
        return inspectAttemptKey(attemptKey)
    }

    suspend fun inspectAttemptKey(attemptKey: String): PlaybackPersistenceRecoveryInspection? {
        requireRecoveryDigest(attemptKey, "Recovery lookup attempt key")
        return database.withTransaction {
            dao.obligation(attemptKey)?.let(PlaybackPersistenceRecoveryMapper::inspect)
        }
    }

    /**
     * Fails closed globally if any row is corrupt, any conflict is sticky, or a durable recovery
     * capacity marker exists. A valid pending row blocks only its exact identity.
     */
    suspend fun barrier(
        identity: PlaybackCompatibilityIdentity,
    ): PlaybackPersistenceRecoveryBarrier {
        val identityKey = PlaybackPersistenceRecoveryMapper.identityKey(identity)
        return database.withTransaction {
            if (
                metadata.value(PLAYBACK_PERSISTENCE_RECOVERY_SATURATION_METADATA_KEY) != null ||
                metadata.value(PLAYBACK_PERSISTENCE_PRIOR_EPOCH_SATURATION_METADATA_KEY) != null
            ) {
                return@withTransaction PlaybackPersistenceRecoveryBarrier.GLOBAL_CORRUPTION
            }
            var blockedForIdentity = false
            dao.all().forEach { entity ->
                when (val inspection = PlaybackPersistenceRecoveryMapper.inspect(entity)) {
                    is PlaybackPersistenceRecoveryInspection.Corrupt ->
                        return@withTransaction PlaybackPersistenceRecoveryBarrier.GLOBAL_CORRUPTION
                    is PlaybackPersistenceRecoveryInspection.Pending -> {
                        blockedForIdentity = blockedForIdentity ||
                            inspection.obligation.identityKey == identityKey
                    }
                    // Only the first request's identity is retained. A collision may implicate a
                    // different identity, so every sticky conflict is necessarily a global block.
                    is PlaybackPersistenceRecoveryInspection.Conflict ->
                        return@withTransaction PlaybackPersistenceRecoveryBarrier.GLOBAL_CORRUPTION
                }
            }
            if (blockedForIdentity) {
                PlaybackPersistenceRecoveryBarrier.BLOCKED_FOR_IDENTITY
            } else {
                PlaybackPersistenceRecoveryBarrier.CLEAR
            }
        }
    }

    suspend fun outstandingCount(): Int = database.withTransaction { dao.count() }

    internal suspend fun resolveExact(
        token: PlaybackPersistenceRecoveryResolutionToken,
    ): PlaybackPersistenceRecoveryResolutionOutcome = database.withTransaction {
        val currentEntity = dao.obligation(token.attemptKey)
            ?: return@withTransaction PlaybackPersistenceRecoveryResolutionOutcome.AlreadyAbsent
        val current = PlaybackPersistenceRecoveryMapper.inspect(currentEntity)
        if (current !is PlaybackPersistenceRecoveryInspection.Pending) {
            return@withTransaction PlaybackPersistenceRecoveryResolutionOutcome.Blocked(current)
        }
        if (current.obligation.resolutionToken() != token) {
            return@withTransaction PlaybackPersistenceRecoveryResolutionOutcome.TokenMismatch(current)
        }
        if (
            dao.deleteExactPending(
                rowId = token.rowId,
                attemptKey = token.attemptKey,
                requestFingerprint = token.requestFingerprint,
            ) != 1
        ) {
            val reloaded = dao.obligation(token.attemptKey)
            return@withTransaction if (reloaded == null) {
                PlaybackPersistenceRecoveryResolutionOutcome.AlreadyAbsent
            } else {
                PlaybackPersistenceRecoveryResolutionOutcome.TokenMismatch(
                    PlaybackPersistenceRecoveryMapper.inspect(reloaded),
                )
            }
        }
        PlaybackPersistenceRecoveryResolutionOutcome.Resolved
    }

    private suspend fun registerAgainstExisting(
        existing: PlaybackPersistenceRecoveryObligationEntity,
        candidate: PlaybackPersistenceRecoveryObligationEntity,
    ): PlaybackPersistenceRecoveryRegistrationOutcome {
        if (existing.hasSameRequestSemantics(candidate)) {
            return when (val inspection = PlaybackPersistenceRecoveryMapper.inspect(existing)) {
                is PlaybackPersistenceRecoveryInspection.Pending ->
                    PlaybackPersistenceRecoveryRegistrationOutcome.ExactReplay(inspection.obligation)
                is PlaybackPersistenceRecoveryInspection.Conflict ->
                    PlaybackPersistenceRecoveryRegistrationOutcome.Conflict(inspection)
                is PlaybackPersistenceRecoveryInspection.Corrupt ->
                    PlaybackPersistenceRecoveryRegistrationOutcome.Corrupt(inspection)
            }
        }

        dao.markConflict(
            attemptKey = candidate.attemptKey,
            conflictingRequestFingerprint = candidate.requestFingerprint,
        )
        val conflicted = requireNotNull(dao.obligation(candidate.attemptKey))
        return when (val inspection = PlaybackPersistenceRecoveryMapper.inspect(conflicted)) {
            is PlaybackPersistenceRecoveryInspection.Conflict ->
                PlaybackPersistenceRecoveryRegistrationOutcome.Conflict(inspection)
            is PlaybackPersistenceRecoveryInspection.Corrupt ->
                PlaybackPersistenceRecoveryRegistrationOutcome.Corrupt(inspection)
            is PlaybackPersistenceRecoveryInspection.Pending ->
                PlaybackPersistenceRecoveryRegistrationOutcome.Corrupt(
                    PlaybackPersistenceRecoveryInspection.Corrupt(
                        rowId = conflicted.rowId,
                        attemptKey = conflicted.attemptKey.safeRecoveryDigestOrNull(),
                    ),
                )
        }
    }
}

private enum class RecoveryObligationState {
    PENDING,
    CONFLICT,
}

internal object PlaybackPersistenceRecoveryMapper {
    fun attemptKey(attemptId: PlaybackAttemptId): String = digest(
        "attempt-v1",
        attemptId.sessionId.value,
        attemptId.ordinal.toString(),
    )

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

    fun toEntity(
        request: UnresolvedStrategyPersistence,
    ): PlaybackPersistenceRecoveryObligationEntity {
        val attemptKey = attemptKey(request.attemptId)
        val identityKey = identityKey(request.identity)
        val fingerprint = requestFingerprint(
            attemptKey = attemptKey,
            identityKey = identityKey,
            sourceScope = request.sourceScope.value,
            audioMode = request.audioMode.name,
            transportMode = request.transportMode.name,
            decoderMode = request.decoderMode.name,
            expectedRecordGeneration = request.expectedRecordGeneration,
            fallbackKnownGoodRecordGeneration = request.fallbackKnownGoodRecordGeneration,
            disposition = request.disposition.name,
            category = request.category?.name,
            modelVersion = request.identity.modelVersion,
        )
        return PlaybackPersistenceRecoveryObligationEntity(
            attemptKey = attemptKey,
            requestFingerprint = fingerprint,
            identityKey = identityKey,
            sourceScope = request.sourceScope.value,
            audioMode = request.audioMode.name,
            transportMode = request.transportMode.name,
            decoderMode = request.decoderMode.name,
            expectedRecordGeneration = request.expectedRecordGeneration,
            fallbackKnownGoodRecordGeneration = request.fallbackKnownGoodRecordGeneration,
            disposition = request.disposition.name,
            category = request.category?.name,
            modelVersion = request.identity.modelVersion,
            state = RecoveryObligationState.PENDING.name,
            conflictingRequestFingerprint = null,
        )
    }

    fun inspect(
        entity: PlaybackPersistenceRecoveryObligationEntity,
    ): PlaybackPersistenceRecoveryInspection = runCatching {
        require(entity.rowId > 0L)
        requireRecoveryDigest(entity.attemptKey, "Persisted recovery attempt key")
        requireRecoveryDigest(entity.requestFingerprint, "Persisted recovery fingerprint")
        requireRecoveryDigest(entity.identityKey, "Persisted recovery identity key")
        val sourceScope = CompatibilityIdentityKey.fromPersisted(entity.sourceScope)
        val audioMode = enumValue<AudioMode>(entity.audioMode)
        val transportMode = enumValue<TransportMode>(entity.transportMode)
        val decoderMode = enumValue<DecoderMode>(entity.decoderMode)
        val disposition = enumValue<UnresolvedPersistenceDisposition>(entity.disposition)
        val category = entity.category?.let { enumValue<FailureCategory>(it) }
        require(entity.expectedRecordGeneration == null || entity.expectedRecordGeneration > 0L)
        require(
            entity.fallbackKnownGoodRecordGeneration == null ||
                entity.fallbackKnownGoodRecordGeneration > 0L,
        )
        require(entity.modelVersion == PLAYBACK_COMPATIBILITY_MODEL_VERSION)
        require(
            disposition != UnresolvedPersistenceDisposition.INVALIDATE_IMPLICATED_COMMIT ||
                category?.implicatesSavedStrategy == true,
        )
        require(
            disposition != UnresolvedPersistenceDisposition.PRESERVE_VERIFIED_COMMIT ||
                category == null,
        )
        require(
            entity.requestFingerprint == requestFingerprint(
                attemptKey = entity.attemptKey,
                identityKey = entity.identityKey,
                sourceScope = entity.sourceScope,
                audioMode = entity.audioMode,
                transportMode = entity.transportMode,
                decoderMode = entity.decoderMode,
                expectedRecordGeneration = entity.expectedRecordGeneration,
                fallbackKnownGoodRecordGeneration = entity.fallbackKnownGoodRecordGeneration,
                disposition = entity.disposition,
                category = entity.category,
                modelVersion = entity.modelVersion,
            ),
        )

        val obligation = PersistedUnresolvedStrategyPersistence(
            rowId = entity.rowId,
            attemptKey = entity.attemptKey,
            requestFingerprint = entity.requestFingerprint,
            identityKey = entity.identityKey,
            sourceScope = sourceScope,
            audioMode = audioMode,
            transportMode = transportMode,
            decoderMode = decoderMode,
            expectedRecordGeneration = entity.expectedRecordGeneration,
            fallbackKnownGoodRecordGeneration = entity.fallbackKnownGoodRecordGeneration,
            disposition = disposition,
            category = category,
            modelVersion = entity.modelVersion,
        )
        when (enumValue<RecoveryObligationState>(entity.state)) {
            RecoveryObligationState.PENDING -> {
                require(entity.conflictingRequestFingerprint == null)
                PlaybackPersistenceRecoveryInspection.Pending(obligation)
            }
            RecoveryObligationState.CONFLICT -> {
                val conflictFingerprint = requireNotNull(entity.conflictingRequestFingerprint)
                requireRecoveryDigest(conflictFingerprint, "Conflicting recovery fingerprint")
                require(conflictFingerprint != entity.requestFingerprint)
                PlaybackPersistenceRecoveryInspection.Conflict(
                    obligation = obligation,
                    conflictingRequestFingerprint = conflictFingerprint,
                )
            }
        }
    }.getOrElse {
        PlaybackPersistenceRecoveryInspection.Corrupt(
            rowId = entity.rowId,
            attemptKey = entity.attemptKey.safeRecoveryDigestOrNull(),
        )
    }

    private fun requestFingerprint(
        attemptKey: String,
        identityKey: String,
        sourceScope: String,
        audioMode: String,
        transportMode: String,
        decoderMode: String,
        expectedRecordGeneration: Long?,
        fallbackKnownGoodRecordGeneration: Long?,
        disposition: String,
        category: String?,
        modelVersion: Int,
    ): String = digest(
        "recovery-outbox-v1",
        attemptKey,
        identityKey,
        sourceScope,
        audioMode,
        transportMode,
        decoderMode,
        expectedRecordGeneration?.toString().orEmpty(),
        fallbackKnownGoodRecordGeneration?.toString().orEmpty(),
        disposition,
        category.orEmpty(),
        modelVersion.toString(),
    )

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

private fun PlaybackPersistenceRecoveryObligationEntity.hasSameRequestSemantics(
    other: PlaybackPersistenceRecoveryObligationEntity,
): Boolean =
    attemptKey == other.attemptKey &&
        requestFingerprint == other.requestFingerprint &&
        identityKey == other.identityKey &&
        sourceScope == other.sourceScope &&
        audioMode == other.audioMode &&
        transportMode == other.transportMode &&
        decoderMode == other.decoderMode &&
        expectedRecordGeneration == other.expectedRecordGeneration &&
        fallbackKnownGoodRecordGeneration == other.fallbackKnownGoodRecordGeneration &&
        disposition == other.disposition &&
        category == other.category &&
        modelVersion == other.modelVersion

private fun requireRecoveryDigest(value: String, label: String) {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "$label is malformed"
    }
}

private fun String.safeRecoveryDigestOrNull(): String? =
    takeIf { value ->
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
