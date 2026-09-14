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
 */
public class HttpException(
    public val code: Int,
    public val url: String,
) : IOException("HTTP $code fetching $url"), RetryAfterHint {

    /**
     * The wait the origin requested via this response's `Retry-After` header, or `null` when the
     * header was absent or unusable. A non-negative [Duration] parsed from delta-seconds or an
     * HTTP-date (a past date, or a negative delta, floors to [Duration.ZERO], i.e. "retry now").
     * The public constructor leaves it `null`; the OkHttp fetchers populate it when they throw.
     * See [RetryAfterHint] for how the retry loop consults it.
     */
    override var retryAfter: Duration? = null
        private set

    /**
     * Records a parsed `Retry-After` wait alongside the status. Internal to this module: the OkHttp
     * fetchers call it so a `Retry-After`-bearing failure carries its hint, while the public surface
     * stays the single [code]/[url] constructor plus the read-only [retryAfter]. Pass `null` when
     * there is no header to honour.
     */
    internal constructor(code: Int, url: String, retryAfter: Duration?) : this(code, url) {
        this.retryAfter = retryAfter
    }
}
