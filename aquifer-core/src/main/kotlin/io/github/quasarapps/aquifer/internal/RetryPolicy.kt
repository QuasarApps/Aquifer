package io.github.quasarapps.aquifer.internal

import io.github.quasarapps.aquifer.RetryAfterHint
import io.github.quasarapps.aquifer.RetryConfig
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration

/** Immutable, testable snapshot of a [RetryConfig] that computes per-attempt delays. */
@Suppress("LongParameterList") // Mirrors RetryConfig's knobs one-to-one; bundling would just duplicate it.
internal class RetryPolicy(
    private val maxAttempts: Int,
    private val initialDelay: Duration,
    private val maxDelay: Duration,
    private val multiplier: Double,
    private val jitter: Double,
    private val retryOn: (Throwable) -> Boolean,
    private val delayFor: (Throwable, Int) -> Duration? = { _, _ -> null },
    private val maxRetryAfter: Duration = RetryConfig().maxRetryAfter,
    private val random: Random = Random.Default,
) {

    constructor(config: RetryConfig) : this(
        maxAttempts = config.maxAttempts,
        initialDelay = config.initialDelay,
        maxDelay = config.maxDelay,
        multiplier = config.multiplier,
        jitter = config.jitter,
        retryOn = config.retryOn,
        delayFor = config.delayFor,
        maxRetryAfter = config.maxRetryAfter,
    )

    /**
     * Returns the delay to wait after failed attempt number [attempt] (1-based), or `null`
     * when no further attempt should be made — either because attempts are exhausted or
     * [failure] is not retryable. A throwing [retryOn] predicate counts as not retryable.
     *
     * The delay follows a fixed precedence: a manual [RetryConfig.delayFor] override, then the
     * failure's own [RetryAfterHint], then the computed exponential schedule. The first two are the
     * app's or origin's stated wait and **replace** the schedule — [maxDelay] does not cap them,
     * only the computed branch is capped and jittered. A stated wait is instead bounded by
     * [RetryConfig.maxRetryAfter]: one longer than that, or non-finite, is treated as *not
     * retryable* (returns `null`, surfacing the failure) rather than parking the key on untrusted
     * advice; a negative one is floored to zero. A throwing or `null`-returning override — including
     * a [RetryAfterHint] whose accessor throws — defers to the next in the precedence.
     */
    fun delayAfter(attempt: Int, failure: Throwable): Duration? {
        if (attempt >= maxAttempts) return null
        val retryable = runCatching { retryOn(failure) }.getOrDefault(false)
        if (!retryable) return null
        val stated = runCatching { delayFor(failure, attempt) }.getOrNull()
            ?: runCatching { (failure as? RetryAfterHint)?.retryAfter }.getOrNull()
        return when {
            stated == null -> computedBackoff(attempt)
            // Honour a stated wait uncapped by maxDelay, but bound it by maxRetryAfter: a wait
            // longer than the app's ceiling, or non-finite, means give up so the failure surfaces
            // now instead of the key parking on an untrusted (or buggy) Retry-After.
            stated.isFinite() && stated <= maxRetryAfter -> stated.coerceAtLeast(Duration.ZERO)
            else -> null
        }
    }

    /** The exponential-backoff delay for [attempt], capped at [maxDelay] and shortened by [jitter]. */
    private fun computedBackoff(attempt: Int): Duration {
        val base = (initialDelay * multiplier.pow(attempt - 1)).coerceAtMost(maxDelay)
        // Jitter only ever shortens the delay, so maxDelay remains a hard cap.
        return base * (1.0 - jitter * random.nextDouble())
    }

    companion object {
        /** Policy matching the default [RetryConfig]: a single attempt, no retries. */
        val NONE = RetryPolicy(RetryConfig())
    }
}
