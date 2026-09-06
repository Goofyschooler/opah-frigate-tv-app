package app.opah.tv.briefing

import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSeverity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class BriefingEntry(
    val item: ReviewItem,
    val contentVersion: String,
    val factualTitle: String,
    val frigateAnalysis: String?,
)

data class BriefingSummary(
    val headline: String,
    val detail: String,
    val entries: List<BriefingEntry>,
    val unseenCount: Int,
    val capped: Boolean,
    val dismissalTargets: List<BriefingDismissalTarget>,
)

/**
 * Removes acknowledged clips from the briefing batch already presented to the user.
 *
 * A briefing may have more unseen candidates than the maximum-sized presented batch. Re-running
 * the summarizer after each acknowledgement would immediately backfill the batch, making a
 * 20-clip catch-up session continue to say "20 clips" after clips were reviewed. Keep the current
 * batch stable instead; a later explicit briefing refresh may present newer candidates.
 */
fun BriefingSummary.afterPresentedEntriesAcknowledged(reviewIds: Set<String>): BriefingSummary? {
    if (reviewIds.isEmpty()) return this
    val removedIds = entries.asSequence()
        .map { it.item.id }
        .filter { it in reviewIds }
        .toSet()
    if (removedIds.isEmpty()) return this
    val remainingEntries = entries.filterNot { it.item.id in removedIds }
    if (remainingEntries.isEmpty()) return null
    val cameraCount = remainingEntries.map { it.item.camera }.distinct().size
    return copy(
        detail = briefingDetail(cameraCount, capped),
        entries = remainingEntries,
        unseenCount = (unseenCount - removedIds.size).coerceAtLeast(remainingEntries.size),
        dismissalTargets = dismissalTargets.filterNot { it.reviewId in removedIds },
    )
}

object BriefingSummarizer {
    fun summarize(
        candidates: Collection<BriefingStoredCandidate>,
        acknowledgements: Map<String, BriefingAcknowledgement>,
        capped: Boolean,
        maximumPresented: Int = BRIEFING_MAX_PRESENTED,
    ): BriefingSummary? {
        require(maximumPresented in 1..BRIEFING_MAX_PRESENTED)
        val unseen = candidates.asSequence()
            .filter { candidate ->
                acknowledgements[candidate.item.id]?.contentVersion != candidate.contentVersion
            }
            .sortedWith(CANDIDATE_ORDER)
            .toList()
        if (unseen.isEmpty()) return null
        val selected = selectWithCameraDiversity(unseen, maximumPresented)
        val entries = selected.map { candidate ->
            BriefingEntry(
                item = candidate.item,
                contentVersion = candidate.contentVersion,
                factualTitle = factualTitle(candidate.item),
                frigateAnalysis = candidate.item.summary?.shortSummary
                    ?.trim()?.replace(Regex("\\s+"), " ")
                    ?.take(MAX_BRIEFING_SUMMARY_CHARS)
                    ?.takeIf(String::isNotEmpty),
            )
        }
        val dismissalTargets = unseen.map { candidate ->
            BriefingDismissalTarget(
                reviewId = candidate.item.id,
                cameraId = candidate.item.camera,
                contentVersion = candidate.contentVersion,
                containsRecognition = candidate.item.containsBriefingRecognition(),
            )
        }
        val cameraCount = unseen.map { it.item.camera }.distinct().size
        val headline = "Catch up on recent activity"
        val detail = briefingDetail(cameraCount, capped)
        return BriefingSummary(headline, detail, entries, unseen.size, capped, dismissalTargets)
    }

    fun contentVersion(item: ReviewItem): String {
        val canonical = buildString {
            append("briefing-content-v1\u001f")
            append(item.id).append('\u001f')
            append(item.camera).append('\u001f')
            append(item.severity.name).append('\u001f')
            append(item.startTime).append('\u001f')
            append(item.endTime).append('\u001f')
            append(item.objects.distinct().sorted().joinToString("\u001e")).append('\u001f')
            append(item.zones.distinct().sorted().joinToString("\u001e")).append('\u001f')
            append(item.audio.distinct().sorted().joinToString("\u001e")).append('\u001f')
            append(item.subLabels.distinct().sorted().joinToString("\u001e")).append('\u001f')
            append(item.summary?.potentialThreatLevel).append('\u001f')
            append(item.summary?.title.orEmpty()).append('\u001f')
            append(item.summary?.shortSummary.orEmpty()).append('\u001f')
            append(item.summary?.otherConcerns.orEmpty().distinct().sorted().joinToString("\u001e"))
        }.toByteArray(StandardCharsets.UTF_8)
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical)
            "v1:${digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }}"
        } finally {
            canonical.fill(0)
        }
    }

    private fun selectWithCameraDiversity(
        ordered: List<BriefingStoredCandidate>,
        maximum: Int,
    ): List<BriefingStoredCandidate> {
        val firstByCamera = ordered.distinctBy { it.item.camera }
        val selected = firstByCamera.take(maximum).toMutableList()
        if (selected.size < maximum) {
            val selectedIds = selected.mapTo(mutableSetOf()) { it.item.id }
            ordered.filterNot { it.item.id in selectedIds }
                .take(maximum - selected.size)
                .forEach(selected::add)
        }
        return selected.sortedWith(CANDIDATE_ORDER)
    }

    private fun factualTitle(item: ReviewItem): String {
        val labels = item.objects.distinct().map { it.replace('_', ' ') }.sorted()
        val subject = when (labels.size) {
            0 -> when (item.severity) {
                ReviewSeverity.SIGNIFICANT_MOTION -> "Significant motion"
                else -> "Camera activity"
            }
            1 -> labels.single().replaceFirstChar(Char::uppercase)
            else -> labels.take(2).joinToString(" and ").replaceFirstChar(Char::uppercase)
        }
        return subject.take(MAX_BRIEFING_TITLE_CHARS)
    }

    private val CANDIDATE_ORDER = compareByDescending<BriefingStoredCandidate> {
        it.item.severity.briefingWeight
    }
        .thenByDescending { it.item.summary?.potentialThreatLevel ?: 0 }
        .thenByDescending { !it.item.hasBeenReviewed }
        .thenByDescending { it.item.startTime }
        .thenBy { it.item.id }

    private val ReviewSeverity.briefingWeight: Int
        get() = when (this) {
            ReviewSeverity.ALERT -> 3
            ReviewSeverity.DETECTION -> 2
            ReviewSeverity.SIGNIFICANT_MOTION -> 1
            ReviewSeverity.UNKNOWN -> 0
        }
}

private fun briefingDetail(cameraCount: Int, capped: Boolean): String = buildString {
    append(
        if (cameraCount == 1) {
            "Highlights from 1 camera since your last visit"
        } else {
            "Highlights from $cameraCount cameras since your last visit"
        },
    )
    if (capped) append(" • More activity may be available")
}

internal fun ReviewItem.containsBriefingRecognition(): Boolean =
    subLabels.isNotEmpty() || linkedEvents.any { event ->
        !event.subLabel.isNullOrBlank() || !event.recognizedLicensePlate.isNullOrBlank()
    }

private const val MAX_BRIEFING_TITLE_CHARS = 120
private const val MAX_BRIEFING_SUMMARY_CHARS = 280
