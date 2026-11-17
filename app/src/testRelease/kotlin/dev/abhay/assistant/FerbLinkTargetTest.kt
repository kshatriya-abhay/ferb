package dev.abhay.assistant

import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.link.LinkContract
import org.junit.Test

/**
 * A release build calls the real Ferb Link, and P2-113's guard checks that package: debug builds
 * call Ferb Link's smoke build (app/build.gradle.kts), and that must never reach a release.
 */
class FerbLinkTargetTest {
    @Test
    fun releaseCallsTheRealFerbLink() {
        assertThat(LinkContract.PACKAGE).isEqualTo("dev.abhay.ferblink")
        assertThat(LinkContract.AUTHORITY).isEqualTo("dev.abhay.ferblink.link")
    }
}
