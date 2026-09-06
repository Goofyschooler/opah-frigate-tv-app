package app.opah.tv.notifications

import app.opah.tv.awareness.AwarenessReview
import app.opah.tv.awareness.AwarenessReviewLifecycle
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class NotificationContentInput(
    val review: AwarenessReview,
    val cameraDisplayName: String?,
    val recognizedPeople: Set<String> = emptySet(),
    val recognizedPlateLabels: Set<String> = emptySet(),
    val showRecognizedPeople: Boolean = false,
    val showPlateLabels: Boolean = false,
    val thumbnailContentVersion: String? = null,
    val actions: Set<NotificationActionKind> = setOf(
        NotificationActionKind.OPEN,
        NotificationActionKind.SNOOZE,
    ),
) {
    init {
        require(recognizedPeople.size <= MAX_NOTIFICATION_CONTENT_ITEMS)
        require(recognizedPlateLabels.size <= MAX_NOTIFICATION_CONTENT_ITEMS)
        require(thumbnailContentVersion == null || thumbnailContentVersion.length <= MAX_CONTENT_VERSION_CHARS)
        require(NotificationActionKind.OPEN in actions)
        require(actions.size <= NotificationActionKind.entries.size)
    }
}

object NotificationContentRenderer {
    fun render(
        decision: AlertDecision,
        input: NotificationContentInput,
    ): NotificationRenderModel {
        require(decision.eligible) { "Ineligible activity cannot be rendered" }
        val notificationClass = requireNotNull(decision.notificationClass)
        val openTarget = requireNotNull(decision.openTarget)
        val camera = input.cameraDisplayName.safeText(MAX_CAMERA_DISPLAY_CHARS)
        val safeTitle = input.review.summary?.title.safeText(MAX_RENDER_TITLE_CHARS)
        val person = input.recognizedPeople.takeIf { input.showRecognizedPeople }
            ?.asSequence()
            ?.mapNotNull { it.safeText(MAX_IDENTITY_DISPLAY_CHARS) }
            ?.sorted()
            ?.firstOrNull()
        val plate = input.recognizedPlateLabels.takeIf { input.showPlateLabels }
            ?.asSequence()
            ?.mapNotNull { it.safeText(MAX_IDENTITY_DISPLAY_CHARS) }
            ?.sorted()
            ?.firstOrNull()
        val objectLabel = input.review.objects.asSequence()
            .mapNotNull { it.safeText(MAX_LABEL_DISPLAY_CHARS)?.humanized() }
            .sorted()
            .firstOrNull()
        val title = when {
            safeTitle != null -> safeTitle
            person != null -> withCamera(person, camera)
            plate != null -> withCamera(plate, camera)
            objectLabel != null -> withCamera(objectLabel.replaceFirstChar(Char::uppercase), camera)
            notificationClass == NotificationClass.ALERT -> withCamera("Important activity", camera)
            else -> withCamera("Detected activity", camera)
        }

        val safeSummary = input.review.summary?.shortSummary.safeText(MAX_RENDER_BODY_CHARS)
        val facts = (input.review.objects + input.review.zones).asSequence()
            .mapNotNull { it.safeText(MAX_LABEL_DISPLAY_CHARS)?.humanized() }
            .distinct()
            .sorted()
            .take(MAX_RENDER_FACTS)
            .joinToString(" • ")
        val body = when {
            safeSummary != null -> safeSummary
            facts.isNotBlank() -> facts
            input.review.lifecycle == AwarenessReviewLifecycle.ACTIVE -> "Activity is happening now"
            else -> "Activity ended"
        }
        val thumbnailVersion = input.thumbnailContentVersion.takeIf {
            decision.privacyLevel == NotificationPrivacy.FULL_PREVIEW ||
                decision.privacyLevel == NotificationPrivacy.BLURRED_PREVIEW
        }
        val actions = input.actions.toSet()
        val fingerprint = contentFingerprint(
            notificationClass = notificationClass,
            privacy = decision.privacyLevel,
            title = title,
            body = body,
            lifecycle = input.review.lifecycle,
            openTarget = openTarget,
            actions = actions,
            thumbnailContentVersion = thumbnailVersion,
        )
        return NotificationRenderModel(
            title = title,
            body = body,
            notificationClass = notificationClass,
            privacy = decision.privacyLevel,
            openTarget = openTarget,
            contentFingerprint = fingerprint,
            thumbnailContentVersion = thumbnailVersion,
            actions = actions,
        )
    }

    private fun contentFingerprint(
        notificationClass: NotificationClass,
        privacy: NotificationPrivacy,
        title: String,
        body: String,
        lifecycle: AwarenessReviewLifecycle,
        openTarget: AlertOpenTarget,
        actions: Set<NotificationActionKind>,
        thumbnailContentVersion: String?,
    ): String {
        val fields = listOf(
            CONTENT_FINGERPRINT_VERSION,
            notificationClass.name,
            privacy.name,
            title,
            body,
            lifecycle.name,
            openTarget.name,
            actions.map(Enum<*>::name).sorted().joinToString(","),
            thumbnailContentVersion.orEmpty(),
        )
        val canonical = fields.joinToString(separator = "") { value -> "${value.length}:$value" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

private fun String?.safeText(maxChars: Int): String? = this
    ?.asSequence()
    ?.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
    ?.joinToString(separator = "")
    ?.trim()
    ?.replace(Regex("\\s+"), " ")
    ?.take(maxChars)
    ?.takeIf(String::isNotBlank)

private fun String.humanized(): String = replace('_', ' ')

private fun withCamera(value: String, camera: String?): String = if (camera == null) {
    value
} else {
    "$value at $camera"
}

private const val CONTENT_FINGERPRINT_VERSION = "opah-notification-content-v1"
private const val MAX_NOTIFICATION_CONTENT_ITEMS = 128
private const val MAX_CONTENT_VERSION_CHARS = 256
private const val MAX_CAMERA_DISPLAY_CHARS = 80
private const val MAX_IDENTITY_DISPLAY_CHARS = 80
private const val MAX_LABEL_DISPLAY_CHARS = 80
private const val MAX_RENDER_TITLE_CHARS = 160
private const val MAX_RENDER_BODY_CHARS = 1_000
private const val MAX_RENDER_FACTS = 4
