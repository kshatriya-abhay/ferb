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
    fun alarmsTakeTheTimeAsWritten() {
        assertThat(summary(SetAlarm.prepare(mapOf("time" to "7 pm"), apps))).isEqualTo("Set an alarm for 19:00")
        assertThat(summary(SetAlarm.prepare(mapOf("time" to "6:45"), apps))).isEqualTo("Set an alarm for 06:45")
        assertThat(reason(SetAlarm.prepare(mapOf("time" to "soon"), apps))).contains("soon")
        assertThat(reason(SetAlarm.prepare(emptyMap(), apps))).isNotNull()
    }

    @Test
    fun timesOfDayAsTheModelWritesThem() {
        // Forms seen from Needle 3 on the desktop check, and a few more.
        val cases = mapOf(
            "8:00 AM" to (8 to 0), "08:00" to (8 to 0), "9:00" to (9 to 0), "8" to (8 to 0), "6:45" to (6 to 45),
            "7 pm" to (19 to 0), "7:30 p.m." to (19 to 30), "12 am" to (0 to 0), "12 pm" to (12 to 0), "19:30" to (19 to 30),
            "6.45" to (6 to 45), "noon" to (12 to 0), "midnight" to (0 to 0),
        )
        cases.forEach { (text, expected) -> assertThat(TimeOfDay.parse(text)).isEqualTo(expected) }
        listOf("25:00", "13 pm", "8:75", "tomorrow", "").forEach { assertThat(TimeOfDay.parse(it)).isNull() }
    }

    @Test
    fun timersTakeTheLengthAsWritten() {
        assertThat(summary(SetTimer.prepare(mapOf("duration" to "90 seconds"), apps))).isEqualTo("Start a 1 min 30 s timer")
        assertThat(summary(SetTimer.prepare(mapOf("duration" to "1 hour 15 minutes"), apps))).isEqualTo("Start a 1 h 15 min timer")
        assertThat(reason(SetTimer.prepare(mapOf("duration" to "a while"), apps))).isNotNull()
        assertThat(reason(SetTimer.prepare(mapOf("duration" to "25 hours"), apps))).isNotNull()
    }

    @Test
    fun durationsAsTheModelWritesThem() {
        val cases = mapOf("10 minutes" to 600, "90 seconds" to 90, "2 hours" to 7200, "45 min" to 2700, "1 hour 15 minutes" to 4500,
            "1 hour and 30 minutes" to 5400, "1.5 hours" to 5400, "half an hour" to 1800, "5 mins" to 300, "1:30" to 90, "30 secs" to 30)
        cases.forEach { (text, seconds) -> assertThat(Durations.parse(text)).isEqualTo(seconds) }
        listOf("soon", "10", "ten minutes", "").forEach { assertThat(Durations.parse(it)).isNull() }
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
    fun fiveToolsAreOnByDefault() {
        assertThat(ToolSettings.enabledNames(ToolSettings.defaults()))
            .containsExactly("set_alarm", "set_timer", "open_app", "open_settings", "set_flashlight").inOrder()
    }

    @Test
    fun harmlessToolsRunFromALowerBar() {
        // Undone in a tap: from 0.3. Alarms and timers create something: from 0.4. Nothing above Cactus's 0.7.
        assertThat(Tools.all.filter { it.defaultRunFrom == 0.4 }.map { it.name }).containsExactly("set_alarm", "set_timer")
        Tools.all.forEach { assertThat(it.defaultRunFrom).isIn(com.google.common.collect.Range.closed(0.3, 0.7)) }
    }
}
