package dev.abhay.assistant.tools

import android.content.Context

/** What running a call did, shown under it. */
sealed interface Outcome {
    /** [actions] are buttons offered next to the result, e.g. "Turn off" after turning the torch on. */
    data class Done(val message: String, val actions: List<FollowUp> = emptyList()) : Outcome
    data class Failed(val reason: String) : Outcome
}

/** A button on a result: [run] does it and gives the new result (with its own buttons, e.g. "Turn on" again). */
class FollowUp(val label: String, val run: (Context) -> Outcome) {
    override fun toString() = "FollowUp($label)"
}

/**
 * A call's arguments, checked: ready to run (with what it will do, in words), or why not.
 * [Ready.opensApp] is true when running it starts another app's screen (an intent), so the next
 * call must wait for the assistant to be back in front.
 */
sealed interface Prepared {
    class Ready(val summary: String, val opensApp: Boolean = true, val run: (Context) -> Outcome) : Prepared
    data class Invalid(val reason: String) : Prepared
}

/**
 * One capability the assistant can be given. Needle 3 only sees [schema] (name, description and
 * parameters); the app checks every argument in [prepare] before acting, because the model's
 * bounds aren't trusted alone.
 */
abstract class Tool(
    /** The function name Needle 3 calls. */
    val name: String,
    /** For the settings screen. */
    val title: String,
    val description: String,
    /** On until the user changes it. */
    val onByDefault: Boolean,
    /** Requests to try, for the empty assistant screen. */
    val examples: List<String>,
    /**
     * The confidence from which its calls run without asking. Lower for harmless actions that a tap
     * undoes (the app checks the arguments either way); Cactus's general advice is 0.7.
     */
    val runFrom: Double = 0.7,
) {
    /** The JSON schema object Needle 3 gets: {"name", "description", "parameters"}. */
    abstract val schema: String

    abstract fun prepare(arguments: Map<String, Any?>, apps: InstalledApps): Prepared
}

/** Reads an integer argument, whether the engine gave it as a number or a numeric string. */
internal fun Map<String, Any?>.int(key: String): Int? = when (val v = this[key]) {
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull()
    else -> null
}

internal fun Map<String, Any?>.text(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

internal fun Map<String, Any?>.bool(key: String): Boolean? = when (val v = this[key]) {
    is Boolean -> v
    is String -> v.trim().lowercase().toBooleanStrictOrNull()
    else -> null
}
