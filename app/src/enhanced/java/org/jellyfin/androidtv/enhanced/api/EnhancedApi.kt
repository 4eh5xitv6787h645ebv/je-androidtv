package org.jellyfin.androidtv.enhanced.api

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 *
 * Every call takes its server and credentials from the client when it is made, before it suspends: the shared client
 * is updated in place on an account switch, and a call started as one user must not be sent as the next.
 */
class EnhancedApi(
	private val api: ApiClient,
	private val jellyfin: Jellyfin,
	/** Where request bodies are encoded and responses parsed; replaced in tests. */
	private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
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

		/** How long a fetched `public-config` is reused for the same server and access token. */
		const val PUBLIC_CONFIG_TTL_MS = 60_000L

		const val PUBLIC_CONFIG = "public-config"

		/** Shared by all instances (pinned copies included), so every feature's read of the config costs one request. */
		private val publicConfigCache = PublicConfigCache(PUBLIC_CONFIG_TTL_MS)

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
		parseDispatcher = parseDispatcher,
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

	/** A GET of [PUBLIC_CONFIG] without a query is answered by [publicConfig], so every feature shares one fetch. */
	suspend fun get(path: String, query: Map<String, Any?> = emptyMap()): JsonElement =
		if (path == PUBLIC_CONFIG && query.isEmpty()) publicConfig()
		else request(HttpMethod.GET, target(path, query), null)

	/**
	 * The plugin's `public-config`, reused for [PUBLIC_CONFIG_TTL_MS] per server and access token (concurrent callers
	 * share one request). [fresh] always fetches and replaces the shared copy, for an explicit reload or a new
	 * session. Failures are never cached.
	 */
	suspend fun publicConfig(fresh: Boolean = false): JsonElement {
		val target = target(PUBLIC_CONFIG, emptyMap())
		return publicConfigCache.get(target.session, fresh) { request(HttpMethod.GET, target, null) }
	}

	/**
	 * Forget the shared `public-config`, for a session change: the next read fetches it, and a fetch already running
	 * is not kept. Needed even for the same server and token (a saved-token login that restores the previous session),
	 * where the cache key alone can't tell the sessions apart.
	 */
	fun invalidatePublicConfig() = publicConfigCache.invalidate()

	suspend fun post(path: String, body: JsonObject? = null, query: Map<String, Any?> = emptyMap()): JsonElement =
		request(HttpMethod.POST, target(path, query), body ?: JsonObject(emptyMap()))

	/** POST with any JSON body (the plugin's bulk routes take arrays). */
	suspend fun postRaw(path: String, body: JsonElement, query: Map<String, Any?> = emptyMap()): JsonElement =
		request(HttpMethod.POST, target(path, query), body)

	suspend fun delete(path: String, query: Map<String, Any?> = emptyMap()): JsonElement =
		request(HttpMethod.DELETE, target(path, query), null)

	/** GET a route that returns plain text (such as `version`) or where the caller needs headers/status. */
	suspend fun getText(path: String, query: Map<String, Any?> = emptyMap()): TextResponse =
		call(HttpMethod.GET, target(path, query), null)

	/** The URL and credentials of one call, read from the client synchronously when the call is made. */
	private class Target(val fullPath: String, val url: String, val authorization: String, val session: String)

	/**
	 * Capture where and as whom a call goes. The public methods evaluate this before their first suspension, so a call
	 * waiting for [parseDispatcher] (or for the `public-config` lock) when the user switches accounts still goes to the
	 * server and user that made it. Authentication is the same header the SDK sends, built from this client's (possibly
	 * pinned) token and client/device info.
	 */
	private fun target(path: String, query: Map<String, Any?>): Target {
		val fullPath = if (path.startsWith("/")) path else "$BASE/$path"
		return Target(
			fullPath = fullPath,
			url = api.createUrl(fullPath, emptyMap(), query.filterValues { it != null }, false),
			authorization = AuthorizationHeaderBuilder.buildHeader(
				api.clientInfo.name,
				api.clientInfo.version,
				api.deviceInfo.id,
				api.deviceInfo.name,
				api.accessToken,
			),
			session = "${api.baseUrl}|${api.accessToken}",
		)
	}

	/**
	 * A JSON call. Encoding the body and parsing the response run on [parseDispatcher]: most callers are on Main
	 * (lifecycleScope), and some responses (calendars, request lists, review pages) are large.
	 */
	private suspend fun request(method: HttpMethod, target: Target, body: JsonElement?): JsonElement =
		withContext(parseDispatcher) { parse(call(method, target, body)) }

	private fun parse(response: TextResponse): JsonElement =
		if (response.text.isBlank()) JsonNull else json.parseToJsonElement(response.text)

	/**
	 * Plain OkHttp transport: the SDK's `request()` throws on non-2xx responses and discards the body, but the
	 * plugin's error bodies carry the code and message the UI needs. The URL and credentials come from [target], never
	 * from the (shared, mutable) client. Cancelling the calling coroutine cancels the request, so a screen that is left
	 * (or reloads) doesn't keep obsolete calls running.
	 */
	private suspend fun call(method: HttpMethod, target: Target, body: JsonElement?): TextResponse {
		val fullPath = target.fullPath
		val request = Request.Builder()
			.url(target.url)
			.header("Authorization", target.authorization)
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

/**
 * One cached JSON document for one session key (server + access token), with concurrent fetches coalesced: callers
 * wait for the request in flight instead of starting their own. The caller's fetch must be made as [get]'s key (the
 * session captured with it), so an entry is always the document of the session it is filed under. [invalidate] drops
 * the entry, and a fetch that started before it is returned to its caller but not kept.
 */
internal class PublicConfigCache(
	private val ttlMs: Long,
	private val clock: () -> Long = System::currentTimeMillis,
) {
	private class Entry(val key: String, val at: Long, val value: JsonElement)

	private val mutex = Mutex()

	/** Guards [entry] and [generation] together, so [invalidate] can't land between a fetch's check and its store. */
	private val lock = Any()
	private var entry: Entry? = null
	private var generation = 0L

	private fun valid(key: String): JsonElement? = synchronized(lock) {
		entry?.takeIf { it.key == key && clock() - it.at in 0 until ttlMs }?.value
	}

	fun invalidate() = synchronized(lock) {
		generation++
		entry = null
	}

	suspend fun get(key: String, fresh: Boolean, fetch: suspend () -> JsonElement): JsonElement {
		if (!fresh) valid(key)?.let { return it }
		return mutex.withLock {
			// Someone else fetched it while we waited
			if (!fresh) valid(key)?.let { return@withLock it }
			val startedIn = synchronized(lock) { generation }
			fetch().also { value ->
				synchronized(lock) { if (generation == startedIn) entry = Entry(key, clock(), value) }
			}
		}
	}
}
