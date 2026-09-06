package app.opah.tv.playback.compatibility

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/** Production implementations must use a non-exportable app-specific keyed HMAC-SHA-256 key. */
fun interface CompatibilityHmacSha256Port {
    /** Implementations must not retain or log [canonicalInput]. */
    fun digest(canonicalInput: ByteArray): ByteArray
}

enum class CompatibilityIdentityDomain(internal val wireName: String) {
    PROFILE("profile"),
    CAMERA("camera"),
    AUTHORIZATION("authorization"),
    DEVICE("device"),
    SERVER_API("server_api"),
    STREAM_CONFIGURATION("stream_configuration"),
    PLAYBACK_SOURCE("playback_source"),
    BRIEFING_SCOPE("briefing_scope"),
}

/**
 * Security-sensitive canonical identity boundary. Callers normalize semantic values (for example,
 * a host's case/default port) before entry; this factory makes field order and byte boundaries
 * unambiguous, applies the keyed digest, and exposes only its versioned nonreversible result.
 */
class CompatibilityIdentityFactory(
    private val hmac: CompatibilityHmacSha256Port,
) {
    fun derive(
        domain: CompatibilityIdentityDomain,
        canonicalComponents: Map<String, String>,
    ): CompatibilityIdentityKey {
        require(canonicalComponents.isNotEmpty()) { "Compatibility identity input must not be empty" }
        require(canonicalComponents.size <= MAX_COMPONENT_COUNT) {
            "Compatibility identity input has too many components"
        }
        val components = canonicalComponents.entries
            .map { entry ->
                require(COMPONENT_NAME.matches(entry.key)) {
                    "Compatibility identity component name is invalid"
                }
                requireWellFormedUtf16(entry.value)
                val valueBytes = entry.value.toByteArray(StandardCharsets.UTF_8)
                require(valueBytes.size <= MAX_COMPONENT_VALUE_BYTES) {
                    "Compatibility identity component is too large"
                }
                CanonicalComponent(entry.key, valueBytes)
            }
            .sortedBy(CanonicalComponent::name)
        val canonical = encode(domain, components)
        return try {
            val digest = hmac.digest(canonical)
            try {
                CompatibilityIdentityKey.fromHmacSha256(digest)
            } finally {
                digest.fill(0)
            }
        } finally {
            canonical.fill(0)
            components.forEach { it.value.fill(0) }
        }
    }

    private fun encode(
        domain: CompatibilityIdentityDomain,
        components: List<CanonicalComponent>,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(MAGIC)
            output.writeInt(CANONICAL_SCHEMA_VERSION)
            output.writeLengthPrefixed(domain.wireName.toByteArray(StandardCharsets.US_ASCII))
            output.writeInt(components.size)
            components.forEach { component ->
                output.writeLengthPrefixed(component.name.toByteArray(StandardCharsets.US_ASCII))
                output.writeLengthPrefixed(component.value)
            }
        }
        return bytes.toByteArray().also {
            require(it.size <= MAX_CANONICAL_BYTES) { "Compatibility identity input is too large" }
        }
    }

    private fun DataOutputStream.writeLengthPrefixed(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun requireWellFormedUtf16(value: String) {
        var index = 0
        while (index < value.length) {
            val codeUnit = value[index]
            when {
                Character.isHighSurrogate(codeUnit) -> {
                    require(
                        index + 1 < value.length && Character.isLowSurrogate(value[index + 1]),
                    ) { "Compatibility identity component contains malformed Unicode" }
                    index += 2
                }

                Character.isLowSurrogate(codeUnit) -> throw IllegalArgumentException(
                    "Compatibility identity component contains malformed Unicode",
                )

                else -> index += 1
            }
        }
    }

    private data class CanonicalComponent(
        val name: String,
        val value: ByteArray,
    )

    private companion object {
        val MAGIC: ByteArray = "opah.compatibility.identity".toByteArray(StandardCharsets.US_ASCII)
        val COMPONENT_NAME = Regex("[a-z][a-z0-9_.-]{0,63}")
        const val CANONICAL_SCHEMA_VERSION = 1
        const val MAX_COMPONENT_COUNT = 64
        const val MAX_COMPONENT_VALUE_BYTES = 4_096
        const val MAX_CANONICAL_BYTES = 32_768
    }
}
