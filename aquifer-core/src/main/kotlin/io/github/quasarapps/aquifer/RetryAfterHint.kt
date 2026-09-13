package io.github.quasarapps.aquifer

import kotlin.time.Duration

/**
 * A fetch failure that carries the origin's own instruction for *when to retry* — the value of an
 * HTTP `Retry-After` header on a `429`/`503`, or any transport's equivalent. A failure that
 * implements this participates in the retry loop's delay decision: when [retryAfter] is non-`null`,
 * it **replaces** the computed exponential backoff for that attempt — the store's
 * [maxDelay][RetryConfig.maxDelay] does not cap it, since honouring the server's stated wait is the
 * point — unless a [delayFor][RetryConfig.delayFor] override answers first.
 *
 * `aquifer-core` has no HTTP types, so a transport that speaks `Retry-After` (the `aquifer-okhttp`
 * helpers, say) implements this on the exception it throws, and the engine consults it on every
 * failure it is about to back off from — without depending on the transport. The hint changes only
 * *how long* to wait, never *whether* to retry: [RetryConfig.retryOn] still gates that and
 * [RetryConfig.maxAttempts] still bounds the count.
 *
 * Because a `Retry-After` is advice from an untrusted origin, the honoured wait is bounded by
 * [RetryConfig.maxRetryAfter]: a [retryAfter] longer than that ceiling — or non-finite — is treated
 * as *not retryable*, so the failure surfaces now instead of parking the key on a hostile or
 * misconfigured value. An accessor that throws is caught and defers to the schedule, mirroring
 * [RetryConfig.delayFor].
 *
 * **Implementing from Java:** [retryAfter] is a `kotlin.time.Duration?`, an inline value class, so
 * its getter is name-mangled on the JVM and awkward to override in Java directly. A Java transport
 * is best served by a tiny Kotlin adapter (a `data class` implementing this interface that wraps the
 * Java exception), or by using [RetryConfig.delayFor] instead, which the engine reads the same way.
 */
public interface RetryAfterHint {

    /**
     * The origin's requested minimum wait before the next attempt, or `null` when the failure
     * carries no such instruction (the normal backoff schedule then applies). A non-`null` value
     * overrides the computed backoff for that one attempt and is **not** capped by
     * [maxDelay][RetryConfig.maxDelay], but *is* bounded by [maxRetryAfter][RetryConfig.maxRetryAfter]
     * — a longer or non-finite wait surfaces the failure instead. A non-positive value means "retry
     * immediately" (it is floored to zero for the reported delay).
     */
    public val retryAfter: Duration?
}
