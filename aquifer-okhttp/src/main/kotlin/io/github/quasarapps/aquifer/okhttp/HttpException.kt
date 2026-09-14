package io.github.quasarapps.aquifer.okhttp

import io.github.quasarapps.aquifer.RetryAfterHint
import java.io.IOException
import kotlin.time.Duration

/**
 * Thrown by [okHttpConditionalFetcher] when a response is neither a success (2xx) nor a
 * `304 Not Modified`, and by [okHttpFetcher] on any non-2xx response. It carries the HTTP [code]
 * so Aquifer's resilience policies can branch on the status instead of a flattened string:
 * for example retry only server errors
 * (`retry { retryOn = { it is HttpException && it.code in 500..599 } }`), or treat a `404`
 * as a terminal miss rather than a retryable error.
 *
 * It extends [IOException], so with no extra configuration it flows through Aquifer's normal
 * fetch-failure path (retry policy, `DataState.Failure`, stale-if-error) exactly like any
 * other I/O failure.
 *
 * It also implements [RetryAfterHint]. When the offending response carried a `Retry-After` header
 * — as a `429` or `503` typically does — both OkHttp fetchers parse it (delta-seconds *or* an
 * HTTP-date) onto [retryAfter], and the retry loop then waits that long instead of its computed
 * backoff for that attempt, bounded by the store's `maxRetryAfter` ceiling. When the header is
 * absent or unparseable [retryAfter] is `null` and the normal exponential schedule applies.
 *
 * @property code the HTTP status code of the offending response.
 * @property url the request URL that produced it, included for diagnostics.
 * @property retryAfter the wait the origin requested via this response's `Retry-After` header, or
 *   `null` when the header was absent or unusable — a non-negative [Duration] from delta-seconds or
 *   an HTTP-date (a past date or negative delta floors to [Duration.ZERO], i.e. "retry now"; an
 *   unrepresentably large delta-seconds becomes [Duration.INFINITE], which the ceiling rejects). The
 *   public constructor leaves it `null`; the OkHttp fetchers attach it when they throw. It is
 *   `@Transient`: a `Retry-After` describes a wait already elapsing, so it is deliberately dropped if
 *   this exception is Java-serialized (a [Duration] is not itself `Serializable`). See
 *   [RetryAfterHint] for how the retry loop consults it.
 */
public class HttpException internal constructor(
    public val code: Int,
    public val url: String,
    @Transient override val retryAfter: Duration?,
) : IOException("HTTP $code fetching $url"), RetryAfterHint {

    /**
     * The locked public constructor: a failure with no `Retry-After` hint. The OkHttp fetchers use
     * the internal three-argument primary constructor to attach a parsed hint when the response
     * carried the header.
     */
    public constructor(code: Int, url: String) : this(code, url, null)
}
