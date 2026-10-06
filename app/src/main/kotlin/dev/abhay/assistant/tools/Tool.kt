package dev.abhay.assistant.tools

import android.content.Context

/** What running a call did, shown under it. */
sealed interface Outcome {
    data class Done(val message: String) : Outcome
    data class Failed(val reason: String) : Outcome
}

/** A call's arguments, checked: ready to run (with what it will do, in words), or why not. */
sealed interface Prepared {
    class Ready(val summary: String, val run: (Context) -> Outcome) : Prepared
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
