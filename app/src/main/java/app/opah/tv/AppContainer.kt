package app.opah.tv

import android.content.Context
import android.os.SystemClock
import app.opah.tv.awareness.AwarenessReviewLifecycle
import app.opah.tv.data.FrigateJsonParsers
import app.opah.tv.data.FrigateRepository
import app.opah.tv.data.CameraImageRepository
import app.opah.tv.data.FrigateSessionManager
import app.opah.tv.data.FrigateOperationsRepository
import app.opah.tv.data.ProfileRepository
import app.opah.tv.data.ReviewImageRepository
import app.opah.tv.data.SettingsRepository
import app.opah.tv.data.compatibilityIdentityComponents
import app.opah.tv.data.network.FrigateApiClient
import app.opah.tv.data.network.FrigatePtzWebSocketClient
import app.opah.tv.data.network.PersistentCookieJar
import app.opah.tv.data.persistence.OpahDatabase
import app.opah.tv.data.persistence.RoomPlaybackPersistenceRecoveryAdapter
import app.opah.tv.data.persistence.RoomNotificationLedgerStore
import app.opah.tv.data.persistence.RoomAlertConfigurationStore
import app.opah.tv.data.persistence.RoomBriefingStore
import app.opah.tv.data.persistence.RoomVerifiedPlaybackStrategyStore
import app.opah.tv.data.realtime.AndroidRealtimeNetworkMonitor
import app.opah.tv.data.realtime.ProcessRealtimeRuntime
import app.opah.tv.data.realtime.RealtimeDemandOwner
import app.opah.tv.data.realtime.RealtimeLeaseId
import app.opah.tv.data.realtime.CameraScopeState
import app.opah.tv.data.realtime.RealtimeAuthenticationState
import app.opah.tv.data.realtime.RealtimeProfileState
import app.opah.tv.data.security.AndroidKeystoreCompatibilityHmac
import app.opah.tv.data.security.SecureSessionStore
import app.opah.tv.data.update.AppUpdateRepository
import app.opah.tv.data.update.DataStoreUpdateCheckCache
import app.opah.tv.data.update.GitHubReleaseApiClient
import app.opah.tv.data.update.AppUpdateDownloadClient
import app.opah.tv.data.update.AppUpdatePackageVerifier
import app.opah.tv.data.update.AppUpdatePreparationRepository
import app.opah.tv.device.DeviceMediaCapabilityService
import app.opah.tv.diagnostics.AndroidOpahLogger
import app.opah.tv.domain.StreamSelectionService
import app.opah.tv.playback.Media3LivePlayerFactory
import app.opah.tv.playback.compatibility.CompatibilityIdentityFactory
import app.opah.tv.playback.compatibility.CompatibilityIdentityDomain
import app.opah.tv.playback.compatibility.PlaybackElapsedRealtimeClock
import app.opah.tv.playback.compatibility.PersistedPlaybackPersistenceRecoveryHandler
import app.opah.tv.playback.compatibility.PlaybackPersistencePriorEpochReconciler
import app.opah.tv.playback.compatibility.PlaybackPersistenceStartupRecoveryOwner
import app.opah.tv.playback.compatibility.PlaybackResourcePlanner
import app.opah.tv.playback.compatibility.PlaybackStrategyPersistenceCoordinator
import app.opah.tv.playback.media3.SingleLivePlaybackCoordinator
import app.opah.tv.privacy.KeystorePinCredentialStore
import app.opah.tv.privacy.DataStorePrivacyPolicyStore
import app.opah.tv.privacy.PinRelockReason
import app.opah.tv.privacy.PinCredentialService
import app.opah.tv.privacy.PinUnlockSessionOwner
import app.opah.tv.privacy.PrivacyRepository
import app.opah.tv.privacy.AuthorizationFreshness
import app.opah.tv.privacy.PinScope
import app.opah.tv.privacy.PrivacyDecision
import app.opah.tv.privacy.PrivacyDecisionEngine
import app.opah.tv.privacy.PrivacyDenialReason
import app.opah.tv.privacy.PrivacyRequest
import app.opah.tv.privacy.PrivacySessionEvidence
import app.opah.tv.privacy.PrivacySurface
import app.opah.tv.privacy.PrivacyTarget
import app.opah.tv.notifications.AlertConfigurationRepository
import app.opah.tv.notifications.AlertConfigurationMutationResult
import app.opah.tv.notifications.AlertConfigurationState
import app.opah.tv.notifications.AlertPolicy
import app.opah.tv.notifications.AlertSnooze
import app.opah.tv.notifications.AlertSnoozeScope
import app.opah.tv.notifications.AlertDecisionReason
import app.opah.tv.notifications.AlertNotificationPost
import app.opah.tv.notifications.AlertContentIntentKind
import app.opah.tv.notifications.AlertOpenTarget
import app.opah.tv.notifications.NotificationActionKind
import app.opah.tv.notifications.NotificationAlertBehavior
import app.opah.tv.notifications.NotificationClass
import app.opah.tv.notifications.NotificationIdentityFactory
import app.opah.tv.notifications.NotificationPrivacy
import app.opah.tv.notifications.NotificationRenderModel
import app.opah.tv.notifications.NotificationCoordinator
import app.opah.tv.notifications.ProcessAlertNotificationOwner
import app.opah.tv.notifications.ProcessAlertModeOwner
import app.opah.tv.notifications.TvAlertOpenResolution
import app.opah.tv.notifications.android.AndroidAlertNotificationPlatform
import app.opah.tv.notifications.android.AndroidNotificationChannels
import app.opah.tv.notifications.android.TvAlertActivationResult
import app.opah.tv.notifications.android.TvAlertServiceController
import app.opah.tv.notifications.android.TvAlertStartResult
import app.opah.tv.notifications.android.TvAlertDeliveryStatus
import app.opah.tv.notifications.android.TvAlertOverlayPermission
import app.opah.tv.notifications.android.TvAlertOverlaySettings
import app.opah.tv.notifications.android.TvAlertOverlaySettingsStore
import app.opah.tv.notifications.android.LOCAL_TEST_THUMBNAIL_CONTENT_VERSION
import app.opah.tv.briefing.BriefingCoordinator
import app.opah.tv.briefing.BriefingScopeFactory
import app.opah.tv.briefing.FrigateBriefingReviewSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val playbackPersistenceRecoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val privacyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val realtimeProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val alertProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitorPresentedReviewId = MutableStateFlow<String?>(null)
    val secureSessionStore = SecureSessionStore(appContext)
    internal val pinCredentialStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KeystorePinCredentialStore(appContext)
    }
    internal val pinUnlockSessionOwner = PinUnlockSessionOwner()
    private val privacyRepositoryDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PrivacyRepository(
            policyStore = DataStorePrivacyPolicyStore(appContext),
            pinStore = pinCredentialStore,
            unlockSessions = pinUnlockSessionOwner,
        )
    }
    internal val privacyRepository: PrivacyRepository
        get() = privacyRepositoryDelegate.value
    internal val pinCredentialService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PinCredentialService(
            store = pinCredentialStore,
            privacyRepository = privacyRepository,
            unlockSessions = pinUnlockSessionOwner,
            monotonicClockMillis = SystemClock::elapsedRealtime,
        )
    }

    val cookieJar = PersistentCookieJar(secureSessionStore)
    val httpClient = FrigateApiClient.defaultClient(cookieJar)
    val apiClient = FrigateApiClient(httpClient, cookieJar)
    val ptzWebSocketClient = FrigatePtzWebSocketClient(httpClient)
    val profileRepository = ProfileRepository(appContext)
    val sessionManager = FrigateSessionManager(
        gateway = apiClient,
        cookieStore = cookieJar,
        credentialStore = secureSessionStore,
        profileStore = profileRepository,
    )
    val frigateRepository = FrigateRepository(apiClient, FrigateJsonParsers())
    val frigateOperationsRepository = FrigateOperationsRepository(
        api = apiClient,
        baseParsers = FrigateJsonParsers(),
        invalidateCapabilities = frigateRepository::invalidateCapabilities,
    )
    val settingsRepository = SettingsRepository(appContext)
    private val tvAlertOverlaySettingsStore = TvAlertOverlaySettingsStore(appContext)
    private val updateApiClient = GitHubReleaseApiClient(
        httpClient = GitHubReleaseApiClient.defaultClient(),
        appVersionName = BuildConfig.VERSION_NAME,
    )
    val appUpdateRepository = AppUpdateRepository(
        gateway = updateApiClient,
        cache = DataStoreUpdateCheckCache(appContext),
        installedVersionName = BuildConfig.VERSION_NAME,
    )
    val appUpdatePreparationRepository = AppUpdatePreparationRepository(
        downloadClient = AppUpdateDownloadClient(GitHubReleaseApiClient.downloadClient()),
        packageVerifier = AppUpdatePackageVerifier(appContext),
        updateDirectory = java.io.File(appContext.cacheDir, "updates"),
    )
    val cameraImageRepository = CameraImageRepository(httpClient)
    val reviewImageRepository = ReviewImageRepository(httpClient)
    val logger = AndroidOpahLogger()
    val deviceMediaCapabilityService = DeviceMediaCapabilityService()
    val streamSelectionService = StreamSelectionService()
    val playbackResourcePlanner = PlaybackResourcePlanner()
    val compatibilityIdentityFactory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CompatibilityIdentityFactory(AndroidKeystoreCompatibilityHmac())
    }
    private val realtimeRuntimeDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProcessRealtimeRuntime(
            processScope = realtimeProcessScope,
            webSockets = httpClient,
            gateway = apiClient,
            parsers = FrigateJsonParsers(),
            savedProfile = profileRepository::load,
            profileKey = { profile ->
                compatibilityIdentityFactory.derive(
                    CompatibilityIdentityDomain.PROFILE,
                    profile.compatibilityIdentityComponents(),
                ).value
            },
            wallClock = System::currentTimeMillis,
        )
    }
    internal val realtimeRuntime: ProcessRealtimeRuntime
        get() = realtimeRuntimeDelegate.value
    private val realtimeNetworkMonitorDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidRealtimeNetworkMonitor(appContext) { event -> realtimeRuntime.owner.dispatch(event) }
    }
    val opahDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OpahDatabase.create(appContext)
    }
    val verifiedPlaybackStrategyStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomVerifiedPlaybackStrategyStore(opahDatabase)
    }
    val playbackPersistenceRecoveryPort by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomPlaybackPersistenceRecoveryAdapter(opahDatabase)
    }
    val notificationLedgerStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomNotificationLedgerStore(opahDatabase.notificationLedgerDao())
    }
    val alertConfigurationStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomAlertConfigurationStore(opahDatabase.alertConfigurationDao())
    }
    val briefingStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomBriefingStore(opahDatabase.briefingDao())
    }
    internal val briefingScopeFactory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BriefingScopeFactory(compatibilityIdentityFactory)
    }
    internal val briefingCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BriefingCoordinator(briefingStore, FrigateBriefingReviewSource(frigateRepository))
    }
    private val alertConfigurationRepositoryDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AlertConfigurationRepository(
            store = alertConfigurationStore,
            wallClockMillis = System::currentTimeMillis,
        )
    }
    internal val alertConfigurationRepository: AlertConfigurationRepository
        get() = alertConfigurationRepositoryDelegate.value
    private val alertNotificationPlatform by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidAlertNotificationPlatform(
            context = appContext,
            client = httpClient,
            profile = { requestedProfileKey ->
                profileRepository.load()?.takeIf { profile ->
                    compatibilityIdentityFactory.derive(
                        CompatibilityIdentityDomain.PROFILE,
                        profile.compatibilityIdentityComponents(),
                    ).value == requestedProfileKey
                }
            },
        )
    }
    private val notificationCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NotificationCoordinator(
            ledgerStore = notificationLedgerStore,
            platform = alertNotificationPlatform,
        )
    }
    private val alertModeOwnerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProcessAlertModeOwner(
            scope = alertProcessScope,
            transportState = realtimeRuntime.owner.state,
            configuration = alertConfigurationRepository,
            profile = { requestedProfileKey ->
                profileRepository.load()?.takeIf { profile ->
                    compatibilityIdentityFactory.derive(
                        CompatibilityIdentityDomain.PROFILE,
                        profile.compatibilityIdentityComponents(),
                    ).value == requestedProfileKey
                }
            },
            gateway = apiClient,
        )
    }
    private val alertNotificationOwnerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProcessAlertNotificationOwner(
            scope = alertProcessScope,
            awareness = realtimeRuntime.awareness,
            configurationRepository = alertConfigurationRepository,
            privacyRepository = privacyRepository,
            transportState = realtimeRuntime.owner.state,
            modeState = alertModeOwnerDelegate.value.state,
            monitorReviewId = monitorPresentedReviewId,
            plateLabels = { requestedProfileKey, camera, detectionIds, authorizedCameraIds ->
                val profile = profileRepository.load()?.takeIf { candidate ->
                    compatibilityIdentityFactory.derive(
                        CompatibilityIdentityDomain.PROFILE,
                        candidate.compatibilityIdentityComponents(),
                    ).value == requestedProfileKey
                } ?: return@ProcessAlertNotificationOwner emptySet()
                frigateRepository.recognizedLicensePlates(
                    profile = profile,
                    allowedCameras = authorizedCameraIds,
                    camera = camera,
                    detectionIds = detectionIds,
                )
            },
            ledgerStore = notificationLedgerStore,
            coordinator = notificationCoordinator,
            wallClockMillis = System::currentTimeMillis,
            monotonicClockMillis = SystemClock::elapsedRealtime,
        )
    }
    val playbackStrategyPersistenceCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlaybackStrategyPersistenceCoordinator(
            store = verifiedPlaybackStrategyStore,
            clock = PlaybackElapsedRealtimeClock(SystemClock::elapsedRealtime),
            recoveryPort = playbackPersistenceRecoveryPort,
            persistedRecoveryHandler = PersistedPlaybackPersistenceRecoveryHandler { obligation ->
                verifiedPlaybackStrategyStore.recoverPersistedUnresolvedWrite(obligation)
            },
        )
    }
    val playbackPersistenceStartupRecoveryOwner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlaybackPersistenceStartupRecoveryOwner(
            scope = playbackPersistenceRecoveryScope,
            recoveryPort = playbackPersistenceRecoveryPort,
            handler = PersistedPlaybackPersistenceRecoveryHandler { obligation ->
                verifiedPlaybackStrategyStore.recoverPersistedUnresolvedWrite(obligation)
            },
            priorEpochReconciler = PlaybackPersistencePriorEpochReconciler {
                verifiedPlaybackStrategyStore.reconcilePriorEpochPendingPersistenceAttempts()
            },
        )
    }
    val livePlayerFactory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Media3LivePlayerFactory()
    }
    private val singleLivePlaybackCoordinatorDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SingleLivePlaybackCoordinator(
            context = appContext,
            processScope = playbackProcessScope,
            identityFactory = compatibilityIdentityFactory,
            strategyStore = verifiedPlaybackStrategyStore,
            persistence = playbackStrategyPersistenceCoordinator,
        )
    }
    internal val singleLivePlaybackCoordinator: SingleLivePlaybackCoordinator
        get() = singleLivePlaybackCoordinatorDelegate.value

    internal fun revokeSingleLivePlayback() {
        if (singleLivePlaybackCoordinatorDelegate.isInitialized()) {
            singleLivePlaybackCoordinatorDelegate.value.revokeAll()
        }
    }

    internal fun onAppBackgrounded() {
        val revokedGrant = pinUnlockSessionOwner.onAppBackgrounded()
        privacyScope.launch {
            privacyRepository.relock(
                reason = PinRelockReason.APP_BACKGROUND,
                forceEpochAdvance = revokedGrant,
            )
        }
    }

    internal fun onSignedOut() {
        alertNotificationPlatform.clearEphemeralContent()
        if (alertNotificationOwnerDelegate.isInitialized()) {
            alertNotificationOwnerDelegate.value.stop()
            alertProcessScope.launch {
                alertNotificationOwnerDelegate.value.purgeActiveProfile()
            }
        }
        if (realtimeRuntimeDelegate.isInitialized()) {
            realtimeRuntimeDelegate.value.signOut()
        }
        if (realtimeNetworkMonitorDelegate.isInitialized()) {
            realtimeNetworkMonitorDelegate.value.stop()
        }
        pinUnlockSessionOwner.onSignOut()
        if (alertConfigurationRepositoryDelegate.isInitialized()) {
            alertConfigurationRepositoryDelegate.value.clearActiveProfile()
        }
        privacyScope.launch {
            privacyRepository.relock(PinRelockReason.SIGN_OUT, forceEpochAdvance = true)
        }
    }

    internal fun startPrivacyInitialization() {
        privacyScope.launch {
            privacyRepository.initialize()
        }
    }

    internal fun onAuthenticatedConnection(
        profile: app.opah.tv.data.model.ConnectionProfile,
        rawFrigateVersion: String,
    ) {
        realtimeRuntime.activateAuthenticatedProfile(profile, rawFrigateVersion)
        realtimeNetworkMonitorDelegate.value.start()
        val profileKey = compatibilityIdentityFactory.derive(
            CompatibilityIdentityDomain.PROFILE,
            profile.compatibilityIdentityComponents(),
        ).value
        alertProcessScope.launch {
            alertConfigurationRepository.initialize(profileKey)
        }
    }

    internal suspend fun setTvAlertPolicy(policy: AlertPolicy): AlertConfigurationMutationResult =
        alertConfigurationRepository.setPolicy(policy)

    internal suspend fun addTvAlertSnooze(snooze: AlertSnooze): AlertConfigurationMutationResult =
        alertConfigurationRepository.addSnooze(snooze)

    internal suspend fun clearTvAlertSnoozes(): AlertConfigurationMutationResult =
        alertConfigurationRepository.clearSnoozes()

    internal fun tvAlertOverlaySettings(): TvAlertOverlaySettings = tvAlertOverlaySettingsStore.load()

    internal fun setTvAlertOverlaySettings(settings: TvAlertOverlaySettings): TvAlertOverlaySettings =
        tvAlertOverlaySettingsStore.save(settings)

    internal suspend fun postLocalTestAlert(includeImage: Boolean): Boolean {
        val configuration = (alertConfigurationRepository.state.value as? AlertConfigurationState.Ready)
            ?.configuration
            ?: return false
        val identities = NotificationIdentityFactory()
        val identity = identities.eventIdentity(configuration.profileKey, LOCAL_TEST_REVIEW_ID)
        alertNotificationPlatform.cancel(identity)
        delay(LOCAL_TEST_REPOST_DELAY_MILLIS)
        return alertNotificationPlatform.post(
            AlertNotificationPost(
                profileKey = configuration.profileKey,
                reviewId = LOCAL_TEST_REVIEW_ID,
                identity = identity,
                actionNonce = identities.newActionNonce(),
                model = NotificationRenderModel(
                    title = "Person at Demo entrance",
                    body = if (includeImage) {
                        "Fictional alert with a test image"
                    } else {
                        "Fictional alert without a camera image"
                    },
                    notificationClass = NotificationClass.ALERT,
                    privacy = if (includeImage) {
                        NotificationPrivacy.FULL_PREVIEW
                    } else {
                        NotificationPrivacy.TEXT_ONLY
                    },
                    openTarget = AlertOpenTarget.ACTIVITY,
                    contentFingerprint = if (includeImage) {
                        "$LOCAL_TEST_FINGERPRINT-image"
                    } else {
                        "$LOCAL_TEST_FINGERPRINT-text"
                    },
                    thumbnailContentVersion = LOCAL_TEST_THUMBNAIL_CONTENT_VERSION.takeIf { includeImage },
                    actions = setOf(NotificationActionKind.OPEN),
                ),
                alertBehavior = NotificationAlertBehavior.AUDIBLE_INITIAL,
                contentIntentKind = AlertContentIntentKind.LOCAL_TEST,
            ),
        )
    }

    internal suspend fun dismissTvAlert(
        profileKey: String,
        reviewId: String,
        actionNonce: String,
    ): Boolean = notificationCoordinator.dismiss(
        profileKey = profileKey,
        reviewId = reviewId,
        actionNonce = actionNonce,
        nowEpochMillis = System.currentTimeMillis().coerceAtLeast(0L),
    )

    /** Resolves an opaque notification tap without trusting any display content in the Intent. */
    internal suspend fun resolveTvAlertOpen(
        profileKey: String,
        reviewId: String,
        actionNonce: String,
    ): TvAlertOpenResolution {
        notificationCoordinator.activeActionRecord(profileKey, reviewId, actionNonce)
            ?: return TvAlertOpenResolution.Unavailable

        val transport = realtimeRuntime.owner.currentState()
        val activeProfile = when (val profile = transport.profileState) {
            RealtimeProfileState.Unavailable -> return TvAlertOpenResolution.RetryLater
            is RealtimeProfileState.Invalid -> return TvAlertOpenResolution.Unavailable
            is RealtimeProfileState.Ready -> profile.profile
                .takeIf { it.id == profileKey }
                ?: return TvAlertOpenResolution.Unavailable
        }
        when (transport.authenticationState) {
            RealtimeAuthenticationState.UNKNOWN -> return TvAlertOpenResolution.RetryLater
            RealtimeAuthenticationState.AUTHENTICATED -> Unit
            RealtimeAuthenticationState.REQUIRED,
            RealtimeAuthenticationState.FORBIDDEN,
            -> return TvAlertOpenResolution.Unavailable
        }
        val scope = (transport.cameraScope as? CameraScopeState.Fresh)?.evidence
            ?.takeIf { it.boundsValid }
            ?: return TvAlertOpenResolution.RetryLater
        val allowedCameraIds = scope.allowedCameraIds
        val processState = realtimeRuntime.awareness.state.value
        if (
            processState.desynchronized ||
            processState.ledger.profileKey == null ||
            processState.authorizedCameraIds != allowedCameraIds
        ) {
            return TvAlertOpenResolution.RetryLater
        }
        if (processState.ledger.profileKey != activeProfile.id) {
            return TvAlertOpenResolution.Unavailable
        }

        privacyRepository.initialize()
        val privacySnapshot = privacyRepository.snapshot(
            session = PrivacySessionEvidence(
                authenticated = true,
                authorizationFreshness = AuthorizationFreshness.FRESH,
                allowedCameraIds = allowedCameraIds,
            ),
            nowMonotonicMillis = SystemClock.elapsedRealtime(),
        )
        val review = processState.ledger.review(reviewId)
            ?.takeIf { it.profileKey == profileKey }
        val resolution = if (review == null) {
            if (allowedCameraIds.isEmpty()) return TvAlertOpenResolution.Unavailable
            if (
                PinScope.ACTIVITY_HISTORY_SEARCH in privacySnapshot.protectedScopes &&
                PinScope.ACTIVITY_HISTORY_SEARCH !in privacySnapshot.pinUnlockProof?.scopes.orEmpty()
            ) {
                return TvAlertOpenResolution.PrivacyBlocked
            }
            val request = PrivacyRequest(
                surface = PrivacySurface.DEEP_LINK,
                target = PrivacyTarget.CameraCollection(
                    collectionId = "expired-notification",
                    cameraIds = allowedCameraIds,
                ),
                expectedPrivacyEpoch = privacySnapshot.epoch,
            )
            when (val decision = PrivacyDecisionEngine().decide(request, privacySnapshot)) {
                is PrivacyDecision.Allow,
                is PrivacyDecision.AllowRedacted,
                -> TvAlertOpenResolution.Activity(expired = true)
                is PrivacyDecision.RequirePin -> return TvAlertOpenResolution.PrivacyBlocked
                is PrivacyDecision.Deny -> return decision.toTvAlertBlockedResolution()
            }
        } else {
            val camera = review.camera
                ?.takeIf(allowedCameraIds::contains)
                ?.takeIf(processState.authorizedCameraIds::contains)
                ?: return TvAlertOpenResolution.Unavailable
            val request = PrivacyRequest(
                surface = PrivacySurface.DEEP_LINK,
                target = PrivacyTarget.Review(
                    reviewId = review.id,
                    cameraId = camera,
                    containsRecognition = review.subLabels.isNotEmpty(),
                ),
                expectedPrivacyEpoch = privacySnapshot.epoch,
            )
            when (val decision = PrivacyDecisionEngine().decide(request, privacySnapshot)) {
                is PrivacyDecision.Allow,
                is PrivacyDecision.AllowRedacted,
                -> when (review.lifecycle) {
                    AwarenessReviewLifecycle.ACTIVE -> TvAlertOpenResolution.LiveCamera(camera)
                    AwarenessReviewLifecycle.ENDED -> TvAlertOpenResolution.RecordedReview(review.id)
                    AwarenessReviewLifecycle.UNKNOWN -> TvAlertOpenResolution.Activity(expired = true)
                }
                is PrivacyDecision.RequirePin -> return TvAlertOpenResolution.PrivacyBlocked
                is PrivacyDecision.Deny -> return decision.toTvAlertBlockedResolution()
            }
        }

        val consumed = notificationCoordinator.acknowledgeOpen(
            profileKey = profileKey,
            reviewId = reviewId,
            actionNonce = actionNonce,
            nowEpochMillis = System.currentTimeMillis().coerceAtLeast(0L),
        )
        return if (consumed) resolution else TvAlertOpenResolution.Unavailable
    }

    private fun PrivacyDecision.Deny.toTvAlertBlockedResolution(): TvAlertOpenResolution =
        when (reason) {
            PrivacyDenialReason.GUEST_CONTENT_BLOCKED,
            PrivacyDenialReason.GUEST_ACTION_BLOCKED,
            PrivacyDenialReason.PRIVATE_CONTENT_HIDDEN,
            -> TvAlertOpenResolution.PrivacyBlocked
            else -> TvAlertOpenResolution.Unavailable
        }

    internal suspend fun snoozeTvAlertFromNotification(
        profileKey: String,
        reviewId: String,
        actionNonce: String,
    ): Boolean {
        val record = notificationCoordinator.activeActionRecord(profileKey, reviewId, actionNonce)
            ?: return false
        val processState = realtimeRuntime.awareness.state.value
        val review = processState.ledger.review(reviewId)
            ?.takeIf { it.profileKey == profileKey }
            ?: return false
        val camera = review.camera?.takeIf(processState.authorizedCameraIds::contains) ?: return false
        val configuration = (alertConfigurationRepository.state.value as? AlertConfigurationState.Ready)
            ?.configuration
            ?.takeIf { it.profileKey == profileKey && it.enabled }
            ?: return false
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        val duration = 15L * 60L * 1_000L
        val expires = if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration
        val mutation = alertConfigurationRepository.addSnooze(
            AlertSnooze(
                scope = AlertSnoozeScope.CAMERA,
                cameraIds = setOf(camera),
                expiresAtEpochMillis = expires,
            ),
        )
        if (mutation is AlertConfigurationMutationResult.Rejected ||
            mutation is AlertConfigurationMutationResult.Unavailable
        ) return false
        return notificationCoordinator.cancelStored(
            record = record.copy(policyVersion = configuration.policyVersion),
            nowEpochMillis = now,
            reason = AlertDecisionReason.SNOOZED,
        ) is app.opah.tv.notifications.NotificationProcessingResult.Cancelled
    }

    internal suspend fun enableTvAlertsFromVisibleUserAction(): TvAlertActivationResult {
        val profileKey = (realtimeRuntime.owner.currentState().profileState as?
            app.opah.tv.data.realtime.RealtimeProfileState.Ready)?.profile?.id
            ?: return TvAlertActivationResult.CONFIGURATION_UNAVAILABLE
        val initialized = alertConfigurationRepository.initialize(profileKey)
        if (initialized !is AlertConfigurationState.Ready) {
            return TvAlertActivationResult.CONFIGURATION_UNAVAILABLE
        }
        return when (alertConfigurationRepository.setEnabled(true)) {
            is AlertConfigurationMutationResult.Updated,
            is AlertConfigurationMutationResult.Unchanged,
            -> {
                val start = TvAlertServiceController.startFromVisibleUserAction(appContext)
                if (start == TvAlertStartResult.STARTED) {
                    TvAlertActivationResult.STARTED
                } else {
                    alertConfigurationRepository.setEnabled(false)
                    start.toActivationResult()
                }
            }
            AlertConfigurationMutationResult.Rejected,
            AlertConfigurationMutationResult.Unavailable,
            -> {
                TvAlertActivationResult.CONFIGURATION_UNAVAILABLE
            }
        }
    }

    internal fun tvAlertDeliveryStatus(): TvAlertDeliveryStatus =
        TvAlertServiceController.deliveryStatus(appContext)

    internal fun tvAlertOverlayPermissionGranted(): Boolean =
        TvAlertOverlayPermission.isGranted(appContext)

    internal suspend fun restoreTvAlertsAfterProcessRecreation(): Boolean {
        if (!AndroidNotificationChannels.eventChannelsAvailable(appContext)) return false
        val profile = profileRepository.load() ?: return false
        if (!cookieJar.hasUnexpiredSession()) return false
        val profileKey = compatibilityIdentityFactory.derive(
            CompatibilityIdentityDomain.PROFILE,
            profile.compatibilityIdentityComponents(),
        ).value
        val configuration = alertConfigurationRepository.initialize(profileKey)
        return configuration is AlertConfigurationState.Ready && configuration.configuration.enabled
    }

    internal suspend fun disableTvAlerts(stopAndroidService: Boolean = true): Boolean {
        alertNotificationPlatform.dismissOverlay()
        val profileKey = (alertConfigurationRepository.state.value as? AlertConfigurationState.Ready)
            ?.configuration?.profileKey
        val mutation = alertConfigurationRepository.setEnabled(false)
        if (stopAndroidService) TvAlertServiceController.stop(appContext)
        stopAlertAwareness()
        val purged = profileKey?.let { purgeAlertProfile(it) } ?: true
        val signInNoticeCleared = notificationCoordinator.cancelSignInRequired()
        return mutation !is AlertConfigurationMutationResult.Unavailable && purged && signInNoticeCleared
    }

    internal fun startAlertAwareness() {
        val runtime = realtimeRuntime
        realtimeNetworkMonitorDelegate.value.start()
        runtime.owner.acquireLease(ALERT_AWARENESS_LEASE, RealtimeDemandOwner.AWARENESS_SERVICE)
        alertNotificationOwnerDelegate.value.start()
        alertModeOwnerDelegate.value.start()
        if (runtime.owner.currentState().profileState is app.opah.tv.data.realtime.RealtimeProfileState.Unavailable) {
            realtimeProcessScope.launch {
                val profile = profileRepository.load() ?: return@launch
                if (!cookieJar.hasUnexpiredSession()) return@launch
                runCatching { apiClient.getVersion(profile) }
                    .onSuccess { version -> runtime.activateAuthenticatedProfile(profile, version) }
            }
        }
    }

    internal fun stopAlertAwareness() {
        if (alertNotificationOwnerDelegate.isInitialized()) {
            alertNotificationOwnerDelegate.value.stop()
        }
        if (alertModeOwnerDelegate.isInitialized()) {
            alertModeOwnerDelegate.value.stop()
        }
        if (!realtimeRuntimeDelegate.isInitialized()) return
        realtimeRuntimeDelegate.value.owner.releaseLease(ALERT_AWARENESS_LEASE)
        if (realtimeRuntimeDelegate.value.owner.currentState().leases.isEmpty() &&
            realtimeNetworkMonitorDelegate.isInitialized()
        ) {
            realtimeNetworkMonitorDelegate.value.stop()
        }
    }

    internal fun dismissTvAlertOverlay() {
        alertNotificationPlatform.dismissOverlay()
    }

    internal val monitorAwarenessState
        get() = realtimeRuntime.awareness.state

    internal val monitorTransportState
        get() = realtimeRuntime.owner.state

    internal fun startMonitorAwareness() {
        val runtime = realtimeRuntime
        realtimeNetworkMonitorDelegate.value.start()
        runtime.owner.acquireLease(MONITOR_AWARENESS_LEASE, RealtimeDemandOwner.MONITOR_MODE)
        if (runtime.owner.currentState().profileState is
            app.opah.tv.data.realtime.RealtimeProfileState.Unavailable
        ) {
            realtimeProcessScope.launch {
                val profile = profileRepository.load() ?: return@launch
                if (!cookieJar.hasUnexpiredSession()) return@launch
                runCatching { apiClient.getVersion(profile) }
                    .onSuccess { version -> runtime.activateAuthenticatedProfile(profile, version) }
            }
        }
    }

    internal fun stopMonitorAwareness() {
        monitorPresentedReviewId.value = null
        if (!realtimeRuntimeDelegate.isInitialized()) return
        realtimeRuntimeDelegate.value.owner.releaseLease(MONITOR_AWARENESS_LEASE)
        if (realtimeRuntimeDelegate.value.owner.currentState().leases.isEmpty() &&
            realtimeNetworkMonitorDelegate.isInitialized()
        ) {
            realtimeNetworkMonitorDelegate.value.stop()
        }
    }

    internal fun updateMonitorPresentedReview(reviewId: String?) {
        monitorPresentedReviewId.value = reviewId?.takeIf {
            it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)
        }
    }

    private companion object {
        val ALERT_AWARENESS_LEASE = RealtimeLeaseId("tv-alert-service")
        val MONITOR_AWARENESS_LEASE = RealtimeLeaseId("monitor-mode")
        const val LOCAL_TEST_REPOST_DELAY_MILLIS = 250L
        const val LOCAL_TEST_REVIEW_ID = "local-fictional-test-alert"
        const val LOCAL_TEST_FINGERPRINT = "opah-local-fictional-test-alert-v1"
    }

    private suspend fun purgeAlertProfile(profileKey: String): Boolean {
        val records = runCatching { notificationLedgerStore.activeOrPending(profileKey) }
            .getOrElse { return false }
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        val cancelled = records.all { record ->
            notificationCoordinator.cancelStored(record, now) is
                app.opah.tv.notifications.NotificationProcessingResult.Cancelled
        }
        if (!cancelled) return false
        return runCatching { notificationLedgerStore.deleteProfile(profileKey) }.isSuccess
    }
}

private fun TvAlertStartResult.toActivationResult(): TvAlertActivationResult = when (this) {
    TvAlertStartResult.STARTED -> TvAlertActivationResult.STARTED
    TvAlertStartResult.NOTIFICATION_PERMISSION_REQUIRED ->
        TvAlertActivationResult.NOTIFICATION_PERMISSION_REQUIRED
    TvAlertStartResult.ON_SCREEN_PERMISSION_REQUIRED ->
        TvAlertActivationResult.ON_SCREEN_PERMISSION_REQUIRED
    TvAlertStartResult.NOTIFICATIONS_BLOCKED -> TvAlertActivationResult.NOTIFICATIONS_BLOCKED
    TvAlertStartResult.START_REJECTED -> TvAlertActivationResult.START_REJECTED
}
