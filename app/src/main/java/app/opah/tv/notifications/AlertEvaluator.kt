package app.opah.tv.notifications

import app.opah.tv.awareness.AwarenessReviewLifecycle
import app.opah.tv.awareness.AwarenessReviewSeverity
import java.util.Calendar

object AlertEvaluator {
    fun evaluate(input: AlertEvaluationInput): AlertDecision {
        val review = input.review
        val camera = review.camera ?: return input.denied(AlertDecisionReason.INCOMPLETE_EVENT)
        if (camera !in input.authorizedCameraIds) {
            return input.denied(AlertDecisionReason.UNAUTHORIZED_CAMERA)
        }

        val privacy = input.effectivePrivacy()
        if (input.privacy.guestModeActive && camera in input.privacy.privateCameraIds) {
            return input.denied(AlertDecisionReason.PRIVATE_CAMERA, NotificationPrivacy.NEVER_NOTIFY)
        }
        if (!input.privacy.pinAllowsNotification) {
            return input.denied(AlertDecisionReason.PIN_LOCKED, NotificationPrivacy.NEVER_NOTIFY)
        }
        if (privacy == NotificationPrivacy.NEVER_NOTIFY) {
            return input.denied(AlertDecisionReason.NEVER_NOTIFY, privacy)
        }
        if (review.reviewed == true) return input.denied(AlertDecisionReason.REVIEWED, privacy)
        if (review.lifecycle == AwarenessReviewLifecycle.UNKNOWN) {
            return input.denied(AlertDecisionReason.UNKNOWN_LIFECYCLE, privacy)
        }

        val notificationClass = review.severity.notificationClass()
            ?: return input.denied(AlertDecisionReason.UNKNOWN_SEVERITY, privacy)
        val policyFailure = input.policyFailure(camera)
        if (policyFailure != null) return input.denied(policyFailure, privacy)
        if (!input.insideSchedule()) return input.denied(AlertDecisionReason.OUTSIDE_SCHEDULE, privacy)
        if (input.snoozes.any { it.appliesTo(camera, input.nowEpochMillis, input.currentFrigateMode) }) {
            return input.denied(AlertDecisionReason.SNOOZED, privacy)
        }
        if (
            !input.presentation.allowSimultaneousSystemAlerts &&
            (input.presentation.viewedReviewId == review.id ||
                input.presentation.monitorReviewId == review.id)
        ) {
            return input.denied(AlertDecisionReason.ALREADY_WATCHED, privacy)
        }

        val ledger = input.ledger
        if (ledger == null && review.lifecycle == AwarenessReviewLifecycle.ENDED) {
            val endedAtMillis = review.endEpochSeconds?.times(1_000.0)?.toLong()
                ?: return input.denied(AlertDecisionReason.ENDED_OUTSIDE_GRACE, privacy)
            val age = input.nowEpochMillis - endedAtMillis
            if (
                review.severity != AwarenessReviewSeverity.ALERT ||
                age !in 0L..MISSED_ALERT_GRACE_MILLIS
            ) {
                return input.denied(AlertDecisionReason.ENDED_OUTSIDE_GRACE, privacy)
            }
        }

        val escalation = ledger != null &&
            review.severity == AwarenessReviewSeverity.ALERT &&
            ledger.highestPresentedSeverity.restriction() < AwarenessReviewSeverity.ALERT.restriction() &&
            ledger.highestAudibleSeverity.restriction() < AwarenessReviewSeverity.ALERT.restriction()
        if (
            ledger?.dismissedAtEpochMillis != null &&
            !escalation &&
            ledger.severityAtDismissal.restriction() >= review.severity.restriction()
        ) {
            return input.denied(AlertDecisionReason.DISMISSED, privacy)
        }
        if (
            ledger != null &&
            !escalation &&
            ledger.contentFingerprint != null &&
            ledger.contentFingerprint == input.contentFingerprint &&
            ledger.lifecycle == review.lifecycle
        ) {
            return input.denied(AlertDecisionReason.DUPLICATE, privacy)
        }

        val reason = when {
            escalation -> AlertDecisionReason.ELIGIBLE_ESCALATION
            ledger == null -> AlertDecisionReason.ELIGIBLE_INITIAL
            else -> AlertDecisionReason.ELIGIBLE_SILENT_UPDATE
        }
        return AlertDecision(
            eligible = true,
            notificationClass = notificationClass,
            privacyLevel = privacy,
            shouldRealert = escalation,
            openTarget = input.openTarget(),
            reason = reason,
        )
    }
}

private fun AlertEvaluationInput.policyFailure(camera: String): AlertDecisionReason? {
    val severity = review.severity
    when (policy.mode) {
        AlertMode.OFF -> return AlertDecisionReason.MODE_OFF
        AlertMode.IMPORTANT_ACTIVITY -> if (severity != AwarenessReviewSeverity.ALERT) {
            return AlertDecisionReason.BELOW_SELECTED_SEVERITY
        }
        AlertMode.ALL_DETECTED_ACTIVITY -> if (
            severity == AwarenessReviewSeverity.SIGNIFICANT_MOTION && !policy.significantMotionEnabled
        ) {
            return AlertDecisionReason.BELOW_SELECTED_SEVERITY
        }
        AlertMode.CUSTOM -> {
            if (severity !in policy.customSeverities) return AlertDecisionReason.CUSTOM_FILTER_MISMATCH
            if (severity == AwarenessReviewSeverity.SIGNIFICANT_MOTION && !policy.significantMotionEnabled) {
                return AlertDecisionReason.CUSTOM_FILTER_MISMATCH
            }
            if (policy.cameraIds.isNotEmpty() && camera !in policy.cameraIds) {
                return AlertDecisionReason.CUSTOM_FILTER_MISMATCH
            }
            if (policy.labels.isNotEmpty() && review.objects.none(policy.labels::contains)) {
                return AlertDecisionReason.CUSTOM_FILTER_MISMATCH
            }
            if (policy.zones.isNotEmpty() && review.zones.none(policy.zones::contains)) {
                return AlertDecisionReason.CUSTOM_FILTER_MISMATCH
            }
            if (policy.subLabels.isNotEmpty() && review.subLabels.none(policy.subLabels::contains)) {
                return AlertDecisionReason.CUSTOM_FILTER_MISMATCH
            }
            if (
                policy.plateLabels.isNotEmpty() &&
                recognizedPlateLabels.none(policy.plateLabels::contains)
            ) return AlertDecisionReason.CUSTOM_FILTER_MISMATCH
            if (
                policy.minimumThreatLevel != null &&
                (review.threatLevel ?: Int.MIN_VALUE) < policy.minimumThreatLevel
            ) return AlertDecisionReason.CUSTOM_FILTER_MISMATCH
            if (
                policy.frigateModes.isNotEmpty() &&
                currentFrigateMode !in policy.frigateModes
            ) return AlertDecisionReason.CUSTOM_FILTER_MISMATCH
        }
    }
    return null
}

private fun AlertEvaluationInput.insideSchedule(): Boolean {
    if (policy.schedules.isEmpty()) return true
    val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = nowEpochMillis }
    val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    val currentDay = calendar.get(Calendar.DAY_OF_WEEK).toAlertDay()
    return policy.schedules.any { window ->
        when {
            window.startMinuteInclusive == window.endMinuteExclusive -> currentDay in window.days
            window.startMinuteInclusive < window.endMinuteExclusive ->
                currentDay in window.days && minute in window.startMinuteInclusive until window.endMinuteExclusive
            minute >= window.startMinuteInclusive -> currentDay in window.days
            minute < window.endMinuteExclusive -> {
                val previous = calendar.clone() as Calendar
                previous.add(Calendar.DAY_OF_MONTH, -1)
                previous.get(Calendar.DAY_OF_WEEK).toAlertDay() in window.days
            }
            else -> false
        }
    }
}

private fun AlertSnooze.appliesTo(camera: String, now: Long, currentMode: String?): Boolean {
    val activeByTime = expiresAtEpochMillis?.let { now < it } ?: false
    // Unknown Mode is fail-closed: a Mode-bound snooze remains active until a fresh change is known.
    val activeByMode = untilModeChangesFrom?.let { currentMode == null || it == currentMode } ?: false
    if (!activeByTime && !activeByMode) return false
    return scope == AlertSnoozeScope.ALL || camera in cameraIds
}

private fun AlertEvaluationInput.effectivePrivacy(): NotificationPrivacy = listOfNotNull(
    policy.notificationPrivacy,
    privacy.globalPrivacy,
    privacy.cameraPrivacy,
    privacy.guestPrivacy.takeIf { privacy.guestModeActive },
).maxBy(NotificationPrivacy::restriction)

private fun AwarenessReviewSeverity.notificationClass(): NotificationClass? = when (this) {
    AwarenessReviewSeverity.ALERT -> NotificationClass.ALERT
    AwarenessReviewSeverity.DETECTION,
    AwarenessReviewSeverity.SIGNIFICANT_MOTION,
    -> NotificationClass.DETECTION
    AwarenessReviewSeverity.UNKNOWN -> null
}

private fun AlertEvaluationInput.openTarget(): AlertOpenTarget = when {
    review.lifecycle == AwarenessReviewLifecycle.ACTIVE -> AlertOpenTarget.LIVE_CAMERA
    recordingAvailable -> AlertOpenTarget.RECORDING
    activityAvailable -> AlertOpenTarget.ACTIVITY
    else -> AlertOpenTarget.EXPIRED_ACTIVITY
}

private fun AlertEvaluationInput.denied(
    reason: AlertDecisionReason,
    privacy: NotificationPrivacy = effectivePrivacy(),
): AlertDecision = AlertDecision(
    eligible = false,
    notificationClass = null,
    privacyLevel = privacy,
    shouldRealert = false,
    openTarget = null,
    reason = reason,
)

private fun AwarenessReviewSeverity?.restriction(): Int = when (this) {
    AwarenessReviewSeverity.UNKNOWN,
    null,
    -> 0
    AwarenessReviewSeverity.SIGNIFICANT_MOTION -> 1
    AwarenessReviewSeverity.DETECTION -> 2
    AwarenessReviewSeverity.ALERT -> 3
}

private fun Int.toAlertDay(): AlertDayOfWeek = when (this) {
    Calendar.SUNDAY -> AlertDayOfWeek.SUNDAY
    Calendar.MONDAY -> AlertDayOfWeek.MONDAY
    Calendar.TUESDAY -> AlertDayOfWeek.TUESDAY
    Calendar.WEDNESDAY -> AlertDayOfWeek.WEDNESDAY
    Calendar.THURSDAY -> AlertDayOfWeek.THURSDAY
    Calendar.FRIDAY -> AlertDayOfWeek.FRIDAY
    Calendar.SATURDAY -> AlertDayOfWeek.SATURDAY
    else -> error("Invalid day of week")
}
