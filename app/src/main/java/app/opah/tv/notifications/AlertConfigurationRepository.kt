package app.opah.tv.notifications

import app.opah.tv.data.persistence.AlertConfigurationReadResult
import app.opah.tv.data.persistence.AlertConfigurationRecord
import app.opah.tv.data.persistence.AlertConfigurationStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface AlertConfigurationState {
    data object Loading : AlertConfigurationState

    data class Ready(val configuration: AlertConfigurationRecord) : AlertConfigurationState

    data class Unavailable(val profileKey: String?) : AlertConfigurationState
}

internal sealed interface AlertConfigurationMutationResult {
    data class Updated(val configuration: AlertConfigurationRecord) : AlertConfigurationMutationResult
    data class Unchanged(val configuration: AlertConfigurationRecord) : AlertConfigurationMutationResult
    data object Rejected : AlertConfigurationMutationResult
    data object Unavailable : AlertConfigurationMutationResult
}

/**
 * Owns the active profile's durable local alert policy. Missing state starts disabled and safe;
 * corrupt or failed storage is never overwritten and disables alert delivery until it is repaired.
 */
internal class AlertConfigurationRepository(
    private val store: AlertConfigurationStore,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutationMutex = Mutex()
    private val mutableState = MutableStateFlow<AlertConfigurationState>(AlertConfigurationState.Loading)
    val state: StateFlow<AlertConfigurationState> = mutableState.asStateFlow()

    suspend fun initialize(
        profileKey: String,
        currentFrigateMode: String? = null,
    ): AlertConfigurationState = mutationMutex.withLock {
        if (!profileKey.isValidProfileKey()) return@withLock publishUnavailable(null)
        val now = safeNow()
        val read = runCatching { store.read(profileKey) }
            .getOrElse { return@withLock publishUnavailable(profileKey) }
        val configuration = when (read) {
            AlertConfigurationReadResult.Missing -> AlertConfigurationRecord(
                profileKey = profileKey,
                enabled = false,
                policyVersion = 0L,
                policy = SAFE_DEFAULT_POLICY,
                snoozes = emptyList(),
                updatedAtEpochMillis = now,
            ).also { created ->
                if (!write(created)) return@withLock publishUnavailable(profileKey)
            }
            is AlertConfigurationReadResult.Available -> read.record
            AlertConfigurationReadResult.Corrupt -> return@withLock publishUnavailable(profileKey)
        }
        val cleaned = configuration.withInactiveSnoozesRemoved(now, currentFrigateMode)
        val readyConfiguration = if (cleaned == configuration) {
            configuration
        } else {
            val version = configuration.nextVersionOrNull()
                ?: return@withLock publishUnavailable(profileKey)
            cleaned.copy(policyVersion = version, updatedAtEpochMillis = now).also { updated ->
                if (!write(updated)) return@withLock publishUnavailable(profileKey)
            }
        }
        AlertConfigurationState.Ready(readyConfiguration).also { mutableState.value = it }
    }

    suspend fun setEnabled(enabled: Boolean): AlertConfigurationMutationResult =
        mutate { it.copy(enabled = enabled) }

    suspend fun setPolicy(policy: AlertPolicy): AlertConfigurationMutationResult =
        mutate { it.copy(policy = policy) }

    suspend fun addSnooze(snooze: AlertSnooze): AlertConfigurationMutationResult = mutate { current ->
        if (snooze in current.snoozes) current
        else if (current.snoozes.size >= MAX_SNOOZES) null
        else current.copy(snoozes = current.snoozes + snooze)
    }

    suspend fun clearSnoozes(): AlertConfigurationMutationResult =
        mutate { it.copy(snoozes = emptyList()) }

    suspend fun cleanInactiveSnoozes(currentFrigateMode: String?): AlertConfigurationMutationResult {
        val now = safeNow()
        return mutate(now) { it.withInactiveSnoozesRemoved(now, currentFrigateMode) }
    }

    fun clearActiveProfile() {
        mutableState.value = AlertConfigurationState.Loading
    }

    private suspend fun mutate(
        now: Long = safeNow(),
        transform: (AlertConfigurationRecord) -> AlertConfigurationRecord?,
    ): AlertConfigurationMutationResult = mutationMutex.withLock {
        val current = (mutableState.value as? AlertConfigurationState.Ready)?.configuration
            ?: return@withLock AlertConfigurationMutationResult.Unavailable
        val transformed = runCatching { transform(current) }
            .getOrElse { return@withLock AlertConfigurationMutationResult.Rejected }
            ?: return@withLock AlertConfigurationMutationResult.Rejected
        if (transformed == current) {
            return@withLock AlertConfigurationMutationResult.Unchanged(current)
        }
        if (transformed.profileKey != current.profileKey) {
            return@withLock AlertConfigurationMutationResult.Rejected
        }
        val version = current.nextVersionOrNull()
            ?: return@withLock publishUnavailableResult(current.profileKey)
        val updated = runCatching {
            transformed.copy(
                policyVersion = version,
                updatedAtEpochMillis = now,
            )
        }.getOrElse { return@withLock AlertConfigurationMutationResult.Rejected }
        if (!write(updated)) return@withLock publishUnavailableResult(current.profileKey)
        mutableState.value = AlertConfigurationState.Ready(updated)
        AlertConfigurationMutationResult.Updated(updated)
    }

    private suspend fun write(record: AlertConfigurationRecord): Boolean =
        runCatching { store.write(record) }.isSuccess

    private fun safeNow(): Long = wallClockMillis().coerceAtLeast(0L)

    private fun publishUnavailable(profileKey: String?): AlertConfigurationState.Unavailable =
        AlertConfigurationState.Unavailable(profileKey).also { mutableState.value = it }

    private fun publishUnavailableResult(profileKey: String): AlertConfigurationMutationResult {
        publishUnavailable(profileKey)
        return AlertConfigurationMutationResult.Unavailable
    }

    private companion object {
        val SAFE_DEFAULT_POLICY = AlertPolicy(
            mode = AlertMode.IMPORTANT_ACTIVITY,
            notificationPrivacy = NotificationPrivacy.TEXT_ONLY,
        )
        const val MAX_SNOOZES = 512
    }
}

internal fun AlertConfigurationRecord.asNotificationView(): AlertConfigurationRecordView =
    AlertConfigurationRecordView(
        profileKey = profileKey,
        enabled = enabled,
        policyVersion = policyVersion,
        policy = policy,
        snoozes = snoozes.toList(),
    )

private fun AlertConfigurationRecord.nextVersionOrNull(): Long? =
    policyVersion.takeIf { it < Long.MAX_VALUE }?.plus(1L)

private fun AlertConfigurationRecord.withInactiveSnoozesRemoved(
    nowEpochMillis: Long,
    currentFrigateMode: String?,
): AlertConfigurationRecord = copy(
    snoozes = snoozes.filter { snooze ->
        val timeStillActive = snooze.expiresAtEpochMillis?.let { nowEpochMillis < it } ?: false
        val modeStillActive = snooze.untilModeChangesFrom?.let { originalMode ->
            currentFrigateMode == null || currentFrigateMode == originalMode
        } ?: false
        timeStillActive || modeStillActive
    },
)

private fun String.isValidProfileKey(): Boolean =
    isNotBlank() && length <= 128 && none(Char::isISOControl)
