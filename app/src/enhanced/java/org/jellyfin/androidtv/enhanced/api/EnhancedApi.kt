package org.jellyfin.androidtv.enhanced.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import timber.log.Timber

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
	}

	/**
	 * A non-2xx response. [body] is the parsed error body when there was one, [code] the plugin's error code
	 * (`unlinked`, `blocked`, `missing_query`, ...) and [serverMessage] its human readable message, when present.
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

	private suspend fun call(
		method: HttpMethod,
		path: String,
		query: Map<String, Any?>,
		body: JsonElement?,
	): TextResponse = withContext(Dispatchers.IO) {
		val fullPath = if (path.startsWith("/")) path else "$BASE/$path"
		val response = try {
			api.request(
				method = method,
				pathTemplate = fullPath,
				pathParameters = emptyMap(),
				queryParameters = query.filterValues { it != null },
				requestBody = body,
			)
		} catch (err: org.jellyfin.sdk.api.client.exception.InvalidStatusException) {
			throw EnhancedApiException(err.status, "JE $method $fullPath failed with ${err.status}")
		}

		val text = response.body.decodeToString()
		if (response.status !in 200..299) {
			Timber.w("JE %s %s -> %d %s", method, fullPath, response.status, text.take(200))
			val errorBody = runCatching { if (text.isBlank()) null else json.parseToJsonElement(text) }.getOrNull()
			val obj = errorBody as? JsonObject
			throw EnhancedApiException(
				status = response.status,
				message = "JE $method $fullPath failed with ${response.status}",
				body = errorBody,
				code = (obj?.get("code") as? JsonPrimitive)?.contentOrNull,
				serverMessage = (obj?.get("message") as? JsonPrimitive)?.contentOrNull,
			)
		}
		TextResponse(response.status, text, response.headers)
	}
}
