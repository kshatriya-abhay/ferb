package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What Ferb does with the computer's English text and calls for spoken requests: each case is a
 * miss (or a fix) from the audio bench and its reruns (2026-10-12), kept here at the component
 * that handles it, so the bench lists themselves could go. The text is what Ferb Link's
 * transcribe returned; the calls are what the computer's model sent for it.
 */
class SpokenRequestsTest {
    private val configs = ToolSettings.defaults()
    private fun routed(text: String) = ToolRouter.route(text, Tools.all, configs).map { it.name }
    private fun summary(prepared: Prepared) = (prepared as? Prepared.Ready)?.summary

    @Test
    fun theComputersTextReachesTheRightTool() {
        mapOf(
            "Set an alarm for 5 am" to "set_alarm",
            "Wake me up at 7:15" to "set_alarm",
            "dopahar 2:00 pm utha dena" to "set_alarm", // typed Hinglish: no "alarm", once a calendar event
            "7 baje jaga dena" to "set_alarm",
            "Set a timer for 2 minutes 30 seconds" to "set_timer",
            "How do I go to Chandni Chowk" to "get_directions", // no tool before "go to"
            "how to reach Andheri station" to "get_directions",
            "tell rohit i am running 10 minutes late" to "send_text", // no tool before "tell"
            "Add meeting to calendar for tomorrow at 4" to "add_event",
            "open phone pay" to "open_app",
        ).forEach { (text, tool) -> assertThat(routed(text)).contains(tool) }
    }

    @Test
    fun alarmTimesAsTheComputerSendsThem() {
        mapOf(
            "5 am" to "05:00", "5:00 am" to "05:00", "5:30" to "05:30", "7:15" to "07:15", "7:45" to "07:45",
            "7 pm" to "19:00", "7:00 pm" to "19:00", "11:00 pm" to "23:00", "2 pm" to "14:00",
            "7 in the evening" to "19:00", "9:15 at night" to "21:15", "7:30 am" to "07:30",
            "5 o'clock" to "05:00", // refused as "not a time of day" before 2026-10-12
            "7 o'clock" to "07:00", // no time of day said: taken as written (Ferb Link adds pm for shaam)
        ).forEach { (time, expected) ->
            assertThat(summary(SetAlarm.prepare(mapOf("time" to time), PrepareEnv()))).isEqualTo("Set an alarm for $expected")
        }
    }

    @Test
    fun timerLengthsAsTheComputerSendsThem() {
        mapOf(
            "1 hour 30 minutes" to "Start a 1 h 30 min timer",
            "2 minutes 30 seconds" to "Start a 2 min 30 s timer",
            "1 hour and a half" to "Start a 1 h 30 min timer", // couldn't be read on the bench
            "1 and a half minutes" to "Start a 1 min 30 s timer", // nor this
        ).forEach { (duration, expected) ->
            assertThat(summary(SetTimer.prepare(mapOf("duration" to duration), PrepareEnv()))).isEqualTo(expected)
        }
    }

    @Test
    fun misheardAppsAndNames() {
        val apps = InstalledApps { listOf(LaunchableApp("PhonePe", "p"), LaunchableApp("HP PAY", "h"), LaunchableApp("Paytm", "t"), LaunchableApp("Zoom", "z")) }
        assertThat(summary(OpenApp.prepare(mapOf("app_name" to "Phone Pay"), PrepareEnv(apps)))).isEqualTo("Open PhonePe") // was HP PAY
        assertThat(summary(OpenApp.prepare(mapOf("app_name" to "ptm"), PrepareEnv(apps)))).isEqualTo("Open Paytm")
        assertThat(summary(OpenApp.prepare(mapOf("app_name" to "zoom"), PrepareEnv(apps)))).isEqualTo("Open Zoom")
        // "shreyansh" heard as "shriansh": no one, rather than a stranger whose name sounds alike.
        val book = listOf(Contact("Kriyansh Verma", "+91 96666 77777"))
        assertThat(ContactMatch.rank("shriansh", book)).isEmpty()
    }
}
