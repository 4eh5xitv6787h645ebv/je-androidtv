package org.jellyfin.androidtv.enhanced.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.enhanced.api.EnhancedApi

/**
 * The user's Jellyfin Enhanced documents under `user-settings/{userId}/…`.
 *
 * Every document is saved as a whole (the plugin has no PATCH), and a partial `settings.json` resets the fields it
 * doesn't contain to defaults, so edits are always GET → modify → POST of the complete document, serialised per
 * document, through an API client pinned to the user who started the edit.
 */
class EnhancedSettingsRepository(
	private val enhancedApi: EnhancedApi,
	private val enhancedRepository: EnhancedRepository,
) {
	private val mutex = Mutex()

	/** A document with the session it was read in; [save] writes it back with the same session. */
	class Document internal constructor(
		internal val api: EnhancedApi,
		internal val userKey: String,
		val file: String,
		val json: JsonObject,
	) {
		fun string(key: String) = (json[key] as? JsonPrimitive)?.content
		fun bool(key: String) = (json[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
		fun int(key: String) = (json[key] as? JsonPrimitive)?.content?.toIntOrNull()
	}

	/** Read one of the user's documents (`settings.json`, `elsewhere.json`, `ddd-triggers.json`, …). */
	suspend fun read(file: String): Document {
		val api = enhancedApi.pinned()
		val userKey = requireNotNull(api.currentUserId()) { "No signed-in user" }.replace("-", "").lowercase()
		val json = api.get("user-settings/$userKey/$file").jsonObject
		return Document(api, userKey, file, json)
	}

	/**
	 * Change fields of a document and save it. [edit] receives the current fields and returns the new ones; keys
	 * mapped to null are removed. `IsAdmin` is never written back.
	 */
	suspend fun edit(file: String, edit: (MutableMap<String, JsonElement>) -> Unit): Document = mutex.withLock {
		val document = read(file)
		val fields = document.json.toMutableMap()
		edit(fields)
		fields.remove("IsAdmin")
		val updated = JsonObject(fields)
		document.api.post("user-settings/${document.userKey}/$file", updated)
		val saved = Document(document.api, document.userKey, file, updated)
		// Only publish into the shared state if the app is still signed in as the user whose document this is
		if (enhancedApi.accessToken != document.api.accessToken) return@withLock saved
		if (file == SETTINGS) {
			runCatching { EnhancedApi.json.decodeFromJsonElement<EnhancedUserSettings>(updated) }
				.onSuccess { enhancedRepository.updateUserSettings(it) }
		}
		if (file == HIDDEN_CONTENT) {
			runCatching { HiddenContentRepository.Snapshot(updated).settings }
				.onSuccess { enhancedRepository.updateHiddenSettings(it) }
		}
		saved
	}

	/** Convenience for `settings.json` booleans and strings. */
	suspend fun setSetting(key: String, value: Boolean) = edit(SETTINGS) { it[key] = JsonPrimitive(value) }
	suspend fun setSetting(key: String, value: String) = edit(SETTINGS) { it[key] = JsonPrimitive(value) }
	suspend fun setSetting(key: String, value: Int) = edit(SETTINGS) { it[key] = JsonPrimitive(value) }

	companion object {
		const val SETTINGS = "settings.json"
		const val HIDDEN_CONTENT = "hidden-content.json"
		const val ELSEWHERE = "elsewhere.json"
		const val DDD_TRIGGERS = "ddd-triggers.json"
		const val BOOKMARKS = "bookmark.json"
		const val SHORTCUTS = "shortcuts.json"
	}
}
