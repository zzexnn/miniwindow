package com.lsp.hypersidebar.util

/** Partial geometry updates must not erase the other gesture's native configuration. */
class NativeConfigValues {
    private val values = linkedMapOf<String, Any>()
    @Synchronized fun merge(update: Map<String, Any>): Map<String, Any> {
        values.putAll(update)
        return LinkedHashMap(values)
    }
}
