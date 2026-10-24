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
        assertThat(summary(SetAlarm.prepare(mapOf("time" to "7 pm"), PrepareEnv(apps)))).isEqualTo("Set an alarm for 19:00")
        assertThat(summary(SetAlarm.prepare(mapOf("time" to "6:45"), PrepareEnv(apps)))).isEqualTo("Set an alarm for 06:45")
        assertThat(reason(SetAlarm.prepare(mapOf("time" to "soon"), PrepareEnv(apps)))).contains("soon")
        assertThat(reason(SetAlarm.prepare(emptyMap(), PrepareEnv(apps)))).isNotNull()
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
        // As the computer's model writes them for spoken requests (2026-10-12: "5 o'clock" was refused).
        val spoken = mapOf("5 o'clock" to (5 to 0), "five o'clock" to (5 to 0), "5 baje" to (5 to 0), "tomorrow at 6" to (6 to 0),
            "6 am tomorrow" to (6 to 0), "7 in the evening" to (19 to 0), "5 in the morning" to (5 to 0), "9 at night" to (21 to 0),
            "12 at night" to (0 to 0), "3 in the afternoon" to (15 to 0), "subah 5" to (5 to 0), "shaam 6 baje" to (18 to 0),
            "7:30 in the evening" to (19 to 30), "6 pm tonight" to (18 to 0), "raat 2 baje" to (2 to 0), "raat 10 baje" to (22 to 0),
            // As Ferb Link leaves the model's own words for the time of day.
            "11 o'clock at night" to (23 to 0), "7 o'clock in the morning" to (7 to 0), "3 o'clock in the afternoon" to (15 to 0))
        spoken.forEach { (text, expected) -> assertThat(TimeOfDay.parse(text)).isEqualTo(expected) }
        // am and pm however they're written (2026-10-24: "10 a.m." wasn't a time of day).
        val written = mapOf("10 a.m." to (10 to 0), "10 A.M." to (10 to 0), "10am." to (10 to 0), "10 a m" to (10 to 0), "10a.m" to (10 to 0),
            "7:15 P.M." to (19 to 15), "7 p. m." to (19 to 0), "12 a.m." to (0 to 0))
        written.forEach { (text, expected) -> assertThat(TimeOfDay.parse(text)).isEqualTo(expected) }
        assertThat(summary(SetAlarm.prepare(SetAlarm.fromRequest(mapOf("time" to "10 a.m."), "set an alarm for 10 a.m."), PrepareEnv(apps)))).contains("10:00")
        // Not a meridiem: "a meeting" after a number.
        assertThat(Meridiem.normalise("5 a meeting")).isEqualTo("5 a meeting")
    }

    @Test
    fun timersTakeTheLengthAsWritten() {
        assertThat(summary(SetTimer.prepare(mapOf("duration" to "90 seconds"), PrepareEnv(apps)))).isEqualTo("Start a 1 min 30 s timer")
        assertThat(summary(SetTimer.prepare(mapOf("duration" to "1 hour 15 minutes"), PrepareEnv(apps)))).isEqualTo("Start a 1 h 15 min timer")
        assertThat(reason(SetTimer.prepare(mapOf("duration" to "a while"), PrepareEnv(apps)))).isNotNull()
        assertThat(reason(SetTimer.prepare(mapOf("duration" to "25 hours"), PrepareEnv(apps)))).isNotNull()
    }

    @Test
    fun durationsAsTheModelWritesThem() {
        val cases = mapOf("10 minutes" to 600, "90 seconds" to 90, "2 hours" to 7200, "45 min" to 2700, "1 hour 15 minutes" to 4500,
            "1 hour and 30 minutes" to 5400, "1.5 hours" to 5400, "half an hour" to 1800, "5 mins" to 300, "1:30" to 90, "30 secs" to 30,
            "one minute" to 60, "ten minutes" to 600, "an hour" to 3600,
            // Halves as the computer's model passes them (audio bench, 2026-10-12).
            "1 hour and a half" to 5400, "an hour and a half" to 5400, "1 and a half minutes" to 90,
            "one and a half hours" to 5400, "2 and a half minutes" to 150, "half a minute" to 30, "a minute and a half" to 90)
        cases.forEach { (text, seconds) -> assertThat(Durations.parse(text)).isEqualTo(seconds) }
        listOf("soon", "10", "").forEach { assertThat(Durations.parse(it)).isNull() }
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
    fun misheardAppNamesAreFoundOnlyWhenClear() {
        val installed = listOf("Paytm", "PhonePe", "Zoom", "Zomato", "Swiggy", "Uber", "Ola", "Maps", "Phone", "Photos", "Gmail",
            "Gallery", "YouTube", "Instagram", "Telegram", "Truecaller", "Amazon", "Flipkart", "Myntra", "Meesho", "Keep Notes",
            "Clock", "Calendar", "Messages", "Contacts", "Files", "Chrome", "Settings", "Camera", "Calculator", "Google Pay", "CRED")
            .mapIndexed { i, label -> LaunchableApp(label, "p$i") }
        fun find(q: String) = InstalledApps.loose(q, installed)?.label
        assertThat(find("ptm")).isEqualTo("Paytm")
        assertThat(find("PTM")).isEqualTo("Paytm")
        assertThat(find("peti m")).isEqualTo("Paytm")
        assertThat(find("ptm kholo")).isEqualTo("Paytm")
        assertThat(find("fone pe")).isEqualTo("PhonePe")
        assertThat(find("swigy")).isEqualTo("Swiggy")
        assertThat(find("flip kart")).isEqualTo("Flipkart")
        // A whole name that sounds right beats one shared word ("pay" in HP PAY).
        assertThat(InstalledApps.best("phone pay", installed + LaunchableApp("HP PAY", "hp"))?.label).isEqualTo("PhonePe")
        // Short or unrelated words open nothing.
        listOf("pm", "tm", "pt", "do", "app", "time", "bank", "xyz", "spotify", "mom", "the car").forEach {
            assertThat(find(it)).isNull()
        }
    }

    @Test
    fun openAppFallsBackToASettingsPage() {
        assertThat(summary(OpenApp.prepare(mapOf("app_name" to "WhatsApp"), PrepareEnv(apps)))).isEqualTo("Open WhatsApp")
        // No "Bluetooth" app, but a settings page: Needle 3 sometimes sends it here.
        assertThat(summary(OpenApp.prepare(mapOf("app_name" to "Bluetooth"), PrepareEnv(apps)))).isEqualTo("Open the bluetooth settings")
        assertThat(summary(OpenApp.prepare(mapOf("app_name" to "Settings"), PrepareEnv(apps)))).isEqualTo("Open Settings")
        assertThat(reason(OpenApp.prepare(mapOf("app_name" to "Spotify"), PrepareEnv(apps)))).contains("Spotify")
        // A misheard name, after the settings pages: "watsap" is WhatsApp.
        assertThat(summary(OpenApp.prepare(mapOf("app_name" to "watsap"), PrepareEnv(apps)))).isEqualTo("Open WhatsApp")
    }

    @Test
    fun settingsPagesAcceptCommonSpellings() {
        assertThat(summary(OpenSettings.prepare(mapOf("page" to "wi-fi"), PrepareEnv(apps)))).isEqualTo("Open the wi-fi settings")
        assertThat(summary(OpenSettings.prepare(mapOf("page" to "wifi"), PrepareEnv(apps)))).isEqualTo("Open the wi-fi settings")
        assertThat(summary(OpenSettings.prepare(mapOf("page" to "date_time"), PrepareEnv(apps)))).isEqualTo("Open the date time settings")
        assertThat(reason(OpenSettings.prepare(mapOf("page" to "kitchen"), PrepareEnv(apps)))).isNotNull()
    }

    @Test
    fun theRestCheckTheirArguments() {
        assertThat(summary(Flashlight.prepare(mapOf("on" to true), PrepareEnv(apps)))).isEqualTo("Turn the flashlight on")
        assertThat(summary(Flashlight.prepare(mapOf("on" to "false"), PrepareEnv(apps)))).isEqualTo("Turn the flashlight off")
        assertThat(reason(Flashlight.prepare(emptyMap(), PrepareEnv(apps)))).isNotNull()
        assertThat(summary(SetVolume.prepare(mapOf("percent" to 40), PrepareEnv(apps)))).isEqualTo("Set the media volume to 40 %")
        assertThat(reason(SetVolume.prepare(mapOf("percent" to 150), PrepareEnv(apps)))).isNotNull()
        // Media only: a request about another volume asks first.
        assertThat(SetVolume.grounded(mapOf("percent" to 0), "Mute the ringer")).isFalse()
        assertThat(SetVolume.grounded(mapOf("percent" to 40), "set volume to 40")).isTrue()
        assertThat(summary(MediaControl.prepare(mapOf("action" to "next"), PrepareEnv(apps)))).isEqualTo("Media: next")
        assertThat(reason(MediaControl.prepare(mapOf("action" to "rewind"), PrepareEnv(apps)))).isNotNull()
        // Resuming is play, whatever Needle 3 said; pausing stays pause.
        fun media(action: String, request: String) = summary(MediaControl.prepare(MediaControl.fromRequest(mapOf("action" to action), request), PrepareEnv(apps)))
        assertThat(media("pause", "Resume the music")).isEqualTo("Media: play")
        assertThat(media("pause", "resume")).isEqualTo("Media: play")
        assertThat(media("pause", "Resume")).isEqualTo("Media: play")
        // An action outside the enum is refused, never taken as pause.
        assertThat(reason(MediaControl.prepare(MediaControl.fromRequest(mapOf("action" to "resume"), "resume"), PrepareEnv(apps)))).isNotNull()
        assertThat(media("pause", "unpause the song")).isEqualTo("Media: play")
        assertThat(media("pause", "please carry on with the playlist")).isEqualTo("Media: play")
        assertThat(media("pause", "pause the music")).isEqualTo("Media: pause")
        assertThat(media("next", "continue to the next song")).isEqualTo("Media: next")
    }

    @Test
    fun notesTakeTheRequestsWords() {
        assertThat(summary(CreateNote.prepare(mapOf("text" to "buy milk"), PrepareEnv(apps)))).isEqualTo("Take a note: \u201cbuy milk\u201d")
        assertThat(reason(CreateNote.prepare(mapOf("text" to " "), PrepareEnv(apps)))).isNotNull()
        assertThat(CreateNote.grounded(mapOf("text" to "the gate code 4512"), "write down the gate code 4512")).isTrue()
        assertThat(CreateNote.grounded(mapOf("text" to "car parked on level 3"), "Note that the car is parked on level 3")).isTrue()
        // A note Needle 3 made up asks first.
        assertThat(CreateNote.grounded(mapOf("text" to "groceries for the week"), "take a note")).isFalse()
    }

    @Test
    fun directionsTakeThePlaceAndTheRequestsMode() {
        assertThat(summary(GetDirections.prepare(mapOf("destination" to "Lodhi Garden"), PrepareEnv(apps)))).isEqualTo("Directions to \u201cLodhi Garden\u201d")
        assertThat(reason(GetDirections.prepare(mapOf("destination" to " "), PrepareEnv(apps)))).isNotNull()
        // The mode is the request's, whatever Needle 3 said.
        assertThat(GetDirections.fromRequest(mapOf("destination" to "MG Road", "mode" to "driving"), "how do I get to MG Road by bus"))
            .isEqualTo(mapOf("destination" to "MG Road", "mode" to "transit"))
        assertThat(GetDirections.fromRequest(mapOf("destination" to "airport", "mode" to "walking"), "directions to the airport"))
            .isEqualTo(mapOf("destination" to "airport"))
        assertThat(GetDirections.modeIn("route to the office on my scooter")).isEqualTo("two-wheeler")
        assertThat(GetDirections.modeIn("cycling route to Cubbon Park")).isEqualTo("bicycling")
        assertThat(GetDirections.modeIn("take me to the cartoon network studio")).isNull()
        assertThat(summary(GetDirections.prepare(mapOf("destination" to "India Gate", "mode" to "walking"), PrepareEnv(apps))))
            .isEqualTo("Directions to \u201cIndia Gate\u201d (walking)")
        assertThat(GetDirections.grounded(mapOf("destination" to "the nearest petrol pump"), "take me to the nearest petrol pump")).isTrue()
        assertThat(GetDirections.grounded(mapOf("destination" to "Mumbai Central"), "take me home")).isFalse()
    }

    private val contacts = ContactBook {
        listOf(
            Contact("Priya Sharma", "+91 98765 43210", "Mobile"),
            Contact("Priya Singh", "+91 99887 76655", "Mobile"),
            Contact("Maa", "+91 98111 22222", "Mobile"),
        )
    }

    @Test
    fun callsOfferTheBestContactAndTheDialer() {
        val ready = MakeCall.prepare(mapOf("name" to "Priya"), PrepareEnv(apps, contacts)) as Prepared.Ready
        // The best match only: equally good ones go to the shorter name.
        assertThat(ready.choices.map { it.label }).containsExactly("Priya Singh")
        assertThat(ready.choices.single().detail).isEqualTo("Mobile \u00b7 +91 99887 76655")
        assertThat(ready.other?.label).isEqualTo("Open dialer")
        // No one by that name: the dialer, to find them by hand.
        val nobody = MakeCall.prepare(mapOf("name" to "Zubair"), PrepareEnv(apps, contacts)) as Prepared.Ready
        assertThat(nobody.choices).isEmpty()
        assertThat(nobody.confirm).isEqualTo("Open dialer")
        // A number is dialled as it is.
        val number = MakeCall.prepare(mapOf("name" to "98765 43210"), PrepareEnv(apps, contacts)) as Prepared.Ready
        assertThat(number.choices.map { it.label }).containsExactly("9876543210")
        // Without the contacts: the dialer, and the reply asks for them.
        val noContacts = MakeCall.prepare(mapOf("name" to "Priya"), PrepareEnv(apps)) as Prepared.Ready
        assertThat(noContacts.confirm).isEqualTo("Open dialer")
        assertThat(noContacts.detail).contains("contacts")
        assertThat(MakeCall.grounded(mapOf("name" to "Priya"), "call Priya")).isTrue()
        assertThat(MakeCall.grounded(mapOf("name" to "Rahul"), "call her")).isFalse()
    }

    @Test
    fun messagesShowOneRecipientAndTheDraft() {
        val ready = SendText.prepare(mapOf("name" to "mum", "message" to "I reached safely"), PrepareEnv(apps, contacts)) as Prepared.Ready
        assertThat(ready.summary).isEqualTo("Text Maa \u00b7 +91 98111 22222")
        assertThat(ready.detail).isEqualTo("\u201cI reached safely\u201d")
        assertThat(ready.confirm).isEqualTo("Proceed")
        assertThat(ready.cancel).isEqualTo("Cancel")
        assertThat(reason(SendText.prepare(mapOf("name" to "mum"), PrepareEnv(apps, contacts)))).isNotNull()
        assertThat(SendText.grounded(mapOf("name" to "Priya", "message" to "I'm running late"), "text Priya I'm running late")).isTrue()
        assertThat(SendText.grounded(mapOf("name" to "Priya", "message" to "see you at the cinema"), "text Priya")).isFalse()
    }

    @Test
    fun callsAndMessagesAlwaysAskFirstUntilChanged() {
        assertThat(ToolConfig.defaults(MakeCall).alwaysConfirm).isTrue()
        assertThat(ToolConfig.defaults(SendText).alwaysConfirm).isTrue()
        assertThat(ToolConfig.defaults(SetAlarm).alwaysConfirm).isFalse()
        assertThat(ToolConfig.fromJson("""{"enabled": true}""", SendText).alwaysConfirm).isTrue()
        assertThat(ToolConfig.fromJson("""{"enabled": true, "always_confirm": false}""", SendText).alwaysConfirm).isFalse()
    }

    @Test
    fun fiveToolsAreOnByDefault() {
        assertThat(ToolSettings.enabledNames(ToolSettings.defaults()))
            .containsExactly("set_alarm", "set_timer", "open_app", "open_settings", "set_flashlight").inOrder()
    }

    @Test
    fun barsStayWithinTheSlidersUsefulRange() {
        // Set per tool from tool-check's table; none needs more than 0.75 to hold back its wrong calls.
        Tools.all.forEach { assertThat(it.defaultRunFrom).isIn(com.google.common.collect.Range.closed(0.3, 0.75)) }
    }

    @Test
    fun numbersCountWhenTheRequestSaysThem() {
        assertThat(SaidNumbers.of("quarter past eight, or noon")).containsExactly(15, 8, 12)
        assertThat(SaidNumbers.grounded("90 seconds", "a 90 second timer")).isTrue()
        assertThat(SaidNumbers.grounded("19:00", "alarm at 7 pm")).isTrue()
        assertThat(SaidNumbers.grounded("08:00", "alarm at 8")).isTrue()
        assertThat(SaidNumbers.grounded("90 seconds", "how long should I boil an egg")).isFalse()
        assertThat(SaidNumbers.grounded("6:45", "set an alarm")).isFalse()
    }
}
