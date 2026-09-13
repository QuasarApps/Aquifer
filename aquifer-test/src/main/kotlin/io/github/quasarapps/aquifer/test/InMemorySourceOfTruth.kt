package io.github.quasarapps.aquifer.test

import io.github.quasarapps.aquifer.PersistedEntry
import io.github.quasarapps.aquifer.SourceOfTruth
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

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
 * disk.failWritesWith = IOException("disk full") // writes now fail; reads still hydrate
 * users.get("grace")                             // the fetch's write-through fails -> onPersistenceWriteFailed
 * ```
 *
 * (`onPersistenceWriteFailed` fires for the *best-effort write-through after a fetch*; a direct
 * [put][io.github.quasarapps.aquifer.Aquifer.put] is all-or-nothing and propagates the failure
 * instead, so the example fetches rather than writes.)
 *
 * ### Storage & threading
 *
 * Entries live in a [LinkedHashMap] guarded by a plain monitor, so **first-write order is
 * preserved** (both [entries] and [keys] enumerate in first-write order — re-writing an existing
 * key keeps its original position) and the store is **safe under concurrent use**: the
 * [SourceOfTruth] contract permits overlapping calls from arbitrary threads, every touch of the
 * backing map is `synchronized`, and the injection knobs ([latency], [failWith], [failReadsWith],
 * [failWritesWith]) are `@Volatile`, so a value one thread assigns is visible to an operation
 * already in flight on another. [entries] returns a defensive copy taken under the monitor, so a
 * snapshot is stable even while other coroutines mutate the store.
 *
 * ### Enumerable
 *
 * This store **can** enumerate its keys, so it overrides [keys]/[keysWhere] to return a non-`null`
 * set (empty when it holds nothing — never `null`, which the contract reserves for "enumeration
 * unsupported"). That is what lets [Aquifer.invalidateWhere][io.github.quasarapps.aquifer.Aquifer.invalidateWhere]
 * reach persisted keys disk-wide when a real store is configured with this persistence. The bulk
 * operations [readAll]/[writeAll]/[deleteMany] are implemented natively over the map rather than as
 * the per-key default loop. (`aquifer-core`'s own test suite deliberately keeps a separate,
 * *non-enumerable* double for the fallback path where `keys()` returns `null`; the two are distinct
 * on purpose.)
 *
 * ### Injection
 *
 * Knobs to bend the store's timing and reliability, all settable at construction and mutable
 * between calls:
 *
 * - [latency]: when positive, a [delay] of that duration is issued at the **start** of every
 *   operation, so a store's slow-persistence behaviour can be driven under `runTest`'s virtual time.
 * - [failWith]: when non-`null`, **every** operation throws it (after any latency delay) — the
 *   "storage is down" case, reaching read and write failures alike.
 * - [failReadsWith] / [failWritesWith]: fail only the reads ([read]/[readAll]/[keys]/[keysWhere])
 *   or only the mutations ([write]/[writeAll]/[delete]/[deleteAll]/[deleteMany]) respectively —
 *   `failWritesWith` is the common "writes fail, reads still hydrate" case, exercising
 *   `onPersistenceWriteFailed` without also blinding hydration. A direction-specific knob takes
 *   precedence over [failWith] for that direction; set the relevant knob back to `null` to resume.
 *
 * None of the knobs affect [entries], which always reports the current contents immediately for
 * assertions.
 */
public class InMemorySourceOfTruth<K : Any, V : Any>(
    latency: Duration = Duration.ZERO,
    failWith: Throwable? = null,
    failReadsWith: Throwable? = null,
    failWritesWith: Throwable? = null,
) : SourceOfTruth<K, V> {

    private val monitor = Any()
    private val storage = LinkedHashMap<K, PersistedEntry<V>>()

    // Nanoseconds behind the Duration accessor: a value class does not take @Volatile cleanly, and
    // the knob must publish across threads (the class KDoc promises overlapping calls see it). A
    // separate infinite bit is the sentinel — not Long.MAX_VALUE, which a large-but-finite Duration
    // also saturates to, so overloading it would make such a latency read back (and park) as infinite.
    @Volatile
    private var latencyNanos: Long = 0L

    @Volatile
    private var latencyInfinite: Boolean = false

    /**
     * A [delay] of this duration is issued at the start of every operation when positive, so a test
     * can model slow persistence under `runTest`'s virtual time. Must be non-negative (both here and
     * at construction). Default: [Duration.ZERO], i.e. no delay. Does not affect [entries].
     *
     * Stored at nanosecond precision behind the scenes, so a value finer than a nanosecond is
     * truncated on read-back and one beyond ~292 years saturates to that ceiling — but a finite
     * latency, however large, still completes once virtual time reaches it. Only [Duration.INFINITE]
     * parks every operation forever (the "storage never responds" knob) and is the one value read
     * back as `INFINITE`.
     */
    public var latency: Duration
        get() = if (latencyInfinite) Duration.INFINITE else latencyNanos.nanoseconds
        set(value) {
            require(value >= Duration.ZERO) { "latency must be non-negative, was $value" }
            // The infinite bit, not the saturated nanos, is what marks "never responds": a finite
            // value past the ~292-year nanos ceiling saturates too, but must still delay (and complete).
            latencyInfinite = value == Duration.INFINITE
            latencyNanos = value.inWholeNanoseconds
        }

    /**
     * When non-`null`, **every** operation throws this after any [latency] delay, so a test can
     * exercise the engine's failing-store paths across reads and writes alike. Overridden per
     * direction by [failReadsWith]/[failWritesWith]. Set it back to `null` to resume. Default:
     * `null`. Does not affect [entries].
     */
    @Volatile
    public var failWith: Throwable? = failWith

    /**
     * When non-`null`, the read operations ([read]/[readAll]/[keys]/[keysWhere]) throw this after
     * any [latency] delay; writes are unaffected. Takes precedence over [failWith] for reads. Set
     * back to `null` to resume. Default: `null`. Does not affect [entries].
     */
    @Volatile
    public var failReadsWith: Throwable? = failReadsWith

    /**
     * When non-`null`, the mutating operations ([write]/[writeAll]/[delete]/[deleteAll]/[deleteMany])
     * throw this after any [latency] delay; reads still hydrate. Takes precedence over [failWith] for
     * writes — the common "writes fail, reads succeed" case for reaching `onPersistenceWriteFailed`.
     * Set back to `null` to resume. Default: `null`. Does not affect [entries].
     */
    @Volatile
    public var failWritesWith: Throwable? = failWritesWith

    /**
     * A stable, insertion-ordered snapshot of the current contents, taken under the monitor, for
     * assertions. It is a defensive copy: mutating the store afterwards does not change a snapshot
     * already read, and no injection knob applies to reading it.
     */
    public val entries: Map<K, PersistedEntry<V>>
        get() = synchronized(monitor) { LinkedHashMap(storage) }

    init {
        // Route the constructor value through the setter so its non-negative check and nanos
        // conversion run — a property initializer would bypass the custom setter.
        this.latency = latency
    }

    /** The read/write direction of an operation, selecting which failure knob [gate] consults. */
    private enum class Access { Read, Write }

    /** Applies the injected [latency] delay then the direction's injected failure, in that order. */
    private suspend fun gate(access: Access) {
        when {
            // INFINITE means "never responds": suspend until cancelled, not delay(...), which is
            // always a finite park a virtual-time jump would blow past — completing the op the caller
            // meant to hang forever. A finite latency, however large, still completes.
            latencyInfinite -> awaitCancellation()
            latencyNanos > 0L -> delay(latencyNanos.nanoseconds)
        }
        val failure = when (access) {
            Access.Read -> failReadsWith ?: failWith
            Access.Write -> failWritesWith ?: failWith
        }
        failure?.let { throw it }
    }

    /** Returns the persisted entry for [key], or `null` when none exists. */
    override suspend fun read(key: K): PersistedEntry<V>? {
        gate(Access.Read)
        return synchronized(monitor) { storage[key] }
    }

    /** Persists [entry] for [key], replacing any previous entry; a new key is appended in write order. */
    override suspend fun write(key: K, entry: PersistedEntry<V>) {
        gate(Access.Write)
        synchronized(monitor) { storage[key] = entry }
    }

    /** Removes the entry for [key], if any. */
    override suspend fun delete(key: K) {
        gate(Access.Write)
        synchronized(monitor) { storage.remove(key) }
    }

    /** Removes every entry. */
    override suspend fun deleteAll() {
        gate(Access.Write)
        synchronized(monitor) { storage.clear() }
    }

    /**
     * Returns the persisted entries for every key in [keys] that has one, in [keys]' iteration order;
     * keys with no stored entry are omitted. Reads the whole batch under one monitor acquisition.
     */
    override suspend fun readAll(keys: Collection<K>): Map<K, PersistedEntry<V>> {
        gate(Access.Read)
        return synchronized(monitor) {
            val result = LinkedHashMap<K, PersistedEntry<V>>(keys.size)
            for (key in keys) storage[key]?.let { result[key] = it }
            result
        }
    }

    /** Persists every entry in [newEntries], replacing any previous entry for those keys, in one batch. */
    override suspend fun writeAll(newEntries: Map<K, PersistedEntry<V>>) {
        gate(Access.Write)
        synchronized(monitor) { storage.putAll(newEntries) }
    }

    /** Removes the entry for every key in [keys] that has one; unknown keys are ignored. */
    override suspend fun deleteMany(keys: Collection<K>) {
        gate(Access.Write)
        synchronized(monitor) { for (key in keys) storage.remove(key) }
    }

    /**
     * Every stored key, in first-write order — non-`null` because this store is enumerable, and
     * empty (never `null`) when it holds nothing.
     */
    override suspend fun keys(): Set<K> {
        gate(Access.Read)
        return synchronized(monitor) { LinkedHashSet(storage.keys) }
    }

    /**
     * The stored keys satisfying [predicate], in first-write order — non-`null`, like [keys]. The
     * key set is snapshotted under the monitor and [predicate] is evaluated off it, so arbitrary
     * predicate code never runs while the monitor is held.
     */
    override suspend fun keysWhere(predicate: (K) -> Boolean): Set<K> {
        gate(Access.Read)
        val snapshot = synchronized(monitor) { LinkedHashSet(storage.keys) }
        return snapshot.filterTo(LinkedHashSet(), predicate)
    }
}
