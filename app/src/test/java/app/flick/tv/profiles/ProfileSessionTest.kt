package app.flick.tv.profiles

import app.flick.tv.settings.LauncherSettings
import app.flick.tv.settings.SettingsCodec
import org.junit.Assert.*
import org.junit.Test

class ProfileSessionTest {
    private val adult = Profile("adult", "Adult", pinSalt = "a".repeat(32), pinHash = "b".repeat(64))
    private val child = Profile("child", "Child", child = true, allowedApps = listOf("example.player"))
    private fun family() = LauncherSettings(profiles = listOf(adult, child), activeProfileId = adult.id)

    @Test fun aSavedAdultInAFamilyIsNotAnAuthorizedColdSession() {
        val settings = family()
        val session = ProfileSession(settings)
        assertEquals(ProfileStartup.Ask, ProfileSession.startup(settings))
        assertNull(session.profile(settings))
        assertFalse(session.canManage(settings))
        assertFalse(session.canLaunch(settings, "example.player"))
    }

    @Test fun childSelectionOpensOnlyAllowedAppsWithoutAdultAuthority() {
        val settings = family().copy(activeProfileId = child.id)
        val session = ProfileSession(settings)
        assertTrue(session.authorize(settings, child, session.state.value.generation))
        assertTrue(session.canLaunch(settings, "example.player"))
        assertFalse(session.canLaunch(settings, "example.other"))
        assertFalse(session.canManage(settings))
    }

    @Test fun reorderingRequiresTheCurrentAuthorizedProfileAndSession() {
        val settings = family().copy(activeProfileId = child.id)
        val session = ProfileSession(settings)
        val generation = session.state.value.generation
        assertFalse(session.canEditAppOrder(settings, child.id, generation))
        assertTrue(session.authorize(settings, child, generation))
        assertTrue(session.canEditAppOrder(settings, child.id, generation))
        assertFalse(session.canEditAppOrder(settings, adult.id, generation))
        assertFalse(session.canManage(settings))
        val changedRole = settings.copy(profiles = listOf(adult, child.copy(child = false)))
        assertFalse(session.canEditAppOrder(changedRole, child.id, generation))
        session.revoke()
        assertFalse(session.canEditAppOrder(settings, child.id, generation))
        assertTrue(session.authorize(settings, child, session.state.value.generation))
        assertFalse(session.canEditAppOrder(settings, child.id, generation))
    }

    @Test fun wakeRequiresANewUnlockEvenForTheSameSavedAdult() {
        val settings = family()
        val session = ProfileSession(settings)
        val beforeWake = session.state.value.generation
        assertTrue(session.authorize(settings, adult, beforeWake))
        assertTrue(session.canManage(settings))
        assertTrue(session.revokeOnWake(settings))
        assertFalse(session.canManage(settings))
        assertFalse(session.authorize(settings, adult, beforeWake))
        assertTrue(session.authorize(settings, adult, session.state.value.generation))
    }

    @Test fun deletingTheUnlockedAdultDoesNotUnlockTheReplacement() {
        val replacement = adult.copy(id = "replacement")
        val settings = family().copy(profiles = listOf(adult, child, replacement))
        val session = ProfileSession(settings)
        val generation = session.state.value.generation
        assertTrue(session.authorize(settings, adult, generation))

        val updated = settings.withoutProfile(adult.id)
        assertEquals(replacement.id, updated.activeProfileId)
        assertTrue(session.renewAfterEdit(updated, generation))
        assertFalse(session.canManage(updated))
        assertTrue(session.reconcile(updated))
        assertFalse(session.authorize(updated, replacement, generation))
    }

    @Test fun editingTheUnlockedAdultsPinRenewsTheSameSession() {
        val settings = family()
        val session = ProfileSession(settings)
        val generation = session.state.value.generation
        assertTrue(session.authorize(settings, adult, generation))

        val updated = settings.copy(profiles = listOf(adult.copy(pinHash = "c".repeat(64)), child))
        assertFalse(session.canManage(updated))
        assertTrue(session.renewAfterEdit(updated, generation))
        assertTrue(session.canManage(updated))
    }

    @Test fun resumeLastIsAnExplicitChoiceThatSurvivesWake() {
        val settings = family().copy(profileStartup = ProfileStartup.ResumeLast)
        val session = ProfileSession(settings)
        assertTrue(session.canManage(settings))
        assertFalse(session.revokeOnWake(settings))
        assertTrue(session.canManage(settings))
    }

    @Test fun changingThePinDuringVerificationCannotUnlockUsingTheOldProfile() {
        val settings = family()
        val session = ProfileSession(settings)
        val changed = settings.copy(profiles = listOf(adult.copy(pinHash = "c".repeat(64)), child))
        assertFalse(session.authorize(changed, adult, session.state.value.generation))
        assertFalse(session.canManage(changed))
    }

    @Test fun deletionAndRoleChangesInvalidateAuthorityBeforeReconciliationRuns() {
        val settings = family().copy(profileStartup = ProfileStartup.ResumeLast)
        for (profiles in listOf(listOf(child), listOf(adult.copy(child = true), child))) {
            val session = ProfileSession(settings)
            val changed = settings.copy(profiles = profiles)
            assertNull(session.profile(changed))
            assertFalse(session.canManage(changed))
            assertTrue(session.reconcile(changed))
            assertNull(session.state.value.authorizedProfileId)
        }
    }

    @Test fun photosAndNamesDoNotResetAnUnlock() {
        val settings = family().copy(profileStartup = ProfileStartup.ResumeLast)
        val session = ProfileSession(settings)
        val renamed = adult.copy(name = "Renamed", avatar = "content://example/photo")
        val changed = settings.copy(profiles = listOf(renamed, child))
        assertFalse(session.reconcile(changed))
        assertEquals(renamed, session.profile(changed))
        assertTrue(session.canManage(changed))
    }

    @Test fun cancellingAPinAttemptKeepsTheCurrentSessionButRetiresItsProof() {
        val settings = family().copy(profileStartup = ProfileStartup.ResumeLast)
        val session = ProfileSession(settings)
        val generation = session.state.value.generation
        session.cancelSelection()
        assertTrue(session.canManage(settings))
        assertFalse(session.authorize(settings, adult, generation))
    }

    @Test fun defaultStaysUnchosenSoAddingTheFirstChildChangesTheFamilyDefault() {
        val original = LauncherSettings(profiles = listOf(adult), activeProfileId = adult.id)
        val encoded = SettingsCodec.write(original)
        assertFalse(encoded.has("profileStartup"))
        val restored = SettingsCodec.read(encoded)
        assertEquals(ProfileStartup.ResumeLast, ProfileSession.startup(restored))
        val withChild = restored.copy(profiles = restored.profiles + child)
        assertEquals(ProfileStartup.Ask, ProfileSession.startup(withChild))
        assertFalse(ProfileSession(withChild).canManage(withChild))
    }

    @Test fun anAdultChosenResumeModeIsRetainedWhenAddingAChild() {
        val original = LauncherSettings(profiles = listOf(adult), activeProfileId = adult.id,
            profileStartup = ProfileStartup.ResumeLast)
        val restored = SettingsCodec.read(SettingsCodec.write(original)).copy(profiles = listOf(adult, child))
        assertEquals(ProfileStartup.ResumeLast, ProfileSession.startup(restored))
        assertTrue(ProfileSession(restored).canManage(restored))
    }

    @Test fun askingOnStartupRequiresAdultPinsBeforeTheSettingCanBeSaved() {
        val settings = LauncherSettings(profileStartup = ProfileStartup.Ask)
        assertThrows(IllegalArgumentException::class.java) { SettingsCodec.read(SettingsCodec.write(settings)) }
    }

    @Test fun childAllowlistChangesApplyToTheExistingSessionImmediately() {
        val settings = family().copy(activeProfileId = child.id)
        val session = ProfileSession(settings)
        assertTrue(session.authorize(settings, child, session.state.value.generation))
        val changed = settings.copy(profiles = listOf(adult, child.copy(allowedApps = emptyList())))
        assertFalse(session.canLaunch(changed, "example.player"))
        assertFalse(session.reconcile(changed))
    }
}
