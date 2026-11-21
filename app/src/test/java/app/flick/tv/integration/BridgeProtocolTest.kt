package app.flick.tv.integration

import app.flick.tv.themes.AppearanceSettings
import app.flick.tv.themes.ThemeCatalog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BridgeProtocolTest {
    private fun command(type: String = "get_status", data: JSONObject = JSONObject(), version: Int = 1): JSONObject =
        JSONObject().put("type", "command").put("protocol", version).put("request_id", "test-request")
            .put("command", type).put("data", data)

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid protocol input was accepted") } catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    @Test fun parsesKnownCommandWithoutCoercingItsFields() {
        val event = BridgeProtocol.event(BridgeProtocol.frame(command().toString())) as BridgeEvent.Command
        assertEquals(BridgeCommandType.GetStatus, event.value.type)
        assertEquals("test-request", event.value.requestId)
        rejected { BridgeProtocol.event(command().put("request_id", 42)) }
    }

    @Test fun directCameraRequestsOnlyAcceptCameraEntities() {
        val camera = JSONObject().put("camera_entity_id", "camera.entry").put("title", "Entry")
        assertEquals(BridgeCommandType.OpenCamera, (BridgeProtocol.event(command("open_camera", camera)) as BridgeEvent.Command).value.type)
        rejected { BridgeProtocol.event(command("open_camera", JSONObject().put("camera_entity_id", "switch.entry"))) }
        rejected { BridgeProtocol.event(command("open_camera", JSONObject().put("camera_entity_id", "https://example.test/stream"))) }
        rejected { BridgeProtocol.event(command("open_camera", camera.put("url", "https://example.test/stream"))) }
    }

    @Test fun appLaunchAcceptsOnlyAnAndroidPackageName() {
        val data = JSONObject().put("package_name", "org.jellyfin.androidtv")
        val request = BridgeProtocol.event(command("launch_app", data)) as BridgeEvent.Command
        assertEquals(BridgeCommandType.LaunchApp, request.value.type)
        rejected { BridgeProtocol.event(command("launch_app", JSONObject())) }
        rejected { BridgeProtocol.event(command("launch_app", JSONObject().put("package_name", "org.jellyfin/androidtv"))) }
        rejected { BridgeProtocol.event(command("launch_app", data.put("activity", "MainActivity"))) }
    }

    @Test fun rejectsUnknownVersionCommandAndFields() {
        rejected { BridgeProtocol.event(command(version = 2)) }
        rejected { BridgeProtocol.event(command("execute_shell")) }
        rejected { BridgeProtocol.event(command().put("unexpected", true)) }
        rejected { BridgeProtocol.event(command(data = JSONObject().put("extra", true))) }
    }

    @Test fun rejectsOversizedAndDeepMessagesBeforeParsing() {
        rejected { BridgeProtocol.frame(" ".repeat(BridgeProtocol.MAX_FRAME_BYTES + 1)) }
        rejected { BridgeProtocol.frame("{\"v\":" + "[".repeat(14) + "0" + "]".repeat(14) + "}") }
        rejected { BridgeProtocol.frame("{\"v\":[" + List(513) { "0" }.joinToString(",") + "]}") }
    }

    @Test fun rejectsPermissiveJsonExtensionsAndTrailingData() {
        listOf("{'x':1}", "{\"x\":1,}", "{\"x\":01}", "{\"x\":NaN}", "{\"x\":1} {}", "{\"x\":[1,]}", "{x:1}").forEach { raw ->
            rejected { BridgeProtocol.frame(raw) }
        }
        rejected { BridgeProtocol.frame("{\"x\":1,\"\\u0078\":2}") }
    }

    @Test fun quotedBracketsAndEscapesDoNotCountAsStructure() {
        val text = "A photo [today] {here} and \"quoted\""
        val parsed = BridgeProtocol.frame(JSONObject().put("text", text).toString())
        assertEquals(text, parsed.getString("text"))
    }

    @Test fun onlyNetworkImagesCanBeManagedRemotely() {
        rejected { BridgeProtocol.validateData(BridgeCommandType.SetProfile, JSONObject().put("id", "person").put("avatar", "content://private/photo")) }
        rejected { BridgeProtocol.validateData(BridgeCommandType.SetWallpapers, JSONObject().put("photos", org.json.JSONArray(listOf("file:///private/photo")))) }
        rejected { BridgeProtocol.validateData(BridgeCommandType.SetProfile, JSONObject().put("id", "person").put("pinHash", "not-accepted")) }
    }

    @Test fun wallpaperNumbersAndBooleansStayTypedAndBounded() {
        rejected { BridgeProtocol.validateData(BridgeCommandType.SetWallpaperOptions, JSONObject().put("interval_seconds", 30.5)) }
        rejected { BridgeProtocol.validateData(BridgeCommandType.SetWallpaperOptions, JSONObject().put("shuffle", "true")) }
        rejected { BridgeProtocol.validateData(BridgeCommandType.SetWallpaperOptions, JSONObject().put("dim_amount", 0.9)) }
        BridgeProtocol.validateData(BridgeCommandType.SetWallpaperOptions, JSONObject().put("interval_seconds", 300).put("shuffle", true))
    }

    @Test fun themeManagementAcceptsOnlyAThemeIdentifier() {
        val event = BridgeProtocol.event(command("list_themes")) as BridgeEvent.Command
        assertEquals(BridgeCommandType.ListThemes, event.value.type)
        BridgeProtocol.validateData(BridgeCommandType.SetTheme, JSONObject().put("id", "custom_1234-5678"))
        rejected { BridgeProtocol.event(command("list_themes", JSONObject().put("colors", JSONObject()))) }
        for (id in listOf<Any>("", "../theme", "x".repeat(65), 42)) rejected {
            BridgeProtocol.validateData(BridgeCommandType.SetTheme, JSONObject().put("id", id))
        }
        rejected { BridgeProtocol.validateData(BridgeCommandType.SetTheme, JSONObject().put("id", "midnight").put("colors", JSONObject())) }
    }

    @Test fun importedThemeIdentifiersFitTheManagementProtocol() {
        val appearance = ThemeCatalog.addImported(AppearanceSettings(), ThemeCatalog.graphite.copy(name = "Ocean"))
        val id = appearance.selectedThemeId
        assertEquals(43, id.length)
        val event = BridgeProtocol.event(command("set_theme", JSONObject().put("id", id))) as BridgeEvent.Command
        assertEquals(BridgeCommandType.SetTheme, event.value.type)
        assertEquals(id, event.value.data.getString("id"))
    }

    @Test fun parentPinUpdatesRequireExactlyFourDigits() {
        BridgeProtocol.validateData(BridgeCommandType.SetProfile, JSONObject().put("id", "person").put("pin", "0123"))
        for (pin in listOf("123", "12345", "12x4", "")) rejected {
            BridgeProtocol.validateData(BridgeCommandType.SetProfile, JSONObject().put("id", "person").put("pin", pin))
        }
    }

    @Test fun notificationsAllowEitherTextFieldButNeverAnEmptyCard() {
        BridgeProtocol.validateData(BridgeCommandType.Notify, JSONObject().put("id", "notice").put("message", "Someone is at the door"))
        BridgeProtocol.validateData(BridgeCommandType.Notify, JSONObject().put("id", "notice").put("title", "Laundry finished").put("message", ""))
        rejected { BridgeProtocol.validateData(BridgeCommandType.Notify, JSONObject().put("id", "notice").put("title", "").put("message", "")) }
    }

    @Test fun diagnosticsDoNotExposeNamesCodesOrPayloads() {
        val state = BridgeState(deviceId = "private-device", name = "private-name", pairingCode = "123456")
        assertFalse(state.toString().contains("private-device"))
        assertFalse(state.toString().contains("private-name"))
        assertFalse(state.toString().contains("123456"))
        val command = BridgeCommand("private-request", BridgeCommandType.SetProfile, JSONObject().put("pin", "1234"))
        assertFalse(command.toString().contains("1234"))
        assertFalse(command.toString().contains("private-request"))
        assertEquals("BridgeIdentity(redacted)", BridgeIdentity("private-origin", "private-secret", true).toString())
    }

    @Test fun repeatedRequestsStayClaimedAcrossConnectionReplacement() {
        val first = BridgeRequestHistory()
        assertTrue(first.claim("request-one"))
        assertFalse(first.claim("request-one"))
        val restored = BridgeRequestHistory(first.snapshot())
        assertFalse(restored.claim("request-one"))
        assertTrue(restored.claim("request-two"))
    }

    @Test fun queueDelayConsumesTheSameBudgetAsExecution() {
        val command = BridgeCommand("queued", BridgeCommandType.NextWallpaper, JSONObject(), receivedAtNanos = 1_000_000_000L)
        assertEquals(9_000L, command.remainingMillis(1_000_000_000L))
        assertEquals(1_000L, command.remainingMillis(9_000_000_000L))
        assertEquals(0L, command.remainingMillis(10_000_000_000L))
        assertEquals(0L, command.remainingMillis(40_000_000_000L))
    }

    @Test fun negotiationRequiresAnExplicitSupportedNumericVersion() {
        BridgeProtocol.validateNegotiation(JSONObject().put("protocol", 1).put("status", "pending"))
        for (version in listOf<Any>(2, "1", true)) rejected {
            BridgeProtocol.validateNegotiation(JSONObject().put("protocol", version).put("status", "connected"))
        }
        rejected { BridgeProtocol.validateNegotiation(JSONObject().put("status", "connected")) }
    }

    @Test fun requestHistoryHasABoundedWindow() {
        val history = BridgeRequestHistory(limit = 2)
        history.claim("one"); history.claim("two"); history.claim("three")
        assertEquals(listOf("two", "three"), history.snapshot())
        assertFalse(history.claim("two"))
        assertTrue(history.claim("one"))
    }
}
