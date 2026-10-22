package org.jellyfin.androidtv.enhanced.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.Instant
import java.util.UUID

/** `hidden-content.json` → `Settings`. Defaults match the plugin's model defaults. */
@Serializable
data class HiddenContentSettings(
	@SerialName("Enabled") val enabled: Boolean = true,
	@SerialName("FilterDiscovery") val filterDiscovery: Boolean = true,
	@SerialName("FilterRecommendations") val filterRecommendations: Boolean = true,
	@SerialName("FilterNextUp") val filterNextUp: Boolean = true,
	@SerialName("FilterContinueWatching") val filterContinueWatching: Boolean = true,
	@SerialName("ShowHideButtons") val showHideButtons: Boolean = true,
	@SerialName("ShowHideConfirmation") val showHideConfirmation: Boolean = true,
	@SerialName("ShowButtonDetails") val showButtonDetails: Boolean = true,
	@SerialName("ShowButtonCast") val showButtonCast: Boolean = false,
)

/** Where a "remove from home" action applies; maps to the plugin's scoped hide routes. */
enum class HomeSurface(val path: String) {
	CONTINUE_WATCHING("continue-watching"),
	NEXT_UP("next-up"),
}

/**
 * Hidden Content for the signed-in user. The document is edited as raw JSON so fields this client doesn't know
 * about survive a save. The plugin filters hidden items out of the normal Jellyfin list endpoints itself; the
 * client only needs to write the document and filter the Seerr results it renders.
 */
class HiddenContentRepository(
	private val enhancedApi: EnhancedApi,
	private val userRepository: UserRepository,
) {
	private val mutex = Mutex()

	class Snapshot(val document: JsonObject) {
		val settings: HiddenContentSettings = document["Settings"]
			?.let { runCatching { EnhancedApi.json.decodeFromJsonElement<HiddenContentSettings>(it) }.getOrNull() }
			?: HiddenContentSettings()

		val entries: List<JsonObject> = (document["Items"] as? JsonObject)?.values?.mapNotNull { it as? JsonObject }.orEmpty()

		private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
		private fun JsonObject.scope() = string("HideScope").ifEmpty { "global" }

		/** True when the item is hidden everywhere (scope `global`). */
		fun isHidden(id: UUID): Boolean {
			val key = normalize(id.toString())
			return entries.any { normalize(it.string("ItemId")) == key && it.scope() == "global" }
		}

		/** TMDB ids hidden everywhere, as `movie:603` / `tv:1399` plus bare ids, for filtering Seerr rows. */
		val hiddenTmdbIds: Set<String> by lazy {
			entries.filter { it.scope() == "global" && it.string("TmdbId").isNotEmpty() }
				.map { it.string("TmdbId") }
				.toSet()
		}
	}

	private fun userKey(): String =
		requireNotNull(userRepository.currentUser.value?.id) { "No signed-in user" }.toString().replace("-", "")

	suspend fun get(): Snapshot =
		Snapshot(enhancedApi.get("user-settings/${userKey()}/hidden-content.json").jsonObject)

	/** Hide [item] everywhere. With [wholeSeries] an episode or season hides its parent show instead. */
	suspend fun hide(item: BaseItemDto, wholeSeries: Boolean = false) = edit { items ->
		val seriesId = item.seriesId
		val entry = if (wholeSeries && seriesId != null) {
			buildEntry(
				itemId = seriesId,
				name = item.seriesName.orEmpty(),
				type = "Series",
				tmdbId = "",
			)
		} else {
			buildEntry(
				itemId = item.id,
				name = item.name.orEmpty(),
				type = item.type.serialName,
				tmdbId = item.providerIds?.entries?.firstOrNull { it.key.equals("Tmdb", true) }?.value.orEmpty(),
				seriesId = seriesId,
				seriesName = item.seriesName,
				seasonNumber = if (item.type == BaseItemKind.EPISODE) item.parentIndexNumber else item.indexNumber.takeIf { item.type == BaseItemKind.SEASON },
				episodeNumber = item.indexNumber.takeIf { item.type == BaseItemKind.EPISODE },
			)
		}
		val key = entry["ItemId"]!!.jsonPrimitive.content
		// Drop any narrower entry for the same item so the global hide wins
		items.keys.filter { normalize(it) == key }.forEach { items.remove(it) }
		items[key] = entry
	}

	/** Remove every global hide entry for [id]. Scoped home-surface entries are left alone. */
	suspend fun unhide(id: UUID) = edit { items ->
		val key = normalize(id.toString())
		items.entries
			.filter { (k, v) ->
				val obj = v as? JsonObject
				val itemId = (obj?.get("ItemId") as? JsonPrimitive)?.contentOrNull ?: k
				val scope = (obj?.get("HideScope") as? JsonPrimitive)?.contentOrNull.orEmpty().ifEmpty { "global" }
				normalize(itemId) == key && scope == "global"
			}
			.map { it.key }
			.forEach { items.remove(it) }
	}

	/** Hide [id] from Continue Watching or Next Up only (the plugin's "Remove from …" action). */
	suspend fun removeFromHome(id: UUID, surface: HomeSurface) = mutex.withLock {
		enhancedApi.post("${surface.path}/hide/$id")
		Unit
	}

	suspend fun undoRemoveFromHome(id: UUID, surface: HomeSurface) = mutex.withLock {
		enhancedApi.delete("${surface.path}/hide/$id")
		Unit
	}

	private suspend fun edit(block: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit) = mutex.withLock {
		val path = "user-settings/${userKey()}/hidden-content.json"
		val document = enhancedApi.get(path).jsonObject
		val items = (document["Items"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
		block(items)
		val updated = JsonObject(document.toMutableMap().apply { put("Items", JsonObject(items)) })
		enhancedApi.post(path, updated)
		Unit
	}

	private fun buildEntry(
		itemId: UUID,
		name: String,
		type: String,
		tmdbId: String,
		seriesId: UUID? = null,
		seriesName: String? = null,
		seasonNumber: Int? = null,
		episodeNumber: Int? = null,
	) = buildJsonObject {
		put("ItemId", normalize(itemId.toString()))
		put("Name", name)
		put("Type", type)
		put("TmdbId", tmdbId)
		put("HiddenAt", Instant.now().toString())
		put("PosterPath", "")
		put("SeriesId", seriesId?.let { normalize(it.toString()) }.orEmpty())
		put("SeriesName", seriesName.orEmpty())
		if (seasonNumber != null) put("SeasonNumber", seasonNumber) else put("SeasonNumber", JsonNull)
		if (episodeNumber != null) put("EpisodeNumber", episodeNumber) else put("EpisodeNumber", JsonNull)
		put("HideScope", "global")
	}
}

private fun normalize(id: String) = id.replace("-", "").lowercase()
