package io.github.quasarapps.aquifer

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Retry behaviour for failed fetches, configured via [AquiferBuilder.retry]:
 *
 * ```
 * val users = aquifer<UserId, User> {
 *     fetcher { api.fetchUser(it) }
 *     retry {
 *         maxAttempts = 3
 *         initialDelay = 250.milliseconds
 *         retryOn = { it is IOException }
 *     }
 * }
 * ```
 *
 * Delays grow exponentially — `initialDelay`, then `initialDelay * multiplier`, and so on —
 * capped at [maxDelay]. [jitter] then randomly *shortens* each delay by up to that fraction,
 * de-synchronising clients that fail in lockstep without ever exceeding the configured cap. That
 * describes the *computed* schedule only: a [delayFor] override or a failure's [RetryAfterHint]
 * replaces the computed delay for an attempt and is **not** capped by [maxDelay] (see [delayFor]).
 *
 * Retrying happens inside the shared single-flight fetch: observers see one `Loading` state
 * for the whole cycle and one `Failure` if every attempt fails. Individual attempts are
 * reported to [AquiferEvents.onFetchRetried]. Cancellation is never retried.
 */
@AquiferDsl
public class RetryConfig internal constructor() {

    /**
     * Total number of attempts, including the first one. The default of 1 means failures are
     * not retried. Must be at least 1.
     */
    public var maxAttempts: Int = 1
        set(value) {
            require(value >= 1) { "maxAttempts must be at least 1, was $value" }
            field = value
        }

    /** Delay before the first retry. Must be positive and finite. Defaults to 250 ms. */
    public var initialDelay: Duration = 250.milliseconds
        set(value) {
            require(value.isPositive() && value.isFinite()) {
                "initialDelay must be positive and finite, was $value"
            }
            field = value
        }

    /**
     * Upper bound for any single *computed backoff* delay. Must be positive and finite. Defaults to
     * 30 s. The override paths — a failure's [RetryAfterHint] and [delayFor] — deliberately bypass
     * this cap, so a server- or app-stated wait is honoured in full.
     */
    public var maxDelay: Duration = 30.seconds
        set(value) {
            require(value.isPositive() && value.isFinite()) {
                "maxDelay must be positive and finite, was $value"
            }
            field = value
        }

    /** Factor applied to the delay after each failed attempt. Must be ≥ 1. Defaults to 2. */
    public var multiplier: Double = 2.0
        set(value) {
            require(value >= 1.0) { "multiplier must be >= 1.0, was $value" }
            field = value
        }

    /**
     * Fraction (0..1) by which each delay is randomly shortened. 0 disables jitter; the
     * default 0.5 draws each delay uniformly from the upper half of its exponential value.
     */
    public var jitter: Double = 0.5
        set(value) {
            require(value in 0.0..1.0) { "jitter must be within 0.0..1.0, was $value" }
            field = value
        }

    /**
     * Decides whether a failure is worth retrying — return `true` to retry. Defaults to
     * retrying everything. A predicate that itself throws is treated as `false`.
     * Cancellation is never passed to this predicate.
     */
    public var retryOn: (Throwable) -> Boolean = { true }

    /**
     * A manual per-failure delay override: given the [Throwable] and the 1-based number of the
     * [attempt] that just failed, return the delay to wait before the next attempt, or `null` to
     * defer to the normal decision. It sits at the **top** of the delay precedence —
     * `delayFor` → the failure's [RetryAfterHint] → the computed exponential schedule — so an app
     * can override even a server-sent `Retry-After`, or supply one for a transport that carries the
     * header some other way. A returned delay **replaces** the computed backoff and is *not* capped
     * by [maxDelay]; a non-positive delay retries immediately.
     *
     * This decides only *how long* to wait, never *whether* to retry: [retryOn] still gates that
     * and [maxAttempts] still bounds the count. An override that itself throws is treated as `null`
     * (defer). Like [retryOn], it is shared across keys and may be invoked **concurrently** for
     * different keys retrying at once, so it must be safe for concurrent use — keep it pure, or
     * synchronise any state it touches. Defaults to always deferring, so behaviour is unchanged
     * unless set.
     */
    public var delayFor: (throwable: Throwable, attempt: Int) -> Duration? = { _, _ -> null }
}
