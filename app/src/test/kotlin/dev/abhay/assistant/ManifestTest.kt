package dev.abhay.assistant

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * The manifest's promises: Ferb can be the phone's assistant (an exported activity for
 * ACTION_ASSIST, what Android's assistant role accepts), asks for no more permissions than the
 * tools need, and never INTERNET (`mise run check-offline` checks the built APK too).
 */
class ManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))

    private fun elements(tag: String) = doc.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    @Test
    fun ferbQualifiesAsTheAssistant() {
        // The sheet over the app in front (AssistActivity), not the chat.
        val main = elements("activity").single { it.getAttributeNS(android, "name") == ".AssistActivity" }
        assertThat(main.getAttributeNS(android, "exported")).isEqualTo("true")
        assertThat(main.getAttributeNS(android, "excludeFromRecents")).isEqualTo("true")
        val filters = main.getElementsByTagName("intent-filter").let { l -> (0 until l.length).map { l.item(it) as Element } }
        val assist = filters.single { f ->
            f.getElementsByTagName("action").let { a -> (0 until a.length).any { (a.item(it) as Element).getAttributeNS(android, "name") == "android.intent.action.ASSIST" } }
        }
        val categories = assist.getElementsByTagName("category").let { c -> (0 until c.length).map { (c.item(it) as Element).getAttributeNS(android, "name") } }
        assertThat(categories).contains("android.intent.category.DEFAULT")
        // No voice interaction service yet (the activity is enough), so nothing binds to Ferb.
        assertThat(elements("service").none { it.getAttributeNS(android, "permission") == "android.permission.BIND_VOICE_INTERACTION" }).isTrue()
    }

    @Test
    fun theFrontDoorRoutesWithoutShowingOrBringingTheChatForward() {
        fun activity(name: String) = elements("activity").single { it.getAttributeNS(android, "name") == name }
        // .MainActivity is what Android saved as the assistant on older grants: it must stay, exported, and show nothing.
        val door = activity(".MainActivity")
        assertThat(door.getAttributeNS(android, "exported")).isEqualTo("true")
        assertThat(door.getAttributeNS(android, "theme")).isEqualTo("@android:style/Theme.NoDisplay")
        // Its own task, out of Recents: the assist trigger doesn't bring the chat's task forward.
        assertThat(door.getAttributeNS(android, "taskAffinity")).isNotEmpty()
        assertThat(door.getAttributeNS(android, "taskAffinity")).isNotEqualTo(activity(".AssistActivity").getAttributeNS(android, "taskAffinity"))
        assertThat(door.getAttributeNS(android, "excludeFromRecents")).isEqualTo("true")
        assertThat(door.getAttributeNS(android, "noHistory")).isEqualTo("true")
        val actions = door.getElementsByTagName("action").let { a -> (0 until a.length).map { (a.item(it) as Element).getAttributeNS(android, "name") } }
        assertThat(actions).contains("android.intent.action.MAIN")
        // The chat itself is reached only through it, or from Ferb's own intents.
        assertThat(activity(".ChatActivity").getAttributeNS(android, "exported")).isEqualTo("false")
    }

    @Test
    fun theAssistTriggerGoesToTheSheetAndAllElseToTheChat() {
        assertThat(Routing.of("android.intent.action.ASSIST")).isEqualTo(Routing.Sheet)
        assertThat(Routing.of("android.intent.action.MAIN")).isEqualTo(Routing.Chat)
        assertThat(Routing.of(null)).isEqualTo(Routing.Chat)
    }

    @Test
    fun obsidianIsVisibleForNotes() {
        val queries = elements("queries").single().getElementsByTagName("data").let { d -> (0 until d.length).map { (d.item(it) as Element).getAttributeNS(android, "scheme") } }
        assertThat(queries).contains("obsidian")
    }

    @Test
    fun thePermissionsAreTheToolsOwnAndNeverInternet() {
        val tools = "http://schemas.android.com/tools"
        val (removed, asked) = elements("uses-permission").partition { it.getAttributeNS(tools, "node") == "remove" }
        val permissions = asked.map { it.getAttributeNS(android, "name") }
        // What a library merges in for the network (ML Kit's usage logs) is taken out again.
        assertThat(removed.map { it.getAttributeNS(android, "name") })
            .containsExactly("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")
        assertThat(permissions).containsExactly(
            "com.android.alarm.permission.SET_ALARM",
            "android.permission.READ_CONTACTS", "android.permission.CALL_PHONE",
            "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR",
            "android.permission.RECORD_AUDIO", "android.permission.WAKE_LOCK",
            "android.permission.POST_NOTIFICATIONS", "android.permission.USE_EXACT_ALARM", "android.permission.RECEIVE_BOOT_COMPLETED",
        )
        assertThat(permissions).doesNotContain("android.permission.INTERNET")
    }
}
