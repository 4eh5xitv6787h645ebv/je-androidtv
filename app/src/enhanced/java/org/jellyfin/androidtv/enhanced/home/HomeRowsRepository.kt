package org.jellyfin.androidtv.enhanced.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.enhanced.bookmarks.BookmarksRepository
import org.jellyfin.androidtv.enhanced.calendar.CalendarHiddenFilter
import org.jellyfin.androidtv.enhanced.calendar.CalendarLogic
import org.jellyfin.androidtv.enhanced.calendar.CalendarRepository
import org.jellyfin.androidtv.enhanced.catchup.CatchUpRepository
import org.jellyfin.androidtv.enhanced.catchup.normalizeItemId
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerProtection
import org.jellyfin.androidtv.enhanced.seerr.SeerrDiscoverFeed
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.seerr.SeerrSource
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId

/**
 * Data of the Jellyfin Enhanced home rows, built on the feature repositories (calendar, bookmarks, Seerr discovery,
 * Catch Up) so the rows filter exactly like their pages. Library cards always use the item as the server returns it
 * to this user, so Spoiler Guard's artwork and Hidden Content apply to them. Every call throws on failure; the row
 * then stays away.
 */
class HomeRowsRepository(
	private val api: ApiClient,
	private val enhancedRepository: EnhancedRepository,
	private val calendarRepository: CalendarRepository,
	private val bookmarksRepository: BookmarksRepository,
	private val seerrRepository: SeerrRepository,
	private val catchUpRepository: CatchUpRepository,
	private val spoilerProtection: SpoilerProtection,
	private val hiddenContentRepository: HiddenContentRepository,
) {
	private companion object {
		const val LOOKUP_CHUNK = 50
		const val TRENDING_MAX_PAGES = 3
		/** Bookmarks looked up per load; gone ones are skipped, so read a few more than the row shows. */
		const val BOOKMARK_LOOKUPS = HomeRowsLogic.JUMP_BACK_IN_LIMIT * 3
		/** Random titles asked for per Catch Up load, to fill the row after dropping dismissed ones. */
		const val CATCH_UP_CANDIDATES = 30
	}

	/** A "Coming up" card's release with the library item to show it with. */
	data class ComingUp(val entry: ComingUpEntry, val item: BaseItemDto)

	/** A "Jump back in" card's bookmark with its library item. */
	data class JumpBackIn(val entry: JumpBackInEntry, val item: BaseItemDto)

	suspend fun comingUp(zone: ZoneId): List<ComingUp> = coroutineScope {
		val state = enhancedRepository.ensureLoaded()
		// Without the configuration neither Spoiler Guard nor Hidden Content can be applied
		if (state.configUnavailable) error("Jellyfin Enhanced configuration unavailable")
		val today = LocalDate.now(zone)
		val eventsCall = async { calendarRepository.events(today, zone).events }
		val configCall = async { calendarRepository.config() }
		val spoilerCall = async { spoilerProtection.snapshot() }
		val upcomingCall = async {
			if (CalendarLogic.mayHideDates(spoilerCall.await())) calendarRepository.upcomingEvents(today, zone).events else null
		}
		// A failed read drops the row rather than showing titles the user hid
		val hiddenCall = async { if (state.hiddenContent) CalendarHiddenFilter.of(hiddenContentRepository.get()) else null }

		val config = configCall.await()
		// The enforced "only requested" option needs the request list; failing to read it drops the row
		val requested = if (config.forceOnlyRequested && state.config.jellyseerrEnabled) calendarRepository.requestedKeys() else emptySet()
		val spoiler = spoilerCall.await()
		if (spoiler.configUnavailable) error("Jellyfin Enhanced configuration unavailable")

		val entries = HomeRowsLogic.comingUp(
			events = eventsCall.await(),
			upcoming = upcomingCall.await(),
			today = today,
			zone = zone,
			spoiler = spoiler,
			hidden = hiddenCall.await(),
			requested = requested,
			forceOnlyRequested = config.forceOnlyRequested,
		)
		// Calendar ids aren't filtered by library access: only what the server returns for this user becomes a card
		val items = items(entries.flatMap { it.libraryIds })
		entries.mapNotNull { entry -> entry.libraryIds.firstNotNullOfOrNull { items[it] }?.let { ComingUp(entry, it) } }
	}

	suspend fun jumpBackIn(): List<JumpBackIn> {
		val entries = bookmarksRepository.list().take(BOOKMARK_LOOKUPS)
		val spoiler = spoilerProtection.snapshot()
		// Every episode name would be withheld blindly; leave the row out until the configuration is readable
		if (spoiler.configUnavailable) error("Jellyfin Enhanced configuration unavailable")
		val items = items(entries.map { it.bookmark.itemId })
		return HomeRowsLogic.jumpBackIn(entries, items.mapValues { HomeRowsLogic.bookmarkedItem(it.value) }, spoiler)
			.mapNotNull { entry -> items[normalizeItemId(entry.item.id.toString())]?.let { JumpBackIn(entry, it) } }
	}

	/** Seerr's trending titles, discovery-filtered (hidden, blocklisted), without library or pending guarded titles. */
	suspend fun trending(title: String): List<SeerrResult> {
		val spoiler = spoilerProtection.snapshot()
		// Pending Spoiler Guard opt-ins can't be told apart without the lists
		if (spoiler.configUnavailable || (spoiler.enabled && spoiler.state == null)) error("Spoiler Guard state unavailable")
		val cursor = SeerrRepository.FeedCursor(seerrRepository.feedsFor(SeerrSource.Discover(SeerrDiscoverFeed.TRENDING, title)))
		val results = mutableListOf<SeerrResult>()
		var pages = 0
		while (cursor.hasMore && pages < TRENDING_MAX_PAGES && HomeRowsLogic.trending(results, spoiler).size < HomeRowsLogic.TRENDING_LIMIT) {
			val batch = try {
				seerrRepository.loadNext(cursor)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				// A later page failing just leaves a shorter row
				if (pages == 0) throw err
				Timber.w(err, "Trending row stopped after %d page(s)", pages)
				break
			}
			pages++
			results += batch.results
		}
		return HomeRowsLogic.trending(results, spoiler)
	}

	/** Dismissed Catch Up titles of the signed-in user (normalised ids). */
	private fun dismissed(): Set<String> = catchUpRepository.open()?.loadDismissed() ?: error("No signed-in user")

	/** A fresh random deal of unwatched titles, as Catch Up deals them (the server applies Hidden Content). */
	suspend fun catchUp(): List<BaseItemDto> {
		val dismissed = dismissed()
		val items = api.itemsApi.getItems(
			includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
			recursive = true,
			filters = setOf(ItemFilter.IS_UNPLAYED),
			sortBy = setOf(ItemSortBy.RANDOM),
			limit = CATCH_UP_CANDIDATES,
			fields = ItemRepository.browseFields,
			imageTypeLimit = 1,
			enableUserData = true,
		).content.items
		return HomeRowsLogic.catchUp(items, dismissed)
	}

	/**
	 * The titles of [itemIds] that still belong in the Catch Up row (unwatched, not dismissed, still visible), in the
	 * same order, so coming back to the home screen doesn't reshuffle the row.
	 */
	suspend fun catchUpRecheck(itemIds: List<String>): List<BaseItemDto> {
		val dismissed = dismissed()
		val items = items(itemIds)
		return HomeRowsLogic.catchUp(itemIds.mapNotNull { items[normalizeItemId(it)] }, dismissed)
	}

	/** Library items by normalised id, as the server returns them to this user; inaccessible ones are left out. */
	private suspend fun items(ids: Collection<String>): Map<String, BaseItemDto> {
		val uuids = ids.mapNotNull { it.toUUIDOrNull() }.distinct()
		if (uuids.isEmpty()) return emptyMap()
		return uuids.chunked(LOOKUP_CHUNK).flatMap { chunk ->
			api.itemsApi.getItems(
				ids = chunk,
				fields = ItemRepository.browseFields,
				imageTypeLimit = 1,
				enableUserData = true,
			).content.items
		}.associateBy { normalizeItemId(it.id.toString()) }
	}
}
