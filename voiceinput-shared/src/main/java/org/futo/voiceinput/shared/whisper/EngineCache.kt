package org.futo.voiceinput.shared.whisper

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe loading cache with single-flight semantics: a value for each key
 * is loaded AT MOST ONCE, even when multiple threads race [getOrLoad] — the
 * losers block until the winner's load completes and then share its result.
 * A failed load is not cached, so the next caller retries.
 *
 * Pure JVM (no Android dependencies) so the concurrency contract is unit-testable.
 */
class EngineCache<K : Any, V> {
    private val loaded = ConcurrentHashMap<K, V>()

    fun getOrLoad(key: K, loader: () -> V): V = loaded.computeIfAbsent(key) { loader() }

    /** Returns the value only if it is ALREADY loaded — never triggers a load. */
    fun peek(key: K): V? = loaded[key]

    val entries: Set<Map.Entry<K, V>> get() = loaded.entries

    fun clear() = loaded.clear()
}
