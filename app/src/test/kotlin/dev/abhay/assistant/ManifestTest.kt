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
        val main = elements("activity").single { it.getAttributeNS(android, "name") == ".MainActivity" }
        assertThat(main.getAttributeNS(android, "exported")).isEqualTo("true")
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
    fun thePermissionsAreTheToolsOwnAndNeverInternet() {
        val permissions = elements("uses-permission").map { it.getAttributeNS(android, "name") }
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
