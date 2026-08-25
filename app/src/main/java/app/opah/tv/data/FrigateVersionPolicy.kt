package app.opah.tv.data

import app.opah.tv.data.model.ServerVersionCompatibility
import app.opah.tv.data.model.ServerVersionInfo
import app.opah.tv.data.model.FrigateApiGeneration

/** Central compatibility policy for the Frigate API contract Opah consumes. */
class FrigateVersionPolicy(
    private val supportedStableVersions: Set<SemanticVersion> = DEFAULT_SUPPORTED_STABLE_VERSIONS,
    private val supportedExactBuilds: Map<String, SemanticVersion> = DEFAULT_SUPPORTED_EXACT_BUILDS,
) {
    fun evaluate(rawVersion: String): ServerVersionInfo {
        val normalizedVersion = normalize(rawVersion)
        val match = VERSION_PATTERN.matchEntire(normalizedVersion)
        val parsed = match?.groupValues?.let { groups ->
            val major = groups[1]
            val minor = groups[2]
            val patch = groups[3]
            SemanticVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
        val prerelease = match?.groupValues?.get(4)?.takeIf(String::isNotBlank)
        val supportedLines = supportedStableVersions + supportedExactBuilds.values
        val compatibility = when {
            parsed == null -> ServerVersionCompatibility.UNKNOWN
            parsed in supportedStableVersions -> ServerVersionCompatibility.SUPPORTED
            supportedExactBuilds.any { (identity, version) ->
                version == parsed && normalizedVersion.matchesValidatedIdentity(identity)
            } -> ServerVersionCompatibility.SUPPORTED
            supportedLines.any { parsed.major == it.major && parsed.minor == it.minor } ->
                ServerVersionCompatibility.COMPATIBLE_UNVERIFIED
            else -> ServerVersionCompatibility.UNSUPPORTED
        }
        val supportedVersionNames = (
            supportedStableVersions.map(SemanticVersion::display) + supportedExactBuilds.keys
        ).sorted().joinToString(separator = " and ")
        val warning = when (compatibility) {
            ServerVersionCompatibility.SUPPORTED -> null
            ServerVersionCompatibility.COMPATIBLE_UNVERIFIED ->
                "Detected Frigate $rawVersion. Opah is validated against $supportedVersionNames; this patch version is expected to be close but is not yet verified."
            ServerVersionCompatibility.UNSUPPORTED ->
                "Detected Frigate $rawVersion. Opah is currently validated only against the Frigate $supportedVersionNames API contracts."
            ServerVersionCompatibility.UNKNOWN ->
                "Frigate returned an unrecognized version value. Opah is validated against $supportedVersionNames."
        }
        return ServerVersionInfo(
            rawVersion = rawVersion,
            major = parsed?.major,
            minor = parsed?.minor,
            patch = parsed?.patch,
            compatibility = compatibility,
            warning = warning,
            normalizedVersion = normalizedVersion,
            prerelease = prerelease,
            apiGeneration = parsed.apiGeneration(),
            validatedContract = compatibility == ServerVersionCompatibility.SUPPORTED,
        )
    }

    data class SemanticVersion(val major: Int, val minor: Int, val patch: Int) {
        val display: String get() = "$major.$minor.$patch"
    }

    private companion object {
        val DEFAULT_SUPPORTED_STABLE_VERSIONS = setOf(SemanticVersion(0, 17, 2))
        val DEFAULT_SUPPORTED_EXACT_BUILDS = mapOf(
            "0.18.0-344efb6" to SemanticVersion(0, 18, 0),
            "0.18.0-beta3" to SemanticVersion(0, 18, 0),
            "0.18.0-beta.3" to SemanticVersion(0, 18, 0),
            "0.18.0-beta3-344efb6" to SemanticVersion(0, 18, 0),
            "0.18.0-344efb6bc1e8db164bb5d3ec9bbfc6dbaf44deb7" to SemanticVersion(0, 18, 0),
        )
        val VERSION_PATTERN = Regex("""(\d+)\.(\d+)\.(\d+)(?:[-+](.+))?""")

        fun normalize(rawVersion: String): String = rawVersion
            .trim()
            .lowercase()
            .removePrefix("frigate ")
            .removePrefix("v")
            .trim()

        fun String.matchesValidatedIdentity(identity: String): Boolean =
            this == identity || startsWith("$identity+")

        fun SemanticVersion?.apiGeneration(): FrigateApiGeneration = when {
            this == null || major != 0 -> FrigateApiGeneration.UNKNOWN
            minor == 17 -> FrigateApiGeneration.V0_17
            minor == 18 -> FrigateApiGeneration.V0_18
            else -> FrigateApiGeneration.UNKNOWN
        }
    }
}
