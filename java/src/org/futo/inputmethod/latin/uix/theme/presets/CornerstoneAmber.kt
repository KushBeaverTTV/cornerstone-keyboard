package org.futo.inputmethod.latin.uix.theme.presets

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.extendedDarkColorScheme
import org.futo.inputmethod.latin.uix.theme.ThemeOption
import org.futo.inputmethod.latin.uix.theme.selector.ThemePreview

// KBP house style, amber variant: same black/white foundation as
// CornerstoneDark, amber #FFB000-family accents instead of yellow.
private val cornerstoneAmber = Color(0xFFFFB000)
private val cornerstoneBlack = Color(0xFF000000)
private val cornerstoneKey = Color(0xFF151515)
private val cornerstoneKeyAlt = Color(0xFF101010)
private val cornerstoneWhite = Color(0xFFFFFFFF)
private val cornerstoneMuted = Color(0xFFB8B8B8)
private val cornerstoneOutline = Color(0xFF3A3A3A)

private val amberScheme = extendedDarkColorScheme(
    primary = cornerstoneAmber,
    onPrimary = cornerstoneBlack,
    primaryContainer = Color(0xFF4A3600),
    onPrimaryContainer = Color(0xFFFFE1A8),
    secondary = cornerstoneAmber,
    onSecondary = cornerstoneBlack,
    secondaryContainer = Color(0xFF2A2000),
    onSecondaryContainer = Color(0xFFFFDC96),
    tertiary = cornerstoneAmber,
    onTertiary = cornerstoneBlack,
    tertiaryContainer = Color(0xFF3A2C00),
    onTertiaryContainer = Color(0xFFFFE1A8),
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF3A0905),
    errorContainer = Color(0xFF7A1F18),
    onErrorContainer = Color(0xFFFFD4CF),
    outline = cornerstoneOutline,
    outlineVariant = Color(0xFF242424),
    surface = cornerstoneBlack,
    onSurface = cornerstoneWhite,
    onSurfaceVariant = cornerstoneMuted,
    surfaceContainerHighest = Color(0xFF1C1C1C),
    shadow = cornerstoneBlack.copy(alpha = 0.8f),
    keyboardSurface = cornerstoneBlack,
    keyboardSurfaceDim = cornerstoneBlack,
    keyboardContainer = cornerstoneKey,
    keyboardContainerVariant = cornerstoneKeyAlt,
    onKeyboardContainer = cornerstoneWhite,
    keyboardPress = Color(0xFF3A2C00),
    keyboardFade0 = cornerstoneBlack,
    keyboardFade1 = cornerstoneBlack,
    primaryTransparent = cornerstoneAmber.copy(alpha = 0.28f),
    onSurfaceTransparent = cornerstoneWhite.copy(alpha = 0.10f),
    navigationBarColor = cornerstoneBlack,
    keyboardContainerPressed = Color(0xFF4A3600),
    onKeyboardContainerPressed = cornerstoneAmber,
    hintColor = cornerstoneMuted
)

val CornerstoneAmber = ThemeOption(
    dynamic = false,
    key = "CornerstoneAmber",
    name = R.string.theme_cornerstone_amber,
    available = { true }
) {
    amberScheme
}

@Composable
@Preview
private fun PreviewCornerstoneAmberTheme() {
    ThemePreview(CornerstoneAmber)
}
