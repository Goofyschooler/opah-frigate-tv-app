package app.opah.tv.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

data class UpdatePackageIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signerDigests: Set<String>,
)

internal fun verifyUpdatePackageIdentity(
    installed: UpdatePackageIdentity,
    candidate: UpdatePackageIdentity,
    expectedVersion: AppVersion,
) {
    val valid = candidate.packageName == installed.packageName &&
        AppVersion.parse(candidate.versionName) == expectedVersion &&
        candidate.versionCode > installed.versionCode &&
        candidate.signerDigests.isNotEmpty() &&
        candidate.signerDigests == installed.signerDigests
    if (!valid) throw AppUpdateException(
        AppUpdateErrorCode.VERIFICATION_FAILED,
        "The downloaded update could not be verified.",
    )
}

class AppUpdatePackageVerifier(private val context: Context) {
    fun verify(apk: File, expectedVersion: AppVersion) {
        val packageManager = context.packageManager
        val installed = packageInfo(packageManager, context.packageName, archive = false)
        val candidate = packageInfo(packageManager, apk.absolutePath, archive = true)
        verifyUpdatePackageIdentity(installed.toIdentity(), candidate.toIdentity(), expectedVersion)
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(
        packageManager: PackageManager,
        value: String,
        archive: Boolean,
    ): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return if (archive) {
            packageManager.getPackageArchiveInfo(value, flags)
        } else {
            packageManager.getPackageInfo(value, flags)
        } ?: throw AppUpdateException(
            AppUpdateErrorCode.VERIFICATION_FAILED,
            "The downloaded update could not be verified.",
        )
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.toIdentity(): UpdatePackageIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            signingInfo?.apkContentsSigners.orEmpty().toList()
        } else {
            signatures.orEmpty().toList()
        }
        return UpdatePackageIdentity(
            packageName = packageName,
            versionName = versionName.orEmpty(),
            versionCode = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong(),
            signerDigests = signatures.map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    }
            }.toSet(),
        )
    }
}
