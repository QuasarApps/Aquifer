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
 */
public interface RetryAfterHint {

    /**
     * The origin's requested minimum wait before the next attempt, or `null` when the failure
     * carries no such instruction (the normal backoff schedule then applies). A non-`null` value
     * overrides the computed backoff for that one attempt and is **not** capped by
     * [maxDelay][RetryConfig.maxDelay]; a non-positive value means "retry immediately".
     */
    public val retryAfter: Duration?
}
