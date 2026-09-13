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
    )

    /**
     * Returns the delay to wait after failed attempt number [attempt] (1-based), or `null`
     * when no further attempt should be made — either because attempts are exhausted or
     * [failure] is not retryable. A throwing [retryOn] predicate counts as not retryable.
     *
     * The delay follows a fixed precedence: a manual [RetryConfig.delayFor] override, then the
     * failure's own [RetryAfterHint], then the computed exponential schedule. The first two are the
     * app's or origin's stated wait and **replace** the schedule outright — [maxDelay] does not cap
     * them; only the computed branch is capped and jittered. The first non-`null` wins; a throwing
     * or `null`-returning override defers to the next.
     */
    fun delayAfter(attempt: Int, failure: Throwable): Duration? {
        if (attempt >= maxAttempts) return null
        val retryable = runCatching { retryOn(failure) }.getOrDefault(false)
        if (!retryable) return null
        // Delay precedence: a manual delayFor override, then the failure's own Retry-After hint,
        // then the computed exponential schedule. The first two are the app's or origin's stated
        // wait and replace the schedule outright (maxDelay does not cap them); the first non-null
        // wins, and a throwing or null-returning override defers to the next.
        return runCatching { delayFor(failure, attempt) }.getOrNull()
            ?: (failure as? RetryAfterHint)?.retryAfter
            ?: computedBackoff(attempt)
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
