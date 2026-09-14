package io.github.quasarapps.aquifer.okhttp

import io.github.quasarapps.aquifer.RetryAfterHint
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
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
        val wait = retryAfterFrom(
            MockResponse().setResponseCode(503).setHeader("Retry-After", "Wed, 21 Oct 2015 07:28:00 GMT"),
        )

        assertEquals(Duration.ZERO, wait)
    }

    @Test
    fun `a future HTTP-date Retry-After becomes the gap until then`() = runTest {
        val oneHourOut = ZonedDateTime.now(ZoneOffset.UTC).plusHours(1).format(DateTimeFormatter.RFC_1123_DATE_TIME)

        val wait = retryAfterFrom(MockResponse().setResponseCode(503).setHeader("Retry-After", oneHourOut))

        // Measured against the response's receipt instant, so a hair under a full hour; wide window
        // keeps it robust on a slow runner while still proving the date arithmetic ran.
        assertTrue(wait != null && wait in 3540.seconds..3600.seconds, "expected ~1h, was $wait")
    }

    @Test
    fun `a negative delta-seconds floors to zero`() = runTest {
        val wait = retryAfterFrom(MockResponse().setResponseCode(503).setHeader("Retry-After", "-5"))

        assertEquals(Duration.ZERO, wait)
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
