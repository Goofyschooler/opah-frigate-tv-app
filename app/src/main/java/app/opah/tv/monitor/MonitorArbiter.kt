package app.opah.tv.monitor

import app.opah.tv.awareness.AwarenessReview
import app.opah.tv.awareness.AwarenessReviewLifecycle
import app.opah.tv.awareness.AwarenessReviewSeverity

/** Pure, fake-clock-driven Monitor Mode arbitration. Playback and image work stay outside it. */
class MonitorArbiter {
    fun initialState(privacyEpoch: Long, nowEpochMillis: Long): MonitorState = MonitorState(
        privacyEpoch = privacyEpoch,
        phaseEnteredAtEpochMillis = nowEpochMillis,
    )

    fun reduce(previous: MonitorState, input: MonitorInput): MonitorTransition {
        val now = input.nowEpochMillis
        if (input.action == MonitorAction.Exit) {
            return MonitorTransition(
                state = previous.copy(
                    phase = MonitorPhase.EXITING,
                    promotion = null,
                    pending = null,
                    phaseEnteredAtEpochMillis = now,
                    deadlineEpochMillis = null,
                ),
                commands = buildList {
                    previous.promotion?.let { add(MonitorCommand.ReleasePromotion(it.cameraId)) }
                    add(MonitorCommand.EndSession)
                },
            )
        }
        if (previous.phase == MonitorPhase.EXITING) return MonitorTransition(previous)

        val currentStillVisible = previous.promotion?.cameraId?.let(input.visibleCameraIds::contains)
            ?: true
        if (!currentStillVisible) {
            return baseline(
                previous = previous,
                input = input,
                releaseCurrent = true,
            )
        }

        val manual = input.action as? MonitorAction.ManualSelect
        if (manual != null) {
            return manualSelection(previous, input, manual.cameraId)
        }

        if (!input.networkAvailable) return networkUnavailable(previous, input)
        if (previous.phase == MonitorPhase.RECOVERING) {
            return baseline(previous, input, releaseCurrent = !previous.liveReleasedForNetworkLoss)
        }

        val winner = bestActiveCandidate(input)
        return when (previous.phase) {
            MonitorPhase.BASELINE -> if (winner == null) {
                quietBaseline(previous, input)
            } else {
                beginDebounce(previous, input, winner)
            }
            MonitorPhase.DEBOUNCING -> continueDebounce(previous, input, winner)
            MonitorPhase.PROMOTING -> continuePromotion(previous, input, winner)
            MonitorPhase.PROMOTED -> continuePromoted(previous, input, winner)
            MonitorPhase.LINGERING -> continueLinger(previous, input, winner)
            MonitorPhase.MANUAL_HOLD -> continueManualHold(previous, input, winner)
            MonitorPhase.RECOVERING,
            MonitorPhase.EXITING,
            -> MonitorTransition(previous)
        }
    }

    private fun beginDebounce(
        previous: MonitorState,
        input: MonitorInput,
        winner: MonitorPromotion?,
    ): MonitorTransition {
        if (winner == null) return MonitorTransition(previous.copy(privacyEpoch = input.privacyEpoch))
        val state = previous.copy(
            phase = MonitorPhase.DEBOUNCING,
            privacyEpoch = input.privacyEpoch,
            pending = winner,
            phaseEnteredAtEpochMillis = input.nowEpochMillis,
            deadlineEpochMillis = safeAdd(
                input.nowEpochMillis,
                input.configuration.timings.debounceMillis,
            ),
        )
        return if (input.configuration.timings.debounceMillis == 0L) {
            promote(state, input, winner)
        } else {
            MonitorTransition(state)
        }
    }

    private fun quietBaseline(
        previous: MonitorState,
        input: MonitorInput,
    ): MonitorTransition {
        if (input.configuration.preset != MonitorPreset.PATROL) {
            return MonitorTransition(
                previous.copy(
                    privacyEpoch = input.privacyEpoch,
                    patrolCameraId = null,
                    patrolDeadlineEpochMillis = null,
                ),
                commands = if (previous.patrolCameraId != null) {
                    listOf(MonitorCommand.ShowBaseline)
                } else {
                    emptyList()
                },
            )
        }
        val cameras = input.configuration.viewCameraIds.filter(input.visibleCameraIds::contains)
        if (cameras.isEmpty()) {
            return MonitorTransition(
                previous.copy(
                    privacyEpoch = input.privacyEpoch,
                    patrolCameraId = null,
                    patrolDeadlineEpochMillis = null,
                ),
                commands = if (previous.patrolCameraId != null) {
                    listOf(MonitorCommand.ShowBaseline)
                } else {
                    emptyList()
                },
            )
        }
        val currentIndex = cameras.indexOf(previous.patrolCameraId)
        val expired = input.nowEpochMillis >=
            (previous.patrolDeadlineEpochMillis ?: Long.MIN_VALUE)
        if (currentIndex >= 0 && !expired) {
            return MonitorTransition(previous.copy(privacyEpoch = input.privacyEpoch))
        }
        val next = if (currentIndex < 0) cameras.first() else cameras[(currentIndex + 1) % cameras.size]
        return MonitorTransition(
            previous.copy(
                privacyEpoch = input.privacyEpoch,
                patrolCameraId = next,
                patrolDeadlineEpochMillis = safeAdd(
                    input.nowEpochMillis,
                    input.configuration.timings.patrolIntervalMillis,
                ),
            ),
            commands = listOf(MonitorCommand.ShowPatrolCamera(next)),
        )
    }

    private fun continueDebounce(
        previous: MonitorState,
        input: MonitorInput,
        winner: MonitorPromotion?,
    ): MonitorTransition {
        if (winner == null) return baseline(previous, input)
        if (winner.cameraId != previous.pending?.cameraId) {
            return beginDebounce(previous.copy(phase = MonitorPhase.BASELINE, pending = null), input, winner)
        }
        if (input.nowEpochMillis >= (previous.deadlineEpochMillis ?: Long.MAX_VALUE)) {
            return promote(previous, input, winner)
        }
        return MonitorTransition(previous.copy(pending = winner, privacyEpoch = input.privacyEpoch))
    }

    private fun promote(
        previous: MonitorState,
        input: MonitorInput,
        winner: MonitorPromotion,
    ): MonitorTransition = MonitorTransition(
        state = previous.copy(
            phase = MonitorPhase.PROMOTING,
            privacyEpoch = input.privacyEpoch,
            promotion = winner,
            pending = null,
            phaseEnteredAtEpochMillis = input.nowEpochMillis,
            deadlineEpochMillis = null,
        ),
        commands = listOf(MonitorCommand.BeginPromotion(winner)),
    )

    private fun continuePromotion(
        previous: MonitorState,
        input: MonitorInput,
        winner: MonitorPromotion?,
    ): MonitorTransition {
        val current = previous.promotion ?: return baseline(previous, input)
        if (winner == null || current.cameraId != winner.cameraId) {
            return replaceOrBaseline(previous, input, winner)
        }
        return when (input.action) {
            MonitorAction.PlaybackReady -> MonitorTransition(
                previous.copy(
                    phase = MonitorPhase.PROMOTED,
                    promotion = winner.copy(presentation = MonitorPresentationKind.LIVE),
                    promotedAtEpochMillis = input.nowEpochMillis,
                    phaseEnteredAtEpochMillis = input.nowEpochMillis,
                ),
            )
            MonitorAction.PlaybackDegradedToSnapshot,
            MonitorAction.PlaybackFailed,
            -> MonitorTransition(
                previous.copy(
                    phase = MonitorPhase.PROMOTED,
                    promotion = winner.copy(presentation = MonitorPresentationKind.SNAPSHOT),
                    promotedAtEpochMillis = input.nowEpochMillis,
                    phaseEnteredAtEpochMillis = input.nowEpochMillis,
                ),
            )
            else -> MonitorTransition(previous.copy(promotion = winner))
        }
    }

    private fun continuePromoted(
        previous: MonitorState,
        input: MonitorInput,
        winner: MonitorPromotion?,
    ): MonitorTransition {
        val current = previous.promotion ?: return baseline(previous, input)
        if (winner?.cameraId == current.cameraId) {
            return MonitorTransition(
                previous.copy(promotion = winner.copy(presentation = current.presentation)),
            )
        }
        val currentActive = winner?.identity() == current.identity()
        if (!currentActive) {
            if (winner != null && winner.rank > current.rank && preemptionAllowed(previous, input)) {
                return replaceOrBaseline(previous, input, winner)
            }
            val minimumDwellDeadline = safeAdd(
                previous.promotedAtEpochMillis ?: previous.phaseEnteredAtEpochMillis,
                input.configuration.timings.minimumDwellMillis,
            )
            val deadline = maxOf(
                safeAdd(input.nowEpochMillis, input.configuration.timings.lingerMillis),
                minimumDwellDeadline,
            )
            return MonitorTransition(
                previous.copy(
                    phase = MonitorPhase.LINGERING,
                    phaseEnteredAtEpochMillis = input.nowEpochMillis,
                    deadlineEpochMillis = deadline,
                ),
            )
        }
        return MonitorTransition(previous.copy(promotion = current.copy(rank = winner.rank)))
    }

    private fun continueLinger(
        previous: MonitorState,
        input: MonitorInput,
        winner: MonitorPromotion?,
    ): MonitorTransition {
        val current = previous.promotion ?: return baseline(previous, input)
        if (winner?.cameraId == current.cameraId) {
            return MonitorTransition(
                previous.copy(
                    phase = MonitorPhase.PROMOTED,
                    promotion = winner.copy(presentation = current.presentation),
                    deadlineEpochMillis = null,
                    phaseEnteredAtEpochMillis = input.nowEpochMillis,
                ),
            )
        }
        if (winner != null && winner.rank > current.rank) {
            return replaceOrBaseline(previous, input, winner)
        }
        if (input.nowEpochMillis >= (previous.deadlineEpochMillis ?: Long.MAX_VALUE)) {
            return baseline(previous, input, releaseCurrent = true)
        }
        return MonitorTransition(previous)
    }

    private fun manualSelection(
        previous: MonitorState,
        input: MonitorInput,
        cameraId: String,
    ): MonitorTransition {
        if (cameraId !in input.configuration.viewCameraIds || cameraId !in input.visibleCameraIds) {
            return MonitorTransition(previous.copy(privacyEpoch = input.privacyEpoch))
        }
        val manualPromotion = MonitorPromotion(
            cameraId = cameraId,
            reviewId = null,
            rank = manualRank(cameraId, input.configuration),
            presentation = MonitorPresentationKind.LIVE,
        )
        val commands = buildList {
            previous.promotion?.takeIf { it.cameraId != cameraId }
                ?.let { add(MonitorCommand.ReleasePromotion(it.cameraId)) }
            if (previous.promotion?.cameraId != cameraId) add(MonitorCommand.BeginPromotion(manualPromotion))
        }
        return MonitorTransition(
            state = previous.copy(
                phase = MonitorPhase.MANUAL_HOLD,
                privacyEpoch = input.privacyEpoch,
                promotion = manualPromotion,
                pending = null,
                phaseEnteredAtEpochMillis = input.nowEpochMillis,
                deadlineEpochMillis = safeAdd(
                    input.nowEpochMillis,
                    input.configuration.timings.manualHoldMillis,
                ),
            ),
            commands = commands,
        )
    }

    private fun continueManualHold(
        previous: MonitorState,
        input: MonitorInput,
        winner: MonitorPromotion?,
    ): MonitorTransition {
        if (input.action == MonitorAction.PlaybackDegradedToSnapshot ||
            input.action == MonitorAction.PlaybackFailed
        ) {
            return MonitorTransition(
                previous.copy(
                    promotion = previous.promotion?.copy(
                        presentation = MonitorPresentationKind.SNAPSHOT,
                    ),
                ),
            )
        }
        if (input.nowEpochMillis < (previous.deadlineEpochMillis ?: Long.MAX_VALUE)) {
            return MonitorTransition(previous)
        }
        return replaceOrBaseline(previous, input, winner)
    }

    private fun networkUnavailable(
        previous: MonitorState,
        input: MonitorInput,
    ): MonitorTransition {
        val lostAt = previous.networkLostAtEpochMillis ?: input.nowEpochMillis
        val releaseNow = previous.promotion != null && !previous.liveReleasedForNetworkLoss &&
            input.nowEpochMillis >= safeAdd(lostAt, input.configuration.timings.networkGraceMillis)
        return MonitorTransition(
            state = previous.copy(
                phase = MonitorPhase.RECOVERING,
                privacyEpoch = input.privacyEpoch,
                networkLostAtEpochMillis = lostAt,
                liveReleasedForNetworkLoss = previous.liveReleasedForNetworkLoss || releaseNow,
                phaseEnteredAtEpochMillis = if (previous.phase == MonitorPhase.RECOVERING) {
                    previous.phaseEnteredAtEpochMillis
                } else {
                    input.nowEpochMillis
                },
            ),
            commands = buildList {
                if (previous.phase != MonitorPhase.RECOVERING) add(MonitorCommand.ShowConnectionProblem)
                if (releaseNow) add(MonitorCommand.ReleasePromotion(previous.promotion.cameraId))
            },
        )
    }

    private fun replaceOrBaseline(
        previous: MonitorState,
        input: MonitorInput,
        winner: MonitorPromotion?,
    ): MonitorTransition {
        if (winner == null) return baseline(previous, input, releaseCurrent = true)
        val released = previous.promotion
        val promoted = promote(
            previous.copy(
                phase = MonitorPhase.BASELINE,
                promotion = null,
                pending = null,
                promotedAtEpochMillis = null,
            ),
            input,
            winner,
        )
        return promoted.copy(
            commands = buildList {
                released?.let { add(MonitorCommand.ReleasePromotion(it.cameraId)) }
                addAll(promoted.commands)
            },
        )
    }

    private fun baseline(
        previous: MonitorState,
        input: MonitorInput,
        releaseCurrent: Boolean = false,
    ): MonitorTransition = MonitorTransition(
        state = previous.copy(
            phase = MonitorPhase.BASELINE,
            privacyEpoch = input.privacyEpoch,
            promotion = null,
            pending = null,
            phaseEnteredAtEpochMillis = input.nowEpochMillis,
            promotedAtEpochMillis = null,
            deadlineEpochMillis = null,
            networkLostAtEpochMillis = null,
            liveReleasedForNetworkLoss = false,
            patrolCameraId = null,
            patrolDeadlineEpochMillis = null,
        ),
        commands = buildList {
            if (releaseCurrent) previous.promotion?.let {
                add(MonitorCommand.ReleasePromotion(it.cameraId))
            }
            add(MonitorCommand.ShowBaseline)
        },
    )

    private fun preemptionAllowed(previous: MonitorState, input: MonitorInput): Boolean {
        val promotedAt = previous.promotedAtEpochMillis ?: previous.phaseEnteredAtEpochMillis
        return input.nowEpochMillis >= safeAdd(
            promotedAt,
            input.configuration.timings.preemptionGuardMillis,
        )
    }

    private fun bestActiveCandidate(input: MonitorInput): MonitorPromotion? {
        if (input.configuration.preset == MonitorPreset.FIXED) return null
        val allowed = input.configuration.viewCameraIds.toSet().intersect(input.visibleCameraIds)
        return input.reviews.asSequence()
            .filter { review ->
                review.lifecycle == AwarenessReviewLifecycle.ACTIVE &&
                    review.camera in allowed &&
                    review.severity.eligible(input.configuration)
            }
            .mapNotNull { review -> review.toPromotion(input.configuration) }
            .maxWithOrNull(compareBy<MonitorPromotion> { it.rank }.thenBy { it.reviewId.orEmpty() })
    }

    private fun AwarenessReview.toPromotion(configuration: MonitorConfiguration): MonitorPromotion? {
        val cameraId = camera ?: return null
        val viewOrder = configuration.viewCameraIds.indexOf(cameraId)
        if (viewOrder < 0) return null
        val explicit = maxOf(
            configuration.cameraPriorities[cameraId] ?: 0,
            zones.maxOfOrNull { configuration.zonePriorities[it] ?: 0 } ?: 0,
            objects.maxOfOrNull { configuration.objectPriorities[it] ?: 0 } ?: 0,
        )
        val startMillis = startEpochSeconds?.let { seconds ->
            (seconds * 1_000.0).takeIf(Double::isFinite)?.coerceIn(0.0, Long.MAX_VALUE.toDouble())
                ?.toLong()
        } ?: 0L
        return MonitorPromotion(
            cameraId = cameraId,
            reviewId = id,
            rank = MonitorRank(
                severity = severity.weight,
                threatLevel = threatLevel?.coerceAtLeast(0) ?: 0,
                explicitPriority = explicit,
                startEpochMillis = startMillis,
                viewOrder = viewOrder,
                stableCameraId = cameraId,
            ),
            presentation = MonitorPresentationKind.LIVE,
        )
    }

    private fun manualRank(cameraId: String, configuration: MonitorConfiguration): MonitorRank =
        MonitorRank(
            severity = Int.MAX_VALUE,
            threatLevel = 0,
            explicitPriority = configuration.cameraPriorities[cameraId] ?: 0,
            startEpochMillis = 0L,
            viewOrder = configuration.viewCameraIds.indexOf(cameraId),
            stableCameraId = cameraId,
        )

    private fun AwarenessReviewSeverity.eligible(configuration: MonitorConfiguration): Boolean = when (this) {
        AwarenessReviewSeverity.ALERT -> true
        AwarenessReviewSeverity.DETECTION -> configuration.preset in setOf(
            MonitorPreset.ACTIVE,
            MonitorPreset.PATROL,
        )
        AwarenessReviewSeverity.SIGNIFICANT_MOTION ->
            configuration.significantMotionEnabled && configuration.preset in setOf(
                MonitorPreset.ACTIVE,
                MonitorPreset.PATROL,
            )
        AwarenessReviewSeverity.UNKNOWN -> false
    }

    private val AwarenessReviewSeverity.weight: Int
        get() = when (this) {
            AwarenessReviewSeverity.ALERT -> 3
            AwarenessReviewSeverity.DETECTION -> 2
            AwarenessReviewSeverity.SIGNIFICANT_MOTION -> 1
            AwarenessReviewSeverity.UNKNOWN -> 0
        }

    private fun MonitorPromotion.identity(): Pair<String, String?> = cameraId to reviewId

    private fun safeAdd(start: Long, duration: Long): Long =
        if (start > Long.MAX_VALUE - duration) Long.MAX_VALUE else start + duration
}
