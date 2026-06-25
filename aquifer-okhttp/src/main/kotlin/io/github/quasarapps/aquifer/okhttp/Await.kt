package io.github.quasarapps.aquifer.okhttp

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * Suspends until this [Call] completes, returning its [Response] or throwing the `IOException`
 * OkHttp reports on failure. The call is enqueued on OkHttp's dispatcher (it never blocks the
 * calling thread) and is cancelled when the coroutine is cancelled; if cancellation races a
 * response that already arrived, that response is closed so the connection is not leaked.
 *
 * This is the bridge [okHttpFetcher] and [okHttpConditionalFetcher] use internally, exposed so
 * a hand-rolled fetcher can `await()` a [Call] without re-implementing the suspend/cancellation
 * plumbing. The caller owns the returned [Response] and must close it (e.g. with `use { }`).
 */
public suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, resource, _ -> resource.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
    continuation.invokeOnCancellation { cancel() }
}
