package dev.abhay.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.onboarding.Onboarding
import dev.abhay.assistant.onboarding.OnboardingState
import dev.abhay.assistant.onboarding.Tip
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The welcome screen and tips, remembered in the phone's preferences (a test set, not the user's). */
@RunWith(AndroidJUnit4::class)
class OnboardingDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun clean() {
        // Preferences are written asynchronously (apply): let the last write land, then delete.
        Thread.sleep(500)
        context.deleteSharedPreferences(PREFS)
    }

    @Test
    fun rememberedAcrossInstancesAndReset() {
        val first = Onboarding.from(context, PREFS)
        assertThat(first.state.value).isEqualTo(OnboardingState())
        first.welcomed()
        first.seen(Tip.Details)
        first.seen(Tip.Undo)
        // A new instance, as after a restart, reads the same.
        val again = Onboarding.from(context, PREFS)
        assertThat(again.state.value).isEqualTo(OnboardingState(welcomed = true, seen = setOf(Tip.Details, Tip.Undo)))
        again.reset()
        assertThat(Onboarding.from(context, PREFS).state.value).isEqualTo(OnboardingState())
    }

    private companion object {
        const val PREFS = "onboarding_devicetest"
    }
}
