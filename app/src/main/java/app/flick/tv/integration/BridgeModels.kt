package app.flick.tv.integration

import org.json.JSONObject

enum class BridgeConnection(val label: String) {
    Disabled("Off"), HomeAssistantRequired("Connect Home Assistant first"), NotPaired("Not paired"),
    Connecting("Connecting…"), Pairing("Waiting for approval"), Connected("Connected"),
    Unavailable("Integration unavailable"), Rejected("Check Home Assistant sign-in"),
}

data class BridgeState(
    val deviceId: String = "",
    val name: String = "Flick",
    val enabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val allowManagement: Boolean = false,
    val homeTextEnabled: Boolean = false,
    val paired: Boolean = false,
    val pairingCode: String? = null,
    val pairingExpiresAtMillis: Long? = null,
    val connection: BridgeConnection = BridgeConnection.Disabled,
) {
    override fun toString() = "BridgeState(enabled=$enabled, paired=$paired, connection=$connection, privateData=redacted)"
}

enum class BridgeCommandType(val wireName: String) {
    GetStatus("get_status"), ListApps("list_apps"), ListProfiles("list_profiles"), ListThemes("list_themes"),
    SetTheme("set_theme"),
    SetProfile("set_profile"), DeleteProfile("delete_profile"), SelectProfile("select_profile"),
    NextWallpaper("next_wallpaper"), SetWallpaperOptions("set_wallpaper_options"),
    SetWallpapers("set_wallpapers"), Notify("notify"), OpenCamera("open_camera"), LaunchApp("launch_app"),
}

class BridgeCommand(val requestId: String, val type: BridgeCommandType, val data: JSONObject,
    val receivedAtNanos: Long = System.nanoTime()) {
    /** Leave transport time inside Home Assistant's ten-second request budget. */
    internal fun remainingMillis(nowNanos: Long = System.nanoTime()): Long {
        val elapsed = (nowNanos - receivedAtNanos).coerceAtLeast(0) / 1_000_000
        return (9_000 - elapsed).coerceIn(0, 9_000)
    }
    override fun toString() = "BridgeCommand(type=$type, privateData=redacted)"
}

internal sealed interface BridgeEvent {
    data object Paired : BridgeEvent
    class Command(val value: BridgeCommand) : BridgeEvent
}

private const val REQUEST_HISTORY_LIMIT = 256

/** Missing history is a new pairing; damaged history must not authorize replaying old requests. */
internal fun readBridgeRequestHistory(raw: String?): List<String> {
    if (raw == null) return emptyList()
    require(raw.length <= 24 * 1024) { "Stored request history is too large" }
    val record = BridgeProtocol.frame("{\"requests\":$raw}")
    require(record.length() == 1) { "Invalid stored request history" }
    val array = record.getJSONArray("requests")
    require(array.length() <= REQUEST_HISTORY_LIMIT) { "Stored request history is too large" }
    val values = (0 until array.length()).map { index ->
        val id = array.opt(index) as? String ?: error("Invalid stored request ID")
        require(BridgeProtocol.validId(id)) { "Invalid stored request ID" }
        id
    }
    require(values.distinct().size == values.size) { "Duplicate stored request IDs" }
    return values
}

/** Claimed requests stay claimed even if a connection closes before their result arrives. */
internal class BridgeRequestHistory(values: List<String> = emptyList(), private val limit: Int = REQUEST_HISTORY_LIMIT) {
    private val ids = LinkedHashSet(values.takeLast(limit))
    fun claim(id: String): Boolean {
        if (!ids.add(id)) return false
        while (ids.size > limit) ids.remove(ids.first())
        return true
    }
    fun snapshot() = ids.toList()
}

internal class BridgeIdentity(val origin: String, val secret: String, val paired: Boolean) {
    override fun toString() = "BridgeIdentity(redacted)"
}

/** Only these public failure codes may cross the companion boundary. */
class BridgeCommandException(val code: String) : Exception(code) {
    init { require(code in setOf("permission_required", "not_supported", "invalid_request")) }
}
