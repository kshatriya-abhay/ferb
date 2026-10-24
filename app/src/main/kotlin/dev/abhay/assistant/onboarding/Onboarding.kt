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
    Fallback("A step couldn't be used this time, so Ferb went on without it. Tap the reply for why."),
    Mic("Tap the mic and speak, then \u2713 to send or \u2715 to cancel. Whistle on this phone hears English."),
    Heard("What Ferb heard. The line above is what it understood."),
    SuggestLink("Want Ferb to understand more? Ferb Link uses a bigger model on a computer of your own.");

    companion object {
        /** The tip to show among those whose thing is on screen ([showing]): the first not [seen] yet, or none. One at a time. */
        fun next(showing: Collection<Tip>, seen: Set<Tip>): Tip? = entries.firstOrNull { it in showing && it !in seen }
    }
}

/** What onboarding has done so far: the welcome and the steps' pages passed ([Page]), and the tips seen. */
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

    /** "Run setup again": the welcome and the steps' pages, keeping the tips seen. */
    fun rerun() = change { it.copy(welcomed = false) }

    private fun change(update: (OnboardingState) -> OnboardingState) {
        _state.update(update)
        store(_state.value)
    }

    companion object {
        private const val PREFS = "onboarding"
        private const val WELCOMED = "welcomed"
        private const val SEEN = "tips_seen"

        /** [prefs]: device tests use their own, so the user's progress is untouched. */
        fun from(context: Context, prefsName: String = PREFS): Onboarding {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
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

/**
 * The first launch's pages, in order: what Ferb is, then one page per step of how it understands
 * you (each with its options, the recommended one picked), then making Ferb the phone's assistant
 * (left out once it is). Every page can be skipped; Settings → Run setup again shows them again.
 */
enum class Page {
    Welcome, Audio, Rewrite, Tools, Assistant;

    companion object {
        /** The pages to show: [Assistant] only while Ferb isn't the phone's assistant yet. */
        fun shown(assistantHeld: Boolean): List<Page> = entries.filter { it != Assistant || !assistantHeld }

        /**
         * The page after [page], or null at the end (the chat). A page no longer shown (Ferb made
         * the assistant from its own page) is the end too.
         */
        fun next(page: Page, assistantHeld: Boolean): Page? = shown(assistantHeld).let { pages ->
            pages.indexOf(page).takeIf { it >= 0 }?.let { pages.getOrNull(it + 1) }
        }

        /** The page before [page], or null at the first (back leaves the app, as on any first screen). */
        fun previous(page: Page, assistantHeld: Boolean): Page? = shown(assistantHeld).let { pages ->
            pages.indexOf(page).takeIf { it >= 0 }?.let { pages.getOrNull(it - 1) } ?: pages.last().takeIf { page !in pages && page == Assistant }
        }
    }
}
