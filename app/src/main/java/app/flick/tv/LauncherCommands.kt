package app.flick.tv

import app.flick.tv.home.HomePolicy
import app.flick.tv.homeassistant.HaCamera
import app.flick.tv.integration.BridgeCommand
import app.flick.tv.integration.BridgeCommandType
import app.flick.tv.integration.BridgeCommandException
import app.flick.tv.integration.BridgeProtocol
import app.flick.tv.notifications.NoticeAction
import app.flick.tv.notifications.TvNotice
import app.flick.tv.profiles.Profile
import app.flick.tv.profiles.ProfileSecurity
import app.flick.tv.profiles.withoutProfile
import app.flick.tv.themes.ThemeCatalog
import app.flick.tv.wallpaper.WallpaperPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** An explicit boundary: Home Assistant can request these operations, never intents or shell commands. */
internal class LauncherCommands(private val runtime: FlickRuntime) {
    fun status(): JSONObject {
        val settings = runtime.store.state.value
        val profile = HomePolicy.profile(settings)
        return JSONObject().put("version", BuildConfig.VERSION_NAME)
            .put("profile", publicProfile(profile, details = false)).put("profiles", publicProfiles(details = false))
            .put("capabilities", JSONArray(buildList {
                add("profiles"); add("wallpapers"); add("themes"); add("home_text"); add("launch_app")
                if (runtime.notifications.state.value.overlayAllowed) {
                    add("notifications")
                    add("camera_view")
                }
            }))
    }

    suspend fun execute(command: BridgeCommand): JSONObject {
        BridgeProtocol.validateData(command.type, command.data)
        if (command.type == BridgeCommandType.GetStatus) return status()
        val bridge = runtime.bridge.state.value
        check(bridge.enabled && bridge.paired) { "Pair this TV with Home Assistant first" }
        if (command.type == BridgeCommandType.Notify) {
            check(bridge.notificationsEnabled) { "Notifications are disabled on this TV" }
            runtime.notifications.refreshPermissions()
            if (!runtime.notifications.state.value.overlayAllowed) throw BridgeCommandException("permission_required")
            showNotice(command.data)
            return JSONObject()
        }
        check(bridge.allowManagement) { "Allow launcher management in Flick settings first" }
        return runtime.withAdultSession { when (command.type) {
            BridgeCommandType.LaunchApp -> {
                check(runtime.isLauncherVisible() && runtime.isScreenOn()) { "Open Flick TV Home first" }
                runtime.catalogue.launch(command.data.getString("package_name"), tvOnly = true)
                JSONObject()
            }
            BridgeCommandType.OpenCamera -> {
                if (!runtime.isScreenOn() || !runtime.notifications.showCamera(
                    command.data.optString("title", "Camera"), command.data.getString("camera_entity_id"),
                )) throw BridgeCommandException("permission_required")
                JSONObject()
            }
            BridgeCommandType.ListApps -> JSONObject().put("apps", JSONArray(runtime.catalogue.apps().map {
                JSONObject().put("package_name", it.packageName).put("name", it.name)
            }))
            BridgeCommandType.ListProfiles -> JSONObject().put("profiles", publicProfiles())
            BridgeCommandType.ListThemes -> publicThemes()
            BridgeCommandType.SetTheme -> {
                val id = command.data.getString("id")
                runtime.store.update { current -> current.copy(appearance = ThemeCatalog.select(current.appearance, id)) }
                publicThemes()
            }
            BridgeCommandType.SetProfile, BridgeCommandType.DeleteProfile, BridgeCommandType.SelectProfile -> editProfile(command)
            BridgeCommandType.NextWallpaper -> { runtime.wallpapers.next(); JSONObject() }
            BridgeCommandType.SetWallpaperOptions -> {
                val data = command.data
                runtime.wallpapers.updateOnlineSelection { current -> current.copy(
                    intervalSeconds = if (data.has("interval_seconds")) data.getInt("interval_seconds") else current.intervalSeconds,
                    shuffle = if (data.has("shuffle")) data.getBoolean("shuffle") else current.shuffle,
                    dimAmount = if (data.has("dim_amount")) data.getDouble("dim_amount").toFloat() else current.dimAmount,
                ) }
                JSONObject()
            }
            BridgeCommandType.SetWallpapers -> {
                val data = command.data
                val urls = data.getJSONArray("photos").let { array -> (0 until array.length()).map { WallpaperPhoto(array.getString(it)) } }
                runtime.wallpapers.updateOnlineSelection { current -> current.copy(photos =
                    ((if (data.optBoolean("replace", true)) emptyList() else current.photos) + urls).distinctBy { it.uri }) }
                JSONObject()
            }
            else -> error("Unsupported launcher operation")
        } }
    }

    private suspend fun editProfile(command: BridgeCommand): JSONObject {
        // Retire adult-screen operations before a remotely selected profile changes their authority.
        check(!runtime.switchingProfile) { "A profile change is already in progress" }
        runtime.switchingProfile = true
        val generation = runtime.profileSession.state.value.generation
        try {
            val owner = currentCoroutineContext().job
            runtime.privilegedJobs.toList().filterNot { it === owner }.forEach { it.cancelAndJoin() }
            runtime.profileLock.withLock {
                val settings = runtime.store.state.value
                val data = command.data
                val id = data.getString("id")
                val existing = settings.profiles.firstOrNull { it.id == id }
                val updated = when (command.type) {
                    BridgeCommandType.SelectProfile -> {
                        requireNotNull(existing) { "This profile no longer exists" }
                        settings.copy(activeProfileId = id)
                    }
                    BridgeCommandType.DeleteProfile -> {
                        requireNotNull(existing) { "This profile no longer exists" }
                        settings.withoutProfile(id)
                    }
                    else -> {
                        var profile = (existing ?: Profile(id, data.optString("name"))).copy(
                            name = if (data.has("name")) data.getString("name").trim() else existing?.name.orEmpty(),
                            child = if (data.has("child")) data.getBoolean("child") else existing?.child ?: false,
                            allowedApps = if (data.has("allowed_apps")) data.getJSONArray("allowed_apps").let { array ->
                                (0 until array.length()).map(array::getString).distinct()
                            } else existing?.allowedApps.orEmpty(),
                            avatar = if (data.has("avatar")) data.optString("avatar").takeUnless { data.isNull("avatar") } else existing?.avatar,
                        )
                        if (profile.child) profile = profile.copy(pinHash = null, pinSalt = null)
                        else if (data.has("pin")) profile = withContext(Dispatchers.Default) { ProfileSecurity.protect(profile, data.getString("pin")) }
                        settings.copy(profiles = if (existing == null) settings.profiles + profile else settings.profiles.map { if (it.id == id) profile else it })
                    }
                }
                runtime.store.save(updated)
                val authorized = if (command.type == BridgeCommandType.SelectProfile) {
                    val selected = updated.profiles.first { it.id == updated.activeProfileId }
                    runtime.profileSession.authorize(runtime.store.state.value, selected, generation)
                } else {
                    runtime.profileSession.renewAfterEdit(runtime.store.state.value, generation)
                }
                check(authorized) { "Choose an adult profile again" }
            }
        } finally { runtime.switchingProfile = false; runtime.reconcileProfileSession() }
        return status()
    }

    private fun showNotice(data: JSONObject) {
        val raw = data.optString("image").takeIf { it.isNotBlank() }
        val origin = runtime.homeAssistant.configuration.value.url
        val snapshot = HaCamera.snapshot(origin, raw)
        val actions = if (runtime.canManage()) data.optJSONArray("actions")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val action = array.getJSONObject(index)
                val id = action.getString("id")
                val camera = if (id == HaCamera.ViewAction) snapshot?.entityId ?: return@mapNotNull null else null
                NoticeAction(id, action.getString("label"), camera = camera)
            }
        }.orEmpty() else emptyList()
        val persistent = data.optBoolean("persistent", false)
        val seconds = data.optInt("timeout", 8)
        val uri = snapshot?.url ?: raw?.let { image ->
            runCatching {
                val base = URI(origin)
                val candidate = URI(image)
                val resolved = if (candidate.isAbsolute) candidate else base.resolve(candidate)
                resolved.toString().takeIf { resolved.scheme in setOf("http", "https") && resolved.host != null && resolved.rawUserInfo == null }
            }.getOrNull()
        }
        val notice = TvNotice(id = data.getString("id"), title = data.optString("title"), message = data.optString("message"),
            actions = actions, persistent = persistent, timeoutMillis = if (seconds == 0) 8_000L else seconds * 1000L,
            interactive = persistent || actions.isNotEmpty())
        runtime.notifications.post(notice, imageUrl = uri)
    }

    private fun publicThemes(): JSONObject {
        val appearance = runtime.store.state.value.appearance
        return JSONObject().put("selected", ThemeCatalog.resolve(appearance).id)
            .put("themes", JSONArray(ThemeCatalog.themes(appearance).map { theme ->
                JSONObject().put("id", theme.id).put("name", theme.name)
            }))
    }

    private fun publicProfiles(details: Boolean = true) = JSONArray(runtime.store.state.value.profiles.map { publicProfile(it, details) })
    private fun publicProfile(profile: Profile, details: Boolean = true) = JSONObject().put("id", profile.id).put("name", profile.name)
        .put("child", profile.child)
        // Document grants are local to Android and cannot be read by Home Assistant.
        .apply { if (details) {
            put("allowed_apps", JSONArray(profile.allowedApps))
            profile.avatar?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { put("avatar", it) }
        } }
}
