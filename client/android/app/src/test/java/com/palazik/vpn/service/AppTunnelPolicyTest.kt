package com.palazik.vpn.service

import com.palazik.vpn.data.model.AppSettings
import com.palazik.vpn.data.model.AppSettingsCodec
import com.palazik.vpn.data.model.SplitTunnelMode
import org.junit.Assert.*
import org.junit.Test

class AppTunnelPolicyTest {
    private val self = "io.aetherlinkx.client.debug"
    private val gos = AppTunnelPolicy.GOSUSLUGI_PACKAGE
    private val browser = "com.android.chrome"
    private val installed = setOf(self, gos, browser)

    private fun resolve(settings: AppSettings) =
        AppTunnelPolicy.resolve(settings, self, installed::contains)

    @Test fun defaultsBypassGosuslugiWithoutManualSelection() {
        val filter = resolve(AppSettings())
        assertFalse(filter.allowOnly)
        assertEquals(listOf(self, gos), filter.packages)
    }

    @Test fun automaticBypassMergesWithoutChangingUserSelection() {
        val settings = AppSettings(bypassPackages = listOf(" $browser ", gos, browser, "missing.app", self))
        assertEquals(listOf(self, browser, gos), resolve(settings).packages)
        assertEquals(5, settings.bypassPackages.size)
    }

    @Test fun disablingAutomaticBypassStillHonorsManualGosuslugiSelection() {
        assertEquals(listOf(self), resolve(AppSettings(bypassGosuslugi = false)).packages)
        assertEquals(listOf(self, gos), resolve(AppSettings(
            bypassGosuslugi = false, bypassPackages = listOf(gos))).packages)
    }

    @Test fun onlyModeExcludesGosuslugiWithoutMixingAndroidFilterModes() {
        val filter = resolve(AppSettings(splitTunnelMode = SplitTunnelMode.ONLY,
            bypassPackages = listOf(gos, browser)))
        assertTrue(filter.allowOnly)
        assertEquals(listOf(browser), filter.packages)
    }

    @Test(expected = IllegalArgumentException::class)
    fun onlyGosuslugiCannotTurnIntoAnEmptyAllowListThatTunnelsEverything() {
        resolve(AppSettings(splitTunnelMode = SplitTunnelMode.ONLY, bypassPackages = listOf(gos)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun removedAppsCannotTurnAnOnlySelectionIntoAnAllAppsTunnel() {
        resolve(AppSettings(splitTunnelMode = SplitTunnelMode.ONLY, bypassPackages = listOf("removed.app")))
    }

    @Test fun manualOnlyGosuslugiWorksWhenAutomaticBypassIsDisabled() {
        val filter = resolve(AppSettings(splitTunnelMode = SplitTunnelMode.ONLY,
            bypassPackages = listOf(gos), bypassGosuslugi = false))
        assertTrue(filter.allowOnly)
        assertEquals(listOf(gos), filter.packages)
    }

    @Test fun absentGosuslugiDoesNotPreventVpnStartup() {
        assertEquals(listOf(self), AppTunnelPolicy.resolve(AppSettings(), self) { it == self }.packages)
    }

    @Test fun emptyOnlySelectionPreservesExistingAllOtherAppsBehavior() {
        val filter = resolve(AppSettings(splitTunnelMode = SplitTunnelMode.ONLY))
        assertFalse(filter.allowOnly)
        assertEquals(listOf(self, gos), filter.packages)
    }

    @Test fun oldSettingsEnableAutomaticBypassAndPreserveManualSelection() {
        val settings = AppSettingsCodec.fromJson("""{"bypassPackages":["$browser"]}""")
        assertTrue(settings.bypassGosuslugi)
        assertEquals(listOf(browser), settings.bypassPackages)
    }

    @Test fun automaticBypassChoiceSurvivesSettingsExportAndRestore() {
        for (enabled in listOf(true, false)) {
            val settings = AppSettings(bypassGosuslugi = enabled, bypassPackages = listOf(browser))
            val restored = AppSettingsCodec.fromJson(AppSettingsCodec.toJson(settings))
            assertEquals(enabled, restored.bypassGosuslugi)
            assertEquals(settings.bypassPackages, restored.bypassPackages)
        }
    }
}
