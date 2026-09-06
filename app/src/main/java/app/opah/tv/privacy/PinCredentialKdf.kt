package app.opah.tv.privacy

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal enum class PinKdfAlgorithm(val jcaName: String) {
    PBKDF2_HMAC_SHA256("PBKDF2WithHmacSHA256"),
    PBKDF2_HMAC_SHA1("PBKDF2WithHmacSHA1"),
}

/** Plain PIN content is deliberately absent; byte arrays are defensively copied. */
internal class PinVerifierRecord(
    val formatVersion: Int,
    val algorithm: PinKdfAlgorithm,
    salt: ByteArray,
    val iterations: Int,
    verifier: ByteArray,
    protectedScopes: Set<PinScope>,
    val createdAtWallClockMillis: Long,
) {
    private val saltBytes: ByteArray = salt.copyOf()
    private val verifierBytes: ByteArray = verifier.copyOf()
    val salt: ByteArray
        get() = saltBytes.copyOf()
    val verifier: ByteArray
        get() = verifierBytes.copyOf()
    val protectedScopes: Set<PinScope> = protectedScopes.toSet()

    override fun toString(): String =
        "PinVerifierRecord(formatVersion=$formatVersion, algorithm=$algorithm, " +
            "iterations=[redacted], verifier=[redacted], protectedScopes=${protectedScopes.size})"
}

internal sealed interface PinSetupOutcome {
    data class Created(val record: PinVerifierRecord) : PinSetupOutcome
    data object ConfirmationMismatch : PinSetupOutcome
    data object Malformed : PinSetupOutcome
    data object Trivial : PinSetupOutcome
    data object KdfUnavailable : PinSetupOutcome
}

internal sealed interface PinVerificationOutcome {
    data class Matched(val upgradedRecord: PinVerifierRecord? = null) : PinVerificationOutcome
    data object NotMatched : PinVerificationOutcome
    data object CorruptRecord : PinVerificationOutcome
    data object KdfUnavailable : PinVerificationOutcome
}

/**
 * Consumes and clears every supplied PIN buffer. Callers must not reuse a buffer after invoking
 * this boundary and must clear any UI text state before awaiting the result.
 */
internal class PinCredentialKdf(
    private val secureRandom: SecureRandom = SecureRandom(),
    private val policy: PinKdfPolicy = PinKdfPolicy.production(),
    private val factoryProvider: (PinKdfAlgorithm) -> SecretKeyFactory? = { algorithm ->
        runCatching { SecretKeyFactory.getInstance(algorithm.jcaName) }.getOrNull()
    },
) {
    fun setup(
        pin: CharArray,
        confirmation: CharArray,
        protectedScopes: Set<PinScope>,
        createdAtWallClockMillis: Long,
    ): PinSetupOutcome = try {
        if (!pin.contentEquals(confirmation)) return PinSetupOutcome.ConfirmationMismatch
        when {
            !policy.isWellFormed(pin) -> PinSetupOutcome.Malformed
            policy.isTrivial(pin) -> PinSetupOutcome.Trivial
            createdAtWallClockMillis < 0L -> PinSetupOutcome.Malformed
            else -> createRecord(
                pin = pin,
                protectedScopes = protectedScopes,
                createdAtWallClockMillis = createdAtWallClockMillis,
            ) ?: PinSetupOutcome.KdfUnavailable
        }
    } finally {
        pin.fill('\u0000')
        confirmation.fill('\u0000')
    }

    fun verify(pin: CharArray, record: PinVerifierRecord): PinVerificationOutcome = try {
        if (!policy.isValidRecord(record)) return PinVerificationOutcome.CorruptRecord
        val salt = record.salt
        val expectedVerifier = record.verifier
        val matches = try {
            val derived = derive(
                pin = pin,
                salt = salt,
                iterations = record.iterations,
                algorithm = record.algorithm,
            ) ?: return PinVerificationOutcome.KdfUnavailable
            try {
                MessageDigest.isEqual(derived, expectedVerifier)
            } finally {
                derived.fill(0)
            }
        } finally {
            salt.fill(0)
            expectedVerifier.fill(0)
        }
        if (!matches) return PinVerificationOutcome.NotMatched

        val upgrade = if (
            record.algorithm == PinKdfAlgorithm.PBKDF2_HMAC_SHA1 &&
            factoryProvider(PinKdfAlgorithm.PBKDF2_HMAC_SHA256) != null
        ) {
            createRecord(
                pin = pin,
                protectedScopes = record.protectedScopes,
                createdAtWallClockMillis = record.createdAtWallClockMillis,
                requiredAlgorithm = PinKdfAlgorithm.PBKDF2_HMAC_SHA256,
            )?.record
        } else {
            null
        }
        PinVerificationOutcome.Matched(upgrade)
    } finally {
        pin.fill('\u0000')
    }

    private fun createRecord(
        pin: CharArray,
        protectedScopes: Set<PinScope>,
        createdAtWallClockMillis: Long,
        requiredAlgorithm: PinKdfAlgorithm? = null,
    ): PinSetupOutcome.Created? {
        val algorithm = requiredAlgorithm ?: preferredAvailableAlgorithm() ?: return null
        val iterations = policy.iterationsFor(algorithm)
        val salt = ByteArray(policy.saltBytes)
        secureRandom.nextBytes(salt)
        val verifier = derive(pin, salt, iterations, algorithm) ?: run {
            salt.fill(0)
            return null
        }
        return try {
            PinSetupOutcome.Created(
                PinVerifierRecord(
                    formatVersion = PIN_RECORD_FORMAT_VERSION,
                    algorithm = algorithm,
                    salt = salt,
                    iterations = iterations,
                    verifier = verifier,
                    protectedScopes = protectedScopes,
                    createdAtWallClockMillis = createdAtWallClockMillis,
                ),
            )
        } finally {
            salt.fill(0)
            verifier.fill(0)
        }
    }

    private fun preferredAvailableAlgorithm(): PinKdfAlgorithm? =
        PinKdfAlgorithm.PBKDF2_HMAC_SHA256.takeIf { factoryProvider(it) != null }
            ?: PinKdfAlgorithm.PBKDF2_HMAC_SHA1.takeIf { factoryProvider(it) != null }

    private fun derive(
        pin: CharArray,
        salt: ByteArray,
        iterations: Int,
        algorithm: PinKdfAlgorithm,
    ): ByteArray? {
        val factory = factoryProvider(algorithm) ?: return null
        val spec = PBEKeySpec(pin, salt, iterations, policy.verifierBits)
        return try {
            factory.generateSecret(spec).encoded
        } catch (_: Exception) {
            null
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val PIN_RECORD_FORMAT_VERSION = 1
    }
}

internal data class PinKdfPolicy(
    val sha256Iterations: Int,
    val sha1Iterations: Int,
    val minimumIterations: Int,
    val maximumIterations: Int,
    val saltBytes: Int,
    val verifierBits: Int,
    val minimumPinDigits: Int,
    val maximumPinDigits: Int,
) {
    init {
        require(minimumIterations > 0 && maximumIterations >= minimumIterations)
        require(sha256Iterations in minimumIterations..maximumIterations)
        require(sha1Iterations in minimumIterations..maximumIterations)
        require(saltBytes in 16..64)
        require(verifierBits in 128..512 && verifierBits % 8 == 0)
        require(minimumPinDigits >= 4 && maximumPinDigits >= minimumPinDigits)
    }

    fun iterationsFor(algorithm: PinKdfAlgorithm): Int = when (algorithm) {
        PinKdfAlgorithm.PBKDF2_HMAC_SHA256 -> sha256Iterations
        PinKdfAlgorithm.PBKDF2_HMAC_SHA1 -> sha1Iterations
    }

    fun isWellFormed(pin: CharArray): Boolean =
        pin.size in minimumPinDigits..maximumPinDigits && pin.all(Char::isDigit)

    fun isTrivial(pin: CharArray): Boolean {
        if (pin.indices.drop(1).all { pin[it] == pin[0] }) return true
        val ascending = pin.indices.drop(1).all { index ->
            pin[index].digitToInt() == (pin[index - 1].digitToInt() + 1) % 10
        }
        val descending = pin.indices.drop(1).all { index ->
            pin[index].digitToInt() == (pin[index - 1].digitToInt() + 9) % 10
        }
        val common = COMMON_PINS.any { known ->
            known.length == pin.size && known.indices.all { index -> known[index] == pin[index] }
        }
        return ascending || descending || common
    }

    fun isValidRecord(record: PinVerifierRecord): Boolean =
        record.formatVersion == PinCredentialKdf.PIN_RECORD_FORMAT_VERSION &&
            record.iterations in minimumIterations..maximumIterations &&
            record.salt.size in 16..64 &&
            record.verifier.size * 8 == verifierBits &&
            record.protectedScopes.size <= PinScope.entries.size &&
            record.createdAtWallClockMillis >= 0L

    companion object {
        fun production(): PinKdfPolicy = PinKdfPolicy(
            sha256Iterations = 210_000,
            sha1Iterations = 310_000,
            minimumIterations = 120_000,
            maximumIterations = 1_200_000,
            saltBytes = 16,
            verifierBits = 256,
            minimumPinDigits = 4,
            maximumPinDigits = 8,
        )

        internal fun testing(iterations: Int = 2): PinKdfPolicy = PinKdfPolicy(
            sha256Iterations = iterations,
            sha1Iterations = iterations,
            minimumIterations = 1,
            maximumIterations = 10_000,
            saltBytes = 16,
            verifierBits = 256,
            minimumPinDigits = 4,
            maximumPinDigits = 8,
        )

        private val COMMON_PINS = setOf("2580", "2000", "2020", "6969", "1212")
    }
}
