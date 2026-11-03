package org.jellyfin.androidtv.enhanced.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The user's Spoiler Guard preferences (`Prefs` in the document, also `GET spoiler-blur/user-prefs`). The category
 * fields are nullable: null follows the admin's policy, false opts this user out of that category, and true is
 * recorded but never enables a category the admin turned off. [useAdvancedCategories] runs the other way: false is
 * the stricter choice (uniform full strip). Older documents only have [skipDisableConfirm].
 */
@Serializable
data class SpoilerGuardPrefs(
	@SerialName("SkipDisableConfirm") val skipDisableConfirm: Boolean = false,
	@SerialName("HideSeriesDescriptions") val hideSeriesDescriptions: Boolean? = null,
	@SerialName("HideEpisodeDescriptions") val hideEpisodeDescriptions: Boolean? = null,
	@SerialName("HideTags") val hideTags: Boolean? = null,
	@SerialName("HideChapterNames") val hideChapterNames: Boolean? = null,
	@SerialName("HideTaglines") val hideTaglines: Boolean? = null,
	@SerialName("HideRatings") val hideRatings: Boolean? = null,
	@SerialName("HideAirDate") val hideAirDate: Boolean? = null,
	@SerialName("ReplaceEpisodeTitles") val replaceEpisodeTitles: Boolean? = null,
	@SerialName("HideCast") val hideCast: Boolean? = null,
	@SerialName("HideReviews") val hideReviews: Boolean? = null,
	@SerialName("HideTrickplay") val hideTrickplay: Boolean? = null,
	@SerialName("UseAdvancedCategories") val useAdvancedCategories: Boolean? = null,
)

/** A guarded library title from the Spoiler Guard document. [key] is the document key (the Jellyfin id, N format). */
data class SpoilerGuardEntry(
	val kind: SpoilerGuardRepository.Kind,
	val key: String,
	/** The Jellyfin id, when the stored value is a valid GUID. */
	val itemId: UUID?,
	val name: String,
	val enabledAt: Instant?,
)

/**
 * A pending opt-in for a title that isn't in the library yet (`PendingTmdb`). [key] is the document key
 * (`tv:123`), [tmdbId] the TMDB id; the server moves it to the library lists once the title arrives.
 */
data class SpoilerGuardPendingEntry(
	val key: String,
	val mediaType: String,
	val tmdbId: Int?,
	val displayName: String,
	val requestedAt: Instant?,
)

/** `GET /JellyfinEnhanced/spoiler-blur/series`: the user's whole Spoiler Guard document. */
@Serializable
data class SpoilerGuardState(
	@SerialName("Series") val series: Map<String, JsonElement> = emptyMap(),
	@SerialName("Movies") val movies: Map<String, JsonElement> = emptyMap(),
	@SerialName("Collections") val collections: Map<String, JsonElement> = emptyMap(),
	@SerialName("PendingTmdb") val pendingTmdb: Map<String, JsonElement> = emptyMap(),
	@SerialName("Prefs") val prefs: SpoilerGuardPrefs = SpoilerGuardPrefs(),
)

/**
 * Spoiler Guard opt-ins. The plugin does the actual protection server side (it rewrites overviews, titles and
 * images in the normal Jellyfin API responses), so the client only toggles which titles are guarded and then
 * reloads the item.
 */
class SpoilerGuardRepository(
	private val enhancedApi: EnhancedApi,
	private val clock: () -> Long = System::currentTimeMillis,
) {
	enum class Kind(val path: String, val nameField: String?) {
		SERIES("series", null),
		MOVIE("movies", "MovieName"),
		COLLECTION("collections", "CollectionName"),
	}

	private val prefsMutex = Mutex()

	private class Cached(val token: String?, val generation: Long, val at: Long, val state: SpoilerGuardState)

	/** The last state read with [getState], with the token and [generation] it was read with, for synchronous menus. */
	@Volatile
	private var cached: Cached? = null

	/** Serializes [getState] reads, so concurrent callers share one request instead of each starting their own. */
	private val readMutex = Mutex()

	private val writes = AtomicLong()

	/**
	 * Increases when a Spoiler Guard write in this client starts and again when it ends (successful or not: a failed
	 * write may still have landed). A state read while the generation changed may predate the write, so it must not
	 * be cached; caches built from [getState] compare the generation instead of trusting their own age.
	 */
	val generation: Long get() = writes.get()

	companion object {
		fun kindOf(item: BaseItemDto): Kind? = when (item.type) {
			BaseItemKind.SERIES -> Kind.SERIES
			BaseItemKind.MOVIE -> Kind.MOVIE
			BaseItemKind.BOX_SET -> Kind.COLLECTION
			else -> null
		}

		fun key(id: UUID) = id.toString().replace("-", "").lowercase()

		/** Pref keys of the per-category overrides, in the web settings panel's order. */
		const val PREF_SKIP_DISABLE_CONFIRM = "SkipDisableConfirm"
		const val PREF_USE_ADVANCED_CATEGORIES = "UseAdvancedCategories"

		/** How long [getState] reuses a state it read: about one screen load, so its parts share one request. */
		const val SHARE_WINDOW_MS = 2_000L

		/** The guarded library titles of [state], by kind, newest first. Entries are read leniently. */
		fun entries(state: SpoilerGuardState): List<SpoilerGuardEntry> = listOf(
			Triple(Kind.SERIES, state.series, "Series"),
			Triple(Kind.MOVIE, state.movies, "Movie"),
			Triple(Kind.COLLECTION, state.collections, "Collection"),
		).flatMap { (kind, map, prefix) ->
			map.map { (key, value) ->
				val obj = value as? JsonObject
				val id = obj.string("${prefix}Id") ?: key
				SpoilerGuardEntry(
					kind = kind,
					key = key,
					itemId = parseGuid(id) ?: parseGuid(key),
					name = obj.string("${prefix}Name").orEmpty(),
					enabledAt = parseInstant(obj.string("EnabledAt")),
				)
			}.sortedByDescending { it.enabledAt }
		}

		/** The pending opt-ins of [state], newest first. */
		fun pendingEntries(state: SpoilerGuardState): List<SpoilerGuardPendingEntry> = state.pendingTmdb.map { (key, value) ->
			val obj = value as? JsonObject
			val type = obj.string("MediaType")?.lowercase() ?: key.substringBefore(':', "")
			SpoilerGuardPendingEntry(
				key = key,
				mediaType = type,
				tmdbId = (obj.string("TmdbId") ?: key.substringAfter(':', "")).toIntOrNull(),
				displayName = obj.string("DisplayName").orEmpty(),
				requestedAt = parseInstant(obj.string("RequestedAt")),
			)
		}.sortedByDescending { it.requestedAt }

		/** A Jellyfin GUID in either the dashed or the N format. */
		fun parseGuid(value: String?): UUID? {
			val hex = value?.replace("-", "")?.takeIf { it.length == 32 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
				?: return null
			return runCatching {
				UUID.fromString("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
			}.getOrNull()
		}

		/** The plugin writes .NET round-trip dates (7 fraction digits, `Z` or an offset, sometimes none). */
		fun parseInstant(value: String?): Instant? {
			if (value.isNullOrBlank()) return null
			return runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
				?: runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }.getOrNull()
		}

		private fun JsonObject?.string(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
	}

	/**
	 * The user's whole Spoiler Guard document. Concurrent reads (the contributors of one screen, a menu and a row)
	 * share one request, and a state read less than [SHARE_WINDOW_MS] ago by this account is reused while no write in
	 * this client ([generation]) has started since. Errors are thrown and never cached.
	 */
	suspend fun getState(): SpoilerGuardState = readMutex.withLock {
		val token = enhancedApi.accessToken
		cached?.takeIf { it.token == token && it.generation == generation && clock() - it.at in 0 until SHARE_WINDOW_MS }
			?.let { return@withLock it.state }

		val before = generation
		val state = EnhancedApi.json.decodeFromJsonElement<SpoilerGuardState>(enhancedApi.get("spoiler-blur/series"))
		// A write started or ended during the read (the response may be the old state) or the account changed: use it
		// this once, don't remember it
		if (generation == before && enhancedApi.accessToken == token) cached = Cached(token, before, clock(), state)
		state
	}

	/** The state last read by [getState] for the current account, if any. May be stale; re-read before acting. */
	fun cachedState(): SpoilerGuardState? =
		cached?.takeIf { it.token == enhancedApi.accessToken && it.generation == generation }?.state

	/** Run a write between two [generation] bumps, dropping the cached state before and after. */
	private suspend fun <T> write(block: suspend () -> T): T {
		writes.incrementAndGet()
		cached = null
		try {
			return block()
		} finally {
			writes.incrementAndGet()
			cached = null
		}
	}

	/** The user's preferences. Errors (including the plugin's 503 for a corrupt document) are thrown, not defaulted. */
	suspend fun getPrefs(): SpoilerGuardPrefs =
		EnhancedApi.json.decodeFromJsonElement(enhancedApi.get("spoiler-blur/user-prefs"))

	/**
	 * Change preferences. The server replaces the whole `Prefs` object, so this reads the current one, changes only
	 * [changes] (null = follow the server) and posts it all back, through a client pinned to one user. Nothing is
	 * written if the read fails.
	 */
	suspend fun setPrefs(changes: Map<String, Boolean?>): SpoilerGuardPrefs {
		// Pinned before waiting: a change queued behind another one still saves for the user who made it
		val api = enhancedApi.pinned()
		return prefsMutex.withLock { setPrefs(api, changes) }
	}

	private suspend fun setPrefs(api: EnhancedApi, changes: Map<String, Boolean?>): SpoilerGuardPrefs {
		val current = api.get("spoiler-blur/user-prefs") as? JsonObject
			?: throw IllegalStateException("Unexpected Spoiler Guard preferences response")
		val fields = current.toMutableMap()
		for ((key, value) in changes) {
			fields[key] = when {
				value != null -> JsonPrimitive(value)
				// Not nullable on the server
				key == PREF_SKIP_DISABLE_CONFIRM -> JsonPrimitive(false)
				else -> JsonNull
			}
		}
		val updated = JsonObject(fields)
		write { api.post("spoiler-blur/user-prefs", updated) }
		return EnhancedApi.json.decodeFromJsonElement(updated)
	}

	fun isGuarded(state: SpoilerGuardState, kind: Kind, id: UUID): Boolean {
		val map = when (kind) {
			Kind.SERIES -> state.series
			Kind.MOVIE -> state.movies
			Kind.COLLECTION -> state.collections
		}
		val key = key(id)
		return map.keys.any { it.replace("-", "").equals(key, ignoreCase = true) }
	}

	suspend fun setGuarded(kind: Kind, id: UUID, name: String?, guarded: Boolean) {
		val path = "spoiler-blur/${kind.path}/${key(id)}"
		write {
			if (guarded) {
				val body = if (kind.nameField != null && name != null) buildJsonObject { put(kind.nameField, name) } else null
				enhancedApi.post(path, body)
			} else {
				enhancedApi.delete(path)
			}
		}
	}

	/** Turn protection off for a listed entry, addressing it by its stored key. */
	suspend fun removeEntry(entry: SpoilerGuardEntry) {
		write { enhancedApi.delete("spoiler-blur/${entry.kind.path}/${entry.itemId?.let(::key) ?: entry.key}") }
	}

	// Pending opt-ins for titles that aren't in the library yet (from the Seerr screens)

	fun isPending(state: SpoilerGuardState, mediaType: String, tmdbId: Int) =
		state.pendingTmdb.containsKey("$mediaType:$tmdbId")

	suspend fun setPending(mediaType: String, tmdbId: Int, displayName: String?, enabled: Boolean) {
		val path = "spoiler-blur/pending/$mediaType/$tmdbId"
		write {
			if (enabled) enhancedApi.post(path, query = mapOf("displayName" to displayName))
			else enhancedApi.delete(path)
		}
	}
}
