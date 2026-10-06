package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolsTest {
    private val apps = InstalledApps {
        listOf(
            LaunchableApp("Camera", "com.android.camera"),
            LaunchableApp("Calculator", "com.miui.calculator"),
            LaunchableApp("WhatsApp", "com.whatsapp"),
            LaunchableApp("WhatsApp Business", "com.whatsapp.w4b"),
            LaunchableApp("Google Play Store", "com.android.vending"),
        )
    }

    private fun summary(prepared: Prepared) = (prepared as? Prepared.Ready)?.summary
    private fun reason(prepared: Prepared) = (prepared as? Prepared.Invalid)?.reason

    @Test
    fun alarmsTakeA24HourTime() {
        assertThat(summary(SetAlarm.prepare(mapOf("hour" to 19, "minute" to 0, "label" to "Gym"), apps))).isEqualTo("Set an alarm for 19:00 (Gym)")
        assertThat(summary(SetAlarm.prepare(mapOf("hour" to "6", "minute" to 45), apps))).isEqualTo("Set an alarm for 06:45")
        assertThat(reason(SetAlarm.prepare(mapOf("hour" to 24, "minute" to 0), apps))).contains("Hour")
        assertThat(reason(SetAlarm.prepare(mapOf("minute" to 5), apps))).isNotNull()
    }

    @Test
    fun timersTakeSecondsWithinADay() {
        assertThat(summary(SetTimer.prepare(mapOf("seconds" to 90, "label" to "Eggs"), apps))).isEqualTo("Start a 1 min 30 s timer (Eggs)")
        assertThat(summary(SetTimer.prepare(mapOf("seconds" to 4500), apps))).isEqualTo("Start a 1 h 15 min timer")
        assertThat(reason(SetTimer.prepare(mapOf("seconds" to 0), apps))).isNotNull()
        assertThat(reason(SetTimer.prepare(mapOf("seconds" to 90_000), apps))).isNotNull()
    }

    @Test
    fun appsAreFoundByName() {
        fun find(q: String) = InstalledApps.best(q, listOf(
            LaunchableApp("Camera", "a"), LaunchableApp("Calculator", "b"), LaunchableApp("WhatsApp", "c"),
            LaunchableApp("WhatsApp Business", "d"), LaunchableApp("Google Play Store", "e"),
        ))?.label
        assertThat(find("WhatsApp")).isEqualTo("WhatsApp") // exact beats the longer name
        assertThat(find("whats")).isEqualTo("WhatsApp")
        assertThat(find("the camera app")).isEqualTo("Camera")
        assertThat(find("play store")).isEqualTo("Google Play Store")
        assertThat(find("calc")).isEqualTo("Calculator")
        assertThat(find("Spotify")).isNull()
        assertThat(find("app")).isNull()
    }

    @Test
    fun openAppFallsBackToASettingsPage() {
        assertThat(summary(OpenApp.prepare(mapOf("app_name" to "WhatsApp"), apps))).isEqualTo("Open WhatsApp")
        // No "Bluetooth" app, but a settings page: Needle 3 sometimes sends it here.
        assertThat(summary(OpenApp.prepare(mapOf("app_name" to "Bluetooth"), apps))).isEqualTo("Open the bluetooth settings")
        assertThat(summary(OpenApp.prepare(mapOf("app_name" to "Settings"), apps))).isEqualTo("Open Settings")
        assertThat(reason(OpenApp.prepare(mapOf("app_name" to "Spotify"), apps))).contains("Spotify")
    }

    @Test
    fun settingsPagesAcceptCommonSpellings() {
        assertThat(summary(OpenSettings.prepare(mapOf("page" to "wi-fi"), apps))).isEqualTo("Open the wi-fi settings")
        assertThat(summary(OpenSettings.prepare(mapOf("page" to "wifi"), apps))).isEqualTo("Open the wi-fi settings")
        assertThat(summary(OpenSettings.prepare(mapOf("page" to "date_time"), apps))).isEqualTo("Open the date time settings")
        assertThat(reason(OpenSettings.prepare(mapOf("page" to "kitchen"), apps))).isNotNull()
    }

    @Test
    fun theRestCheckTheirArguments() {
        assertThat(summary(Flashlight.prepare(mapOf("on" to true), apps))).isEqualTo("Turn the flashlight on")
        assertThat(summary(Flashlight.prepare(mapOf("on" to "false"), apps))).isEqualTo("Turn the flashlight off")
        assertThat(reason(Flashlight.prepare(emptyMap(), apps))).isNotNull()
        assertThat(summary(SetVolume.prepare(mapOf("stream" to "ring", "percent" to 0), apps))).isEqualTo("Set the ring volume to 0 %")
        assertThat(reason(SetVolume.prepare(mapOf("stream" to "ring", "percent" to 150), apps))).isNotNull()
        assertThat(reason(SetVolume.prepare(mapOf("stream" to "bass", "percent" to 10), apps))).isNotNull()
        assertThat(summary(MediaControl.prepare(mapOf("action" to "next"), apps))).isEqualTo("Media: next")
        assertThat(reason(MediaControl.prepare(mapOf("action" to "rewind"), apps))).isNotNull()
    }

    @Test
    fun settingsKeepOnlyRealToolsInCatalogueOrder() {
        assertThat(ToolSettings.normalise(setOf("set_flashlight", "gone_tool", "set_alarm"))).containsExactly("set_alarm", "set_flashlight").inOrder()
        assertThat(ToolSettings.defaults()).containsExactly("set_alarm", "set_timer", "open_app", "open_settings", "set_flashlight")
    }
}
