package app.flick.tv.profiles

import app.flick.tv.settings.LauncherSettings
import app.flick.tv.settings.SettingsCodec

/** Apply one permission to the latest saved profile, preserving unrelated edits and permissions. */
internal fun LauncherSettings.withChildApp(profileId: String, app: String, allowed: Boolean): LauncherSettings {
    require(SettingsCodec.validPackageName(app)) { "Invalid app package" }
    val profile = profiles.firstOrNull { it.id == profileId } ?: error("This profile no longer exists")
    require(profile.child) { "App access is configured for child profiles" }
    val apps = profile.allowedApps.toMutableSet()
    if (allowed) {
        require(app in apps || apps.size < 100) { "Choose up to one hundred allowed apps" }
        apps.add(app)
    } else apps.remove(app)
    return copy(profiles = profiles.map { if (it.id == profileId) it.copy(allowedApps = apps.toList()) else it })
}

internal fun LauncherSettings.withoutProfile(profileId: String): LauncherSettings {
    val remaining = profiles.filterNot { it.id == profileId }
    val fallback = remaining.firstOrNull { !it.child } ?: error("Keep at least one adult profile")
    val selected = activeProfileId.takeIf { id -> remaining.any { it.id == id } } ?: fallback.id
    return copy(profiles = remaining, activeProfileId = selected)
}
