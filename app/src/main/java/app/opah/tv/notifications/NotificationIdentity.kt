package app.opah.tv.notifications

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

data class AndroidNotificationIdentity(
    val tag: String,
    val id: Int = EVENT_NOTIFICATION_ID,
)

class NotificationIdentityFactory(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun eventIdentity(profileKey: String, reviewId: String): AndroidNotificationIdentity {
        profileKey.requireOpaqueIdentity("Profile key", MAX_PROFILE_KEY_CHARS)
        reviewId.requireOpaqueIdentity("Review ID", MAX_REVIEW_ID_CHARS)
        val logicalKey = "$TAG_VERSION\u0000$profileKey\u0000$reviewId"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(logicalKey.toByteArray(StandardCharsets.UTF_8))
        return AndroidNotificationIdentity(tag = "$TAG_PREFIX${digest.base64UrlNoPadding()}")
    }

    fun newActionNonce(): String = ByteArray(ACTION_NONCE_BYTES)
        .also(secureRandom::nextBytes)
        .base64UrlNoPadding()
}

private fun String.requireOpaqueIdentity(label: String, maxChars: Int) {
    require(isNotBlank() && length <= maxChars) { "$label is invalid" }
    require(none { it.isISOControl() }) { "$label contains control characters" }
}

private fun ByteArray.base64UrlNoPadding(): String {
    val output = StringBuilder((size * 4 + 2) / 3)
    var index = 0
    while (index + 2 < size) {
        val value = ((this[index].toInt() and 0xff) shl 16) or
            ((this[index + 1].toInt() and 0xff) shl 8) or
            (this[index + 2].toInt() and 0xff)
        output.append(BASE64_URL_ALPHABET[value ushr 18])
        output.append(BASE64_URL_ALPHABET[(value ushr 12) and 63])
        output.append(BASE64_URL_ALPHABET[(value ushr 6) and 63])
        output.append(BASE64_URL_ALPHABET[value and 63])
        index += 3
    }
    val remaining = size - index
    if (remaining == 1) {
        val value = (this[index].toInt() and 0xff) shl 16
        output.append(BASE64_URL_ALPHABET[value ushr 18])
        output.append(BASE64_URL_ALPHABET[(value ushr 12) and 63])
    } else if (remaining == 2) {
        val value = ((this[index].toInt() and 0xff) shl 16) or
            ((this[index + 1].toInt() and 0xff) shl 8)
        output.append(BASE64_URL_ALPHABET[value ushr 18])
        output.append(BASE64_URL_ALPHABET[(value ushr 12) and 63])
        output.append(BASE64_URL_ALPHABET[(value ushr 6) and 63])
    }
    return output.toString()
}

internal const val EVENT_NOTIFICATION_ID = 1
private const val TAG_VERSION = "opah-notification-v1"
private const val TAG_PREFIX = "opah1_"
private const val ACTION_NONCE_BYTES = 32
private const val MAX_PROFILE_KEY_CHARS = 128
private const val MAX_REVIEW_ID_CHARS = 256
private const val BASE64_URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
