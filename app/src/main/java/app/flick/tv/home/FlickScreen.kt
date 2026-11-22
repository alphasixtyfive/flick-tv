package app.flick.tv.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import app.flick.tv.ui.theme.FlickColors
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import app.flick.tv.settings.SettingsPage
import app.flick.tv.ui.theme.TvLayout
import app.flick.tv.ui.components.TvSettingRow
import app.flick.tv.apps.AppEntry
import app.flick.tv.apps.ContinueItem
import app.flick.tv.profiles.Profile
import app.flick.tv.profiles.ProfileAvatar
import app.flick.tv.profiles.ProfileSessionScreen
import app.flick.tv.settings.NativeSettingsPanel
import app.flick.tv.ui.components.TvAction
import app.flick.tv.ui.components.TvFocusSurface
import app.flick.tv.ui.components.TvMessage
import app.flick.tv.ui.components.RemotePress
import app.flick.tv.ui.components.navigationFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.flick.tv.ui.icons.FlickIcons
import app.flick.tv.ui.theme.homeMetadataStyle
import app.flick.tv.wallpaper.WallpaperBackground
import app.flick.tv.wallpaper.WallpaperPhoto

internal sealed interface HomePanel {
    data object Settings : HomePanel
    data object AllApps : HomePanel
    data object Profiles : HomePanel
    data object HomeAssistantControls : HomePanel
    data object Wallpaper : HomePanel
    data object Weather : HomePanel
    data object HomeAssistantSetup : HomePanel
    data object HomeText : HomePanel
    data class HomeEntities(val fromControls: Boolean = false) : HomePanel
    data object Integration : HomePanel
    data object Theme : HomePanel
    data object Backup : HomePanel
    data object Updates : HomePanel
    data object ManageProfiles : HomePanel
    data object ProfileStartup : HomePanel
    data object TvStandby : HomePanel
    data class EditProfile(val profile: Profile) : HomePanel
    data class Pin(val profile: Profile) : HomePanel
    data class AppOptions(val app: AppEntry, val fromAllApps: Boolean = false) : HomePanel
    data class ContinueOptions(val item: ContinueItem) : HomePanel
}

@Composable
fun FlickScreen(state: HomeState, actions: HomeActions) {
    if (state.recoveryRequired) {
        RecoveryScreen(actions.openRecoverySettings)
        return
    }
    if (state.profileSession.authorizedProfileId == null || state.profileSession.authorizedProfileId != state.settings.activeProfileId) {
        ProfileSessionScreen(state, actions)
        return
    }
    val profile = HomePolicy.profile(state.settings)
    val child = profile.child
    val showNextWallpaper = state.settings.showWallpaper && profile.wallpaperUri == null
    val apps = remember(state.settings, state.apps) {
        HomePolicy.visibleApps(state.settings, state.apps)
    }
    val byPackage = remember(apps) { apps.associateBy { it.packageName } }
    val favorites = remember(state.settings, state.apps) {
        HomePolicy.homeAppPackages(state.settings, state.apps)
    }
    var panel by remember { mutableStateOf<HomePanel?>(null) }
    var settingsPage by remember { mutableStateOf(SettingsPage.Root) }
    var retainedPanel by remember { mutableStateOf<HomePanel?>(null) }
    val panelVisibility = remember { MutableTransitionState(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val layoutDirection = LocalLayoutDirection.current
    val allAppsScroll = rememberLazyGridState()
    var focusedAllApp by remember { mutableStateOf<String?>(null) }
    val appsScroll = rememberLazyListState()
    val continueScroll = rememberLazyListState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    var returnFocus by remember { mutableStateOf("profile") }
    var hadPanel by remember { mutableStateOf(false) }
    var initialHomeFocusPending by remember { mutableStateOf(true) }
    var profilePlaced by remember { mutableStateOf(false) }
    // Home receives focus only after its panels and overlays have closed.
    val backgroundEnabled = panel == null && !panelVisibility.currentState && !panelVisibility.targetState
    val homeSemantics = if (backgroundEnabled) Modifier else Modifier.clearAndSetSemantics {}
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun homeFocus(id: String, startBoundary: Boolean = false, endBoundary: Boolean = false): Modifier = Modifier
        .focusProperties {
            canFocus = backgroundEnabled
            start = if (startBoundary) FocusRequester.Cancel else FocusRequester.Default
            end = if (endBoundary) FocusRequester.Cancel else FocusRequester.Default
        }
        .focusRequester(requesters.getOrPut(id) { FocusRequester() })
        .onFocusChanged {
            if (it.isFocused) {
                initialHomeFocusPending = false
                if (backgroundEnabled && !hadPanel) returnFocus = id
            }
        }
    var moving by remember(profile.id, state.profileSession.generation) { mutableStateOf<String?>(null) }
    var moveOrder by remember(profile.id, state.profileSession.generation) { mutableStateOf<List<String>>(emptyList()) }
    val movePress = remember(profile.id, state.profileSession.generation) { RemotePress() }
    val appOrder = if (moving != null) moveOrder else favorites
    val displayedApps = remember(appOrder, byPackage) { appOrder.mapNotNull(byPackage::get) }
    val lastTopAction = when {
        showNextWallpaper -> "wallpaper"
        !child && state.settings.showSettings -> "settings"
        !child && state.settings.showHomeAssistant && state.haConfiguration.configured -> "home-assistant"
        else -> "profile"
    }
    LaunchedEffect(moving, windowFocused, byPackage.keys) {
        movePress.cancel()
        if (moving != null && moving !in byPackage) moving = null
    }
    DisposableEffect(movePress) { onDispose { movePress.cancel() } }
    SideEffect {
        val valid = displayedApps.map { "app:" + it.packageName }.toSet() +
            state.continueItems.map { "show:" + it.id } + setOf("profile", "home-assistant", "settings", "wallpaper", "all-apps")
        requesters.keys.retainAll(valid)
    }
    fun openPanel(destination: HomePanel) {
        moving = null
        movePress.cancel()
        if (destination is HomePanel.Pin) actions.clearProfileError()
        hadPanel = true
        panel = destination
    }
    fun openSettings() {
        settingsPage = SettingsPage.Root
        openPanel(HomePanel.Settings)
        panelVisibility.targetState = true
    }
    fun closePanel() {
        panelVisibility.targetState = false
        panel = null
    }
    LaunchedEffect(initialHomeFocusPending, profilePlaced, windowFocused, panel, backgroundEnabled, hadPanel) {
        if (initialHomeFocusPending && profilePlaced && windowFocused && panel == null && backgroundEnabled && !hadPanel) {
            requesters["profile"]?.requestFocus()
        }
    }
    val fontScale = if (state.settings.largeText) 1.12f else 1f
    val messageStyle = homeMetadataStyle(fontScale)
    val homeText = if (panel == null && state.bridge.homeTextEnabled && state.bridge.paired &&
        state.bridge.connection == app.flick.tv.integration.BridgeConnection.Connected) {
        state.homeText.visibleFor(profile.id, child, state.profileSession.generation)?.text
    } else null

    LaunchedEffect(panel) {
        if (panel != null) {
            if (panel?.isOverlay() == false) retainedPanel = panel
            hadPanel = true
        }
        panelVisibility.targetState = panel != null && panel?.isOverlay() == false
    }
    LaunchedEffect(panel, windowFocused, panelVisibility.isIdle, panelVisibility.currentState, panelVisibility.targetState) {
        if (hadPanel && windowFocused && panelVisibility.isIdle && !panelVisibility.currentState && !panelVisibility.targetState && panel == null) {
            val appIndex = displayedApps.indexOfFirst { "app:" + it.packageName == returnFocus }
            val continueIndex = state.continueItems.indexOfFirst { "show:" + it.id == returnFocus }
            if (appIndex >= 0) appsScroll.scrollToItem(appIndex)
            if (continueIndex >= 0) continueScroll.scrollToItem(continueIndex)
            withFrameNanos { }
            val restored = runCatching {
                val requester = requesters[returnFocus] ?: return@runCatching false
                requester.requestFocus()
                true
            }.getOrDefault(false)
            if (!restored) runCatching { requesters["profile"]?.requestFocus() }
            hadPanel = false
            retainedPanel = null
        }
    }
    LaunchedEffect(moving, moveOrder) {
        val target = moving ?: return@LaunchedEffect
        val index = displayedApps.indexOfFirst { it.packageName == target }
        if (index >= 0) {
            appsScroll.scrollToItem(index)
            withFrameNanos { }
            runCatching { requesters["app:" + target]?.requestFocus() }
        }
    }
    LaunchedEffect(state.settings.activeProfileId, child) {
        closePanel()
        moving = null
    }
    LaunchedEffect(state.backup.preview) {
        if (state.backup.preview == app.flick.tv.backup.BackupPreview.Restore) openPanel(HomePanel.Backup)
    }
    LaunchedEffect(state.homeRequestVersion) {
        if (state.homeRequestVersion > 0) {
            if (panel is HomePanel.Pin) actions.cancelProfileSelection()
            closePanel()
            moving = null
        }
    }
    // Keep Back inside Flick while an animated panel is waiting for its own handler.
    BackHandler {
        if (panel != null) closePanel()
        moving = null
    }

    Box(Modifier.fillMaxSize().navigationFeedback()
        .onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_SETTINGS) {
                if (panel?.isOverlay() == true) return@onPreviewKeyEvent true
                if (event.type == KeyEventType.KeyDown && !child) openSettings()
                return@onPreviewKeyEvent true
            }
            val packageName = moving ?: return@onPreviewKeyEvent false
            if (panel != null || !backgroundEnabled) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionLeft, Key.DirectionRight)) {
                val visualDelta = if (event.key == Key.DirectionLeft) -1 else 1
                val orderDelta = if (layoutDirection == LayoutDirection.Rtl) -visualDelta else visualDelta
                moveOrder = HomePolicy.moveFavorite(moveOrder, byPackage.keys, packageName,
                    orderDelta)
                true
            } else if (event.key in listOf(Key.DirectionUp, Key.DirectionDown)) {
                true
            } else if (event.nativeKeyEvent.keyCode in listOf(
                    android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_BUTTON_A,
                    android.view.KeyEvent.KEYCODE_BUTTON_SELECT)) {
                val key = event.nativeKeyEvent
                when (event.type) {
                    KeyEventType.KeyDown -> movePress.down(key.keyCode, key.repeatCount)
                    KeyEventType.KeyUp -> if (movePress.up(key.keyCode, key.isCanceled) == RemotePress.Release.Click) {
                        actions.reorderApps(profile.id, moveOrder.filter(byPackage::containsKey))
                        moving = null
                    }
                }
                true
            } else false
        }) {
        key(profile.id) {
            WallpaperBackground(
                photo = state.displayedWallpaperUri?.let { WallpaperPhoto(it) },
                dimAmount = state.wallpaperSettings.dimAmount,
                modifier = Modifier.fillMaxSize(),
            )
        }
        HomeHeader(Modifier.fillMaxWidth().padding(horizontal = TvLayout.SafeHorizontal, vertical = TvLayout.SafeVertical)
            .then(homeSemantics),
            message = homeText, messageStyle = messageStyle,
            clock = {
                ClockWeather(state.settings.clock24, state.settings.showWeather,
                    state.settings.weatherLocation != null, state.weather, fontScale)
            }) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(TvLayout.Gap), verticalAlignment = Alignment.CenterVertically) {
                TvFocusSurface(
                    onClick = { openPanel(HomePanel.Profiles) }, shape = CircleShape,
                    enabled = backgroundEnabled,
                    onLongClick = if (child) null else ({ openSettings() }),
                    modifier = homeFocus("profile", startBoundary = true, endBoundary = lastTopAction == "profile")
                        .onPlaced { profilePlaced = true }.size(TvLayout.TopActionSize).semantics {
                        contentDescription = profile.name
                        stateDescription = "Active profile"
                    },
                ) {
                    ProfileAvatar(profile, TvLayout.TopActionSize)
                }
                if (!child && state.settings.showHomeAssistant && state.haConfiguration.configured) TvAction(
                    label = "Home Assistant", icon = FlickIcons.HomeAssistant,
                    enabled = backgroundEnabled,
                    modifier = homeFocus("home-assistant", endBoundary = lastTopAction == "home-assistant"),
                    onClick = { openPanel(HomePanel.HomeAssistantControls) },
                )
                if (!child && state.settings.showSettings) TvAction(
                    label = "Settings", icon = Icons.Default.Settings,
                    enabled = backgroundEnabled,
                    modifier = homeFocus("settings", endBoundary = lastTopAction == "settings"),
                    onClick = { openSettings() },
                )
                if (showNextWallpaper) TvAction(
                    label = "Next wallpaper", icon = Icons.Default.PhotoLibrary,
                    enabled = backgroundEnabled,
                    modifier = homeFocus("wallpaper", endBoundary = true), onClick = actions.rotateWallpaper,
                )
            }
        }

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = TvLayout.SafeHorizontal, end = TvLayout.SafeHorizontal, bottom = TvLayout.AppDockBottom)
            .then(homeSemantics),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (!child && state.settings.showContinue && state.continueItems.isNotEmpty()) Column(
                verticalArrangement = Arrangement.spacedBy(TvLayout.Gap),
            ) {
                Text("Continue watching", fontSize = (16 * fontScale).sp, color = Color.White.copy(alpha = .84f),
                    modifier = Modifier.padding(horizontal = TvLayout.HomeRowPadding))
                LazyRow(state = continueScroll, horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(TvLayout.HomeRowPadding)) {
                    itemsIndexed(state.continueItems, key = { _, item -> item.id }) { index, item ->
                        ContinueCard(item, enabled = backgroundEnabled, modifier = homeFocus("show:" + item.id,
                            startBoundary = index == 0, endBoundary = index == state.continueItems.lastIndex), onClick = { actions.openContinue(item) },
                            onLongClick = { openPanel(HomePanel.ContinueOptions(item)) })
                    }
                }
            }
            if (moving != null) Text("Use Left or Right to move. OK saves. Back cancels.", style = MaterialTheme.typography.bodySmall)
            LazyRow(state = appsScroll, horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(TvLayout.HomeRowPadding)) {
                itemsIndexed(displayedApps, key = { _, app -> app.packageName }) { index, app ->
                    AppCard(app, enabled = backgroundEnabled, modifier = homeFocus("app:" + app.packageName,
                        startBoundary = index == 0, endBoundary = child && index == displayedApps.lastIndex),
                        compact = state.settings.compactTiles, moving = moving == app.packageName,
                        onClick = {
                            if (moving != null) {
                                actions.reorderApps(profile.id, moveOrder)
                                moving = null
                            } else actions.launchApp(app)
                        },
                        onLongClick = { openPanel(HomePanel.AppOptions(app)) })
                }
                if (!child) item(key = "all-apps") {
                    TvFocusSurface(onClick = { openPanel(HomePanel.AllApps) }, enabled = backgroundEnabled,
                        modifier = homeFocus("all-apps", startBoundary = displayedApps.isEmpty(), endBoundary = true)
                            .width(TvLayout.appTileWidth(state.settings.compactTiles)).aspectRatio(16f / 9f)) {
                        Icon(Icons.Default.Apps, "All apps", Modifier.size(40.dp).align(Alignment.Center))
                    }
                }
            }
            if (state.loading && apps.isEmpty()) Text("Loading apps…", style = MaterialTheme.typography.bodyMedium)
            if (!state.loading && child && displayedApps.isEmpty()) Text("No allowed apps are installed.", style = MaterialTheme.typography.bodyMedium)
        }


        AnimatedVisibility(visibleState = panelVisibility, enter = slideInHorizontally(tween(200, easing = LinearOutSlowInEasing)) {
            if (layoutDirection == LayoutDirection.Rtl) -it else it
        }, exit = slideOutHorizontally(tween(160, easing = FastOutLinearInEasing)) {
            if (layoutDirection == LayoutDirection.Rtl) -it else it
        }, modifier = Modifier.align(Alignment.CenterEnd)
                .onPreviewKeyEvent { panel == null || panel?.isOverlay() == true }) {
            val displayed = panel?.takeUnless { it.isOverlay() } ?: retainedPanel
            if (displayed == HomePanel.Settings) NativeSettingsPanel(state, actions, page = settingsPage,
                onPageChange = { if (panel == displayed) settingsPage = it }, onClose = { if (panel == displayed) closePanel() },
                onWeather = { if (panel == displayed) openPanel(HomePanel.Weather) },
                onWallpaper = { if (panel == displayed) openPanel(HomePanel.Wallpaper) },
                onProfiles = { if (panel == displayed) openPanel(HomePanel.ManageProfiles) },
                onUpdates = { if (panel == displayed) openPanel(HomePanel.Updates) },
                onTheme = { if (panel == displayed) openPanel(HomePanel.Theme) },
                onBackup = { if (panel == displayed) openPanel(HomePanel.Backup) },
                onHomeAssistant = { if (panel == displayed) openPanel(HomePanel.HomeAssistantSetup) })
            else HomePanels(displayed, state, actions, onClose = { if (panel == displayed) closePanel() },
                onPanel = { if (panel == displayed) openPanel(it) })
        }
        panel?.takeIf { it.isOverlay() }?.let { overlay ->
            HomeOverlays(overlay, state, actions, allAppsScroll,
                isCurrent = { panel === overlay }, onClose = { if (panel === overlay) closePanel() },
                onAppOptionsBack = {
                    val menu = overlay as? HomePanel.AppOptions
                    if (menu != null) {
                        val destination = appOptionsBackDestination(panel, menu)
                        if (destination !== panel) {
                            if (destination == null) closePanel() else openPanel(destination)
                        }
                    }
                },
                onPanel = { destination ->
                    if (panel === overlay) openPanel(destination)
                },
                onMove = move@{ app ->
                    if (panel !== overlay) return@move
                    returnFocus = "app:" + app.packageName
                    moving = app.packageName
                    moveOrder = favorites.filter(byPackage::containsKey)
                    closePanel()
                },
                onRemoveContinue = remove@{
                    if (panel !== overlay) return@remove
                    val item = (overlay as? HomePanel.ContinueOptions)?.item
                    if (item != null) {
                        val index = state.continueItems.indexOfFirst { it.id == item.id }
                        val neighbor = state.continueItems.getOrNull(index + 1) ?: state.continueItems.getOrNull(index - 1)
                        returnFocus = neighbor?.let { "show:" + it.id } ?: displayedApps.firstOrNull()?.let { "app:" + it.packageName } ?: "all-apps"
                        actions.removeContinue(item)
                        closePanel()
                    }
                }, focusedApp = focusedAllApp, onFocusedApp = { focusedAllApp = it })
        }
        TvMessage(state.message, actions.dismissMessage)
    }
}

@Composable
private fun RecoveryScreen(openAndroidSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().background(FlickColors.Background), contentAlignment = Alignment.Center) {
        Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Setup could not be read", style = MaterialTheme.typography.headlineSmall)
            Text("Flick kept the damaged setup private and locked its controls. Open Android app settings to clear its storage, then set it up again. Android will ask you to confirm.", style = MaterialTheme.typography.bodyLarge)
            TvSettingRow("Open Android app settings", icon = Icons.Default.Settings, onClick = openAndroidSettings)
        }
    }
}
