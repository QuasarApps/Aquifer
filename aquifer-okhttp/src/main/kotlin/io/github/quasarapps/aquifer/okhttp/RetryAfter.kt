package io.github.quasarapps.aquifer.okhttp

import okhttp3.Response
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Parses the `Retry-After` header of [response] into the wait it asks for, or `null` when the header
 * is absent or unusable. Both forms defined by the HTTP spec are accepted: delta-seconds
 * (`Retry-After: 120`) becomes that many seconds, and an HTTP-date
 * (`Retry-After: Wed, 21 Oct 2099 07:28:00 GMT`) becomes the gap from the response's own `Date`
 * header to that instant. Every result is floored at [Duration.ZERO] — a past date, or a negative
 * delta, means "retry now" — and any parse failure is absorbed to `null` rather than failing the
 * fetch, since the header is advisory. Delta-seconds is tried first, so a bare number is never
 * misread as a (failing) date.
 *
 * The HTTP-date form requires a `Date` header to anchor against, and returns `null` (deferring to the
 * computed backoff) when there is none. The alternative — measuring against the client's receipt
 * clock ([Response.receivedResponseAtMillis]) — is unsafe *here* in a way it is not for
 * [parseServerFreshness]'s `Expires`: a client clock running slow inflates the gap, and a gap past
 * `maxRetryAfter` is treated as *not* retryable, so skew could silently turn a retryable failure into
 * a hard one. An advisory header must never make things worse than no header, so a `Date`-less
 * HTTP-date is dropped rather than guessed. (`Date` is present on virtually all responses; this is
 * the rare fallback, not the common path.)
 *
 * One deliberate non-`null`: an all-digit delta-seconds too large for a `Long` is a *valid* (if
 * absurd) wait, so it becomes [Duration.INFINITE] rather than `null`. A `null` would fall through to
 * the retry loop's short computed backoff; [Duration.INFINITE] instead trips the `maxRetryAfter`
 * ceiling as a non-finite, over-limit wait, so an unbounded numeric header surfaces the failure —
 * the very outcome that ceiling exists for — rather than sneaking past it.
 */
internal fun parseRetryAfter(response: Response): Duration? = runCatching {
    val raw = response.header("Retry-After")?.trim().orEmpty()
    if (raw.isEmpty()) return@runCatching null
    raw.toLongOrNull()?.let { return@runCatching it.coerceAtLeast(0L).seconds }
    // Not representable as a Long. An all-digit value is an overflowing delta-seconds: surface it as
    // a non-finite wait so it hits the maxRetryAfter ceiling instead of the short backoff. Anything
    // else is an HTTP-date (or unusable).
    if (raw.all { it in '0'..'9' }) return@runCatching Duration.INFINITE
    val dateMillis = response.headers.getDate("Retry-After")?.time ?: return@runCatching null
    // Anchor on the server's own Date; without it, don't guess against the client clock (see KDoc).
    val referenceMillis = response.headers.getDate("Date")?.time ?: return@runCatching null
    (dateMillis - referenceMillis).coerceAtLeast(0L).milliseconds
}.getOrNull()
