package dev.abhay.assistant.tools

/**
 * Which tools Needle 3 sees for a request. Above five tools it only weighs the five that best match
 * a request, so instead of giving it every tool turned on, the app gives it the ones the request
 * mentions (their [ToolConfig.keywords]) and re-initialises when that set changes (about 0.1 s on the phone).
 * A request that mentions none gets every tool turned on, and Needle 3's own retrieval.
 * On tool-check with all nine tools, routing kept 111 of 132 answers right (110 with every tool
 * seen) and raised the confidence of right calls, e.g. opening apps from 0.38–0.95 to 0.64–0.96.
 */
object ToolRouter {
    /** Needle 3 weighs every tool up to this many. */
    const val MAX_SEEN = 5

    /** The tools in [enabled] that [request] mentions (by their keywords in [configs]), in [enabled]'s order. Empty: none. */
    fun route(request: String, enabled: List<Tool>, configs: Map<String, ToolConfig>): List<Tool> =
        enabled.filter { tool -> (configs[tool.name] ?: ToolConfig.defaults(tool)).mentionedIn(request) }

    /**
     * The tools that are off but that [request] mentions: when no tool on could do it, Ferb offers
     * to turn one on ("pause the music" with Media controls off).
     */
    fun off(request: String, configs: Map<String, ToolConfig>): List<Tool> =
        route(request, Tools.all.filter { configs[it.name]?.enabled == false }, configs)
}
