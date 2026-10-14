package dev.abhay.assistant.onboarding

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The one-time hints that explain what the screens no longer spell out (the UX review of
 * 2026-10-12 took the text off them; see the vault's "Plan - Onboarding"). Each shows once, on
 * the thing it explains, the first time that thing is on screen, in this order of priority; it's
 * gone once dismissed or used.
 */
enum class Tip(val text: String) {
    TryOne("Tap one, or type your own request below."),
    Details("Tap a reply to see what Ferb understood, and why it ran or asked."),
    AskFirst("Ferb asks first when it isn't sure, or when a tool reaches other people. Tap to go ahead, or not."),
    Undo("Changed your mind? The buttons on a result undo it, or turn it off."),
    MoreTools("Five tools are on at first. Turn on calls, messages, the calendar and more in Settings → Tools."),
    Keywords("Requests with these words go to this tool. Tap a word to change it."),
    Answerer("Pick who answers: this phone, or your computer through Ferb Link."),
    OnPhone("Your computer couldn't answer, so your phone did. Tap the reply for why."),
    Mic("Speak English or Hindi: tap the mic, tap again to stop, then send."),
    Heard("What your computer heard. The line above is what Ferb did with it."),
    SuggestLink("Want Ferb to understand more? Ferb Link uses a bigger model on a computer of your own.");

    companion object {
        /** The tip to show among those whose thing is on screen ([showing]): the first not [seen] yet, or none. One at a time. */
        fun next(showing: Collection<Tip>, seen: Set<Tip>): Tip? = entries.firstOrNull { it in showing && it !in seen }
    }
}

/** What onboarding has done so far: the welcome screen passed, and the tips seen. */
data class OnboardingState(val welcomed: Boolean = false, val seen: Set<Tip> = emptySet())

/**
 * The welcome screen and the tips, remembered on the phone ([from]: the app's preferences) and
 * reset from Settings ("Show tips again"), which brings back the welcome screen too. Nothing
 * leaves the phone: there are no analytics.
 */
class Onboarding(private val load: () -> OnboardingState, private val store: (OnboardingState) -> Unit) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun welcomed() = change { it.copy(welcomed = true) }

    /** [tip] was dismissed, or the thing it explains was used. */
    fun seen(tip: Tip) {
        if (tip !in _state.value.seen) change { it.copy(seen = it.seen + tip) }
    }

    /** "Show tips again": the welcome screen and every tip, as on the first launch. */
    fun reset() = change { OnboardingState() }

    private fun change(update: (OnboardingState) -> OnboardingState) {
        _state.update(update)
        store(_state.value)
    }

    companion object {
        private const val PREFS = "onboarding"
        private const val WELCOMED = "welcomed"
        private const val SEEN = "tips_seen"

        fun from(context: Context): Onboarding {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Onboarding(
                load = {
                    OnboardingState(
                        welcomed = prefs.getBoolean(WELCOMED, false),
                        // Tips a newer version dropped are ignored.
                        seen = prefs.getStringSet(SEEN, emptySet()).orEmpty().mapNotNull { name -> Tip.entries.firstOrNull { it.name == name } }.toSet(),
                    )
                },
                store = { s -> prefs.edit().putBoolean(WELCOMED, s.welcomed).putStringSet(SEEN, s.seen.map { it.name }.toSet()).apply() },
            )
        }
    }
}
