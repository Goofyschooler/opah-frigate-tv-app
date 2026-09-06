package app.opah.tv.privacy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class PinCredentialSnapshot(
    val verifierRecord: PinVerifierRecord,
    val attemptState: PersistedPinAttemptState = PersistedPinAttemptState(),
    val credentialRevision: Long = 1L,
    val upgradedAtWallClockMillis: Long? = null,
) {
    override fun toString(): String =
        "PinCredentialSnapshot(verifierRecord=$verifierRecord, " +
            "attemptState=[redacted], credentialRevision=$credentialRevision, " +
            "upgraded=${upgradedAtWallClockMillis != null})"
}

internal sealed interface PinCredentialDecodeResult {
    data class Decoded(val snapshot: PinCredentialSnapshot) : PinCredentialDecodeResult
    data object Corrupt : PinCredentialDecodeResult
}

/**
 * Strict, bounded wire codec for the cleartext that is sealed by the Android Keystore adapter.
 * It contains a verifier, never a PIN. Unknown algorithms, scopes, or versions fail closed.
 */
internal class PinCredentialRecordCodec(
    private val kdfPolicy: PinKdfPolicy = PinKdfPolicy.production(),
    private val attemptPolicy: PinAttemptRatePolicy = PinAttemptRatePolicy(),
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(snapshot: PinCredentialSnapshot): ByteArray {
        require(isValid(snapshot)) { "Invalid PIN credential record" }
        val salt = snapshot.verifierRecord.salt
        val verifier = snapshot.verifierRecord.verifier
        return try {
            json.encodeToString(
                PersistedPinCredential.serializer(),
                PersistedPinCredential(
                    storageFormatVersion = STORAGE_FORMAT_VERSION,
                    verifierFormatVersion = snapshot.verifierRecord.formatVersion,
                    algorithm = snapshot.verifierRecord.algorithm.name,
                    saltHex = salt.toHex(),
                    iterations = snapshot.verifierRecord.iterations,
                    verifierHex = verifier.toHex(),
                    protectedScopes = snapshot.verifierRecord.protectedScopes
                        .map(PinScope::name)
                        .sorted(),
                    failedAttempts = snapshot.attemptState.failedAttempts,
                    nextAllowedWallClockMillis = snapshot.attemptState.nextAllowedWallClockMillis,
                    createdAtWallClockMillis = snapshot.verifierRecord.createdAtWallClockMillis,
                    credentialRevision = snapshot.credentialRevision,
                    upgradedAtWallClockMillis = snapshot.upgradedAtWallClockMillis,
                ),
            ).encodeToByteArray()
        } finally {
            salt.fill(0)
            verifier.fill(0)
        }
    }

    fun decode(cleartext: ByteArray): PinCredentialDecodeResult {
        if (cleartext.isEmpty() || cleartext.size > MAX_CLEARTEXT_BYTES) {
            return PinCredentialDecodeResult.Corrupt
        }
        val persisted = runCatching {
            json.decodeFromString(PersistedPinCredential.serializer(), cleartext.decodeToString())
        }.getOrNull() ?: return PinCredentialDecodeResult.Corrupt
        if (
            persisted.storageFormatVersion != STORAGE_FORMAT_VERSION ||
            persisted.verifierFormatVersion != PinCredentialKdf.PIN_RECORD_FORMAT_VERSION ||
            persisted.credentialRevision !in 1L..MAX_CREDENTIAL_REVISION ||
            persisted.createdAtWallClockMillis < 0L ||
            persisted.upgradedAtWallClockMillis?.let {
                it < persisted.createdAtWallClockMillis
            } == true ||
            persisted.protectedScopes.size > PinScope.entries.size ||
            persisted.protectedScopes.distinct().size != persisted.protectedScopes.size
        ) {
            return PinCredentialDecodeResult.Corrupt
        }
        val algorithm = PinKdfAlgorithm.entries.singleOrNull { it.name == persisted.algorithm }
            ?: return PinCredentialDecodeResult.Corrupt
        val scopes = persisted.protectedScopes.mapTo(mutableSetOf()) { encoded ->
            PinScope.entries.singleOrNull { it.name == encoded }
                ?: return PinCredentialDecodeResult.Corrupt
        }
        val salt = persisted.saltHex.decodeHex(MAX_SALT_BYTES)
            ?: return PinCredentialDecodeResult.Corrupt
        val verifier = persisted.verifierHex.decodeHex(MAX_VERIFIER_BYTES)
            ?: return PinCredentialDecodeResult.Corrupt
        return try {
            val record = PinVerifierRecord(
                formatVersion = persisted.verifierFormatVersion,
                algorithm = algorithm,
                salt = salt,
                iterations = persisted.iterations,
                verifier = verifier,
                protectedScopes = scopes,
                createdAtWallClockMillis = persisted.createdAtWallClockMillis,
            )
            val attempts = PersistedPinAttemptState(
                failedAttempts = persisted.failedAttempts,
                nextAllowedWallClockMillis = persisted.nextAllowedWallClockMillis,
            )
            val snapshot = PinCredentialSnapshot(
                verifierRecord = record,
                attemptState = attempts,
                credentialRevision = persisted.credentialRevision,
                upgradedAtWallClockMillis = persisted.upgradedAtWallClockMillis,
            )
            if (isValid(snapshot)) {
                PinCredentialDecodeResult.Decoded(snapshot)
            } else {
                PinCredentialDecodeResult.Corrupt
            }
        } finally {
            salt.fill(0)
            verifier.fill(0)
        }
    }

    private fun isValid(snapshot: PinCredentialSnapshot): Boolean =
        kdfPolicy.isValidRecord(snapshot.verifierRecord) &&
            attemptPolicy.isStructurallyValid(snapshot.attemptState) &&
            snapshot.credentialRevision in 1L..MAX_CREDENTIAL_REVISION &&
            snapshot.upgradedAtWallClockMillis?.let {
                it >= snapshot.verifierRecord.createdAtWallClockMillis
            } != false

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        this@toHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private fun String.decodeHex(maxBytes: Int): ByteArray? {
        if (isEmpty() || length % 2 != 0 || length > maxBytes * 2) return null
        val result = ByteArray(length / 2)
        for (index in result.indices) {
            val high = this[index * 2].hexValue()
            val low = this[index * 2 + 1].hexValue()
            if (high == null || low == null) {
                result.fill(0)
                return null
            }
            result[index] = ((high shl 4) or low).toByte()
        }
        return result
    }

    private fun Char.hexValue(): Int? = when (this) {
        in '0'..'9' -> code - '0'.code
        in 'a'..'f' -> code - 'a'.code + 10
        else -> null
    }

    private companion object {
        const val STORAGE_FORMAT_VERSION = 1
        const val MAX_CLEARTEXT_BYTES = 4_096
        const val MAX_SALT_BYTES = 64
        const val MAX_VERIFIER_BYTES = 64
        const val MAX_CREDENTIAL_REVISION = 1_000_000_000L
        const val HEX = "0123456789abcdef"
    }
}

@Serializable
private data class PersistedPinCredential(
    val storageFormatVersion: Int,
    val verifierFormatVersion: Int,
    val algorithm: String,
    val saltHex: String,
    val iterations: Int,
    val verifierHex: String,
    val protectedScopes: List<String>,
    val failedAttempts: Int,
    val nextAllowedWallClockMillis: Long,
    val createdAtWallClockMillis: Long,
    val credentialRevision: Long,
    val upgradedAtWallClockMillis: Long? = null,
)
