package org.jellyfin.androidtv.enhanced.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A one-connection-at-a-time HTTP server on loopback. [respond] gets the request line and returns the raw response,
 * or null to never answer (the connection is then held until the client closes it, which [closedByClient] records).
 */
private class LocalServer(private val respond: (String) -> String?) : AutoCloseable {
	val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
	val port get() = socket.localPort
	val requestArrived = CompletableDeferred<String>()
	val closedByClient = CountDownLatch(1)

	init {
		thread(isDaemon = true) {
			while (!socket.isClosed) {
				val client = runCatching { socket.accept() }.getOrNull() ?: break
				thread(isDaemon = true) { serve(client) }
			}
		}
	}

	private fun serve(client: Socket): Unit = client.use {
		val input = client.getInputStream().bufferedReader()
		val requestLine = input.readLine() ?: return
		// Skip the headers
		do {
			val header = input.readLine()
		} while (!header.isNullOrEmpty())
		requestArrived.complete(requestLine)
		val response = respond(requestLine)
		if (response == null) {
			// Hold the request until the client gives up on it
			if (runCatching { client.getInputStream().read() }.getOrDefault(-1) == -1) closedByClient.countDown()
			return
		}
		val output = client.getOutputStream()
		output.write(response.toByteArray())
		output.flush()
	}

	override fun close() = socket.close()
}

private fun http(status: String, body: String) =
	"HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"

private fun enhancedApi(port: Int, token: String = "token"): EnhancedApi {
	val client = mockk<ApiClient> {
		every { baseUrl } returns "http://127.0.0.1:$port"
		every { accessToken } returns token
		every { clientInfo } returns ClientInfo("test", "1")
		every { deviceInfo } returns DeviceInfo("device", "Device")
		every { createUrl(any(), any(), any(), any()) } answers {
			val query = thirdArg<Map<String, Any?>>().entries.joinToString("&") { "${it.key}=${it.value}" }
			"http://127.0.0.1:$port${firstArg<String>()}" + if (query.isEmpty()) "" else "?$query"
		}
	}
	return EnhancedApi(client, mockk())
}

/** [EnhancedApi]'s transport: results, structured errors, and cancellation of the underlying request. */
class EnhancedApiTest : FunSpec({
	test("a successful response is parsed; an empty one is JsonNull") {
		LocalServer { line -> if ("empty" in line) http("204 No Content", "") else http("200 OK", """{"ok":true}""") }.use { server ->
			val api = enhancedApi(server.port)
			api.get("public-config").jsonObject["ok"] shouldBe JsonPrimitive(true)
			api.delete("empty") shouldBe JsonNull
			server.requestArrived.await() shouldBe "GET /JellyfinEnhanced/public-config HTTP/1.1"
		}
	}

	test("an error response keeps its status, body, code and message") {
		LocalServer { http("403 Forbidden", """{"code":"blocked","message":"Not allowed"}""") }.use { server ->
			val err = shouldThrow<EnhancedApi.EnhancedApiException> { enhancedApi(server.port).post("jellyseerr/request") }
			err.status shouldBe 403
			err.code shouldBe "blocked"
			err.serverMessage shouldBe "Not allowed"
			err.body?.jsonObject?.get("code") shouldBe JsonPrimitive("blocked")
		}
	}

	test("a network failure is status 0") {
		val port = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }
		shouldThrow<EnhancedApi.EnhancedApiException> { enhancedApi(port).get("version") }.status shouldBe 0
	}

	test("cancelling the caller cancels the request instead of waiting for the server") {
		LocalServer { null }.use { server ->
			coroutineScope {
				val call = async { enhancedApi(server.port).get("arr/calendar") }
				server.requestArrived.await()
				call.cancel()
				// The old blocking transport held the connection for the whole read timeout (60 s)
				runInterruptible(Dispatchers.IO) { server.closedByClient.await(10, TimeUnit.SECONDS) } shouldBe true
			}
		}
	}

	test("public-config is fetched once per session and shared; a fresh read replaces it") {
		val requests = AtomicInteger()
		LocalServer { http("200 OK", """{"n":${requests.incrementAndGet()}}""") }.use { server ->
			val api = enhancedApi(server.port)
			api.get("public-config").jsonObject["n"] shouldBe JsonPrimitive(1)
			// Another instance for the same session (a pinned copy) shares it
			enhancedApi(server.port).get("public-config").jsonObject["n"] shouldBe JsonPrimitive(1)
			api.publicConfig(fresh = true).jsonObject["n"] shouldBe JsonPrimitive(2)
			api.get("public-config").jsonObject["n"] shouldBe JsonPrimitive(2)
			// Another account never sees it
			enhancedApi(server.port, token = "other").get("public-config").jsonObject["n"] shouldBe JsonPrimitive(3)
			requests.get() shouldBe 3
		}
	}
})

/** [PublicConfigCache]: lifetime, session keys, coalescing and failures. */
class PublicConfigCacheTest : FunSpec({
	test("an entry is reused within its lifetime for the same key only") {
		var now = 0L
		val cache = PublicConfigCache(ttlMs = 60_000) { now }
		var fetches = 0
		suspend fun read(key: String, fresh: Boolean = false) = cache.get({ key }, fresh) { JsonPrimitive(++fetches) }

		read("a") shouldBe JsonPrimitive(1)
		now += 59_999
		read("a") shouldBe JsonPrimitive(1)
		read("b") shouldBe JsonPrimitive(2)
		read("a") shouldBe JsonPrimitive(3)
		read("a", fresh = true) shouldBe JsonPrimitive(4)
		now += 60_000
		read("a") shouldBe JsonPrimitive(5)
	}

	test("concurrent reads share one fetch") {
		val cache = PublicConfigCache(ttlMs = 60_000) { 0L }
		val fetches = AtomicInteger()
		val release = CompletableDeferred<Unit>()
		coroutineScope {
			val reads = List(5) {
				async {
					cache.get({ "a" }, fresh = false) {
						release.await()
						JsonPrimitive(fetches.incrementAndGet())
					}
				}
			}
			release.complete(Unit)
			reads.awaitAll().toSet() shouldBe setOf(JsonPrimitive(1))
		}
		fetches.get() shouldBe 1
	}

	test("a failure is not cached, and a fetch across an account switch is not kept") {
		val cache = PublicConfigCache(ttlMs = 60_000) { 0L }
		shouldThrow<IllegalStateException> { cache.get({ "a" }, fresh = false) { error("offline") } }
		cache.get({ "a" }, fresh = false) { JsonPrimitive(1) } shouldBe JsonPrimitive(1)

		var key = "b"
		cache.get({ key }, fresh = false) {
			key = "c"
			JsonPrimitive(2)
		} shouldBe JsonPrimitive(2)
		key = "b"
		cache.get({ key }, fresh = false) { JsonPrimitive(3) } shouldBe JsonPrimitive(3)
	}
})
