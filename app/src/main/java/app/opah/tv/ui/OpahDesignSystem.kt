package app.opah.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

internal object OpahDesignTokens {
    val ScreenHorizontalMargin = 48.dp
    val ScreenVerticalMargin = 32.dp
    val OverscanSafeInset = 24.dp
    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 16.dp
    val SpaceLg = 24.dp
    val SpaceXl = 32.dp
    val MediaCornerRadius = 14.dp
    val DialogCornerRadius = 18.dp
    const val ScrimOpacity = 0.68f
    const val MediaOverlayOpacity = 0.62f
    const val PageAnimationMillis = 320
    const val PreviewCrossfadeMillis = 250
}

@Immutable
internal data class OpahUiPreferences(
    val reducedMotion: Boolean = false,
    val highContrast: Boolean = false,
)

internal val LocalOpahUiPreferences = staticCompositionLocalOf { OpahUiPreferences() }

@Immutable
internal data class OpahSemanticColors(
    val alert: Color,
    val detection: Color,
    val warning: Color,
    val offline: Color,
    val destructive: Color,
    val success: Color,
    val selection: Color,
    val focus: Color,
)

internal val LocalOpahSemanticColors = staticCompositionLocalOf {
    OpahSemanticColors(
        alert = Color(0xFFE85D45),
        detection = Color(0xFF4EA8DE),
        warning = Color(0xFFF2B84B),
        offline = Color(0xFF9AA4B2),
        destructive = Color(0xFFE5484D),
        success = Color(0xFF55C59A),
        selection = Color(0xFFFF7048),
        focus = Color.White,
    )
}

internal enum class FocusableSurfaceStyle {
    LEGACY_CARD,
    MEDIA,
    NAVIGATION,
    SETTINGS_ROW,
    SEGMENTED_TAB,
    PRIMARY_ACTION,
    SECONDARY_ACTION,
    PLAYBACK_ACTION,
}

@Composable
internal fun NavigationItem(
    focusKey: String,
    selected: Boolean,
    accessibilityLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    externalFocusRequester: FocusRequester? = null,
    onFocused: (String) -> Unit = {},
    content: @Composable () -> Unit,
) {
    FocusableSurface(
        focusKey = focusKey,
        restoreFocusKey = null,
        onFocusRestored = {},
        onClick = onClick,
        onLongClick = onLongClick,
        selected = selected,
        accessibilityLabel = accessibilityLabel,
        externalFocusRequester = externalFocusRequester,
        onFocused = onFocused,
        activateOnKeyUp = true,
        style = FocusableSurfaceStyle.NAVIGATION,
        modifier = modifier,
        content = content,
    )
}

@Composable
internal fun MediaTile(
    focusKey: String,
    accessibilityLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    restoreFocusKey: String? = null,
    onFocusRestored: () -> Unit = {},
    externalFocusRequester: FocusRequester? = null,
    onFocused: (String) -> Unit = {},
    onFocusStateChanged: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    FocusableSurface(
        focusKey = focusKey,
        restoreFocusKey = restoreFocusKey,
        onFocusRestored = onFocusRestored,
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        selected = selected,
        accessibilityLabel = accessibilityLabel,
        externalFocusRequester = externalFocusRequester,
        onFocused = onFocused,
        onFocusStateChanged = onFocusStateChanged,
        style = FocusableSurfaceStyle.MEDIA,
        modifier = modifier,
        content = content,
    )
}

@Composable
internal fun SettingsRow(
    focusKey: String,
    title: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    restoreFocusKey: String? = null,
    onFocusRestored: () -> Unit = {},
    externalFocusRequester: FocusRequester? = null,
    accessibilityLabel: String = listOfNotNull(title, value).joinToString(", "),
) {
    FocusableSurface(
        focusKey = focusKey,
        restoreFocusKey = restoreFocusKey,
        onFocusRestored = onFocusRestored,
        onClick = onClick,
        accessibilityLabel = accessibilityLabel,
        externalFocusRequester = externalFocusRequester,
        activateOnKeyUp = true,
        style = FocusableSurfaceStyle.SETTINGS_ROW,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            value?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ChoiceRow(
    focusKey: String,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    externalFocusRequester: FocusRequester? = null,
) {
    FocusableSurface(
        focusKey = focusKey,
        restoreFocusKey = null,
        onFocusRestored = {},
        onClick = onClick,
        selected = selected,
        accessibilityLabel = "$title, ${if (selected) "selected" else "not selected"}",
        externalFocusRequester = externalFocusRequester,
        style = FocusableSurfaceStyle.SETTINGS_ROW,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f))
            if (selected) Text("Selected", color = LocalOpahSemanticColors.current.selection)
        }
    }
}

@Composable
internal fun SegmentedTab(
    focusKey: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    restoreFocusKey: String? = null,
    onFocusRestored: () -> Unit = {},
    externalFocusRequester: FocusRequester? = null,
) {
    FocusableSurface(
        focusKey = focusKey,
        restoreFocusKey = restoreFocusKey,
        onFocusRestored = onFocusRestored,
        onClick = onClick,
        selected = selected,
        accessibilityLabel = label,
        externalFocusRequester = externalFocusRequester,
        activateOnKeyUp = SEGMENTED_TAB_ACTIVATES_ON_KEY_UP,
        style = FocusableSurfaceStyle.SEGMENTED_TAB,
        modifier = modifier,
    ) {
        Box(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

internal const val SEGMENTED_TAB_ACTIVATES_ON_KEY_UP = true

@Composable
internal fun PrimaryAction(
    focusKey: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    externalFocusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) = ActionSurface(
    focusKey,
    label,
    onClick,
    FocusableSurfaceStyle.PRIMARY_ACTION,
    modifier,
    enabled,
    externalFocusRequester,
    onFocused,
)

@Composable
internal fun SecondaryAction(
    focusKey: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    externalFocusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) = ActionSurface(
    focusKey,
    label,
    onClick,
    FocusableSurfaceStyle.SECONDARY_ACTION,
    modifier,
    enabled,
    externalFocusRequester,
    onFocused,
)

@Composable
internal fun PlaybackAction(
    focusKey: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    externalFocusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) = ActionSurface(
    focusKey,
    label,
    onClick,
    FocusableSurfaceStyle.PLAYBACK_ACTION,
    modifier,
    enabled,
    externalFocusRequester,
    onFocused,
)

@Composable
private fun ActionSurface(
    focusKey: String,
    label: String,
    onClick: () -> Unit,
    style: FocusableSurfaceStyle,
    modifier: Modifier,
    enabled: Boolean,
    externalFocusRequester: FocusRequester?,
    onFocused: () -> Unit,
) {
    FocusableSurface(
        focusKey = focusKey,
        restoreFocusKey = null,
        onFocusRestored = {},
        onClick = onClick,
        enabled = enabled,
        accessibilityLabel = label,
        externalFocusRequester = externalFocusRequester,
        onFocused = { onFocused() },
        style = style,
        modifier = modifier,
    ) {
        Box(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun StatusBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (color.luminance() >= 0.46f) Color(0xFF101418) else Color.White
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = contentColor, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun DialogSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(OpahDesignTokens.DialogCornerRadius),
            )
            .padding(OpahDesignTokens.SpaceLg),
    ) {
        content()
    }
}

@Composable
internal fun TimelineMarker(
    color: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(if (selected) 5.dp else 3.dp)
            .height(if (selected) 22.dp else 16.dp)
            .background(color, RoundedCornerShape(2.dp)),
    )
}

internal val MediaTileShape = RoundedCornerShape(OpahDesignTokens.MediaCornerRadius)
