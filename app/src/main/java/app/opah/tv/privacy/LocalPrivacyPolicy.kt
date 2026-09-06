package app.opah.tv.privacy

internal data class LocalPrivacyPolicy(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val epoch: Long = 1L,
    val guestModeActive: Boolean = false,
    val startInGuestMode: Boolean = false,
    val privateCameraIds: Set<String> = emptySet(),
    val guestModeFrigateModes: Set<String> = emptySet(),
    val ownerRecognitionDisclosure: RecognitionDisclosure = RecognitionDisclosure.SHOW_ALL,
    val guestRecognitionDisclosure: RecognitionDisclosure = RecognitionDisclosure.HIDE_ALL,
    val globalNotificationDisclosure: NotificationDisclosure = NotificationDisclosure.TEXT_ONLY,
    val guestNotificationDisclosure: NotificationDisclosure = NotificationDisclosure.TEXT_ONLY,
) {
    fun isValid(): Boolean =
        formatVersion == CURRENT_FORMAT_VERSION &&
            epoch in 1L until Long.MAX_VALUE &&
            privateCameraIds.isSafeIdentifierSet(MAX_PRIVATE_CAMERAS) &&
            guestModeFrigateModes.isSafeIdentifierSet(MAX_GUEST_MODE_MAPPINGS)

    fun nextEpoch(): Long? = epoch.takeIf { it < Long.MAX_VALUE - 1L }?.plus(1L)

    companion object {
        const val CURRENT_FORMAT_VERSION = 1
        const val MAX_PRIVATE_CAMERAS = 256
        const val MAX_GUEST_MODE_MAPPINGS = 32
        const val MAX_IDENTIFIER_LENGTH = 256

        fun recommendedDefault(): LocalPrivacyPolicy = LocalPrivacyPolicy()
    }
}

private fun Set<String>.isSafeIdentifierSet(maximumSize: Int): Boolean =
    size <= maximumSize && all { value ->
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= LocalPrivacyPolicy.MAX_IDENTIFIER_LENGTH &&
            value.none { character ->
                character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt()
            }
    }
