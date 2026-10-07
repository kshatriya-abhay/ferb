package dev.abhay.assistant.tools

import dev.abhay.assistant.engine.Decision
import dev.abhay.assistant.engine.NeedleReply
import org.json.JSONObject

/**
 * How one tool is set up: whether it's on, whether its calls always ask first, the confidence from
 * which they run without asking, and the tool's own options (by [ToolOption.key]).
 */
data class ToolConfig(
    val enabled: Boolean,
    val alwaysConfirm: Boolean,
    val runFrom: Double,
    val options: Map<String, String> = emptyMap(),
) {
    /** The chosen value of [option], or its default if none (or an unknown one) is saved. */
    fun choice(option: ToolOption.Choice): String =
        options[option.key]?.takeIf { value -> option.items.any { it.id == value } } ?: option.default

    /** Leaves out a bar at [tool]'s default, so a later change of the default reaches it. */
    fun toJson(tool: Tool): String = JSONObject()
        .put("enabled", enabled)
        .put("always_confirm", alwaysConfirm)
        .apply { if (runFrom != tool.defaultRunFrom) put("run_from", runFrom) }
        .put("options", JSONObject(options as Map<*, *>))
        .toString()

    companion object {
        fun defaults(tool: Tool) = ToolConfig(
            enabled = tool.onByDefault,
            alwaysConfirm = false,
            runFrom = tool.defaultRunFrom,
            options = tool.options.associate { it.key to it.default },
        )

        /** Reads [json] for [tool]; anything missing or unreadable falls back to the tool's default. */
        fun fromJson(json: String, tool: Tool): ToolConfig {
            val fallback = defaults(tool)
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return fallback
            val options = o.optJSONObject("options")
            return ToolConfig(
                enabled = o.optBoolean("enabled", fallback.enabled),
                alwaysConfirm = o.optBoolean("always_confirm", false),
                runFrom = o.optDouble("run_from", fallback.runFrom).takeIf { !it.isNaN() && it != OLD_DEFAULT }
                    ?.coerceIn(MIN_RUN_FROM, MAX_RUN_FROM) ?: fallback.runFrom,
                options = fallback.options + (options?.keys()?.asSequence()?.associateWith { options.getString(it) } ?: emptyMap()),
            )
        }

        /** The slider's range: below 0.1 the engine withholds calls anyway; at 1.0 a tool effectively always asks. */
        const val MIN_RUN_FROM = 0.1
        const val MAX_RUN_FROM = 1.0

        /**
         * Until 2026-10-07 most tools' default, and saves kept every bar: a saved 0.3 is almost
         * surely one nobody moved (the slider is continuous), so it means the current default.
         */
        private const val OLD_DEFAULT = 0.3
    }
}

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
        data class Item(val id: String, val label: String, val description: String)
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
 * mentioned in [request] (its [Tool.cues]), or has values the request doesn't say
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
            val called = reply.calls.map { configs[it.name] }
            val runAt = called.maxOfOrNull { it?.runFrom ?: Decision.RUN_AT } ?: Decision.RUN_AT
            val alwaysConfirm = called.any { it?.alwaysConfirm == true }
            val unmentioned = reply.calls.any { call -> Tools.byName(call.name)?.cues?.containsMatchIn(request) == false }
            val ungrounded = reply.calls.any { call -> Tools.byName(call.name)?.grounded(call.arguments, request) == false }
            val decision = when {
                reply.error != null -> Decision.Refuse
                (alwaysConfirm || unmentioned || ungrounded) && reply.calls.isNotEmpty() -> Decision.Confirm
                else -> Decision.of(reply, runAt)
            }
            return Verdict(decision, runAt, alwaysConfirm, unmentioned, ungrounded)
        }
    }
}
