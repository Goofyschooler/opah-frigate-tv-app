package app.opah.tv.playback.compatibility

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.LinkedHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-local, non-preemptive lease owner for a fixed trusted device capacity.
 *
 * Every pending exact candidate is replanned with every active exact candidate. A pending request
 * is denied if the desired-state plan would remove or change any existing allocation, so a grant
 * remains valid until its holder explicitly releases it. This deliberately provides no priority
 * preemption: [PlaybackResourceLeasePort] has no callback with which to revoke an active player.
 * Exact active acquisitions and recently released tokens are idempotent for a bounded retry
 * horizon; a released attempt ID cannot be reacquired while its tombstone is retained.
 */
class PlaybackResourceLeaseCoordinator(
    private val capacity: PlaybackResourceCapacity,
    private val planner: PlaybackResourcePlanner = PlaybackResourcePlanner(),
    private val releasedTombstoneLimit: Int = DEFAULT_RELEASED_TOMBSTONES,
) : PlaybackResourceLeasePort {
    private val mutex = Mutex()
    private val secureRandom = SecureRandom()
    private val active = LinkedHashMap<PlaybackAttemptId, ActiveLease>()
    private val released = LinkedHashMap<PlaybackAttemptId, PlaybackResourceLeaseToken>()

    init {
        require(releasedTombstoneLimit in 1..MAX_RELEASED_TOMBSTONES)
    }

    override suspend fun acquire(
        attemptId: PlaybackAttemptId,
        purpose: PlaybackPurpose,
        candidate: PlaybackCandidate,
        resourcePolicy: PlaybackResourcePolicy,
    ): PlaybackResourceLeaseResult = mutex.withLock {
        active[attemptId]?.let { existing ->
            val isExactRetry = existing.request.purpose == purpose &&
                existing.request.candidates.single() == candidate &&
                existing.request.resourcePolicy == resourcePolicy
            return@withLock if (isExactRetry) {
                PlaybackResourceLeaseResult.Granted(existing.token)
            } else {
                replayDenial()
            }
        }
        if (released.containsKey(attemptId)) return@withLock replayDenial()
        // PlaybackResourceCapacity and PlaybackResourcePlanner both cap request sets at 32. At
        // that absolute boundary the pending request cannot be represented and decoder capacity
        // is already exhausted; all smaller full-capacity sets still go through the planner.
        if (active.size >= MAX_PLANNABLE_REQUESTS) {
            return@withLock PlaybackResourceLeaseResult.Denied(
                PlaybackResourceDenialReason.DECODER_LIMIT,
            )
        }

        val pendingRequest = try {
            requestFor(
                attemptId = attemptId,
                purpose = purpose,
                candidate = candidate,
                resourcePolicy = resourcePolicy,
                retained = false,
            )
        } catch (_: IllegalArgumentException) {
            return@withLock PlaybackResourceLeaseResult.Denied(
                PlaybackResourceDenialReason.NO_POLICY_COMPATIBLE_CANDIDATE,
            )
        }
        if (active.values.any { it.request.id == pendingRequest.id }) {
            // A SHA-256 request-ID collision must never alias two lease owners.
            return@withLock replayDenial()
        }

        val requests = ArrayList<PlaybackResourceRequest>(active.size + 1)
        active.values.mapTo(requests, ActiveLease::request)
        requests += pendingRequest
        val plan = try {
            planner.plan(capacity, requests)
        } catch (_: IllegalArgumentException) {
            return@withLock PlaybackResourceLeaseResult.Denied(
                PlaybackResourceDenialReason.NO_POLICY_COMPATIBLE_CANDIDATE,
            )
        }

        val displaced = active.values.firstOrNull { lease ->
            plan.allocationFor(lease.request.id)?.candidate?.key() != lease.candidateKey
        }
        if (displaced != null) {
            val reason = plan.denials.firstOrNull { it.requestId == displaced.request.id }?.reason
                ?: PlaybackResourceDenialReason.DECODER_LIMIT
            return@withLock PlaybackResourceLeaseResult.Denied(reason)
        }

        val pendingAllocation = plan.allocationFor(pendingRequest.id)
        if (pendingAllocation?.candidate?.key() != candidate.key()) {
            val reason = plan.denials.firstOrNull { it.requestId == pendingRequest.id }?.reason
                ?: PlaybackResourceDenialReason.NO_POLICY_COMPATIBLE_CANDIDATE
            return@withLock PlaybackResourceLeaseResult.Denied(reason)
        }

        val token = newLeaseToken()
        check(active.size < capacity.maximumConcurrentDecoders) {
            "Playback resource coordinator rejected an impossible over-capacity grant"
        }
        active[attemptId] = ActiveLease(
            request = requestFor(
                attemptId = attemptId,
                purpose = purpose,
                candidate = candidate,
                resourcePolicy = resourcePolicy,
                retained = true,
            ),
            candidateKey = candidate.key(),
            token = token,
        )
        PlaybackResourceLeaseResult.Granted(token)
    }

    override suspend fun release(
        attemptId: PlaybackAttemptId,
        lease: PlaybackResourceLeaseToken,
    ): PlaybackResourceLeaseReleaseOutcome = mutex.withLock {
        val owned = active[attemptId] ?: return@withLock if (released[attemptId] == lease) {
            PlaybackResourceLeaseReleaseOutcome.RELEASED
        } else {
            PlaybackResourceLeaseReleaseOutcome.FAILED
        }
        if (owned.token != lease) {
            return@withLock PlaybackResourceLeaseReleaseOutcome.FAILED
        }
        active.remove(attemptId)
        released[attemptId] = lease
        while (released.size > releasedTombstoneLimit) {
            released.remove(released.keys.first())
        }
        PlaybackResourceLeaseReleaseOutcome.RELEASED
    }

    private fun requestFor(
        attemptId: PlaybackAttemptId,
        purpose: PlaybackPurpose,
        candidate: PlaybackCandidate,
        resourcePolicy: PlaybackResourcePolicy,
        retained: Boolean,
    ): PlaybackResourceRequest = PlaybackResourceRequest(
        id = requestId(attemptId),
        purpose = purpose,
        priority = COORDINATOR_PRIORITY,
        candidates = listOf(candidate),
        resourcePolicy = resourcePolicy,
        currentlyAllocatedCandidate = candidate.key().takeIf { retained },
    )

    private fun newLeaseToken(): PlaybackResourceLeaseToken {
        repeat(MAX_TOKEN_GENERATION_ATTEMPTS) {
            val bytes = ByteArray(LEASE_TOKEN_BYTES)
            secureRandom.nextBytes(bytes)
            val token = PlaybackResourceLeaseToken.fromTrustedCoordinator(
                LEASE_TOKEN_PREFIX + bytes.toLowerHex(),
            )
            if (
                active.values.none { it.token == token } &&
                released.values.none { it == token }
            ) {
                return token
            }
        }
        error("Unable to allocate a unique playback resource lease token")
    }

    private data class ActiveLease(
        val request: PlaybackResourceRequest,
        val candidateKey: PlaybackCandidateKey,
        val token: PlaybackResourceLeaseToken,
    )

    private companion object {
        const val COORDINATOR_PRIORITY = 0
        const val MAX_PLANNABLE_REQUESTS = 32
        const val LEASE_TOKEN_BYTES = 24
        const val LEASE_TOKEN_PREFIX = "lease-"
        const val MAX_TOKEN_GENERATION_ATTEMPTS = 4
        const val DEFAULT_RELEASED_TOMBSTONES = 512
        const val MAX_RELEASED_TOMBSTONES = 32_768
        val REQUEST_ID_DOMAIN: ByteArray =
            "opah.playback.resource-request.v1".toByteArray(StandardCharsets.US_ASCII)

        fun replayDenial(): PlaybackResourceLeaseResult.Denied =
            PlaybackResourceLeaseResult.Denied(PlaybackResourceDenialReason.ATTEMPT_ID_REPLAY)

        fun requestId(attemptId: PlaybackAttemptId): PlaybackResourceRequestId {
            val session = attemptId.sessionId.value.toByteArray(StandardCharsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update(REQUEST_ID_DOMAIN)
                update(0.toByte())
                update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(session.size).array())
                update(session)
                update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(attemptId.ordinal).array())
            }.digest()
            return PlaybackResourceRequestId("request-" + digest.toLowerHex())
        }

        fun ByteArray.toLowerHex(): String {
            val result = CharArray(size * 2)
            forEachIndexed { index, value ->
                val unsigned = value.toInt() and 0xff
                result[index * 2] = HEX[unsigned ushr 4]
                result[index * 2 + 1] = HEX[unsigned and 0x0f]
            }
            return String(result)
        }

        const val HEX = "0123456789abcdef"
    }
}
