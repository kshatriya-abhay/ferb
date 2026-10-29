package dev.abhay.assistant.ui

import androidx.test.platform.app.InstrumentationRegistry

/** A string resource's text, as the screens show it (the device tests look nodes up by it). */
internal fun str(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
