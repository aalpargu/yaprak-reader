package com.yaprak.reader

import com.yaprak.reader.feature.reader.ReaderActivity
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Tests reach reader internals by name. ReaderActivity's members are `internal` (Kotlin mangles their
 * JVM names with the module suffix) and several groups now live in `ReaderActivity*Kt` files as
 * extension functions. This resolves all three forms behind the old getDeclaredMethod call shape.
 */
class ReaderMethod(private val method: Method, private val extension: Boolean) {
    var isAccessible: Boolean
        get() = method.isAccessible
        set(value) { method.isAccessible = value }

    fun invoke(target: Any?, vararg args: Any?): Any? = try {
        if (extension) method.invoke(null, target, *args) else method.invoke(target, *args)
    } catch (wrapped: InvocationTargetException) {
        throw wrapped
    }
}

private val readerExtensionFiles = listOf("Content", "Selection", "IndexedChapter", "Pdf", "PdfPanels", "Display", "Session")
    .map { "com.yaprak.reader.feature.reader.ReaderActivity${it}Kt" }

fun Class<*>.readerMethod(name: String, vararg types: Class<*>?): ReaderMethod {
    @Suppress("UNCHECKED_CAST") val params = types as Array<Class<*>>
    if (!ReaderActivity::class.java.isAssignableFrom(this)) return ReaderMethod(getDeclaredMethod(name, *params), false)
    val names = listOf(name, "$name\$app_debug", "$name\$app_release")
    for (candidate in names) runCatching { return ReaderMethod(getDeclaredMethod(candidate, *params), false) }
    for (file in readerExtensionFiles) {
        val owner = runCatching { Class.forName(file) }.getOrNull() ?: continue
        for (candidate in names) runCatching {
            return ReaderMethod(owner.getDeclaredMethod(candidate, ReaderActivity::class.java, *params), true)
        }
    }
    throw NoSuchMethodException("$name on ReaderActivity or its extension files")
}
