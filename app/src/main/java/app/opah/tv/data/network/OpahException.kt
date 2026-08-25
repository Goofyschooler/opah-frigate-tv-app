package app.opah.tv.data.network

enum class OpahErrorCode {
    AUTHENTICATION_EXPIRED,
    INVALID_CREDENTIALS,
    PERMISSION_DENIED,
    RATE_LIMITED,
    BAD_REQUEST,
    OPERATION_CONFLICT,
    UNPROCESSABLE_REQUEST,
    NOT_FOUND,
    SERVER_ERROR,
    REDIRECT_REJECTED,
    TLS_FAILURE,
    DNS_FAILURE,
    CONNECTION_REFUSED,
    TIMEOUT,
    INVALID_RESPONSE,
    UNSUPPORTED_SERVER,
    PLAYBACK_FAILURE,
    UNKNOWN,
}

enum class OperationFailureKind {
    AUTHENTICATION,
    AUTHORIZATION,
    INVALID_REQUEST,
    CONFLICT,
    RESOURCE_OR_ENDPOINT_MISSING,
    RATE_LIMIT,
    TRANSIENT_SERVER,
    NETWORK,
    INVALID_RESPONSE,
    UNKNOWN,
}

enum class RecoveryAction {
    SIGN_IN,
    RETRY,
    CHECK_CONNECTION,
    CHECK_SERVER_URL,
    USE_DIFFERENT_ACCOUNT,
    NONE,
}

data class OpahFailure(
    val code: OpahErrorCode,
    val userMessage: String,
    val recoveryAction: RecoveryAction,
    val retryable: Boolean,
    val diagnosticCode: String = code.name,
    val httpStatus: Int? = null,
    val kind: OperationFailureKind = code.defaultFailureKind(),
)

open class OpahException(
    val failure: OpahFailure,
    cause: Throwable? = null,
) : Exception(failure.userMessage, cause) {
    constructor(userMessage: String, cause: Throwable? = null) : this(
        OpahFailure(
            code = OpahErrorCode.UNKNOWN,
            userMessage = userMessage,
            recoveryAction = RecoveryAction.NONE,
            retryable = false,
        ),
        cause,
    )

    val userMessage: String get() = failure.userMessage
}

class AuthenticationExpiredException(
    message: String = "The Frigate session is missing or expired. Sign in again.",
) : OpahException(
    OpahFailure(
        code = OpahErrorCode.AUTHENTICATION_EXPIRED,
        userMessage = message,
        recoveryAction = RecoveryAction.SIGN_IN,
        retryable = false,
    ),
)

class InvalidCredentialsException(
    message: String = "Frigate rejected the username or password",
) : OpahException(
    OpahFailure(
        code = OpahErrorCode.INVALID_CREDENTIALS,
        userMessage = message,
        recoveryAction = RecoveryAction.SIGN_IN,
        retryable = false,
    ),
)

fun Throwable.toOpahFailure(): OpahFailure = when (this) {
    is OpahException -> failure
    else -> OpahFailure(
        code = OpahErrorCode.UNKNOWN,
        userMessage = "An unexpected Opah error occurred",
        recoveryAction = RecoveryAction.RETRY,
        retryable = true,
    )
}

private fun OpahErrorCode.defaultFailureKind(): OperationFailureKind = when (this) {
    OpahErrorCode.AUTHENTICATION_EXPIRED,
    OpahErrorCode.INVALID_CREDENTIALS,
    -> OperationFailureKind.AUTHENTICATION
    OpahErrorCode.PERMISSION_DENIED -> OperationFailureKind.AUTHORIZATION
    OpahErrorCode.BAD_REQUEST,
    OpahErrorCode.UNPROCESSABLE_REQUEST,
    -> OperationFailureKind.INVALID_REQUEST
    OpahErrorCode.OPERATION_CONFLICT -> OperationFailureKind.CONFLICT
    OpahErrorCode.NOT_FOUND,
    OpahErrorCode.UNSUPPORTED_SERVER,
    -> OperationFailureKind.RESOURCE_OR_ENDPOINT_MISSING
    OpahErrorCode.RATE_LIMITED -> OperationFailureKind.RATE_LIMIT
    OpahErrorCode.SERVER_ERROR,
    OpahErrorCode.TIMEOUT,
    -> OperationFailureKind.TRANSIENT_SERVER
    OpahErrorCode.TLS_FAILURE,
    OpahErrorCode.DNS_FAILURE,
    OpahErrorCode.CONNECTION_REFUSED,
    -> OperationFailureKind.NETWORK
    OpahErrorCode.REDIRECT_REJECTED,
    OpahErrorCode.INVALID_RESPONSE,
    -> OperationFailureKind.INVALID_RESPONSE
    OpahErrorCode.PLAYBACK_FAILURE,
    OpahErrorCode.UNKNOWN,
    -> OperationFailureKind.UNKNOWN
}
