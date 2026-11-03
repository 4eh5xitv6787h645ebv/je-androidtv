package org.jellyfin.androidtv.enhanced.home

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.bookmarks.BookmarkLogic
import org.jellyfin.androidtv.enhanced.calendar.attempt
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.ui.EnhancedDestinations
import org.jellyfin.androidtv.enhanced.ui.menu.EnhancedItemMenu
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrCardPresenter
import org.jellyfin.androidtv.ui.home.HomeFragmentRow
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.util.PlaybackHelper
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/**
 * The optional Jellyfin Enhanced rows of the home screen ([HomeRowKind]), added after upstream's rows.
 *
 * Created by the home fragment in `onCreate`; loading starts at once, in parallel with upstream's rows, and each row
 * appears when its data is ready (never before upstream's rows are in place, see [addToRowsAdapter]). A row that
 * fails to load is left out (logged). Rows come and go live with the user's choices on the settings page and with the
 * server's gates, are reloaded when the home screen is resumed (protection-sensitive ones cleared first when Spoiler
 * Guard changed meanwhile, see [refreshAll]) and start over when the signed-in user changes. Cards
 * hidden from the card menu leave the rows at once. Nothing is added on a server without the plugin or when every
 * row is off.
 */
class EnhancedHomeRows(private val fragment: Fragment) : HomeFragmentRow, KoinComponent {
	private val enhancedRepository by inject<EnhancedRepository>()
	private val repository by inject<HomeRowsRepository>()
	private val userRepository by inject<UserRepository>()
	private val spoilerGuardRepository by inject<SpoilerGuardRepository>()

	private class Slot(val kind: HomeRowKind) {
		var job: Job? = null
		/** The loaded cards; null until loaded or after a failure. */
		var cards: List<Any>? = null
		var row: ListRow? = null
		/** [SpoilerGuardRepository.generation] when the row was last loaded. */
		var spoilerGeneration = -1L
	}

	private val slots = HomeRowKind.entries.associateWith { Slot(it) }
	private var shown: List<HomeRowKind> = emptyList()
	/** Server and user the rows were loaded for; another one starts every row over. */
	private var identity: Pair<String?, UUID?>? = null
	/** Items hidden from the card menu while this screen lives, kept out of loads that were already under way. */
	/** Ids hidden from a card menu, each with the latest load generation that may still carry the card. */
	private val hiddenIds = mutableMapOf<UUID, Long>()
	private var loadGeneration = 0L
	private var rowsAdapter: MutableObjectAdapter<Row>? = null
	private var cardPresenter: CardPresenter? = null
	private val zone: ZoneId = ZoneId.systemDefault()

	init {
		// Absent until homeModule is registered: then the home screen stays stock
		val store = getKoin().getOrNull<HomeRowsStore>()
		if (store != null) start(store) else Timber.w("homeModule not registered; no Jellyfin Enhanced home rows")
	}

	private fun start(store: HomeRowsStore) {
		fragment.lifecycleScope.launch {
			combine(store.enabled, enhancedRepository.state, userRepository.currentUser) { enabled, state, user ->
				HomeRowKind.shown(enabled, state) to (user?.serverId to user?.id)
			}
				.distinctUntilChanged()
				.collect { (kinds, identity) -> show(kinds, identity) }
		}
		fragment.lifecycleScope.launch {
			EnhancedItemMenu.hidden.collect { onHidden(it.id) }
		}
		fragment.lifecycleScope.launch {
			var first = true
			fragment.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				// Back from details, playback, Catch Up or settings: bring the rows up to date
				if (first) first = false else refreshAll()
			}
		}
	}

	/** Called once upstream's rows are in [rowsAdapter]; rows loaded meanwhile are added now, after them. */
	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		this.cardPresenter = cardPresenter
		this.rowsAdapter = rowsAdapter
		HomeRowKind.entries.forEach(::render)
	}

	private fun show(kinds: List<HomeRowKind>, identity: Pair<String?, UUID?>) {
		// Another account: nothing of the previous one's rows may stay, so every row loads afresh
		if (this.identity != null && this.identity != identity) {
			hiddenIds.clear()
			for (slot in slots.values) {
				slot.job?.cancel()
				slot.cards = null
				render(slot.kind)
			}
			shown = emptyList()
		}
		this.identity = identity
		val previous = shown
		shown = kinds
		for (kind in HomeRowKind.entries) {
			val slot = slots.getValue(kind)
			when {
				kind in kinds && kind !in previous -> load(kind, refresh = false)
				kind !in kinds && kind in previous -> {
					slot.job?.cancel()
					slot.cards = null
					render(kind)
				}
			}
		}
	}

	/**
	 * Reloads every shown row. When Spoiler Guard changed in this client since the rows were loaded (a title guarded on
	 * its details page, say), the [HomeRowKind.protectionSensitive] rows are taken off the screen first: their names,
	 * dates and artwork were resolved against the old lists and must not stay up while the new ones load.
	 */
	private fun refreshAll() {
		val generation = spoilerGuardRepository.generation
		for (slot in slots.values) {
			if (!slot.kind.protectionSensitive || slot.spoilerGeneration == generation) continue
			slot.job?.cancel()
			slot.cards = null
			render(slot.kind)
		}
		shown.forEach { load(it, refresh = true) }
	}

	/**
	 * Loads a row. A failed first load leaves the row out; a failed refresh keeps the cards already shown, except on
	 * [HomeRowKind.protectionSensitive] rows, which are cleared: protection may have changed meanwhile.
	 */
	private fun load(kind: HomeRowKind, refresh: Boolean) {
		val slot = slots.getValue(kind)
		val previous = slot.cards
		slot.job?.cancel()
		val generation = ++loadGeneration
		slot.spoilerGeneration = spoilerGuardRepository.generation
		slot.job = fragment.lifecycleScope.launch {
			// The SDK's HTTP client blocks the calling dispatcher, as upstream's rows do, so load off the main thread
			attempt { withContext(Dispatchers.IO) { cards(kind, if (refresh) previous else null) } }
				.onSuccess { cards ->
					// A hide announced before this load started is already reflected by the server (and may have been
					// restored since); only hides that raced with the load are still applied locally
					hiddenIds.entries.removeAll { it.value < generation }
					slot.cards = hiddenIds.keys.fold(cards) { kept, id -> kept?.let { withoutHidden(it, id) } }
					render(kind)
				}
				.onFailure { err ->
					Timber.w(err, "Jellyfin Enhanced home row %s not loaded", kind.key)
					if (!refresh || kind.protectionSensitive) {
						slot.cards = null
						render(kind)
					}
				}
		}
	}

	private suspend fun cards(kind: HomeRowKind, previous: List<Any>?): List<Any>? {
		return when (kind) {
			HomeRowKind.COMING_UP -> {
				val today = LocalDate.now(zone)
				repository.comingUp(zone).map { (entry, item) ->
					val context = fragment.context ?: return null
					HomeItemCard(
						item = item,
						title = item.seriesName?.takeIf { it.isNotBlank() } ?: item.name ?: entry.event.title,
						subtitle = listOfNotNull(HomeRowsLogic.episodeCode(entry.event), dayText(context, HomeRowsLogic.dayLabel(entry.day, today)))
							.joinToString(" · ")
							.ifEmpty { null },
						key = listOf(entry.event.source, entry.event.instanceName, entry.event.eventId).joinToString("/"),
					)
				}
			}

			HomeRowKind.JUMP_BACK_IN -> repository.jumpBackIn().map { (entry, item) ->
				val context = fragment.context ?: return null
				val bookmark = entry.entry.bookmark
				val name = bookmarkName(context, entry)
				val label = bookmark.label?.takeIf { it.isNotBlank() }
				BookmarkCard(
					item = item,
					title = label ?: name,
					subtitle = listOfNotNull(if (label != null) name else null, BookmarkLogic.formatTimestamp(bookmark.timestampSeconds))
						.joinToString(" · "),
					bookmarkId = entry.entry.bookmarkId,
					positionTicks = BookmarkLogic.ticks(bookmark.timestampSeconds),
				)
			}

			HomeRowKind.TRENDING -> {
				val context = fragment.context ?: return null
				repository.trending(context.getString(kind.title))
			}

			HomeRowKind.CATCH_UP -> {
				val context = fragment.context ?: return null
				val keptIds = previous?.mapNotNull { (it as? BaseItemDtoBaseRowItem)?.baseItem?.id?.toString() }
				val items = keptIds?.let { repository.catchUpRecheck(it) }?.takeIf { it.isNotEmpty() } ?: repository.catchUp()
				if (items.isEmpty()) emptyList()
				else listOf(GridButtonBaseRowItem(OpenCatchUpButton(context.getString(R.string.je_home_open_catch_up)))) +
					items.map { BaseItemDtoBaseRowItem(it) }
			}
		}
	}

	private fun dayText(context: Context, label: DayLabel): String? = when (label) {
		DayLabel.NONE -> null
		DayLabel.TODAY -> context.getString(R.string.je_home_today)
		DayLabel.TOMORROW -> context.getString(R.string.je_home_tomorrow)
		is DayLabel.Weekday -> label.day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
	}

	/** The bookmark's item name, or the neutral "Series · Season 2, Episode 1" when the episode title is withheld. */
	private fun bookmarkName(context: Context, entry: JumpBackInEntry): String {
		val neutral = entry.name.neutral ?: return entry.name.text ?: context.getString(R.string.je_bookmarks_untitled)
		val season = neutral.seasonNumber
		val episode = neutral.episodeNumber
		val numbers = when {
			season != null && episode != null -> context.getString(R.string.je_bookmarks_episode_numbered, season, episode)
			episode != null -> context.getString(R.string.je_bookmarks_episode_number, episode)
			else -> context.getString(R.string.je_bookmarks_episode)
		}
		return listOfNotNull(neutral.seriesName, numbers).joinToString(" · ")
	}

	/** Puts the row's current cards on screen: adds, updates or removes its row. */
	private fun render(kind: HomeRowKind) {
		val rowsAdapter = rowsAdapter ?: return
		val cardPresenter = cardPresenter ?: return
		val context = fragment.context ?: return
		val slot = slots.getValue(kind)
		val cards = slot.cards?.takeIf { kind in shown }

		val existing = slot.row
		if (cards.isNullOrEmpty()) {
			if (existing != null) rowsAdapter.remove(existing)
			slot.row = null
			return
		}

		if (existing != null) {
			@Suppress("UNCHECKED_CAST")
			// Same cards may carry new labels (the day moved on): rebind them all
			(existing.adapter as MutableObjectAdapter<Any>).replaceAll(cards, areContentsTheSame = { old, new -> old === new })
			return
		}

		val presenter = if (kind == HomeRowKind.TRENDING) SeerrCardPresenter { removeCard(slot, it) } else cardPresenter
		val row = ListRow(HeaderItem(context.getString(kind.title)), MutableObjectAdapter<Any>(presenter).apply { replaceAll(cards) })
		// Keep the JE rows in their fixed order, after upstream's
		val next = HomeRowKind.entries
			.filter { it.ordinal > kind.ordinal }
			.firstNotNullOfOrNull { slots.getValue(it).row }
			?.let { rowsAdapter.indexOf(it) }
			?.takeIf { it >= 0 }
		if (next != null) rowsAdapter.add(next, row) else rowsAdapter.add(row)
		slot.row = row
	}

	/** An item hidden from the card menu leaves every row (and a row goes once it has no titles left). */
	private fun onHidden(id: UUID) {
		hiddenIds[id] = loadGeneration
		for (slot in slots.values) {
			val cards = slot.cards ?: continue
			val kept = withoutHidden(cards, id)
			if (kept !== cards) {
				slot.cards = kept
				render(slot.kind)
			}
		}
	}

	private fun withoutHidden(cards: List<Any>, id: UUID) = HomeRowsLogic.withoutHidden(cards, id) { (it as? BaseRowItem)?.baseItem }

	/** A Seerr card hidden from its menu leaves the row (and the row goes once empty). */
	private fun removeCard(slot: Slot, result: SeerrResult) {
		slot.cards = slot.cards?.filterNot { it is SeerrResult && it.dedupeKey == result.dedupeKey }
		render(slot.kind)
	}

	companion object : KoinComponent {
		/**
		 * Handles a click on a card of these rows that doesn't simply open the item's details: bookmarks play from
		 * their position, Seerr cards open Seerr details, the first Catch Up card opens the deck. False for anything
		 * else, which the home screen handles as before.
		 */
		fun onItemClicked(context: Context, item: Any?): Boolean {
			when {
				item is BookmarkCard ->
					get<PlaybackHelper>().retrieveAndPlay(listOf(item.playItemId), false, item.positionTicks, 0, context)

				item is SeerrResult -> get<NavigationRepository>().navigate(
					EnhancedDestinations.seerrDetails(item.mediaType ?: SeerrRepository.MEDIA_MOVIE, item.id)
				)

				item is GridButtonBaseRowItem && item.gridButton is OpenCatchUpButton ->
					get<NavigationRepository>().navigate(EnhancedDestinations.hub("catchup"))

				else -> return false
			}
			return true
		}
	}
}
