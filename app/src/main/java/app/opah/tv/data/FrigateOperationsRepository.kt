package app.opah.tv.data

import app.opah.tv.data.model.BatchExportRequest
import app.opah.tv.data.model.BatchExportStart
import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.ExportIncident
import app.opah.tv.data.model.FrigateModes
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.model.IncidentDraft
import app.opah.tv.data.model.MotionSearchRequest
import app.opah.tv.data.model.MotionSearchStatus
import app.opah.tv.data.model.OnDemandRecordingStart
import app.opah.tv.data.network.FrigateContractOperation
import app.opah.tv.data.network.FrigateGateway
import app.opah.tv.data.network.OpahErrorCode
import app.opah.tv.data.network.OpahException
import app.opah.tv.data.network.OpahFailure
import app.opah.tv.data.network.RecoveryAction
import app.opah.tv.data.network.frigateApiContract
import kotlinx.coroutines.delay

data class ModeSwitchResult(
    val previousMode: String?,
    val activeMode: String?,
)

/** Generation-neutral, permission-aware entry point for 0.18 additions. */
class FrigateOperationsRepository(
    private val api: FrigateGateway,
    private val baseParsers: FrigateJsonParsers,
    private val parsers018: Frigate018JsonParsers = Frigate018JsonParsers(),
    private val versionPolicy: FrigateVersionPolicy = FrigateVersionPolicy(),
    private val invalidateCapabilities: (CapabilityInvalidationReason) -> Unit = {},
    private val verificationDelay: suspend () -> Unit = { delay(MODE_VERIFICATION_DELAY_MS) },
) {
    suspend fun loadModes(profile: ConnectionProfile, rawVersion: String): FrigateModes {
        contract(rawVersion).require(FrigateContractOperation.PROFILE_MODES)
        return parsers018.parseModes(api.getProfiles(profile))
    }

    suspend fun switchMode(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
        rawVersion: String,
        modeName: String?,
    ): ModeSwitchResult {
        contract(rawVersion).require(FrigateContractOperation.PROFILE_MODE_SWITCH)
        requireAdmin(user)
        val before = parsers018.parseModes(api.getProfiles(profile))
        val normalizedTarget = modeName?.trim()?.takeIf(String::isNotEmpty)
        if (normalizedTarget != null && before.modes.none { it.name == normalizedTarget }) {
            throw invalidOperation("That Mode is no longer available.")
        }

        api.setActiveProfile(profile, normalizedTarget)
        var verified: String? = null
        for (attempt in 0 until MODE_VERIFICATION_ATTEMPTS) {
            verified = parsers018.parseActiveMode(api.getActiveProfile(profile))
            if (verified == normalizedTarget) break
            if (attempt < MODE_VERIFICATION_ATTEMPTS - 1) verificationDelay()
        }
        if (verified != normalizedTarget) {
            throw OpahException(
                OpahFailure(
                    code = OpahErrorCode.OPERATION_CONFLICT,
                    userMessage = "Frigate did not activate that Mode",
                    recoveryAction = RecoveryAction.RETRY,
                    retryable = true,
                    diagnosticCode = "MODE_NOT_VERIFIED",
                ),
            )
        }
        invalidateCapabilities(CapabilityInvalidationReason.MODE_CHANGED)
        return ModeSwitchResult(before.activeMode, verified)
    }

    suspend fun startMotionSearch(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
        rawVersion: String,
        request: MotionSearchRequest,
    ): String {
        contract(rawVersion).require(FrigateContractOperation.MOTION_SEARCH)
        requireCamera(user, request.camera)
        return parsers018.parseMotionSearchJobId(api.startMotionSearch(profile, request))
    }

    suspend fun loadMotionSearch(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
        rawVersion: String,
        camera: String,
        jobId: String,
    ): MotionSearchStatus {
        contract(rawVersion).require(FrigateContractOperation.MOTION_SEARCH)
        requireCamera(user, camera)
        return parsers018.parseMotionSearchStatus(api.getMotionSearch(profile, camera, jobId))
    }

    suspend fun cancelMotionSearch(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
        rawVersion: String,
        camera: String,
        jobId: String,
    ) {
        contract(rawVersion).require(FrigateContractOperation.MOTION_SEARCH)
        requireCamera(user, camera)
        api.cancelMotionSearch(profile, camera, jobId)
    }

    suspend fun startOnDemandRecording(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
        rawVersion: String,
        camera: String,
        durationSeconds: Int?,
    ): OnDemandRecordingStart {
        contract(rawVersion).require(FrigateContractOperation.ON_DEMAND_RECORDING)
        requireAdmin(user)
        requireCamera(user, camera)
        return parsers018.parseOnDemandRecordingStart(
            api.startOnDemandRecording(profile, camera, durationSeconds),
        )
    }

    suspend fun stopOnDemandRecording(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
        rawVersion: String,
        eventId: String,
    ) {
        contract(rawVersion).require(FrigateContractOperation.ON_DEMAND_RECORDING)
        requireAdmin(user)
        api.stopOnDemandRecording(profile, eventId)
    }

    suspend fun startBatchExport(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
        rawVersion: String,
        request: BatchExportRequest,
    ): BatchExportStart {
        contract(rawVersion).require(FrigateContractOperation.BATCH_EXPORT)
        if (request.items.any { it.camera !in user.allowedCameras }) permissionDenied()
        if (request.existingIncidentId != null && !user.isAdmin()) permissionDenied()
        return parsers018.parseBatchExportStart(
            api.startBatchExport(profile, request),
            user.allowedCameras,
        )
    }

    suspend fun loadIncidents(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
        rawVersion: String,
        explicitlyCreatedEmptyIncidentIds: Set<String> = emptySet(),
    ): List<ExportIncident> {
        contract(rawVersion).require(FrigateContractOperation.INCIDENTS)
        val exports = baseParsers.parseRecordingExports(api.getExports(profile), user.allowedCameras)
        val authorizedIncidentIds = exports.mapNotNull { it.incidentId }.toSet()
        val permittedEmptyIds = explicitlyCreatedEmptyIncidentIds.takeIf { user.isAdmin() }.orEmpty()
        return parsers018.parseIncidents(
            api.getIncidents(profile),
            authorizedIncidentIds,
            permittedEmptyIds,
        )
    }

    suspend fun createIncident(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
        rawVersion: String,
        draft: IncidentDraft,
    ): String = mutateIncident(user, rawVersion) {
        parsers018.parseIncidentMutationId(api.createIncident(profile, draft))
    }

    suspend fun updateIncident(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
        rawVersion: String,
        incidentId: String,
        draft: IncidentDraft,
    ): String = mutateIncident(user, rawVersion) { api.updateIncident(profile, incidentId, draft) }

    suspend fun deleteIncident(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
        rawVersion: String,
        incidentId: String,
        deleteClips: Boolean,
    ): String = mutateIncident(user, rawVersion) {
        api.deleteIncident(profile, incidentId, deleteClips)
    }

    suspend fun reassignExports(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
        rawVersion: String,
        exportIds: Set<String>,
        incidentId: String?,
    ): String = mutateIncident(user, rawVersion) {
        requireAuthorizedExports(profile, user, exportIds)
        api.reassignExports(profile, exportIds, incidentId)
    }

    suspend fun renameExport(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
        rawVersion: String,
        exportId: String,
        name: String,
    ): String = mutateIncident(user, rawVersion) {
        requireAuthorizedExports(profile, user, setOf(exportId))
        api.renameExport(profile, exportId, name)
    }

    private suspend fun <T> mutateIncident(
        user: FrigateUserProfile,
        rawVersion: String,
        block: suspend () -> T,
    ): T {
        contract(rawVersion).require(FrigateContractOperation.INCIDENT_MUTATION)
        requireAdmin(user)
        return block()
    }

    private suspend fun requireAuthorizedExports(
        profile: ConnectionProfile,
        user: FrigateUserProfile,
        exportIds: Set<String>,
    ) {
        val visibleIds = baseParsers.parseRecordingExports(
            api.getExports(profile),
            user.allowedCameras,
        ).mapTo(mutableSetOf()) { it.id }
        if (exportIds.isEmpty() || !visibleIds.containsAll(exportIds)) permissionDenied()
    }

    private fun contract(rawVersion: String) = frigateApiContract(versionPolicy.evaluate(rawVersion))

    private fun requireAdmin(user: FrigateUserProfile) {
        if (!user.isAdmin()) permissionDenied()
    }

    private fun requireCamera(user: FrigateUserProfile, camera: String) {
        if (camera !in user.allowedCameras) permissionDenied()
    }

    private fun FrigateUserProfile.isAdmin(): Boolean = role.equals("admin", ignoreCase = true)

    private fun permissionDenied(): Nothing = throw OpahException(
        OpahFailure(
            code = OpahErrorCode.PERMISSION_DENIED,
            userMessage = "This Frigate account does not have permission for that operation",
            recoveryAction = RecoveryAction.USE_DIFFERENT_ACCOUNT,
            retryable = false,
        ),
    )

    private fun invalidOperation(message: String) = OpahException(
        OpahFailure(
            code = OpahErrorCode.BAD_REQUEST,
            userMessage = message,
            recoveryAction = RecoveryAction.RETRY,
            retryable = true,
        ),
    )

    private companion object {
        const val MODE_VERIFICATION_ATTEMPTS = 3
        const val MODE_VERIFICATION_DELAY_MS = 200L
    }
}
