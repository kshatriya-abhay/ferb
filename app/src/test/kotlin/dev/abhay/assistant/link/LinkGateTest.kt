package dev.abhay.assistant.link

import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.testing.FakeApp
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Review P2-113: Ferb sends nothing to an app in Ferb Link's place unless it's signed with Ferb's
 * key, as Ferb Link checks Ferb. Every call goes through FerbLink's one provider path.
 */
class LinkGateTest {
    private class Packages(var provider: String? = LinkContract.PACKAGE, var sameKey: Boolean = true) : LinkPackages {
        override fun installed() = true
        override fun providerPackage() = provider
        override fun sameKey() = sameKey
    }

    /** Ferb Link's provider, answering like a set-up one, and remembering what it was sent. */
    private class Transport : LinkTransport {
        val sent = mutableListOf<Pair<String, Map<String, Any>?>>()
        override fun call(method: String, extras: Map<String, Any>?): LinkResult? {
            sent += method to extras
            val values = mapOf<String, Any>(LinkContract.RESULT_VERSION to LinkContract.VERSION, LinkContract.RESULT_ENABLED to true, LinkContract.RESULT_CONFIGURED to true)
            return LinkResult { values[it] }
        }
        override fun hold() = Unit
        override fun release() = Unit
    }

    /** No Wi-Fi service: the low-latency lock is skipped. */
    class NoWifi : ContextWrapper(null) {
        override fun getApplicationContext() = this
        override fun getSystemService(name: String): Any? = null
    }

    private fun link(packages: Packages, transport: Transport) =
        FerbLink(FakeApp.allocate(NoWifi::class.java), packages, transport, clock = { 0L }, log = {})

    private fun everyCall(link: FerbLink): List<String> = runBlocking {
        listOf(
            describe(link.status()),
            link.decide("call mum", "[]", 6_000).toString(),
            link.rewrite("call mum", "Rewrite it.", 64, 10_000, null).toString(),
            link.check().toString(),
            link.transcribe(byteArrayOf(1, 2, 3), "audio/mp4").toString(),
        ).also { link.wake() }
    }

    @Test
    fun anAppWithAnotherKeyIsSentNothing() {
        val transport = Transport()
        val link = link(Packages(sameKey = false), transport)
        val replies = everyCall(link)
        assertThat(transport.sent).isEmpty()
        assertThat(link.status()).isEqualTo(LinkStatus.NotAllowed)
        assertThat(replies.drop(1).all { it.contains("same key") || it.contains("not allowed") }).isTrue()
    }

    @Test
    fun anotherAppHoldingFerbLinksAuthorityIsSentNothing() {
        val transport = Transport()
        val link = link(Packages(provider = "com.example.other"), transport)
        everyCall(link)
        assertThat(transport.sent).isEmpty()
        assertThat(link.status()).isEqualTo(LinkStatus.NotAllowed)
    }

    @Test
    fun ferbLinkWithFerbsKeyIsCalled() {
        val transport = Transport()
        val link = link(Packages(), transport)
        everyCall(link)
        assertThat(transport.sent.map { it.first }).containsExactly(
            LinkContract.METHOD_STATUS, LinkContract.METHOD_DECIDE, LinkContract.METHOD_REWRITE,
            LinkContract.METHOD_CHECK, LinkContract.METHOD_TRANSCRIBE, LinkContract.METHOD_WAKE,
        ).inOrder()
        assertThat(link.status()).isInstanceOf(LinkStatus.Ready::class.java)
    }
}
