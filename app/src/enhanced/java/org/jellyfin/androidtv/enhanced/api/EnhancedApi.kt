package org.jellyfin.androidtv.enhanced.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin wrapper around the Jellyfin SDK client for calling the Jellyfin Enhanced plugin's REST API
 * (`/JellyfinEnhanced/...`). It reuses the SDK's base URL and authorization header, so every call is
 * made as the signed-in user. Paths without a leading slash are relative to [BASE]; paths with one are
 * ordinary Jellyfin routes.
 */
class EnhancedApi(
	private val api: ApiClient,
	private val jellyfin: Jellyfin,
) {
	companion object {
		const val BASE = "/JellyfinEnhanced"

		val json = Json {
			ignoreUnknownKeys = true
			isLenient = true
			coerceInputValues = true
			explicitNulls = false
		}

		private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

		/** One client for all instances (pinned copies included): connection pool and threads are shared. */
		private val httpClient: OkHttpClient by lazy {
			OkHttpClient.Builder()
				.connectTimeout(15, TimeUnit.SECONDS)
				.readTimeout(60, TimeUnit.SECONDS)
				.build()
		}
	}

	/**
	 * A non-2xx response (or, with [status] 0, a network failure). [body] is the parsed error body when there was
	 * one, [code] the plugin's error code (`unlinked`, `blocked`, `missing_query`, ...) and [serverMessage] its
	 * human readable message, when present.
	 */
	class EnhancedApiException(
		val status: Int,
		message: String,
		val body: JsonElement? = null,
		val code: String? = null,
		val serverMessage: String? = null,
	) : Exception(message)

	/** A raw successful response. */
	class TextResponse(val status: Int, val text: String, val headers: Map<String, List<String>>)

	val baseUrl: String? get() = api.baseUrl
	val accessToken: String? get() = api.accessToken

	/**
	 * A copy bound to the current server and access token. The shared client is updated in place when the user
	 * switches accounts, so multi-request operations that must stay with one user use this instead.
	 */
	fun pinned(): EnhancedApi = EnhancedApi(
		api = jellyfin.createApi(
			baseUrl = api.baseUrl,
			accessToken = api.accessToken,
			clientInfo = api.clientInfo,
			deviceInfo = api.deviceInfo,
			httpClientOptions = api.httpClientOptions,
		),
		jellyfin = jellyfin,
	)

	/** An authenticated Jellyfin image URL. Keep [tag] as returned by the server: the plugin encodes state in it. */
	fun imageUrl(path: String, tag: String?, maxWidth: Int): String = api.createUrl(
		path,
		emptyMap(),
		mapOf("tag" to tag, "maxWidth" to maxWidth).filterValues { it != null },
		true,
	)

	/** An authenticated URL for any server path (e.g. a plugin image route). */
	fun url(path: String, query: Map<String, Any?> = emptyMap()): String =
		api.createUrl(if (path.startsWith("/")) path else "$BASE/$path", emptyMap(), query.filterValues { it != null }, true)

	/** The id of the user the client's token belongs to. */
	suspend fun currentUserId(): String? =
		(get("/Users/Me") as? JsonObject)?.get("Id")?.let { (it as? JsonPrimitive)?.content }

	suspend fun get(path: String, query: Map<String, Any?> = emptyMap()): JsonElement =
		parse(call(HttpMethod.GET, path, query, null))

	suspend fun post(path: String, body: JsonObject? = null, query: Map<String, Any?> = emptyMap()): JsonElement =
		parse(call(HttpMethod.POST, path, query, body ?: JsonObject(emptyMap())))

	/** POST with any JSON body (the plugin's bulk routes take arrays). */
	suspend fun postRaw(path: String, body: JsonElement, query: Map<String, Any?> = emptyMap()): JsonElement =
		parse(call(HttpMethod.POST, path, query, body))

	suspend fun delete(path: String, query: Map<String, Any?> = emptyMap()): JsonElement =
		parse(call(HttpMethod.DELETE, path, query, null))

	/** GET a route that returns plain text (such as `version`) or where the caller needs headers/status. */
	suspend fun getText(path: String, query: Map<String, Any?> = emptyMap()): TextResponse =
		call(HttpMethod.GET, path, query, null)

	private fun parse(response: TextResponse): JsonElement =
		if (response.text.isBlank()) JsonNull else json.parseToJsonElement(response.text)

	/**
	 * Plain OkHttp transport: the SDK's `request()` throws on non-2xx responses and discards the body, but the
	 * plugin's error bodies carry the code and message the UI needs. Authentication is the same header the SDK
	 * sends, built from this client's (possibly pinned) token and client/device info. Cancelling the calling coroutine
	 * cancels the request, so a screen that is left (or reloads) doesn't keep obsolete calls running.
	 */
	private suspend fun call(
		method: HttpMethod,
		path: String,
		query: Map<String, Any?>,
		body: JsonElement?,
	): TextResponse {
		val fullPath = if (path.startsWith("/")) path else "$BASE/$path"
		val url = api.createUrl(fullPath, emptyMap(), query.filterValues { it != null }, false)
		val authorization = AuthorizationHeaderBuilder.buildHeader(
			api.clientInfo.name,
			api.clientInfo.version,
			api.deviceInfo.id,
			api.deviceInfo.name,
			api.accessToken,
		)
		val request = Request.Builder()
			.url(url)
			.header("Authorization", authorization)
			.header("Accept", "application/json")
			.method(
				method.name,
				when {
					body != null -> json.encodeToString(JsonElement.serializer(), body).toRequestBody(JSON_MEDIA_TYPE)
					method == HttpMethod.POST -> "{}".toRequestBody(JSON_MEDIA_TYPE)
					else -> null
				},
			)
			.build()

		val res = try {
			httpClient.newCall(request).await()
		} catch (err: IOException) {
			throw EnhancedApiException(0, "JE $method $fullPath failed: ${err.message}")
		}
		if (res.status !in 200..299) {
			Timber.w("JE %s %s -> %d %s", method, fullPath, res.status, res.text.take(200))
			val errorBody = runCatching { if (res.text.isBlank()) null else json.parseToJsonElement(res.text) }.getOrNull()
			val obj = errorBody as? JsonObject
			throw EnhancedApiException(
				status = res.status,
				message = "JE $method $fullPath failed with ${res.status}",
				body = errorBody,
				code = (obj?.get("code") as? JsonPrimitive)?.contentOrNull,
				serverMessage = (obj?.get("message") as? JsonPrimitive)?.contentOrNull,
			)
		}
		return res
	}

	/**
	 * Run the call on OkHttp's dispatcher and read the whole body there, so cancellation (which cancels the call) also
	 * stops a slow body. Transport failures are thrown as [IOException].
	 */
	private suspend fun Call.await(): TextResponse = suspendCancellableCoroutine { continuation ->
		continuation.invokeOnCancellation { cancel() }
		enqueue(object : Callback {
			override fun onFailure(call: Call, e: IOException) {
				continuation.resumeWithException(e)
			}

			override fun onResponse(call: Call, response: Response) {
				val result = try {
					response.use { TextResponse(it.code, it.body?.string().orEmpty(), it.headers.toMultimap()) }
				} catch (err: IOException) {
					continuation.resumeWithException(err)
					return
				}
				continuation.resume(result)
			}
		})
	}
}
