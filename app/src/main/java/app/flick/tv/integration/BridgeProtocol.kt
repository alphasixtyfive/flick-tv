package app.flick.tv.integration

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import app.flick.tv.homeassistant.HaCamera
import app.flick.tv.json.JsonStructure

/** Limits apply before a JSON library can recurse or allocate large collections. */
object BridgeProtocol {
    const val VERSION = 1
    const val MAX_FRAME_BYTES = 512 * 1024
    private val identifier = Regex("[A-Za-z0-9_.:-]{1,64}")
    private val profileId = Regex("[A-Za-z0-9_-]{1,64}")
    private val packageName = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

    fun frame(text: String): JSONObject {
        require(text.length <= MAX_FRAME_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES) { "Integration message is too large" }
        JsonStructure(text).check()
        return JSONObject(text)
    }

    internal fun event(value: JSONObject, receivedAtNanos: Long = System.nanoTime()): BridgeEvent {
        keys(value, setOf("type", "protocol", "request_id", "command", "data"))
        if (value.has("protocol")) require(number(value, "protocol") == VERSION.toDouble()) { "Unsupported integration protocol" }
        return when (string(value, "type", 16)) {
            "paired" -> { keys(value, setOf("type", "protocol")); BridgeEvent.Paired }
            "command" -> {
                val request = string(value, "request_id", 64).also { require(identifier.matches(it)) { "Invalid request ID" } }
                val type = BridgeCommandType.entries.firstOrNull { it.wireName == value.opt("command") }
                    ?: error("Unsupported integration command")
                val data = value.opt("data") as? JSONObject ?: error("Command data must be an object")
                validateData(type, data)
                BridgeEvent.Command(BridgeCommand(request, type, data, receivedAtNanos))
            }
            else -> error("Unsupported integration event")
        }
    }

    fun validateData(type: BridgeCommandType, data: JSONObject) {
        when (type) {
            BridgeCommandType.GetStatus, BridgeCommandType.ListApps, BridgeCommandType.ListProfiles, BridgeCommandType.ListThemes,
            BridgeCommandType.NextWallpaper -> keys(data, emptySet())
            BridgeCommandType.DeleteProfile, BridgeCommandType.SelectProfile, BridgeCommandType.SetTheme -> {
                keys(data, setOf("id")); require(profileId.matches(string(data, "id", 64))) { "Invalid profile ID" }
            }
            BridgeCommandType.SetProfile -> {
                keys(data, setOf("id", "name", "child", "allowed_apps", "avatar", "pin"))
                require(profileId.matches(string(data, "id", 64))) { "Invalid profile ID" }
                if (data.has("name")) string(data, "name", 48)
                if (data.has("child")) boolean(data, "child")
                if (data.has("allowed_apps")) strings(data, "allowed_apps", 100).forEach { require(it.length <= 256 && packageName.matches(it)) { "Invalid app package" } }
                if (data.has("avatar") && !data.isNull("avatar")) httpUrl(string(data, "avatar", 4096))
                if (data.has("pin")) require(Regex("[0-9]{4}").matches(string(data, "pin", 4))) { "Use exactly four digits for a PIN" }
            }
            BridgeCommandType.SetWallpaperOptions -> {
                keys(data, setOf("interval_seconds", "shuffle", "dim_amount"))
                require(data.length() > 0) { "Choose a wallpaper option" }
                if (data.has("interval_seconds")) integer(data, "interval_seconds", 30..86_400)
                if (data.has("shuffle")) boolean(data, "shuffle")
                if (data.has("dim_amount")) require(number(data, "dim_amount") in 0.0..0.7) { "Invalid wallpaper dimming" }
            }
            BridgeCommandType.SetWallpapers -> {
                keys(data, setOf("photos", "replace"))
                strings(data, "photos", 500).forEach(::httpUrl)
                if (data.has("replace")) boolean(data, "replace")
            }
            BridgeCommandType.OpenCamera -> {
                keys(data, setOf("camera_entity_id", "title"))
                require(HaCamera.isEntity(string(data, "camera_entity_id", 135))) { "Choose a camera entity" }
                if (data.has("title")) string(data, "title", 100)
            }
            BridgeCommandType.LaunchApp -> {
                keys(data, setOf("package_name"))
                require(packageName.matches(string(data, "package_name", 256))) { "Invalid app package" }
            }
            BridgeCommandType.Notify -> {
                keys(data, setOf("id", "title", "message", "image", "actions", "persistent", "timeout"))
                require(identifier.matches(string(data, "id", 64))) { "Invalid notification ID" }
                val title = if (data.has("title")) string(data, "title", 100, allowEmpty = true) else ""
                val message = string(data, "message", 2048, allowEmpty = true)
                require(title.isNotBlank() || message.isNotBlank()) { "Enter notification text" }
                if (data.has("persistent")) boolean(data, "persistent")
                if (data.has("timeout")) integer(data, "timeout", 0..120)
                if (data.has("image") && !data.isNull("image")) string(data, "image", 4096)
                if (data.has("actions")) {
                    val actions = data.opt("actions") as? JSONArray ?: error("Actions must be a list")
                    require(actions.length() <= 2) { "Choose at most two notification actions" }
                    val ids = mutableSetOf<String>()
                    for (index in 0 until actions.length()) {
                        val action = actions.opt(index) as? JSONObject ?: error("Invalid notification action")
                        keys(action, setOf("id", "label"))
                        val id = string(action, "id", 64)
                        require(identifier.matches(id) && ids.add(id)) { "Invalid notification action ID" }
                        string(action, "label", 32)
                    }
                }
            }
        }
    }

    internal fun validateNegotiation(result: JSONObject) {
        require(number(result, "protocol") == VERSION.toDouble()) { "Unsupported integration protocol" }
        require(result.opt("status") in setOf("pending", "connected")) { "Invalid integration response" }
    }

    internal fun encoded(value: JSONObject): String = value.toString().also {
        require(it.length <= MAX_FRAME_BYTES && it.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES) { "Integration response is too large" }
    }

    internal fun validId(value: String) = identifier.matches(value)
    private fun keys(value: JSONObject, allowed: Set<String>) {
        require(value.keys().asSequence().all { it in allowed }) { "Unsupported integration field" }
    }
    private fun string(value: JSONObject, key: String, maximum: Int, allowEmpty: Boolean = false): String {
        val text = value.opt(key) as? String ?: error("Invalid integration text")
        require(text.length <= maximum && (allowEmpty || text.isNotBlank()) && text.none { it.isISOControl() && it !in "\n\t" }) { "Invalid integration text" }
        return text
    }
    private fun boolean(value: JSONObject, key: String) = (value.opt(key) as? Boolean) ?: error("Invalid integration option")
    private fun number(value: JSONObject, key: String): Double = ((value.opt(key) as? Number)?.toDouble()
        ?: error("Invalid integration number")).also { require(it.isFinite()) { "Invalid integration number" } }
    private fun integer(value: JSONObject, key: String, range: IntRange) {
        val number = number(value, key)
        require(number == number.toInt().toDouble() && number.toInt() in range) { "Invalid integration number" }
    }
    private fun strings(value: JSONObject, key: String, maximum: Int): List<String> {
        val array = value.opt(key) as? JSONArray ?: error("Invalid integration list")
        require(array.length() <= maximum) { "Integration list is too large" }
        return (0 until array.length()).map { array.opt(it) as? String ?: error("Invalid integration list item") }
    }
    private fun httpUrl(value: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        require(value.length <= 4096 && uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawFragment == null) { "Use an http or https image URL" }
    }
}
