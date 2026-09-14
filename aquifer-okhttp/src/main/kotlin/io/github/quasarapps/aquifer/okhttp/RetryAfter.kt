package io.github.quasarapps.aquifer.okhttp

import okhttp3.Response
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Parses the `Retry-After` header of [response] into the wait it asks for, or `null` when the header
 * is absent or unusable. Both forms defined by the HTTP spec are accepted: delta-seconds
 * (`Retry-After: 120`) becomes that many seconds, and an HTTP-date
 * (`Retry-After: Wed, 21 Oct 2099 07:28:00 GMT`) becomes the gap from when the response was received
 * ([Response.receivedResponseAtMillis]) to that instant. Every result is floored at [Duration.ZERO]
 * — a past date, or a negative delta, means "retry now" — and any parse failure is absorbed to `null`
 * rather than failing the fetch, since the header is advisory. Delta-seconds is tried first, so a
 * bare number is never misread as a (failing) date.
 */
internal fun parseRetryAfter(response: Response): Duration? = runCatching {
    val raw = response.header("Retry-After")?.trim().orEmpty()
    if (raw.isEmpty()) return@runCatching null
    raw.toLongOrNull()?.let { return@runCatching it.coerceAtLeast(0L).seconds }
    val dateMillis = response.headers.getDate("Retry-After")?.time ?: return@runCatching null
    (dateMillis - response.receivedResponseAtMillis).coerceAtLeast(0L).milliseconds
}.getOrNull()
