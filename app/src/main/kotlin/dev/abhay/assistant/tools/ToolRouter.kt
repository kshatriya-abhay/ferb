package dev.abhay.assistant.tools

/**
 * Which tools Needle 3 sees for a request. Above five tools it only weighs the five that best match
 * a request, so instead of giving it every tool turned on, the app gives it the ones the request
 * mentions (their [Tool.cues]) and re-initialises when that set changes (about 0.1 s on the phone).
 * A request that mentions none gets every tool turned on, and Needle 3's own retrieval.
 * On tool-check with all nine tools, routing kept 111 of 132 answers right (110 with every tool
 * seen) and raised the confidence of right calls, e.g. opening apps from 0.38–0.95 to 0.64–0.96.
 */
object ToolRouter {
    /** Needle 3 weighs every tool up to this many. */
    const val MAX_SEEN = 5

    /** The tools in [enabled] that [request] mentions, in [enabled]'s order; a tool without cues always is. Empty: none. */
    fun route(request: String, enabled: List<Tool>): List<Tool> =
        enabled.filter { tool -> tool.cues?.containsMatchIn(request) ?: true }

    /**
     * For "Check all tools as a fallback", instead of Needle 3's retrieval: every tool turned on,
     * at most [MAX_SEEN] per group, so it weighs each of them. One engine run per group, so more
     * tools take longer.
     */
    fun groups(enabled: List<Tool>): List<List<Tool>> = enabled.chunked(MAX_SEEN)
}
