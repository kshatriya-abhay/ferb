package dev.abhay.assistant.tools

import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import org.json.JSONArray
import org.json.JSONObject

/**
 * How one tool is set up: whether it's on, whether its calls always ask first, the confidence from
 * which they run without asking, the tool's own options (by [ToolOption.key]), and the [keywords]
 * that send a request to it ([Tool.keywords] until the user changes them).
 */
data class ToolConfig(
    val enabled: Boolean,
    val alwaysConfirm: Boolean,
    val runFrom: Double,
    val options: Map<String, String> = emptyMap(),
    val keywords: List<String> = emptyList(),
    /** [Tool.ruledOutBy]: not saved, always the tool's. */
    val ruledOutBy: String? = null,
) {
    /** [keywords] as a matcher, less [ruledOutBy]; null if there are none, so no request mentions the tool. */
    val cues: Regex? by lazy { Keywords.regex(keywords, ruledOutBy) }

    /** Whether [request] contains one of the [keywords]. */
    fun mentionedIn(request: String): Boolean = cues?.containsMatchIn(request) == true

    /** The chosen value of [option], or its default if none (or an unknown one) is saved. */
    fun choice(option: ToolOption.Choice): String =
        options[option.key]?.takeIf { value -> option.items.any { it.id == value } } ?: option.default

    /** Leaves out a bar and keywords at [tool]'s defaults, so a later change of the default reaches them. */
    fun toJson(tool: Tool): String = JSONObject()
        .put("v", VERSION)
        .put("enabled", enabled)
        .put("always_confirm", alwaysConfirm)
        .apply { if (runFrom != tool.defaultRunFrom) put("run_from", runFrom) }
        .apply { if (keywords != tool.keywords) put("keywords", JSONArray(keywords)) }
        .put("options", JSONObject(options as Map<*, *>))
        .toString()

    companion object {
        fun defaults(tool: Tool) = ToolConfig(
            enabled = tool.onByDefault,
            alwaysConfirm = tool.confirmByDefault,
            runFrom = tool.defaultRunFrom,
            options = tool.options.associate { it.key to it.default },
            keywords = tool.keywords,
            ruledOutBy = tool.ruledOutBy,
        )

        /** Reads [json] for [tool]; anything missing or unreadable falls back to the tool's default. */
        fun fromJson(json: String, tool: Tool): ToolConfig {
            val fallback = defaults(tool)
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return fallback
            val options = o.optJSONObject("options")
            return ToolConfig(
                enabled = o.optBoolean("enabled", fallback.enabled),
                alwaysConfirm = o.optBoolean("always_confirm", fallback.alwaysConfirm),
                runFrom = o.optDouble("run_from", fallback.runFrom).takeIf { !it.isNaN() && (o.has("v") || it != OLD_DEFAULT) }
                    ?.coerceIn(MIN_RUN_FROM, MAX_RUN_FROM) ?: fallback.runFrom,
                options = fallback.options + (options?.keys()?.asSequence()?.associateWith { options.getString(it) } ?: emptyMap()),
                // An empty saved list is the user's choice (no keywords), not a missing one.
                keywords = o.optJSONArray("keywords")?.let { words -> (0 until words.length()).mapNotNull { Keywords.clean(words.optString(it)) }.distinct() }
                    ?: fallback.keywords,
                ruledOutBy = fallback.ruledOutBy,
            )
        }

        /** The slider's range: below 0.1 the engine withholds calls anyway; at 1.0 a tool effectively always asks. */
        const val MIN_RUN_FROM = 0.1
        const val MAX_RUN_FROM = 1.0

        /**
         * Until 2026-10-07 most tools' default, and saves kept every bar: in a save from before
         * then (without [VERSION]), a 0.3 is almost surely one nobody moved, so it means the
         * current default. In a later save it's a choice: the slider stops at 0.30.
         */
        private const val OLD_DEFAULT = 0.3

        /** Written in every save since 2026-11-16, so a bar of 0.3 isn't taken for [OLD_DEFAULT]. */
        private const val VERSION = 2
    }
}

/**
 * [tool]'s config, or its defaults when none is saved: the one answer for a missing config, for the
 * router, the verdict and the view model alike ([ToolSettings] keeps every tool's, so this is rare).
 */
fun Map<String, ToolConfig>.of(tool: Tool): ToolConfig = this[tool.name] ?: ToolConfig.defaults(tool)

/** A setting of one tool's own, beyond the ones every tool has. */
sealed interface ToolOption {
    val key: String
    val title: String
    val default: String

    /** One of several [items], e.g. which clock app alarms go to. */
    data class Choice(
        override val key: String,
        override val title: String,
        val items: List<Item>,
        override val default: String,
        /** Shown under the choices, e.g. that more are coming. */
        val note: String? = null,
    ) : ToolOption {
        /** [needsObsidian]: offered only with Obsidian installed (Markdown notes open there). */
        data class Item(val id: String, val label: String, val description: String, val needsObsidian: Boolean = false)
    }

    /** The notes folder in the user's vault ([dev.abhay.assistant.notes.SafNotes]), picked once and kept outside the tools' draft. */
    data class NotesFolder(override val key: String, override val title: String) : ToolOption {
        override val default = ""
    }

    /** One of the phone's calendars that can take events, listed when the page opens; [default] "" is the account's own ([CalendarBook.target]). */
    data class Calendar(override val key: String, override val title: String) : ToolOption {
        override val default = ""
    }
}

/** Where alarms and timers go. One provider today; the choice is there so others can be added. */
object ClockProviders {
    const val SYSTEM_CLOCK = "system_clock"

    val option = ToolOption.Choice(
        key = "clock",
        title = "Clock",
        items = listOf(
            ToolOption.Choice.Item(SYSTEM_CLOCK, "Clock app", "Change or delete them there"),
        ),
        default = SYSTEM_CLOCK,
        note = "More clocks coming later.",
    )
}

/**
 * Whether a reply runs without asking: never if any tool it calls is set to always confirm, isn't
 * mentioned in [request] (its [ToolConfig.keywords]), or has values the request doesn't say
 * ([Tool.grounded]); otherwise when the confidence reaches the highest bar among those tools.
 * [runAt] is that bar.
 */
data class Verdict(
    val decision: Decision,
    val runAt: Double,
    val alwaysConfirm: Boolean,
    val unmentioned: Boolean = false,
    val ungrounded: Boolean = false,
) {
    companion object {
        fun of(reply: NeedleReply, configs: Map<String, ToolConfig>, request: String): Verdict {
            // A call to a tool Ferb doesn't have has no config: it's judged by the default bar, and fails its checks.
            val called = reply.calls.map { call -> Tools.byName(call.name)?.let(configs::of) }
            val runAt = called.maxOfOrNull { it?.runFrom ?: Decision.RUN_AT } ?: Decision.RUN_AT
            val alwaysConfirm = called.any { it?.alwaysConfirm == true }
            val unmentioned = called.any { it?.mentionedIn(request) == false }
            // A time Ferb asks about (the call's [dev.abhay.assistant.engine.ToolCall.time]) asks too.
            val ungrounded = reply.calls.any { call -> call.time?.ask != null || Tools.byName(call.name)?.grounded(call.arguments, request) == false }
            val decision = when {
                reply.error != null -> Decision.Refuse
                (alwaysConfirm || unmentioned || ungrounded) && reply.calls.isNotEmpty() -> Decision.Confirm
                else -> Decision.of(reply, runAt)
            }
            return Verdict(decision, runAt, alwaysConfirm, unmentioned, ungrounded)
        }
    }
}
