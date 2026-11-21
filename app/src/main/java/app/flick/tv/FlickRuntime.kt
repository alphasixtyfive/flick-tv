package app.flick.tv

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import app.flick.tv.apps.AndroidCatalogue
import app.flick.tv.homeassistant.HaDisplayPower
import app.flick.tv.homeassistant.HomeAssistantRepository
import app.flick.tv.integration.BridgeCommand
import app.flick.tv.integration.FlickBridgeClient
import app.flick.tv.hometext.HomeTextProfile
import app.flick.tv.notifications.NotificationController
import app.flick.tv.platform.NavigationSounds
import app.flick.tv.profiles.ProfileSecurity
import app.flick.tv.profiles.ProfileSession
import app.flick.tv.settings.SettingsStore
import app.flick.tv.wallpaper.WallpaperRepository
import app.flick.tv.updates.UpdateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/** Process-owned features shared by the home screen and its optional background connection. */
class FlickRuntime(context: Context) {
    internal val backupTransactions = BackupTransactions(context.applicationContext)
    private val restoreRecovered = backupTransactions.recoverBeforeStartup()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = SettingsStore(context, startupRecoveryFailed = !restoreRecovered)
    internal val backups by lazy { LauncherBackups(context.applicationContext, this) }
    internal val profileSession = ProfileSession(store.state.value)
    val navigationSounds = NavigationSounds(context.applicationContext)
    val profiles = ProfileSecurity(context)
    val catalogue = AndroidCatalogue(context)
    val homeAssistant = HomeAssistantRepository(context, scope)
    val wallpapers = WallpaperRepository(context, scope)
    val profileLock = Mutex()
    val privilegedJobs = ConcurrentHashMap.newKeySet<Job>()
    internal var profileSelection: Job? = null
    @Volatile var switchingProfile = false
    private val visibleState = MutableStateFlow(false)
    private var visible: Boolean
        get() = visibleState.value
        set(value) { visibleState.value = value }
    val updates by lazy { UpdateController(context, scope, canInstall = { visible && canManage() && !switchingProfile }) }
    private val power = context.getSystemService(PowerManager::class.java)
    private val commands = LauncherCommands(this)
    val notifications = NotificationController(context, scope,
        onNavigationFeedback = { navigationSounds.play(store.state.value.navigationSound) },
        cameraStream = { camera -> withAdultSession { homeAssistant.cameraStream(camera) } },
        canOpenCamera = { !switchingProfile && canManage() && power.isInteractive && homeAssistant.displayPower.value != HaDisplayPower.Off },
    ) { action ->
        if (canManage()) scope.launch {
            runCatching { withAdultSession { bridge.notificationAction(action.noticeId, action.actionId) } }
        }
    }
    val bridge = FlickBridgeClient(context, scope, onCommand = { command: BridgeCommand ->
        withContext(Dispatchers.Main.immediate) { commands.execute(command) }
    }, status = { commands.status() })

    init {
        val screenOff = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    notifications.closeCamera()
                    revokeProfileSession()
                }
            }
        }
        // This receiver has the same lifetime as the process-owned session, including while an app plays.
        if (Build.VERSION.SDK_INT >= 33) context.applicationContext.registerReceiver(screenOff,
            IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            context.applicationContext.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
        scope.launch { store.state.collect { reconcileProfileSession() } }
        scope.launch {
            combine(store.state, profileSession.state, visibleState, store.requiresRecovery) { settings, session, visible, recovery ->
                if (!visible || recovery) null else profileSession.profile(settings)?.let {
                    HomeTextProfile(it.id, it.child, session.generation)
                }
            }.distinctUntilChanged().collect(bridge::setHomeTextProfile)
        }
        scope.launch {
            homeAssistant.displayPowerInterruptions.drop(1).collect {
                notifications.closeCamera()
                revokeProfileSession()
            }
        }
        scope.launch {
            homeAssistant.credentialRevision.drop(1).collect {
                notifications.dismissAll()
                runCatching { bridge.reconnect() }
            }
        }
        scope.launch {
            bridge.state.filter { it.deviceId.isNotEmpty() }.map { it.enabled && it.notificationsEnabled }.distinctUntilChanged().collect { enabled ->
                if (!enabled) notifications.dismissNotifications()
            }
        }
        scope.launch {
            bridge.state.filter { it.deviceId.isNotEmpty() }.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (enabled && visible) notifications.enableBackground(true)
                else if (!enabled) {
                    notifications.enableBackground(false)
                }
            }
        }
        scope.launch {
            combine(store.state, profileSession.state) { settings, _ ->
                profileSession.profile(settings)?.let { it.id to it.child }
            }.distinctUntilChanged().drop(1).collect {
                notifications.dismissAll()
            }
        }
        scope.launch {
            combine(store.state, profileSession.state, notifications.state, bridge.state.map { it.connection }.distinctUntilChanged()) { _, _, _, _ -> Unit }
                .collectLatest {
                    delay(100)
                    runCatching { bridge.publishStatus() }
                }
        }
    }

    fun onBackgroundServiceStarted() { scope.launch { runCatching { bridge.reconnect() } } }
    fun onBackgroundServiceStopped() { notifications.dismissNotifications() }

    /** Call from a visible activity: Android restricts starting services from the background. */
    fun resume() {
        if (!power.isInteractive) revokeProfileSession()
        visible = true
        notifications.refreshPermissions()
        if (bridge.state.value.enabled) notifications.enableBackground(true)
    }
    fun pause() { visible = false }

    internal fun isScreenOn() = power.isInteractive
    internal fun isLauncherVisible() = visible
    internal fun canManage() = !store.requiresRecovery.value && profileSession.canManage(store.state.value)
    internal fun canLaunch(packageName: String) = !switchingProfile && !store.requiresRecovery.value &&
        profileSession.canLaunch(store.state.value, packageName)

    internal fun revokeProfileSession() {
        if (profileSession.revokeOnWake(store.state.value)) cancelSessionWork()
    }

    internal fun reconcileProfileSession() {
        if (!switchingProfile && profileSession.reconcile(store.state.value)) cancelSessionWork()
    }

    private fun cancelSessionWork() {
        profileSelection?.cancel()
        privilegedJobs.toList().forEach { it.cancel() }
        notifications.dismissAll()
        updates.cancel()
    }

    /** A cancelled management operation must not cancel its caller's command/event loop. */
    internal suspend fun <T> withAdultSession(block: suspend () -> T): T {
        check(canManage() && !switchingProfile) { "Choose an adult profile first" }
        return try {
            coroutineScope {
                val owner = currentCoroutineContext().job
                privilegedJobs.add(owner)
                try {
                    check(canManage() && !switchingProfile) { "Choose an adult profile first" }
                    block()
                } finally { privilegedJobs.remove(owner) }
            }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            error("The profile was locked. Choose an adult profile again.")
        }
    }
}
