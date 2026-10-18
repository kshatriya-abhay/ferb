package dev.abhay.assistant.tools

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Test

/** [TimeArgs]: the computer's time structure resolved into a call's arguments, checked, and asked about. */
class TimeArgsTest {
    private val now = LocalDateTime.parse("2026-10-16T09:00").atZone(ZoneId.of("Asia/Kolkata"))

    private fun json(text: String) = JSONObject(text)
    private val kalSavaSaat = json("""{"v":1,"kind":"at","day":{"relative":1},"time":{"hour":7,"minute":15,"meridiem":"am","part":"morning"},"source":"kal subah sava saat baje"}""")

    @Test
    fun theResolvedTimeReplacesTheModelsAndTheToolReadsIt() {
        val args = TimeArgs.apply(SetAlarm.name, mapOf("time" to "7:15 am", "when" to kalSavaSaat), listOf("kal subah sava saat baje utha dena"), now)
        assertThat(args["time"]).isEqualTo("07:15")
        assertThat(args[TimeArgs.ASK]).isNull()
        // The resolved time needs no second numbers check, though "07:15" isn't said as such.
        assertThat(SetAlarm.grounded(args, "kal subah sava saat baje utha dena")).isTrue()
    }

    @Test
    fun eventsAndRemindersGetTheirDateAndTime() {
        val kal4 = json("""{"v":1,"kind":"at","day":{"relative":1},"time":{"hour":4,"minute":0,"meridiem":null,"part":null},"source":"kal 4 baje"}""")
        val event = TimeArgs.apply(AddEvent.name, mapOf("title" to "meeting", "when" to kal4), listOf("kal 4 baje meeting calendar mein daal do"), now)
        assertThat(event[TimeArgs.AT]).isEqualTo("2026-10-17T16:00")
        val plan = AddEvent.plan("meeting", event + ("_request" to "kal 4 baje meeting calendar mein daal do"), now.toLocalDateTime(), now.zone)!!
        assertThat(plan.day.toString()).isEqualTo("2026-10-17")
        assertThat(plan.start.toString()).isEqualTo("16:00")
        val weekly = json("""{"v":1,"kind":"every","period":"week","on":["monday"],"time":{"hour":8,"minute":0,"meridiem":null,"part":null},"source":"every Monday at 8"}""")
        val reminder = TimeArgs.apply(AddReminder.name, mapOf("text" to "take out the bins", "time" to weekly), listOf("remind me every Monday at 8 to take out the bins"), now)
        assertThat(reminder[TimeArgs.AT]).isEqualTo("2026-10-19T08:00")
        assertThat(reminder[TimeArgs.REPEAT]).isEqualTo("weekly")
    }

    @Test
    fun aDisagreeingModelValueAsks() {
        // The model wrote 6:30 for "half an hour before 6:30"; the structure says 6:00.
        val six = json("""{"v":1,"kind":"at","day":null,"time":{"hour":6,"minute":0,"meridiem":null,"part":null},"source":"6"}""")
        val args = TimeArgs.apply(SetAlarm.name, mapOf("time" to "6:30", "when" to six), listOf("wake me at 6"), now)
        assertThat(args["time"]).isEqualTo("06:00")
        assertThat(args[TimeArgs.ASK] as String).contains("6:30")
    }

    @Test
    fun aTimeTheRequestDoesntSayAsks() {
        // The source isn't the request's words, or a number in it isn't said.
        val made = json("""{"v":1,"kind":"at","day":null,"time":{"hour":7,"minute":0,"meridiem":"am","part":null},"source":"at 7 am"}""")
        assertThat(TimeArgs.apply(SetAlarm.name, mapOf("when" to made), listOf("wake me up early"), now)[TimeArgs.ASK]).isNotNull()
        val wrongNumber = json("""{"v":1,"kind":"duration","length":{"days":0,"hours":0,"minutes":20,"seconds":0},"source":"das minute"}""")
        assertThat(TimeArgs.apply(SetTimer.name, mapOf("when" to wrongNumber), listOf("das minute ka timer"), now)[TimeArgs.ASK]).isNotNull()
    }

    @Test
    fun hindiAndFractionWordsGroundTheirNumbers() {
        fun at(h: Int, m: Int, source: String) = TimeExpr.At(null, TimeExpr.Clock(h, m, null, null), source)
        assertThat(TimeArgs.grounded(at(7, 15, "sava saat baje"), listOf("sava saat baje utha dena"))).isTrue()
        assertThat(TimeArgs.grounded(at(7, 45, "paune aath baje"), listOf("paune aath baje ka alarm"))).isTrue()
        assertThat(TimeArgs.grounded(at(19, 45, "quarter to eight"), listOf("alarm at quarter to eight in the evening"))).isTrue()
        assertThat(TimeArgs.grounded(TimeExpr.Lasting(TimeExpr.Length(hours = 1, minutes = 30), "dedh ghante"), listOf("dedh ghante ka timer"))).isTrue()
        assertThat(TimeArgs.grounded(at(9, 0, "9 baje"), listOf("10 baje utha dena"))).isFalse()
        // Spoken: the heard words count too.
        assertThat(TimeArgs.grounded(at(7, 15, "sava saat"), listOf("Set an alarm for 7:15 am", "sava saat baje utha dena"))).isTrue()
    }

    @Test
    fun anAlarmOnAnotherDayOffersTheNextRingOrAReminder() {
        val monday = json("""{"v":1,"kind":"at","day":{"weekday":"monday","which":null},"time":{"hour":7,"minute":0,"meridiem":"am","part":null},"source":"Monday 7 am"}""")
        val args = TimeArgs.apply(SetAlarm.name, mapOf("when" to monday), listOf("set an alarm for Monday 7 am"), now)
        assertThat(args["time"]).isEqualTo("07:00")
        assertThat(args[TimeArgs.WANTED]).isEqualTo("2026-10-19T07:00")
        assertThat(args[TimeArgs.ASK] as String).contains("next 07:00")
    }

    @Test
    fun withoutAStructureNothingChanges() {
        val args = mapOf<String, Any?>("time" to "7 pm")
        assertThat(TimeArgs.apply(SetAlarm.name, args, listOf("alarm at 7 pm"), now)).isEqualTo(args)
        assertThat(TimeArgs.apply(OpenApp.name, mapOf("app_name" to "spotify", "when" to kalSavaSaat), listOf("open spotify"), now))
            .isEqualTo(mapOf("app_name" to "spotify", "when" to kalSavaSaat))
    }
}
