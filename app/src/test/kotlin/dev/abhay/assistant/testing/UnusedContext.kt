package dev.abhay.assistant.testing

import android.content.Context
import android.content.ContextWrapper

/** A Context for code that takes one but doesn't use it, made without Android's constructor (no Android in JVM tests). */
fun unusedContext(): Context = FakeApp.allocate(ContextWrapper::class.java)
