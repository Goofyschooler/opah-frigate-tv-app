package app.opah.tv.data

import app.opah.tv.data.model.FrigateCapabilityAvailability
import app.opah.tv.data.network.OpahFailure
import app.opah.tv.data.network.OperationFailureKind

/**
 * Converts one operation failure into short-lived capability evidence.
 * A missing resource or endpoint is deliberately unknown until the selected
 * version adapter determines which interpretation is valid.
 */
internal fun capabilityAvailabilityForFailure(
    failure: OpahFailure,
): FrigateCapabilityAvailability = when (failure.kind) {
    OperationFailureKind.AUTHENTICATION -> FrigateCapabilityAvailability.AUTHENTICATION_REQUIRED
    OperationFailureKind.AUTHORIZATION -> FrigateCapabilityAvailability.NOT_PERMITTED
    OperationFailureKind.RATE_LIMIT,
    OperationFailureKind.TRANSIENT_SERVER,
    OperationFailureKind.NETWORK,
    -> FrigateCapabilityAvailability.TEMPORARILY_UNAVAILABLE
    OperationFailureKind.INVALID_REQUEST,
    OperationFailureKind.CONFLICT,
    OperationFailureKind.RESOURCE_OR_ENDPOINT_MISSING,
    OperationFailureKind.INVALID_RESPONSE,
    OperationFailureKind.UNKNOWN,
    -> FrigateCapabilityAvailability.UNKNOWN
}
