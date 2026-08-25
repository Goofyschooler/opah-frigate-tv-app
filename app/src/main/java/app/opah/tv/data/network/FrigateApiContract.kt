package app.opah.tv.data.network

import app.opah.tv.data.model.FrigateApiGeneration
import app.opah.tv.data.model.ServerVersionInfo

enum class FrigateContractOperation {
    CORE_DISCOVERY,
    REVIEW,
    RECORDING_HISTORY,
    SEMANTIC_SEARCH,
    SINGLE_EXPORT,
    DELETE_EXPORT,
    PROFILE_MODES,
    PROFILE_MODE_SWITCH,
    MOTION_SEARCH,
    ON_DEMAND_RECORDING,
    BATCH_EXPORT,
    EXPORT_JOBS,
    INCIDENTS,
    INCIDENT_MUTATION,
}

enum class ExportDeletionRoute { SINGLE, BULK, NONE }

/** Exact server-generation contract selected before endpoint work begins. */
data class FrigateApiContract(
    val generation: FrigateApiGeneration,
    val operations: Set<FrigateContractOperation>,
    val exportDeletionRoute: ExportDeletionRoute,
) {
    fun supports(operation: FrigateContractOperation): Boolean = operation in operations

    fun require(operation: FrigateContractOperation) {
        if (!supports(operation)) throw UnsupportedFrigateOperationException(operation)
    }
}

class UnsupportedFrigateOperationException(
    val operation: FrigateContractOperation,
) : OpahException(
    OpahFailure(
        code = OpahErrorCode.UNSUPPORTED_SERVER,
        userMessage = "This Frigate version does not support that Opah feature",
        recoveryAction = RecoveryAction.NONE,
        retryable = false,
        diagnosticCode = "UNSUPPORTED_${operation.name}",
    ),
)

internal fun frigateApiContract(version: ServerVersionInfo): FrigateApiContract = when (
    version.apiGeneration
) {
    FrigateApiGeneration.V0_17 -> FRIGATE_017_CONTRACT
    FrigateApiGeneration.V0_18 -> FRIGATE_018_CONTRACT
    FrigateApiGeneration.UNKNOWN -> UNKNOWN_CONTRACT
}

private val SHARED_OPERATIONS = setOf(
    FrigateContractOperation.CORE_DISCOVERY,
    FrigateContractOperation.REVIEW,
    FrigateContractOperation.RECORDING_HISTORY,
    FrigateContractOperation.SEMANTIC_SEARCH,
    FrigateContractOperation.SINGLE_EXPORT,
    FrigateContractOperation.DELETE_EXPORT,
)

private val FRIGATE_017_CONTRACT = FrigateApiContract(
    generation = FrigateApiGeneration.V0_17,
    operations = SHARED_OPERATIONS,
    exportDeletionRoute = ExportDeletionRoute.SINGLE,
)

private val FRIGATE_018_CONTRACT = FrigateApiContract(
    generation = FrigateApiGeneration.V0_18,
    operations = SHARED_OPERATIONS + setOf(
        FrigateContractOperation.PROFILE_MODES,
        FrigateContractOperation.PROFILE_MODE_SWITCH,
        FrigateContractOperation.MOTION_SEARCH,
        FrigateContractOperation.ON_DEMAND_RECORDING,
        FrigateContractOperation.BATCH_EXPORT,
        FrigateContractOperation.EXPORT_JOBS,
        FrigateContractOperation.INCIDENTS,
        FrigateContractOperation.INCIDENT_MUTATION,
    ),
    exportDeletionRoute = ExportDeletionRoute.BULK,
)

private val UNKNOWN_CONTRACT = FrigateApiContract(
    generation = FrigateApiGeneration.UNKNOWN,
    operations = emptySet(),
    exportDeletionRoute = ExportDeletionRoute.NONE,
)
