package app.opah.tv.playback.media3

import app.opah.tv.playback.compatibility.CurrentPlaybackAccess
import app.opah.tv.playback.compatibility.FreshPlaybackRouteSnapshot
import app.opah.tv.playback.compatibility.FreshPlaybackRouteSnapshotProvider
import app.opah.tv.playback.compatibility.FreshPlaybackRouteSnapshotResult
import app.opah.tv.playback.compatibility.PlaybackAccessGuard
import app.opah.tv.playback.compatibility.PlaybackExecutionAuthorization
import app.opah.tv.playback.compatibility.PlaybackRouteLookup
import app.opah.tv.playback.compatibility.ResolvedPlaybackToken
import app.opah.tv.playback.compatibility.SnapshotRouteId
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DEFAULT_MAXIMUM_RTSP_ROUTES = 256
private const val DEFAULT_MAXIMUM_PROFILE_REVISION_FLOORS = 64

/**
 * One trusted, immutable read of current access and only the configured RTSP routes derived from
 * that same read. Raw routes stay in the Media3 adapter package and never enter a compatibility
 * plan, state flow, persistence record, or diagnostic report.
 */
internal class TrustedRtspRouteSnapshot(
    val currentAccess: CurrentPlaybackAccess,
    routes: Map<PlaybackRouteLookup, PrevalidatedRtspUri>,
    snapshotTokens: Map<SnapshotRouteId, ResolvedPlaybackToken> = emptyMap(),
) {
    val routes: Map<PlaybackRouteLookup, PrevalidatedRtspUri> =
        Collections.unmodifiableMap(LinkedHashMap(routes))
    val snapshotTokens: Map<SnapshotRouteId, ResolvedPlaybackToken> =
        Collections.unmodifiableMap(LinkedHashMap(snapshotTokens))

    override fun toString(): String =
        "TrustedRtspRouteSnapshot(access=[redacted], routes=${routes.size}, " +
            "snapshots=${snapshotTokens.size})"
}

internal sealed interface TrustedRtspRouteSnapshotResult {
    data class Available(
        val snapshot: TrustedRtspRouteSnapshot,
    ) : TrustedRtspRouteSnapshotResult

    data object AuthenticationRequired : TrustedRtspRouteSnapshotResult

    data object StaleAccess : TrustedRtspRouteSnapshotResult

    data object Unavailable : TrustedRtspRouteSnapshotResult
}

/**
 * Must read one already-materialized process snapshot; implementations must not perform network
 * I/O. The gateway serializes this read with token publication/redemption so a late older read
 * cannot roll authorization back over a newer one.
 */
internal fun interface TrustedRtspRouteSnapshotSource {
    suspend fun freshSnapshot(): TrustedRtspRouteSnapshotResult
}

/**
 * Joins the public compatibility gateway to the trusted Media3 route seam.
 *
 * A token is stable only while its access guard, route lookup, and prevalidated URI are all
 * unchanged. Redemption performs another serialized fresh read. Authentication loss, stale
 * access, a route change, a revision rollback, or an adapter error invalidates the prior token
 * before Media3 can receive a URI.
 */
internal class RevisionBoundRtspRouteGateway(
    private val source: TrustedRtspRouteSnapshotSource,
    private val maximumRoutes: Int = DEFAULT_MAXIMUM_RTSP_ROUTES,
    private val maximumProfileRevisionFloors: Int = DEFAULT_MAXIMUM_PROFILE_REVISION_FLOORS,
) : FreshPlaybackRouteSnapshotProvider, TrustedRtspTokenRedeemer {
    private val mutex = Mutex()
    private var activeGuard: PlaybackAccessGuard? = null
    private val revisionFloors = LinkedHashMap<
        app.opah.tv.playback.compatibility.CompatibilityIdentityKey,
        RevisionFloor,
    >()
    private var tokenOrdinal = 0L
    private val activeByLookup = LinkedHashMap<PlaybackRouteLookup, ActiveRoute>()
    private val activeByToken = LinkedHashMap<ResolvedPlaybackToken, ActiveRoute>()

    init {
        require(maximumRoutes in 1..DEFAULT_MAXIMUM_RTSP_ROUTES)
        require(maximumProfileRevisionFloors in 1..DEFAULT_MAXIMUM_PROFILE_REVISION_FLOORS)
    }

    override suspend fun freshSnapshot(): FreshPlaybackRouteSnapshotResult = mutex.withLock {
        when (val refreshed = refreshLocked()) {
            is RefreshResult.Available ->
                FreshPlaybackRouteSnapshotResult.Available(refreshed.snapshot)
            RefreshResult.AuthenticationRequired ->
                FreshPlaybackRouteSnapshotResult.AuthenticationRequired
            RefreshResult.StaleAccess -> FreshPlaybackRouteSnapshotResult.StaleAccess
            RefreshResult.Unavailable -> FreshPlaybackRouteSnapshotResult.Unavailable
        }
    }

    override suspend fun redeem(token: ResolvedPlaybackToken): TrustedRtspTokenRedemption =
        mutex.withLock {
            when (refreshLocked()) {
                is RefreshResult.Available -> {
                    val route = activeByToken[token]
                    if (route == null) staleRedemption()
                    else TrustedRtspTokenRedemption.Resolved(route.route)
                }
                RefreshResult.AuthenticationRequired -> authenticationRequiredRedemption()
                RefreshResult.StaleAccess -> staleRedemption()
                RefreshResult.Unavailable -> resolutionUnavailableRedemption()
            }
        }

    internal suspend fun invalidate() {
        mutex.withLock { clearActiveLocked() }
    }

    internal suspend fun activeRouteCount(): Int = mutex.withLock { activeByLookup.size }

    private suspend fun refreshLocked(): RefreshResult {
        val result = try {
            source.freshSnapshot()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            clearActiveLocked()
            return RefreshResult.StaleAccess
        }
        return when (result) {
            TrustedRtspRouteSnapshotResult.AuthenticationRequired -> {
                clearActiveLocked()
                RefreshResult.AuthenticationRequired
            }
            TrustedRtspRouteSnapshotResult.StaleAccess -> {
                clearActiveLocked()
                RefreshResult.StaleAccess
            }
            TrustedRtspRouteSnapshotResult.Unavailable -> {
                clearActiveLocked()
                RefreshResult.Unavailable
            }
            is TrustedRtspRouteSnapshotResult.Available ->
                publishLocked(result.snapshot)
        }
    }

    private fun publishLocked(sourceSnapshot: TrustedRtspRouteSnapshot): RefreshResult {
        val guard = sourceSnapshot.currentAccess.guard
        if (isRevisionRollback(guard)) {
            clearActiveLocked()
            return RefreshResult.StaleAccess
        }
        if (!hasExactAvailableRouteSet(sourceSnapshot)) {
            clearActiveLocked()
            return RefreshResult.Unavailable
        }
        if (
            sourceSnapshot.routes.size > maximumRoutes ||
            sourceSnapshot.snapshotTokens.values.distinct().size !=
            sourceSnapshot.snapshotTokens.size
        ) {
            clearActiveLocked()
            return RefreshResult.Unavailable
        }

        val nextByLookup = LinkedHashMap<PlaybackRouteLookup, ActiveRoute>()
        val nextByToken = LinkedHashMap<ResolvedPlaybackToken, ActiveRoute>()
        for ((lookup, route) in sourceSnapshot.routes) {
            val existing = activeByLookup[lookup]
                ?.takeIf { activeGuard == guard && it.route.sameRouteAs(route) }
            val active = existing ?: ActiveRoute(
                lookup = lookup,
                token = issueToken() ?: run {
                    clearActiveLocked()
                    return RefreshResult.Unavailable
                },
                route = route,
            )
            if (nextByToken.put(active.token, active) != null) {
                clearActiveLocked()
                return RefreshResult.Unavailable
            }
            nextByLookup[lookup] = active
        }
        if (sourceSnapshot.snapshotTokens.values.any(nextByToken::containsKey)) {
            clearActiveLocked()
            return RefreshResult.Unavailable
        }

        val domainSnapshot = try {
            FreshPlaybackRouteSnapshot(
                currentAccess = sourceSnapshot.currentAccess,
                liveTokens = nextByLookup.mapValues { it.value.token },
                snapshotTokens = sourceSnapshot.snapshotTokens,
            )
        } catch (_: IllegalArgumentException) {
            clearActiveLocked()
            return RefreshResult.Unavailable
        }

        activeByLookup.clear()
        activeByLookup.putAll(nextByLookup)
        activeByToken.clear()
        activeByToken.putAll(nextByToken)
        activeGuard = guard
        advanceRevisionFloor(guard)
        return RefreshResult.Available(domainSnapshot)
    }

    private fun hasExactAvailableRouteSet(snapshot: TrustedRtspRouteSnapshot): Boolean {
        val expected = snapshot.currentAccess.authorizedSources
            .asSequence()
            .filter { it.available }
            .mapTo(LinkedHashSet()) { source ->
                PlaybackRouteLookup(source.routeId, source.streamId)
            }
        return expected.size <= maximumRoutes && snapshot.routes.keys == expected
    }

    private fun isRevisionRollback(incoming: PlaybackAccessGuard): Boolean {
        val floor = revisionFloors[incoming.profileScope]
            ?: return revisionFloors.size >= maximumProfileRevisionFloors
        return incoming.authorizationRevision < floor.authorizationRevision ||
            incoming.privacyRevision < floor.privacyRevision
    }

    private fun advanceRevisionFloor(guard: PlaybackAccessGuard) {
        val floor = revisionFloors[guard.profileScope]
        revisionFloors[guard.profileScope] = if (floor == null) {
            RevisionFloor(
                authorizationRevision = guard.authorizationRevision,
                privacyRevision = guard.privacyRevision,
            )
        } else {
            floor.copy(
                authorizationRevision = maxOf(
                    floor.authorizationRevision,
                    guard.authorizationRevision,
                ),
                privacyRevision = maxOf(floor.privacyRevision, guard.privacyRevision),
            )
        }
    }

    private fun issueToken(): ResolvedPlaybackToken? {
        if (tokenOrdinal == Long.MAX_VALUE) return null
        tokenOrdinal += 1
        return ResolvedPlaybackToken.fromTrustedAdapter(
            "rtsp-${tokenOrdinal.toString(36)}-${UUID.randomUUID()}",
        )
    }

    private fun clearActiveLocked() {
        activeGuard = null
        activeByLookup.clear()
        activeByToken.clear()
    }

    private data class ActiveRoute(
        val lookup: PlaybackRouteLookup,
        val token: ResolvedPlaybackToken,
        val route: PrevalidatedRtspUri,
    )

    private data class RevisionFloor(
        val authorizationRevision: Long,
        val privacyRevision: Long,
    )

    private sealed interface RefreshResult {
        data class Available(val snapshot: FreshPlaybackRouteSnapshot) : RefreshResult
        data object AuthenticationRequired : RefreshResult
        data object StaleAccess : RefreshResult
        data object Unavailable : RefreshResult
    }
}

private fun authenticationRequiredRedemption(): TrustedRtspTokenRedemption =
    TrustedRtspTokenRedemption.AccessLost(
        PlaybackExecutionAuthorization.AUTHENTICATION_REQUIRED,
    )

private fun staleRedemption(): TrustedRtspTokenRedemption =
    TrustedRtspTokenRedemption.AccessLost(
        PlaybackExecutionAuthorization.STALE_ACCESS,
    )

private fun resolutionUnavailableRedemption(): TrustedRtspTokenRedemption =
    TrustedRtspTokenRedemption.AccessLost(
        PlaybackExecutionAuthorization.RESOLUTION_UNAVAILABLE,
    )
