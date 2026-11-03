package org.jellyfin.androidtv.enhanced.catchup

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
	 * Marks the targets played, one item at a time: the movie, or the episodes under the chosen show or seasons that
	 * aren't played yet. The returned action remembers exactly those ids for undo. [onCount] gets the number of items
	 * before writing starts. If a write fails, the ones this call marked are unmarked again before the error is
	 * rethrown; episodes that were played before are never touched.
	 *
	 * The writes run to the end even when the caller is cancelled (the user left the page), so a press is never left
	 * half applied without an undo entry.
	 */
	suspend fun markWatched(
		item: CatchUpItem,
		action: CatchUpAction.Watched,
		onCount: (Int) -> Unit = {},
	): CatchUpAction.Watched {
		val ids = when {
			item.isSeries -> action.targetIds.flatMap { unplayedEpisodes(it) }.distinct()
			// Already played (watched elsewhere since it was dealt): nothing to mark, so undo won't unmark it either
			item.userData?.played == true -> emptyList()
			else -> listOf(item.id)
		}
		onCount(ids.size)
		withContext(NonCancellable) {
			val result = PlayedWrites.run(ids) { api.post(playedPath(it)) }
			val error = result.error ?: return@withContext
			if (result.done.isNotEmpty()) {
				PlayedWrites.run(result.done) { api.delete(playedPath(it)) }.error
					?.let { Timber.w(it, "Couldn't roll back a partial Catch Up watched action") }
			}
			throw error
		}
		return action.copy(markedIds = ids)
	}

	/** Likes the title: JE's watchlist is the Likes flag, the same one its Seerr watchlist sync uses. */
	suspend fun addToWatchlist(action: CatchUpAction.Watchlist) {
		api.post("/Users/$userId/Items/${action.likedId}/Rating", query = mapOf("likes" to true))
	}

	/**
	 * Reverses a server-side action. Dismissals are local and need nothing here. A Watched undo that fails part way
	 * throws [CatchUpPartialUndoException] with what's left to unmark.
	 */
	suspend fun revert(action: CatchUpAction) {
		when (action) {
			is CatchUpAction.Watched -> unmarkWatched(action)
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

	/** Unmarks exactly the items the action marked; like marking, it runs to the end once started. */
	private suspend fun unmarkWatched(action: CatchUpAction.Watched) {
		val result = withContext(NonCancellable) { PlayedWrites.run(action.markedIds) { api.delete(playedPath(it)) } }
		val error = result.error ?: return
		if (result.done.isEmpty()) throw error
		throw CatchUpPartialUndoException(action.copy(markedIds = result.remaining), result.done.size, error)
	}

	/**
	 * The episodes under [parentId] that aren't played yet. This is the server-filtered listing, so episodes hidden
	 * with Hidden Content aren't in it and are neither marked nor (on undo) unmarked.
	 */
	private suspend fun unplayedEpisodes(parentId: String): List<String> =
		EnhancedApi.json.decodeFromJsonElement<CatchUpItemsPage>(
			api.get(
				"/Items",
				mapOf(
					"userId" to userId,
					"ParentId" to parentId,
					"Recursive" to true,
					"IncludeItemTypes" to "Episode",
					"Filters" to "IsUnplayed",
					// Missing episodes can't be watched; marking the parent skipped them too
					"IsMissing" to false,
					"EnableImages" to false,
					"EnableUserData" to false,
				),
			)
		).items.map { it.id }

	private fun playedPath(itemId: String) = "/Users/$userId/PlayedItems/$itemId"
}
