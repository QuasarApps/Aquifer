package io.github.quasarapps.aquifer.okhttp

import io.github.quasarapps.aquifer.RetryAfterHint
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class RetryAfterTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    private val fetcher = okHttpFetcher<String, String>(
        callFactory = client,
        request = { key -> Request.Builder().url(server.url("/items/$key")).build() },
        parse = { _, body -> body.string() },
    )

    private val conditionalFetcher = okHttpConditionalFetcher<String, String>(
        callFactory = client,
        request = { key -> Request.Builder().url(server.url("/items/$key")).build() },
        parse = { _, body -> body.string() },
    )

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.shutdown()
    }

    /** Enqueues [response], runs the plain fetcher, and returns the failure's parsed `Retry-After`. */
    private suspend fun retryAfterFrom(response: MockResponse): Duration? {
        server.enqueue(response)
        return assertFailsWith<HttpException> { fetcher("k") }.retryAfter
    }

    @Test
    fun `a delta-seconds Retry-After becomes that many seconds`() = runTest {
        val wait = retryAfterFrom(MockResponse().setResponseCode(503).setHeader("Retry-After", "120"))

        assertEquals(120.seconds, wait)
    }

    @Test
    fun `a past HTTP-date Retry-After floors to zero`() = runTest {
        // Anchored on the response's own Date, so the gap is deterministic (a 2015 target against a
        // 2024 Date is negative → zero) rather than dependent on the wall clock at receipt.
        val wait = retryAfterFrom(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Date", "Mon, 01 Jan 2024 00:00:00 GMT")
                .setHeader("Retry-After", "Wed, 21 Oct 2015 07:28:00 GMT"),
        )

        assertEquals(Duration.ZERO, wait)
    }

    @Test
    fun `a future HTTP-date Retry-After is the exact gap from the response Date`() = runTest {
        val wait = retryAfterFrom(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Date", "Mon, 01 Jan 2024 00:00:00 GMT")
                .setHeader("Retry-After", "Mon, 01 Jan 2024 01:00:00 GMT"),
        )

        assertEquals(1.hours, wait)
    }

    @Test
    fun `a negative delta-seconds floors to zero`() = runTest {
        val wait = retryAfterFrom(MockResponse().setResponseCode(503).setHeader("Retry-After", "-5"))

        assertEquals(Duration.ZERO, wait)
    }

    @Test
    fun `an all-digit Retry-After too large for Long surfaces as an infinite wait`() = runTest {
        // A valid-but-absurd delta-seconds that overflows Long must not fall through to null (and the
        // short computed backoff); it becomes a non-finite wait that trips the maxRetryAfter ceiling.
        // Uses 429 rather than 503: OkHttp's own RetryAndFollowUpInterceptor parses a 503's numeric
        // Retry-After (Integer.valueOf) and would throw on the overflow before the fetcher sees it,
        // whereas a 429 — the canonical Retry-After code — passes straight through to our parser.
        val wait = retryAfterFrom(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "99999999999999999999999999"),
        )

        assertEquals(Duration.INFINITE, wait)
    }

    @Test
    fun `an absent Retry-After leaves retryAfter null`() = runTest {
        assertNull(retryAfterFrom(MockResponse().setResponseCode(500).setBody("boom")))
    }

    @Test
    fun `an unparseable Retry-After leaves retryAfter null`() = runTest {
        assertNull(retryAfterFrom(MockResponse().setResponseCode(503).setHeader("Retry-After", "soon-ish")))
    }

    @Test
    fun `the conditional fetcher also parses Retry-After`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "30"))

        val failure = assertFailsWith<HttpException> { conditionalFetcher("k", null) }

        assertEquals(30.seconds, failure.retryAfter)
    }

    @Test
    fun `HttpException surfaces the wait through the RetryAfterHint contract`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "10"))

        val hint: RetryAfterHint = assertFailsWith<HttpException> { fetcher("k") }

        assertEquals(10.seconds, hint.retryAfter)
    }
}
