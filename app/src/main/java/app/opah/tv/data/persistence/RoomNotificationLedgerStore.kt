package app.opah.tv.data.persistence

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert
import app.opah.tv.awareness.AwarenessReviewLifecycle
import app.opah.tv.awareness.AwarenessReviewSeverity
import app.opah.tv.notifications.AlertOpenTarget
import app.opah.tv.notifications.NotificationLedgerReadResult
import app.opah.tv.notifications.NotificationLedgerRecord
import app.opah.tv.notifications.NotificationLedgerStore
import app.opah.tv.notifications.PendingNotificationOperation
import app.opah.tv.notifications.PendingNotificationOperationKind

@Entity(
    tableName = "notification_ledger",
    primaryKeys = ["profileKey", "reviewId"],
    indices = [
        Index(value = ["profileKey", "pendingOperationKind"]),
        Index(value = ["profileKey", "notificationActive"]),
        Index(value = ["profileKey", "reconciledAtEpochMillis"]),
    ],
)
data class NotificationLedgerEntity(
    val profileKey: String,
    val reviewId: String,
    val notificationTag: String,
    val actionNonce: String,
    val lifecycle: String,
    val highestPresentedSeverity: String?,
    val highestAudibleSeverity: String?,
    val contentFingerprint: String?,
    val lastPostAtEpochMillis: Long?,
    val lastAlertAtEpochMillis: Long?,
    val dismissedAtEpochMillis: Long?,
    val severityAtDismissal: String?,
    val openTarget: String?,
    val policyVersion: Long,
    val privacyEpoch: Long,
    val notificationActive: Boolean,
    val pendingOperationId: String?,
    val pendingOperationKind: String?,
    val pendingOperationStagedAtEpochMillis: Long?,
    val terminalReason: String?,
    val reconciledAtEpochMillis: Long,
)

@Dao
interface NotificationLedgerDao {
    @Query(
        "SELECT * FROM notification_ledger " +
            "WHERE profileKey = :profileKey AND reviewId = :reviewId LIMIT 1",
    )
    suspend fun record(profileKey: String, reviewId: String): NotificationLedgerEntity?

    @Query(
        "SELECT * FROM notification_ledger " +
            "WHERE profileKey = :profileKey AND pendingOperationKind IS NOT NULL " +
            "ORDER BY pendingOperationStagedAtEpochMillis ASC, reviewId ASC " +
            "LIMIT :limit",
    )
    suspend fun pending(profileKey: String, limit: Int): List<NotificationLedgerEntity>

    @Query(
        "SELECT * FROM notification_ledger " +
            "WHERE profileKey = :profileKey " +
            "AND (notificationActive = 1 OR pendingOperationKind IS NOT NULL) " +
            "ORDER BY reconciledAtEpochMillis ASC, reviewId ASC " +
            "LIMIT :limit",
    )
    suspend fun activeOrPending(profileKey: String, limit: Int): List<NotificationLedgerEntity>

    @Upsert
    suspend fun upsert(entity: NotificationLedgerEntity)

    @Query(
        "UPDATE notification_ledger SET " +
            "pendingOperationId = NULL, pendingOperationKind = NULL, " +
            "pendingOperationStagedAtEpochMillis = NULL, notificationActive = :notificationActive, " +
            "reconciledAtEpochMillis = :reconciledAtEpochMillis " +
            "WHERE profileKey = :profileKey AND reviewId = :reviewId " +
            "AND pendingOperationId = :operationId",
    )
    suspend fun settle(
        profileKey: String,
        reviewId: String,
        operationId: String,
        notificationActive: Boolean,
        reconciledAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM notification_ledger WHERE profileKey = :profileKey")
    suspend fun deleteProfile(profileKey: String)
}

class RoomNotificationLedgerStore(
    private val dao: NotificationLedgerDao,
) : NotificationLedgerStore {
    override suspend fun read(profileKey: String, reviewId: String): NotificationLedgerReadResult {
        val entity = dao.record(profileKey, reviewId) ?: return NotificationLedgerReadResult.Missing
        return runCatching { entity.toDomain() }
            .fold(
                onSuccess = NotificationLedgerReadResult::Available,
                onFailure = { NotificationLedgerReadResult.Corrupt },
            )
    }

    override suspend fun pending(profileKey: String): List<NotificationLedgerRecord> =
        dao.pending(profileKey, MAX_PENDING_OPERATIONS_PER_RECOVERY).mapNotNull { entity ->
            runCatching(entity::toDomain).getOrNull()
        }

    override suspend fun activeOrPending(profileKey: String): List<NotificationLedgerRecord> =
        dao.activeOrPending(profileKey, MAX_ACTIVE_OPERATIONS_PER_SWEEP).mapNotNull { entity ->
            runCatching(entity::toDomain).getOrNull()
        }

    override suspend fun upsert(record: NotificationLedgerRecord) = dao.upsert(record.toEntity())

    override suspend fun settle(
        profileKey: String,
        reviewId: String,
        operationId: String,
        notificationActive: Boolean,
        reconciledAtEpochMillis: Long,
    ): Boolean = dao.settle(
        profileKey,
        reviewId,
        operationId,
        notificationActive,
        reconciledAtEpochMillis,
    ) == 1

    override suspend fun deleteProfile(profileKey: String) = dao.deleteProfile(profileKey)
}

private fun NotificationLedgerRecord.toEntity(): NotificationLedgerEntity = NotificationLedgerEntity(
    profileKey = profileKey,
    reviewId = reviewId,
    notificationTag = notificationTag,
    actionNonce = actionNonce,
    lifecycle = lifecycle.name,
    highestPresentedSeverity = highestPresentedSeverity?.name,
    highestAudibleSeverity = highestAudibleSeverity?.name,
    contentFingerprint = contentFingerprint,
    lastPostAtEpochMillis = lastPostAtEpochMillis,
    lastAlertAtEpochMillis = lastAlertAtEpochMillis,
    dismissedAtEpochMillis = dismissedAtEpochMillis,
    severityAtDismissal = severityAtDismissal?.name,
    openTarget = openTarget?.name,
    policyVersion = policyVersion,
    privacyEpoch = privacyEpoch,
    notificationActive = notificationActive,
    pendingOperationId = pendingOperation?.operationId,
    pendingOperationKind = pendingOperation?.kind?.name,
    pendingOperationStagedAtEpochMillis = pendingOperation?.stagedAtEpochMillis,
    terminalReason = terminalReason,
    reconciledAtEpochMillis = reconciledAtEpochMillis,
)

private fun NotificationLedgerEntity.toDomain(): NotificationLedgerRecord {
    val pendingFields = listOf(
        pendingOperationId,
        pendingOperationKind,
        pendingOperationStagedAtEpochMillis,
    )
    require(pendingFields.all { it == null } || pendingFields.all { it != null }) {
        "Pending operation is incomplete"
    }
    return NotificationLedgerRecord(
        profileKey = profileKey,
        reviewId = reviewId,
        notificationTag = notificationTag,
        actionNonce = actionNonce,
        lifecycle = enumValueOf(lifecycle),
        highestPresentedSeverity = highestPresentedSeverity?.let(::enumValueOf),
        highestAudibleSeverity = highestAudibleSeverity?.let(::enumValueOf),
        contentFingerprint = contentFingerprint,
        lastPostAtEpochMillis = lastPostAtEpochMillis,
        lastAlertAtEpochMillis = lastAlertAtEpochMillis,
        dismissedAtEpochMillis = dismissedAtEpochMillis,
        severityAtDismissal = severityAtDismissal?.let(::enumValueOf),
        openTarget = openTarget?.let(::enumValueOf),
        policyVersion = policyVersion,
        privacyEpoch = privacyEpoch,
        notificationActive = notificationActive,
        pendingOperation = pendingOperationId?.let { id ->
            PendingNotificationOperation(
                operationId = id,
                kind = enumValueOf(requireNotNull(pendingOperationKind)),
                stagedAtEpochMillis = requireNotNull(pendingOperationStagedAtEpochMillis),
            )
        },
        terminalReason = terminalReason,
        reconciledAtEpochMillis = reconciledAtEpochMillis,
    )
}

private const val MAX_PENDING_OPERATIONS_PER_RECOVERY = 2_048
private const val MAX_ACTIVE_OPERATIONS_PER_SWEEP = 2_048
