package dev.abhay.assistant.voice

import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide

/**
 * H2's answer from Gemini Nano, typed ([ContentRewrite]): the descriptions are schema-H.json's, as the
 * frozen run on the phone asked them (the content probe's HCommand), word for word.
 */
@Generable(description = "A request to a phone assistant, as one English command and, apart, the user's own words to keep")
data class HCommand(
    @Guide(description = "One short, plain English command for the phone, written from the request (its action, day and time, person and list), or NONE")
    val command: String,
    @Guide(description = "What the user wants written down or sent, copied exactly as said, in the same language and script; only those words, without the tool's words, the person, the list's name, any time or day, or fillers; empty when there is none")
    val content: String,
)
