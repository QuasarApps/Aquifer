package io.github.quasarapps.aquifer.test

import io.github.quasarapps.aquifer.PersistedEntry
import io.github.quasarapps.aquifer.SourceOfTruth
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * An in-memory [SourceOfTruth] for tests that need to exercise the real engine's **persistence**
 * paths — hydration on a memory miss, write-through, the bulk and enumeration seams — without
 * standing up disk. Hand it to [persistence][io.github.quasarapps.aquifer.AquiferBuilder.persistence]
 * and drive a real `Aquifer` against it, then assert on [entries]; or call its methods directly as a
 * [SourceOfTruth] contract fixture.
 *
 * ```
 * val disk = InMemorySourceOfTruth<String, User>()
 * val users = aquifer<String, User> {
 *     scope(backgroundScope)
 *     fetcher { api.fetchUser(it) }
 *     persistence(disk)
 * }
 *
 * users.get("ada")
 * settle()
 * assertEquals(setOf("ada"), disk.entries.keys) // the fetch wrote through to persistence
 *
 * disk.failWith = IOException("disk full") // now every persistence call fails, e.g. exercising
 * users.put("grace", grace)                // onPersistenceWriteFailed on the failing write-through
 * ```
 *
 * ### Storage & threading
 *
 * Entries live in a [LinkedHashMap] guarded by a plain monitor, so **insertion order is preserved**
 * (both [entries] and [keys] enumerate in write order) and the store is **safe under concurrent
 * use**: the [SourceOfTruth] contract permits overlapping calls from arbitrary threads, and every
 * touch of the backing map is `synchronized`. [entries] returns a defensive copy taken under that
 * monitor, so a snapshot is stable even while other coroutines mutate the store.
 *
 * ### Enumerable
 *
 * This store **can** enumerate its keys, so it overrides [keys]/[keysWhere] to return a non-`null`
 * set (empty when it holds nothing — never `null`, which the contract reserves for "enumeration
 * unsupported"). That is what lets [Aquifer.invalidateWhere][io.github.quasarapps.aquifer.Aquifer.invalidateWhere]
 * reach persisted keys disk-wide when a real store is configured with this persistence. The bulk
 * operations [readAll]/[writeAll]/[deleteMany] are implemented natively over the map rather than as
 * the per-key default loop.
 *
 * ### Injection
 *
 * Two knobs let a test bend the store's timing and reliability, both settable at construction and
 * mutable between calls:
 *
 * - [latency]: when positive, a [delay] of that duration is issued at the **start** of every
 *   operation, so a store's slow-persistence behaviour can be driven under `runTest`'s virtual time.
 * - [failWith]: when non-`null`, every operation throws it **after** any latency delay, so the
 *   engine's failing-store paths (a propagating [write][SourceOfTruth.write] failure, a swallowed
 *   background write-through) can be exercised. Set it back to `null` to resume normal operation.
 *
 * Neither knob affects [entries], which always reports the current contents immediately for
 * assertions.
 */
public class InMemorySourceOfTruth<K : Any, V : Any>(
    latency: Duration = Duration.ZERO,
    failWith: Throwable? = null,
) : SourceOfTruth<K, V> {

    private val monitor = Any()
    private val storage = LinkedHashMap<K, PersistedEntry<V>>()

    /**
     * A [delay] of this duration is issued at the start of every operation when positive, so a test
     * can model slow persistence under `runTest`'s virtual time. Must be non-negative (both here and
     * at construction). Default: [Duration.ZERO], i.e. no delay. Does not affect [entries].
     */
    public var latency: Duration = latency
        set(value) {
            require(value >= Duration.ZERO) { "latency must be non-negative, was $value" }
            field = value
        }

    /**
     * When non-`null`, every operation throws this after any [latency] delay, so a test can exercise
     * the engine's failing-store paths. Set it back to `null` to resume normal operation. Default:
     * `null`. Does not affect [entries].
     */
    public var failWith: Throwable? = failWith

    /**
     * A stable, insertion-ordered snapshot of the current contents, taken under the monitor, for
     * assertions. It is a defensive copy: mutating the store afterwards does not change a snapshot
     * already read, and [latency]/[failWith] never apply to reading it.
     */
    public val entries: Map<K, PersistedEntry<V>>
        get() = synchronized(monitor) { LinkedHashMap(storage) }

    init {
        require(this.latency >= Duration.ZERO) { "latency must be non-negative, was ${this.latency}" }
    }

    /** Applies the injected [latency] delay then the injected [failWith] failure, in that order. */
    private suspend fun gate() {
        val wait = latency
        if (wait > Duration.ZERO) delay(wait)
        failWith?.let { throw it }
    }

    /** Returns the persisted entry for [key], or `null` when none exists. */
    override suspend fun read(key: K): PersistedEntry<V>? {
        gate()
        return synchronized(monitor) { storage[key] }
    }

    /** Persists [entry] for [key], replacing any previous entry; a new key is appended in write order. */
    override suspend fun write(key: K, entry: PersistedEntry<V>) {
        gate()
        synchronized(monitor) { storage[key] = entry }
    }

    /** Removes the entry for [key], if any. */
    override suspend fun delete(key: K) {
        gate()
        synchronized(monitor) { storage.remove(key) }
    }

    /** Removes every entry. */
    override suspend fun deleteAll() {
        gate()
        synchronized(monitor) { storage.clear() }
    }

    /**
     * Returns the persisted entries for every key in [keys] that has one, in [keys]' iteration order;
     * keys with no stored entry are omitted. Reads the whole batch under one monitor acquisition.
     */
    override suspend fun readAll(keys: Collection<K>): Map<K, PersistedEntry<V>> {
        gate()
        return synchronized(monitor) {
            val result = LinkedHashMap<K, PersistedEntry<V>>(keys.size)
            for (key in keys) storage[key]?.let { result[key] = it }
            result
        }
    }

    /** Persists every entry in [entries], replacing any previous entry for those keys, in one batch. */
    override suspend fun writeAll(entries: Map<K, PersistedEntry<V>>) {
        gate()
        synchronized(monitor) { storage.putAll(entries) }
    }

    /** Removes the entry for every key in [keys] that has one; unknown keys are ignored. */
    override suspend fun deleteMany(keys: Collection<K>) {
        gate()
        synchronized(monitor) { for (key in keys) storage.remove(key) }
    }

    /**
     * Every stored key, in write order — non-`null` because this store is enumerable, and empty
     * (never `null`) when it holds nothing.
     */
    override suspend fun keys(): Set<K> {
        gate()
        return synchronized(monitor) { LinkedHashSet(storage.keys) }
    }

    /**
     * The stored keys satisfying [predicate], in write order — non-`null`, like [keys]. The key set
     * is snapshotted under the monitor and [predicate] is evaluated off it, so arbitrary predicate
     * code never runs while the monitor is held.
     */
    override suspend fun keysWhere(predicate: (K) -> Boolean): Set<K> {
        gate()
        val snapshot = synchronized(monitor) { LinkedHashSet(storage.keys) }
        return snapshot.filterTo(LinkedHashSet(), predicate)
    }
}
