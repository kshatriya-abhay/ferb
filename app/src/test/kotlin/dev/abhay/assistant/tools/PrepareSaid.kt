package dev.abhay.assistant.tools

import dev.abhay.assistant.engine.ToolCall

/** The call Needle 3 made with [arguments], gated for [request] ([DateGate.apply]) as the app gates it, at [now]. */
fun Tool.gatedCall(request: String, now: java.time.ZonedDateTime, vararg arguments: Pair<String, Any?>): ToolCall {
    val gated = DateGate.apply(name, mapOf(*arguments), request, null, now)
    return ToolCall(name, gated.arguments, gated.time)
}

/** [request] prepared as the app prepares it: the call gated ([gatedCall]) at [PrepareEnv.now], then [Tool.prepareCall] with Ferb's time. */
fun Tool.prepareSaid(request: String, env: PrepareEnv, vararg arguments: Pair<String, Any?>): Prepared {
    val call = gatedCall(request, env.now, *arguments)
    return prepareCall(call, request, env.copy(time = call.time))
}
