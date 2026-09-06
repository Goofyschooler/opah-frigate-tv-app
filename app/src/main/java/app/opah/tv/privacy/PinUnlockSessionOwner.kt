package app.opah.tv.privacy

internal enum class PinRelockReason {
    APP_BACKGROUND,
    INACTIVITY,
    GUEST_ACTIVATED,
    MANUAL,
    PRIVACY_EPOCH_CHANGED,
    SIGN_OUT,
}

internal data class PinUnlockSessionPolicy(
    val inactivityTimeoutMillis: Long = 5 * 60_000L,
) {
    init {
        require(inactivityTimeoutMillis in MINIMUM_TIMEOUT_MILLIS..MAXIMUM_TIMEOUT_MILLIS)
    }

    private companion object {
        const val MINIMUM_TIMEOUT_MILLIS = 30_000L
        const val MAXIMUM_TIMEOUT_MILLIS = 24 * 60 * 60_000L
    }
}

/** Process-only owner. No Android saved state, persistence, or PIN content crosses this boundary. */
internal class PinUnlockSessionOwner(
    private val policy: PinUnlockSessionPolicy = PinUnlockSessionPolicy(),
) {
    private data class Session(
        val privacyEpoch: Long,
        val scopes: Set<PinScope>,
        var lastActivityMonotonicMillis: Long,
    )

    private val lock = Any()
    private var session: Session? = null
    private var lastRelockReason: PinRelockReason? = null

    fun unlock(
        privacyEpoch: Long,
        scopes: Set<PinScope>,
        nowMonotonicMillis: Long,
    ): PinUnlockProof? = synchronized(lock) {
        if (privacyEpoch < 0L || nowMonotonicMillis < 0L) {
            session = null
            lastRelockReason = PinRelockReason.PRIVACY_EPOCH_CHANGED
            return@synchronized null
        }
        session = Session(privacyEpoch, scopes.toSet(), nowMonotonicMillis)
        lastRelockReason = null
        PinUnlockProof(privacyEpoch, scopes.toSet())
    }

    fun currentProof(
        privacyEpoch: Long,
        nowMonotonicMillis: Long,
    ): PinUnlockProof? = synchronized(lock) {
        val active = validSession(privacyEpoch, nowMonotonicMillis) ?: return@synchronized null
        PinUnlockProof(active.privacyEpoch, active.scopes.toSet())
    }

    /** Records owner interaction only while the exact privacy epoch is still unlocked. */
    fun recordActivity(privacyEpoch: Long, nowMonotonicMillis: Long): Boolean = synchronized(lock) {
        val active = validSession(privacyEpoch, nowMonotonicMillis) ?: return@synchronized false
        active.lastActivityMonotonicMillis = nowMonotonicMillis
        true
    }

    /** Carries an already verified session across one owner-approved policy mutation. */
    fun advancePrivacyEpoch(previousEpoch: Long, nextEpoch: Long): Boolean = synchronized(lock) {
        val active = session ?: return@synchronized false
        if (
            previousEpoch < 0L || nextEpoch < 0L || nextEpoch == previousEpoch ||
            active.privacyEpoch != previousEpoch
        ) {
            session = null
            lastRelockReason = PinRelockReason.PRIVACY_EPOCH_CHANGED
            return@synchronized false
        }
        session = active.copy(privacyEpoch = nextEpoch)
        true
    }

    fun relock(reason: PinRelockReason): Boolean = synchronized(lock) {
        val hadActiveSession = session != null
        session = null
        lastRelockReason = reason
        hadActiveSession
    }

    fun onAppBackgrounded(): Boolean = relock(PinRelockReason.APP_BACKGROUND)

    fun onGuestActivated(): Boolean = relock(PinRelockReason.GUEST_ACTIVATED)

    fun lockNow(): Boolean = relock(PinRelockReason.MANUAL)

    fun onSignOut(): Boolean = relock(PinRelockReason.SIGN_OUT)

    internal fun relockReasonForTest(): PinRelockReason? = synchronized(lock) { lastRelockReason }

    private fun validSession(privacyEpoch: Long, nowMonotonicMillis: Long): Session? {
        val active = session ?: return null
        if (privacyEpoch != active.privacyEpoch || privacyEpoch < 0L || nowMonotonicMillis < 0L) {
            session = null
            lastRelockReason = PinRelockReason.PRIVACY_EPOCH_CHANGED
            return null
        }
        val elapsed = nowMonotonicMillis - active.lastActivityMonotonicMillis
        if (elapsed < 0L || elapsed >= policy.inactivityTimeoutMillis) {
            session = null
            lastRelockReason = PinRelockReason.INACTIVITY
            return null
        }
        return active
    }
}
