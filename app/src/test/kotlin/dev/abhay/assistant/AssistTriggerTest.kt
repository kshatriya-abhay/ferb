package dev.abhay.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Review P2-156: the sheet listens at once only for the system's assist trigger. */
class AssistTriggerTest {
    @Test
    fun onlyTheSystemsTriggerListensAtOnce() {
        assertThat(AssistTrigger.fromSystem("com.android.systemui", named = false)).isTrue()
        assertThat(AssistTrigger.fromSystem("android", named = false)).isTrue()
        // Another app in front, sending ACTION_ASSIST while it plays "call mum".
        assertThat(AssistTrigger.fromSystem("com.example.player", named = false)).isFalse()
        // An intent that names its own referrer could name anyone: Ferb can't tell, so no.
        assertThat(AssistTrigger.fromSystem("com.android.systemui", named = true)).isFalse()
        assertThat(AssistTrigger.fromSystem(null, named = false)).isFalse()
    }
}
