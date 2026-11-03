package org.jellyfin.androidtv.enhanced.catchup

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.calendar.attempt
import timber.log.Timber

/**
 * Titles dismissed from the deck, per server and user, on this device only. This is deliberately not Hidden
 * Content: a dismissal only keeps the title out of Catch Up, as the web's browser-local list does.
 */
class CatchUpDismissStore(context: Context) {
	private val prefs = context.getSharedPreferences("je_catchup", Context.MODE_PRIVATE)

	fun load(serverId: String, userId: String): Set<String> =
		prefs.getStringSet(key(serverId, userId), null)?.toSet().orEmpty()

	fun save(serverId: String, userId: String, ids: Set<String>) {
		prefs.edit().apply {
			if (ids.isEmpty()) remove(key(serverId, userId)) else putStringSet(key(serverId, userId), ids)
		}.apply()
	}

	internal companion object {
		fun key(serverId: String, userId: String) = "dismissed_${normalizeItemId(serverId)}_${normalizeItemId(userId)}"
	}
}

/** Opens a [CatchUpSession] for the signed-in user. */
class CatchUpRepository(
	private val enhancedApi: EnhancedApi,
	private val sessionRepository: SessionRepository,
	private val dismissStore: CatchUpDismissStore,
) {
	/** Event logging outlives the page: it's fire-and-forget and must not be cancelled by leaving the deck. */
	private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	/** A session bound to the current server, user and token, or null when nobody is signed in. */
	fun open(): CatchUpSession? {
		val session = sessionRepository.currentSession.value ?: return null
		return CatchUpSession(
			api = enhancedApi.pinned(),
			serverId = normalizeItemId(session.serverId.toString()),
			userId = normalizeItemId(session.userId.toString()),
			dismissStore = dismissStore,
			logScope = logScope,
		)
	}
}

/**
 * The deck's server calls for one user. Built on a pinned client, so a deck (and the multi-call watched/undo
 * writes) can't end up acting as another account after a user switch.
 */
class CatchUpSession internal constructor(
	private val api: EnhancedApi,
	val serverId: String,
	val userId: String,
	private val dismissStore: CatchUpDismissStore,
	private val logScope: CoroutineScope,
) {
	private companion object {
		const val FIELDS = "Overview,Genres,ProductionYear,OfficialRating,CommunityRating,RunTimeTicks,ProviderIds,ChildCount"
	}

	/** Poster URL; the tag is kept as returned, since the plugin encodes Spoiler Guard state in it. */
	fun posterUrl(item: CatchUpItem): String? = item.primaryTag?.let { api.imageUrl("/Items/${item.id}/Images/Primary", it, 400) }

	fun backdropUrl(item: CatchUpItem): String? = item.backdropTag?.let { api.imageUrl("/Items/${item.id}/Images/Backdrop/0", it, 1280) }

	fun loadDismissed(): Set<String> = dismissStore.load(serverId, userId)

	fun saveDismissed(ids: Set<String>) = dismissStore.save(serverId, userId, ids)

	/** A random batch of unwatched titles. The server applies Hidden Content and Spoiler Guard to this query. */
	suspend fun batch(filter: CatchUpFilter, limit: Int = CatchUpDeck.BATCH_SIZE): List<CatchUpItem> =
		EnhancedApi.json.decodeFromJsonElement<CatchUpItemsPage>(
			api.get(
				"/Items",
				mapOf(
					"userId" to userId,
					"IncludeItemTypes" to filter.itemTypes,
					"Recursive" to true,
					"Filters" to "IsUnplayed",
					"SortBy" to "Random",
					"Limit" to limit,
					"Fields" to FIELDS,
					"EnableImageTypes" to "Primary,Backdrop",
					"ImageTypeLimit" to 1,
					"EnableUserData" to true,
				),
			)
		).items

	/** The title as the user sees it now, or null when it's gone (deleted, hidden or no longer accessible). */
	suspend fun item(itemId: String): CatchUpItem? =
		EnhancedApi.json.decodeFromJsonElement<CatchUpItemsPage>(
			api.get("/Items", mapOf("userId" to userId, "Ids" to itemId, "Fields" to FIELDS, "EnableUserData" to true))
		).items.firstOrNull()

	suspend fun seasons(seriesId: String): List<CatchUpSeason> =
		EnhancedApi.json.decodeFromJsonElement<CatchUpSeasonsPage>(
			api.get("/Shows/$seriesId/Seasons", mapOf("userId" to userId, "Fields" to "ChildCount", "EnableUserData" to true))
		).items

	/**
	 * Marks the targets played. For shows it first records which episodes were already played, so undo can put them
	 * back. If a later target fails, the earlier ones are reverted before the error is rethrown.
	 */
	suspend fun markWatched(item: CatchUpItem, action: CatchUpAction.Watched): CatchUpAction.Watched {
		val previouslyPlayed = if (item.isSeries) action.targetIds.flatMap { playedEpisodes(it) }.distinct() else emptyList()
		val marked = mutableListOf<String>()
		try {
			for (target in action.targetIds) {
				api.post(playedPath(target))
				marked += target
			}
		} catch (err: Exception) {
			if (marked.isNotEmpty()) attempt { unmarkWatched(marked, previouslyPlayed) }
				.onFailure { Timber.w(it, "Couldn't roll back a partial Catch Up watched action") }
			throw err
		}
		return action.copy(previouslyPlayed = previouslyPlayed)
	}

	/** Likes the title: JE's watchlist is the Likes flag, the same one its Seerr watchlist sync uses. */
	suspend fun addToWatchlist(action: CatchUpAction.Watchlist) {
		api.post("/Users/$userId/Items/${action.likedId}/Rating", query = mapOf("likes" to true))
	}

	/** Reverses a server-side action. Dismissals are local and need nothing here. */
	suspend fun revert(action: CatchUpAction) {
		when (action) {
			is CatchUpAction.Watched -> unmarkWatched(action.targetIds, action.previouslyPlayed)
			is CatchUpAction.Watchlist -> if (!action.alreadyLiked) api.delete("/Users/$userId/Items/${action.likedId}/Rating")
			CatchUpAction.Dismiss -> Unit
		}
	}

	/** Logs a deck action. Best effort: the server ignores it unless Catch Up logging is on, errors are dropped. */
	fun log(event: JsonObject) {
		logScope.launch {
			attempt { api.post("catchup/event", event) }.onFailure { Timber.d(it, "Catch Up event not logged") }
		}
	}

	private suspend fun unmarkWatched(targetIds: List<String>, previouslyPlayed: List<String>) {
		for (target in targetIds) api.delete(playedPath(target))
		for (episode in previouslyPlayed) api.post(playedPath(episode))
	}

	private suspend fun playedEpisodes(parentId: String): List<String> =
		EnhancedApi.json.decodeFromJsonElement<CatchUpItemsPage>(
			api.get(
				"/Items",
				mapOf(
					"userId" to userId,
					"ParentId" to parentId,
					"Recursive" to true,
					"IncludeItemTypes" to "Episode",
					"Filters" to "IsPlayed",
					"EnableImages" to false,
					"EnableUserData" to false,
				),
			)
		).items.map { it.id }

	private fun playedPath(itemId: String) = "/Users/$userId/PlayedItems/$itemId"
}
