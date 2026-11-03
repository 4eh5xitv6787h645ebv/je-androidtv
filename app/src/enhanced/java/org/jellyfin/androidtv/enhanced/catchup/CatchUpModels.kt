package org.jellyfin.androidtv.enhanced.catchup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Jellyfin GUIDs come dashed or dashless depending on the route; compare and store them dashless and lowercase. */
internal fun normalizeItemId(id: String): String = id.replace("-", "").lowercase()

/** The user data fields of a library item the deck reads. */
@Serializable
data class CatchUpUserData(
	@SerialName("Played") val played: Boolean = false,
	@SerialName("Likes") val likes: Boolean? = null,
	@SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
)

/**
 * A library movie or show from the normal Items API (already filtered by the server for Hidden Content and Spoiler
 * Guard). [id] is the Jellyfin GUID.
 */
@Serializable
data class CatchUpItem(
	@SerialName("Id") val id: String,
	@SerialName("Name") val name: String? = null,
	@SerialName("Type") val type: String? = null,
	@SerialName("ProductionYear") val productionYear: Int? = null,
	@SerialName("OfficialRating") val officialRating: String? = null,
	@SerialName("CommunityRating") val communityRating: Double? = null,
	@SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
	/** Number of seasons for a show. */
	@SerialName("ChildCount") val childCount: Int? = null,
	@SerialName("Genres") val genres: List<String> = emptyList(),
	@SerialName("Overview") val overview: String? = null,
	@SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
	@SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
	@SerialName("UserData") val userData: CatchUpUserData? = null,
) {
	val key: String get() = normalizeItemId(id)
	val isSeries: Boolean get() = type == "Series"
	val primaryTag: String? get() = imageTags["Primary"]
	val backdropTag: String? get() = backdropImageTags.firstOrNull()

	/** Runtime in whole minutes (10,000,000 ticks per second), or null when unknown. */
	val runtimeMinutes: Int?
		get() = runTimeTicks?.takeIf { it > 0 }?.let { ((it + 300_000_000L) / 600_000_000L).toInt() }?.takeIf { it > 0 }
}

/** `GET /Items` page. */
@Serializable
data class CatchUpItemsPage(
	@SerialName("Items") val items: List<CatchUpItem> = emptyList(),
	@SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

/** A season of a show, from `GET /Shows/{id}/Seasons`. */
@Serializable
data class CatchUpSeason(
	@SerialName("Id") val id: String,
	@SerialName("Name") val name: String? = null,
	@SerialName("IndexNumber") val indexNumber: Int? = null,
	/** Episode count. */
	@SerialName("ChildCount") val childCount: Int? = null,
	@SerialName("UserData") val userData: CatchUpUserData? = null,
) {
	val episodeCount: Int get() = childCount ?: 0

	/** Episodes already watched, as the web's picker shows it ("3/10"). */
	val watchedCount: Int
		get() = (episodeCount - (userData?.unplayedItemCount ?: episodeCount)).coerceIn(0, episodeCount)

	val fullyWatched: Boolean get() = userData?.played == true || (episodeCount > 0 && watchedCount >= episodeCount)
}

/** `GET /Shows/{id}/Seasons` page. */
@Serializable
data class CatchUpSeasonsPage(
	@SerialName("Items") val items: List<CatchUpSeason> = emptyList(),
)

/** Which kinds of titles the deck deals. */
enum class CatchUpFilter(val itemTypes: String) {
	ALL("Movie,Series"),
	MOVIES("Movie"),
	SHOWS("Series");

	fun matches(item: CatchUpItem): Boolean = when (this) {
		ALL -> true
		MOVIES -> !item.isSeries
		SHOWS -> item.isSeries
	}
}

/** What a deck action did, with what its undo needs. */
sealed interface CatchUpAction {
	/** The action name of `catchup/event`. */
	val logName: String

	/**
	 * Marked played: [targetIds] are the movie, the whole show, or the chosen seasons. [seasonNumbers] is logged
	 * (`["all"]` for a whole show, null for a movie). [markedIds] are the items this action itself marked played (the
	 * movie, or the episodes under the targets that weren't played yet). Undo unmarks exactly those and never a whole
	 * show or season, so episodes watched earlier (including ones hidden from the listing) keep their state. After a
	 * partly failed undo it holds only the ones still to unmark.
	 */
	data class Watched(
		val targetIds: List<String>,
		val seasonNumbers: List<String>?,
		val markedIds: List<String> = emptyList(),
	) : CatchUpAction {
		override val logName get() = "watched"
	}

	/** Liked (JE's watchlist). When it was [alreadyLiked], undo leaves the like alone. */
	data class Watchlist(val likedId: String, val alreadyLiked: Boolean) : CatchUpAction {
		override val logName get() = "watchlist"
	}

	/** Dismissed on this device only. */
	data object Dismiss : CatchUpAction {
		override val logName get() = "dismiss"
	}
}

/** One applied action on the undo stack. */
data class CatchUpDone(val item: CatchUpItem, val action: CatchUpAction)

/** `POST catchup/event` bodies (camelCase, unlike most JE documents). */
object CatchUpEvents {
	const val OPEN = "open"
	const val UNDO = "undo"

	fun swipe(
		action: String,
		itemId: String?,
		ok: Boolean?,
		undoOf: String? = null,
		seasons: List<String>? = null,
	): JsonObject = JsonObject(
		buildMap {
			put("type", JsonPrimitive("swipe"))
			put("action", JsonPrimitive(action))
			if (undoOf != null) put("undoOf", JsonPrimitive(undoOf))
			if (itemId != null) put("itemId", JsonPrimitive(itemId))
			if (ok != null) put("ok", JsonPrimitive(ok))
			if (seasons != null) put("seasons", JsonArray(seasons.map(::JsonPrimitive)))
		}
	)

	/** The event for an applied action; shows log which seasons they marked. */
	fun forAction(done: CatchUpDone, ok: Boolean): JsonObject = swipe(
		action = done.action.logName,
		itemId = done.item.id,
		ok = ok,
		seasons = (done.action as? CatchUpAction.Watched)?.seasonNumbers,
	)
}
