package app.opah.tv.notifications.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.opah.tv.notifications.AndroidNotificationIdentity

internal object TvAlertOverlayPermission {
    fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)
}

/**
 * Shows one short, passive TV alert above the current app.
 *
 * The overlay never accepts touch or D-pad input. Opening and snoozing remain available through
 * the durable Android notification, while this surface provides the visible interruption that
 * some TV launchers omit.
 */
internal class TvAlertOverlayPresenter(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val settingsStore = TvAlertOverlaySettingsStore(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var active: ActiveOverlay? = null
    private var generation: Long = 0L

    fun show(
        profileKey: String,
        reviewId: String,
        identity: AndroidNotificationIdentity,
        title: String,
        body: String,
        thumbnail: Bitmap?,
    ): Boolean {
        if (!TvAlertOverlayPermission.isGranted(appContext)) return false
        mainHandler.post {
            if (!TvAlertOverlayPermission.isGranted(appContext)) return@post
            showOnMain(profileKey, reviewId, identity, title, body, thumbnail)
        }
        return true
    }

    fun dismiss(identity: AndroidNotificationIdentity) {
        mainHandler.post {
            active?.takeIf { it.identity == identity }?.let { removeOnMain(it) }
        }
    }

    fun dismiss(profileKey: String, reviewId: String) {
        mainHandler.post {
            active?.takeIf { it.profileKey == profileKey && it.reviewId == reviewId }
                ?.let { removeOnMain(it) }
        }
    }

    fun dismissProfile(profileKey: String) {
        mainHandler.post {
            active?.takeIf { it.profileKey == profileKey }?.let { removeOnMain(it) }
        }
    }

    fun dismissAll() {
        mainHandler.post { active?.let { removeOnMain(it) } }
    }

    private fun showOnMain(
        profileKey: String,
        reviewId: String,
        identity: AndroidNotificationIdentity,
        title: String,
        body: String,
        thumbnail: Bitmap?,
    ) {
        active?.let { removeOnMain(it) }
        generation += 1L
        val settings = settingsStore.load()
        if (settings.displayDurationSeconds == 0) return
        val card = buildCard(title, body, thumbnail, settings)
        val overlay = ActiveOverlay(
            profileKey = profileKey,
            reviewId = reviewId,
            identity = identity,
            view = card,
            generation = generation,
        )
        val added = runCatching {
            windowManager.addView(card, layoutParams(settings, thumbnail != null))
        }.isSuccess
        if (!added) return
        active = overlay
        card.alpha = 0f
        card.translationY = if (settings.verticalPosition == TvAlertOverlayVerticalPosition.TOP) {
            -dp(10).toFloat()
        } else {
            dp(10).toFloat()
        }
        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ENTER_ANIMATION_MILLIS)
            .start()
        mainHandler.postDelayed(
            { active?.takeIf { it.generation == overlay.generation }?.let { removeOnMain(it) } },
            tvAlertOverlayDisplayDurationMillis(settings.displayDurationSeconds),
        )
    }

    private fun removeOnMain(overlay: ActiveOverlay) {
        if (active === overlay) active = null
        overlay.view.animate().cancel()
        runCatching { windowManager.removeViewImmediate(overlay.view) }
    }

    private fun buildCard(
        title: String,
        body: String,
        thumbnail: Bitmap?,
        settings: TvAlertOverlaySettings,
    ): View {
        val card = FrameLayout(appContext).apply {
            background = roundedBackground(
                fill = CARD_COLOR,
                radius = dp(12).toFloat(),
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Alert, $title, $body"
            elevation = dp(8).toFloat()
        }
        val row = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(
            row,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        if (thumbnail != null) {
            val image = ImageView(appContext).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(thumbnail)
                background = roundedBackground(
                    fill = Color.TRANSPARENT,
                    radius = dp(8).toFloat(),
                )
                clipToOutline = true
            }
            val (imageWidthDp, imageHeightDp) = imageDimensions(settings.imageSize)
            row.addView(
                image,
                LinearLayout.LayoutParams(dp(imageWidthDp), dp(imageHeightDp)).apply {
                    marginEnd = dp(10)
                },
            )
        }

        val textColumn = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
        }
        row.addView(
            textColumn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        textColumn.addView(
            textView(
                text = title,
                sizeSp = 17f,
                color = Color.WHITE,
                style = Typeface.BOLD,
                maximumLines = 1,
            ),
        )
        textColumn.addView(
            textView(
                text = body,
                sizeSp = 14f,
                color = SECONDARY_TEXT_COLOR,
                style = Typeface.NORMAL,
                maximumLines = 2,
            ).apply {
                setPadding(0, dp(3), 0, 0)
            },
        )
        return card
    }

    private fun imageDimensions(size: TvAlertOverlayImageSize): Pair<Int, Int> = when (size) {
        TvAlertOverlayImageSize.SMALL -> 96 to 54
        TvAlertOverlayImageSize.MEDIUM -> 144 to 81
        TvAlertOverlayImageSize.LARGE -> 208 to 117
    }

    private fun textView(
        text: String,
        sizeSp: Float,
        color: Int,
        style: Int,
        maximumLines: Int,
    ) = TextView(appContext).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        setTypeface(typeface, style)
        maxLines = maximumLines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun layoutParams(settings: TvAlertOverlaySettings, hasThumbnail: Boolean): WindowManager.LayoutParams {
        val availableWidth = appContext.resources.displayMetrics.widthPixels - dp(64)
        val preferredWidth = when {
            !hasThumbnail -> TEXT_ONLY_CARD_WIDTH_DP
            settings.imageSize == TvAlertOverlayImageSize.SMALL -> SMALL_IMAGE_CARD_WIDTH_DP
            settings.imageSize == TvAlertOverlayImageSize.MEDIUM -> MEDIUM_IMAGE_CARD_WIDTH_DP
            else -> LARGE_IMAGE_CARD_WIDTH_DP
        }
        val width = dp(preferredWidth).coerceAtMost(availableWidth).coerceAtLeast(dp(280))
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = overlayGravity(settings)
            x = if (settings.horizontalPosition == TvAlertOverlayHorizontalPosition.CENTER) 0 else dp(20)
            y = dp(20)
            setTitle("Opah TV alert")
        }
    }

    private fun overlayGravity(settings: TvAlertOverlaySettings): Int {
        val vertical = when (settings.verticalPosition) {
            TvAlertOverlayVerticalPosition.TOP -> Gravity.TOP
            TvAlertOverlayVerticalPosition.BOTTOM -> Gravity.BOTTOM
        }
        val horizontal = when (settings.horizontalPosition) {
            TvAlertOverlayHorizontalPosition.LEFT -> Gravity.START
            TvAlertOverlayHorizontalPosition.CENTER -> Gravity.CENTER_HORIZONTAL
            TvAlertOverlayHorizontalPosition.RIGHT -> Gravity.END
        }
        return vertical or horizontal
    }

    private fun roundedBackground(
        fill: Int,
        stroke: Int? = null,
        strokeWidth: Int = 0,
        radius: Float,
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
        if (stroke != null && strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()

    private data class ActiveOverlay(
        val profileKey: String,
        val reviewId: String,
        val identity: AndroidNotificationIdentity,
        val view: View,
        val generation: Long,
    )

    private companion object {
        const val TEXT_ONLY_CARD_WIDTH_DP = 410
        const val SMALL_IMAGE_CARD_WIDTH_DP = 480
        const val MEDIUM_IMAGE_CARD_WIDTH_DP = 530
        const val LARGE_IMAGE_CARD_WIDTH_DP = 620
        const val ENTER_ANIMATION_MILLIS = 180L
        const val CARD_COLOR = 0xE81A1F28.toInt()
        const val SECONDARY_TEXT_COLOR = 0xFFD7DEE8.toInt()
    }
}
