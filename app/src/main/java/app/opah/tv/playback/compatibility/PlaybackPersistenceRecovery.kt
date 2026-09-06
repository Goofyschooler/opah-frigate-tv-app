package app.opah.tv.playback.compatibility

/**
 * Exact capability for deleting one privacy-minimized persistence-recovery obligation.
 *
 * The three values are redundant on purpose: a stale or forged token must not be able to delete
 * a different row that happens to reuse either a row ID or attempt key.
 */
data class PlaybackPersistenceRecoveryToken(
    val rowId: Long,
    val attemptKey: String,
    val requestFingerprint: String,
) {
    init {
        require(rowId > 0L) { "Recovery row ID must be positive" }
        requireRecoveryHash(attemptKey, "Recovery attempt key")
        requireRecoveryHash(requestFingerprint, "Recovery request fingerprint")
    }
}

/**
 * Process-death-safe recovery data. This intentionally contains no route, URI, credential,
 * media item, lease, player, decoder, socket, or other process-local capability.
 */
data class PersistedPlaybackPersistenceRecoveryObligation(
    val token: PlaybackPersistenceRecoveryToken,
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
        requireRecoveryHash(identityKey, "Recovery identity key")
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
}

sealed interface PlaybackPersistenceRecoveryRegistrationOutcome {
    val token: PlaybackPersistenceRecoveryToken?

    data class Registered(
        override val token: PlaybackPersistenceRecoveryToken,
    ) : PlaybackPersistenceRecoveryRegistrationOutcome

    data class ExactReplay(
        override val token: PlaybackPersistenceRecoveryToken,
    ) : PlaybackPersistenceRecoveryRegistrationOutcome

    data object Conflict : PlaybackPersistenceRecoveryRegistrationOutcome {
        override val token: PlaybackPersistenceRecoveryToken? = null
    }

    data object Corrupt : PlaybackPersistenceRecoveryRegistrationOutcome {
        override val token: PlaybackPersistenceRecoveryToken? = null
    }

    data object CapacityReached : PlaybackPersistenceRecoveryRegistrationOutcome {
        override val token: PlaybackPersistenceRecoveryToken? = null
    }

    data object Unavailable : PlaybackPersistenceRecoveryRegistrationOutcome {
        override val token: PlaybackPersistenceRecoveryToken? = null
    }
}

sealed interface PlaybackPersistenceRecoveryStartupEntry {
    val rowId: Long

    data class Pending(
        val obligation: PersistedPlaybackPersistenceRecoveryObligation,
    ) : PlaybackPersistenceRecoveryStartupEntry {
        override val rowId: Long = obligation.token.rowId
    }

    data class Conflict(
        override val rowId: Long,
    ) : PlaybackPersistenceRecoveryStartupEntry {
        init {
            require(rowId > 0L)
        }
    }

    data class Corrupt(
        override val rowId: Long,
    ) : PlaybackPersistenceRecoveryStartupEntry {
        init {
            require(rowId > 0L)
        }
    }
}

/** Durable outbox boundary used by compatibility orchestration and startup recovery. */
interface PlaybackPersistenceRecoveryPort {
    suspend fun register(
        request: UnresolvedStrategyPersistence,
    ): PlaybackPersistenceRecoveryRegistrationOutcome

    /** Returns entries in strictly increasing row-ID order after [afterRowId]. */
    suspend fun startupBatch(
        afterRowId: Long,
        limit: Int,
    ): List<PlaybackPersistenceRecoveryStartupEntry>

    /**
     * Runs [handler] and, only after a conclusive durable result, resolves [token]. Durable
     * implementations must conjoin the handler transaction and exact deletion atomically.
     */
    suspend fun handleAndResolveExact(
        token: PlaybackPersistenceRecoveryToken,
        handler: suspend (
            PersistedPlaybackPersistenceRecoveryObligation,
        ) -> PlaybackPersistenceRecoveryHandlingOutcome,
    ): PlaybackPersistenceRecoveryHandlingOutcome
}

/** Fail-closed default for isolated callers that do not own a durable recovery outbox. */
object UnavailablePlaybackPersistenceRecoveryPort : PlaybackPersistenceRecoveryPort {
    override suspend fun register(
        request: UnresolvedStrategyPersistence,
    ): PlaybackPersistenceRecoveryRegistrationOutcome =
        PlaybackPersistenceRecoveryRegistrationOutcome.Unavailable

    override suspend fun startupBatch(
        afterRowId: Long,
        limit: Int,
    ): List<PlaybackPersistenceRecoveryStartupEntry> = emptyList()

    override suspend fun handleAndResolveExact(
        token: PlaybackPersistenceRecoveryToken,
        handler: suspend (
            PersistedPlaybackPersistenceRecoveryObligation,
        ) -> PlaybackPersistenceRecoveryHandlingOutcome,
    ): PlaybackPersistenceRecoveryHandlingOutcome =
        PlaybackPersistenceRecoveryHandlingOutcome.UNAVAILABLE
}

enum class PlaybackPersistenceRecoveryHandlingOutcome {
    /** The handler durably and conclusively applied the exact persisted obligation. */
    CONCLUSIVELY_HANDLED,

    /** The handler could not establish a durable result; the obligation remains pending. */
    UNAVAILABLE,

    /** Stored state is conflicting or corrupt and requires explicit repair. */
    BLOCKED,
}

fun interface PersistedPlaybackPersistenceRecoveryHandler {
    suspend fun handle(
        obligation: PersistedPlaybackPersistenceRecoveryObligation,
    ): PlaybackPersistenceRecoveryHandlingOutcome
}

/** Reconciles phase-one rows left by a prior process after durable outbox recovery runs. */
fun interface PlaybackPersistencePriorEpochReconciler {
    suspend fun reconcilePriorEpochPendingAttempts(): PlaybackPersistenceRecoveryHandlingOutcome
}

private fun requireRecoveryHash(value: String, label: String) {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "$label is malformed"
    }
}
