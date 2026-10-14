package dev.abhay.assistant.testing

import android.content.Context
import android.content.ContextWrapper

/** A Context for code that takes one but doesn't use it, made without Android's constructor (no Android in JVM tests). */
fun unusedContext(): Context {
    val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, ContextWrapper::class.java) as Context
}
