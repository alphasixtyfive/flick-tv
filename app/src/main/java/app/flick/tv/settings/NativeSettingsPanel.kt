package app.flick.tv.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.tv.BuildConfig
import app.flick.tv.R
import app.flick.tv.home.HomeActions
import app.flick.tv.home.HomeState
import app.flick.tv.ui.components.TvPanel
import app.flick.tv.ui.components.TvChoiceDialog
import app.flick.tv.ui.components.TvSettingRow
import app.flick.tv.ui.icons.FlickIcons
import app.flick.tv.ui.theme.FlickColors
import app.flick.tv.ui.theme.TvLayout

private enum class SettingChoice { Tiles, Text, Clock, Sound, Temperature }

/** Only settings with several related controls receive a section. */
@Composable
fun NativeSettingsPanel(
    state: HomeState,
    actions: HomeActions,
    page: SettingsPage,
    onPageChange: (SettingsPage) -> Unit,
    onClose: () -> Unit,
    onWeather: () -> Unit,
    onWallpaper: () -> Unit,
    onProfiles: () -> Unit,
    onHomeAssistant: () -> Unit,
    onUpdates: () -> Unit,
    onTheme: () -> Unit,
    onBackup: () -> Unit,
) {
    val settings = state.settings
    var choice by remember { mutableStateOf<SettingChoice?>(null) }
    var returnRow by remember { mutableStateOf<SettingsPage?>(null) }
    val returnFocus = remember { FocusRequester() }
    fun rowFocus(child: SettingsPage): Modifier =
        if (child == returnRow) Modifier.focusRequester(returnFocus) else Modifier

    val back: () -> Unit = {
        val parent = page.parent()
        if (parent == null) {
            onClose()
        } else {
            returnRow = page
            onPageChange(parent)
        }
    }
    TvPanel(page.title, onBack = back, preferredFocus = returnFocus.takeIf { returnRow?.parent() == page }) {
        when (page) {
            SettingsPage.Root -> {
                TvSettingRow("Wallpapers", icon = Icons.Default.Wallpaper, onClick = onWallpaper)
                TvSettingRow("Home screen", icon = Icons.Default.Home, modifier = rowFocus(SettingsPage.Home),
                    onClick = { onPageChange(SettingsPage.Home) })
                TvSettingRow("Home Assistant", detail = state.haConnectionState.label,
                    icon = FlickIcons.HomeAssistant, onClick = onHomeAssistant)
                TvSettingRow("Clock & weather", icon = Icons.Default.Schedule, modifier = rowFocus(SettingsPage.Clock),
                    onClick = { onPageChange(SettingsPage.Clock) })
                TvSettingRow("Profiles", icon = Icons.Default.Person, onClick = onProfiles)
                TvSettingRow("Device", icon = Icons.Default.Tv, modifier = rowFocus(SettingsPage.Device),
                    onClick = { onPageChange(SettingsPage.Device) })
                TvSettingRow("About", icon = Icons.Default.Info, modifier = rowFocus(SettingsPage.About),
                    onClick = { onPageChange(SettingsPage.About) })
            }
            SettingsPage.Home -> {
                TvSettingRow("Theme", detail = app.flick.tv.themes.ThemeCatalog.resolve(settings.appearance).name,
                    icon = Icons.Default.Palette, onClick = onTheme)
                TvSettingRow("App tiles", detail = if (settings.compactTiles) "Compact" else "Comfortable", icon = Icons.Default.ViewModule,
                    onClick = { choice = SettingChoice.Tiles })
                TvSettingRow("Text size", detail = if (settings.largeText) "Larger" else "Standard", icon = Icons.Default.TextFields,
                    onClick = { choice = SettingChoice.Text })
                TvSettingRow("Continue watching", checked = settings.showContinue && state.continueAccess,
                    detail = if (state.continueAccess) "" else "Allow access to TV recommendations",
                    icon = Icons.Default.PlayArrow,
                    onClick = {
                        if (!state.continueAccess) actions.requestContinueAccess()
                        else actions.updateSettings(settings, settings.copy(showContinue = !settings.showContinue))
                    })
                if (settings.showContinue && state.continueAccess && settings.removedContinue.isNotEmpty()) TvSettingRow(
                    "Restore Continue watching", detail = "Show titles removed from this row", icon = Icons.Default.Restore,
                    onClick = { actions.updateSettings(settings, settings.copy(removedContinue = emptySet())) })
                TvSettingRow("Top buttons", icon = Icons.Default.Tune, modifier = rowFocus(SettingsPage.TopControls),
                    onClick = { onPageChange(SettingsPage.TopControls) })
                TvSettingRow("Navigation sounds", detail = settings.navigationSound.label(), icon = Icons.AutoMirrored.Filled.VolumeUp,
                    onClick = { choice = SettingChoice.Sound })
            }
            SettingsPage.Clock -> {
                TvSettingRow("Clock", detail = if (settings.clock24) "24 hour" else "12 hour", icon = Icons.Default.Schedule,
                    onClick = { choice = SettingChoice.Clock })
                TvSettingRow("Weather", checked = settings.showWeather, icon = Icons.Default.Cloud,
                    onClick = { actions.updateSettings(settings, settings.copy(showWeather = !settings.showWeather)) })
                TvSettingRow("Weather location", detail = settings.weatherLocation?.name ?: "Choose a city", icon = Icons.Default.LocationOn,
                    onClick = onWeather)
                settings.weatherLocation?.let { location ->
                    TvSettingRow("Temperature units", detail = if (location.fahrenheit) "Fahrenheit" else "Celsius", icon = Icons.Default.Thermostat,
                        onClick = { choice = SettingChoice.Temperature })
                }
            }
            SettingsPage.TopControls -> {
                TvSettingRow("Home Assistant", checked = settings.showHomeAssistant, icon = FlickIcons.HomeAssistant,
                    onClick = { actions.updateSettings(settings, settings.copy(showHomeAssistant = !settings.showHomeAssistant)) })
                TvSettingRow("Settings", checked = settings.showSettings, icon = Icons.Default.Settings,
                    onClick = { actions.updateSettings(settings, settings.copy(showSettings = !settings.showSettings)) })
                TvSettingRow("Wallpaper", checked = settings.showWallpaper, icon = Icons.Default.PhotoLibrary,
                    onClick = { actions.updateSettings(settings, settings.copy(showWallpaper = !settings.showWallpaper)) })
                Text("Hold the profile button in an adult profile to open Settings, even when its button is hidden. The remote’s Settings key also works.", style = androidx.tv.material3.MaterialTheme.typography.bodySmall)
            }
            SettingsPage.Device -> {
                TvSettingRow("Screensaver", detail = "Idle videos and photos", icon = Icons.Default.Landscape,
                    onClick = actions.openScreensaverSettings)
                TvSettingRow("Backup & restore", icon = Icons.Default.SettingsBackupRestore, onClick = onBackup)
                TvSettingRow("Android settings", icon = Icons.Default.Tv, onClick = actions.openAndroidSettings)
            }
            SettingsPage.About -> {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = TvLayout.RowHorizontalPadding, vertical = TvLayout.Gap),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TvLayout.Gap),
                ) {
                    Image(
                        painterResource(R.drawable.flick_icon),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(TvLayout.RowRadius)),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Flick TV", style = MaterialTheme.typography.titleLarge)
                        Text("Version ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall, color = FlickColors.Secondary)
                    }
                }
                TvSettingRow("Updates", icon = Icons.Default.SystemUpdate, onClick = onUpdates)
                TvSettingRow("Developed by", detail = "@alphasixtyfive", icon = Icons.Default.Person,
                    onClick = { actions.openProjectLink(ProjectLinks.DEVELOPER) })
                TvSettingRow("Source code", detail = "GitHub", icon = Icons.Default.Code,
                    onClick = { actions.openProjectLink(ProjectLinks.SOURCE) })
                TvSettingRow("Third-party licences", icon = Icons.Default.Description,
                    onClick = { actions.openProjectLink(ProjectLinks.LICENCES) })
            }

        }
    }
    val dismiss = { choice = null }
    when (choice) {
        SettingChoice.Tiles -> TvChoiceDialog("App tiles", listOf(true, false), settings.compactTiles,
            { if (it) "Compact" else "Comfortable" }, { actions.updateSettings(settings, settings.copy(compactTiles = it)) }, dismiss)
        SettingChoice.Text -> TvChoiceDialog("Text size", listOf(false, true), settings.largeText,
            { if (it) "Larger" else "Standard" }, { actions.updateSettings(settings, settings.copy(largeText = it)) }, dismiss)
        SettingChoice.Clock -> TvChoiceDialog("Clock", listOf(false, true), settings.clock24,
            { if (it) "24 hour" else "12 hour" }, { actions.updateSettings(settings, settings.copy(clock24 = it)) }, dismiss)
        SettingChoice.Sound -> TvChoiceDialog("Navigation sounds", NavigationSound.entries, settings.navigationSound,
            { it.label() }, { actions.updateSettings(settings, settings.copy(navigationSound = it)) }, dismiss)
        SettingChoice.Temperature -> settings.weatherLocation?.let { location ->
            TvChoiceDialog("Temperature units", listOf(false, true), location.fahrenheit,
                { if (it) "Fahrenheit" else "Celsius" },
                { actions.updateSettings(settings, settings.copy(weatherLocation = location.copy(fahrenheit = it))) }, dismiss)
        }
        null -> Unit
    }

}

private fun NavigationSound.label() = when (this) {
    NavigationSound.Soft -> "Soft"
    NavigationSound.System -> "Built-in"
    NavigationSound.Off -> "Off"
}
