package app.opah.tv.notifications

import app.opah.tv.awareness.AwarenessReviewLifecycle
import app.opah.tv.awareness.AwarenessReviewSeverity

enum class PendingNotificationOperationKind {
    POST,
    CANCEL,
}

data class PendingNotificationOperation(
    val operationId: String,
    val kind: PendingNotificationOperationKind,
    val stagedAtEpochMillis: Long,
) {
    init {
        operationId.requireLedgerIdentity("Operation ID", MAX_LEDGER_TOKEN_CHARS)
        require(stagedAtEpochMillis >= 0L) { "Operation time must not be negative" }
    }
}

data class NotificationLedgerRecord(
    val profileKey: String,
    val reviewId: String,
    val notificationTag: String,
    val actionNonce: String,
    val lifecycle: AwarenessReviewLifecycle,
    val highestPresentedSeverity: AwarenessReviewSeverity?,
    val highestAudibleSeverity: AwarenessReviewSeverity?,
    val contentFingerprint: String?,
    val lastPostAtEpochMillis: Long?,
    val lastAlertAtEpochMillis: Long?,
    val dismissedAtEpochMillis: Long?,
    val severityAtDismissal: AwarenessReviewSeverity?,
    val openTarget: AlertOpenTarget?,
    val policyVersion: Long,
    val privacyEpoch: Long,
    val notificationActive: Boolean,
    val pendingOperation: PendingNotificationOperation?,
    val terminalReason: String?,
    val reconciledAtEpochMillis: Long,
) {
    init {
        profileKey.requireLedgerIdentity("Profile key", MAX_LEDGER_PROFILE_CHARS)
        reviewId.requireLedgerIdentity("Review ID", MAX_LEDGER_REVIEW_CHARS)
        notificationTag.requireLedgerIdentity("Notification tag", MAX_LEDGER_TOKEN_CHARS)
        actionNonce.requireLedgerIdentity("Action nonce", MAX_LEDGER_TOKEN_CHARS)
        contentFingerprint?.requireLedgerIdentity("Content fingerprint", MAX_LEDGER_TOKEN_CHARS)
        require(policyVersion >= 0L) { "Policy version must not be negative" }
        require(privacyEpoch >= 0L) { "Privacy epoch must not be negative" }
        require(reconciledAtEpochMillis >= 0L) { "Reconciliation time must not be negative" }
        listOfNotNull(
            lastPostAtEpochMillis,
            lastAlertAtEpochMillis,
            dismissedAtEpochMillis,
        ).forEach { require(it >= 0L) { "Ledger time must not be negative" } }
        require(terminalReason == null || terminalReason.length <= MAX_TERMINAL_REASON_CHARS) {
            "Terminal reason is too long"
        }
    }

    fun evaluatorLedger(): AlertNotificationLedger = AlertNotificationLedger(
        highestPresentedSeverity = highestPresentedSeverity,
        highestAudibleSeverity = highestAudibleSeverity,
        contentFingerprint = contentFingerprint,
        lifecycle = lifecycle,
        dismissedAtEpochMillis = dismissedAtEpochMillis,
        severityAtDismissal = severityAtDismissal,
        notificationActive = notificationActive,
    )
}

sealed interface NotificationLedgerReadResult {
    data object Missing : NotificationLedgerReadResult
    data class Available(val record: NotificationLedgerRecord) : NotificationLedgerReadResult
    data object Corrupt : NotificationLedgerReadResult
}

interface NotificationLedgerStore {
    suspend fun read(profileKey: String, reviewId: String): NotificationLedgerReadResult
    suspend fun pending(profileKey: String): List<NotificationLedgerRecord>
    suspend fun activeOrPending(profileKey: String): List<NotificationLedgerRecord>
    suspend fun upsert(record: NotificationLedgerRecord)
    suspend fun settle(
        profileKey: String,
        reviewId: String,
        operationId: String,
        notificationActive: Boolean,
        reconciledAtEpochMillis: Long,
    ): Boolean
    suspend fun deleteProfile(profileKey: String)
}

private fun String.requireLedgerIdentity(label: String, maxChars: Int) {
    require(isNotBlank() && length <= maxChars) { "$label is invalid" }
    require(none(Char::isISOControl)) { "$label contains control characters" }
}

private const val MAX_LEDGER_PROFILE_CHARS = 128
private const val MAX_LEDGER_REVIEW_CHARS = 256
private const val MAX_LEDGER_TOKEN_CHARS = 256
private const val MAX_TERMINAL_REASON_CHARS = 128
