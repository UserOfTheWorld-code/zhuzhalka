package com.v2ray.ang.ui.compose

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val BrandYellow = Color(0xFFFFD43B)
private val BrandSurface = Color(0xFF121212)
private val BrandSurfaceRaised = Color(0xFF1C1C1C)
private val BrandText = Color(0xFFF5F5F5)
private val BrandTextSecondary = Color(0xFFA8A8A8)

// One fixed palette keeps every screen consistent with the black-and-yellow brand.
private val BrandColors = darkColorScheme(
    primary = BrandYellow,
    onPrimary = Color.Black,
    primaryContainer = BrandSurfaceRaised,
    onPrimaryContainer = BrandYellow,
    secondary = BrandYellow,
    onSecondary = Color.Black,
    secondaryContainer = BrandSurfaceRaised,
    onSecondaryContainer = BrandText,
    tertiary = BrandYellow,
    onTertiary = Color.Black,
    tertiaryContainer = BrandSurfaceRaised,
    onTertiaryContainer = BrandText,
    error = BrandYellow,
    errorContainer = BrandSurfaceRaised,
    onError = Color.Black,
    onErrorContainer = BrandYellow,
    background = Color.Black,
    onBackground = BrandText,
    surface = Color.Black,
    onSurface = BrandText,
    surfaceVariant = BrandSurfaceRaised,
    onSurfaceVariant = BrandTextSecondary,
    outline = Color(0xFF707070),
    outlineVariant = Color(0xFF303030),
    inverseSurface = BrandSurfaceRaised,
    inverseOnSurface = BrandText,
    inversePrimary = BrandYellow,
    scrim = Color.Black,
    surfaceTint = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = BrandSurfaceRaised,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = BrandSurface,
    surfaceContainer = BrandSurface,
    surfaceContainerHigh = BrandSurfaceRaised,
    surfaceContainerHighest = BrandSurfaceRaised,
    primaryFixed = BrandYellow,
    primaryFixedDim = BrandYellow,
    onPrimaryFixed = Color.Black,
    onPrimaryFixedVariant = Color.Black,
    secondaryFixed = BrandSurfaceRaised,
    secondaryFixedDim = BrandSurfaceRaised,
    onSecondaryFixed = BrandText,
    onSecondaryFixedVariant = BrandTextSecondary,
    tertiaryFixed = BrandSurfaceRaised,
    tertiaryFixedDim = BrandSurfaceRaised,
    onTertiaryFixed = BrandText,
    onTertiaryFixedVariant = BrandTextSecondary,
)

// The result text distinguishes success from failure; color is only an accent.
val colorPing = BrandYellow
val colorPingRed = BrandYellow
val colorConfigType = BrandTextSecondary
val colorFabActive = BrandYellow
val colorFabInactiveLight = BrandTextSecondary
val colorFabInactiveDark = BrandTextSecondary
val dividerColorLight = Color(0xFF303030)
val dividerColorDark = Color(0xFF303030)

// Toast messages carry their meaning without additional semantic colors.
val toastNormalBgLight = BrandSurfaceRaised
val toastNormalBgDark = BrandSurfaceRaised
val toastSuccessBg = BrandSurfaceRaised
val toastErrorBg = BrandSurfaceRaised
val toastInfoBg = BrandSurfaceRaised
val toastIconCircleBg = Color(0x33F5F5F5)
val toastTextColor = BrandText

// Retain the existing preference API and stored values for backup compatibility.
// The branded AppTheme deliberately does not use these older appearance options.
object ThemeManager {
    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, true)
    )
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
        _dynamicColorEnabled.value =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, true)
    }
}

val LocalDarkTheme = compositionLocalOf { true }

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val snackbarController = rememberAppSnackbarController()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides true,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(
            colorScheme = BrandColors
        ) {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}
