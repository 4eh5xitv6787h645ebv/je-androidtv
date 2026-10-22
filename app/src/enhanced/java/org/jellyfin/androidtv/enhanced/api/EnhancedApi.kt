package org.jellyfin.androidtv.enhanced.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import timber.log.Timber

/**
 * Thin wrapper around the Jellyfin SDK client for calling the Jellyfin Enhanced plugin's REST API
 * (`/JellyfinEnhanced/...`). It reuses the SDK's base URL and authorization header, so every call is
 * made as the signed-in user.
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

	class EnhancedApiException(val status: Int, message: String) : Exception(message)

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

	/** The id of the user the client's token belongs to. */
	suspend fun currentUserId(): String? =
		(get("/Users/Me") as? JsonObject)?.get("Id")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

	suspend fun get(path: String, query: Map<String, Any?> = emptyMap()): JsonElement =
		call(HttpMethod.GET, path, query, null)

	suspend fun post(path: String, body: JsonObject? = null, query: Map<String, Any?> = emptyMap()): JsonElement =
		call(HttpMethod.POST, path, query, body)

	suspend fun delete(path: String, query: Map<String, Any?> = emptyMap()): JsonElement =
		call(HttpMethod.DELETE, path, query, null)

	private suspend fun call(
		method: HttpMethod,
		path: String,
		query: Map<String, Any?>,
		body: JsonObject?,
	): JsonElement = withContext(Dispatchers.IO) {
		val fullPath = if (path.startsWith("/")) path else "$BASE/$path"
		val response = try {
			api.request(
				method = method,
				pathTemplate = fullPath,
				pathParameters = emptyMap(),
				queryParameters = query.filterValues { it != null },
				// POST endpoints read a JSON body; send an empty object rather than nothing
				requestBody = body ?: if (method == HttpMethod.POST) JsonObject(emptyMap()) else null,
			)
		} catch (err: org.jellyfin.sdk.api.client.exception.InvalidStatusException) {
			throw EnhancedApiException(err.status, "JE $method $fullPath failed with ${err.status}")
		}

		val text = response.body.decodeToString()
		if (response.status !in 200..299) {
			Timber.w("JE %s %s -> %d %s", method, fullPath, response.status, text.take(200))
			throw EnhancedApiException(response.status, "JE $method $fullPath failed with ${response.status}")
		}
		if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
	}
}
