package org.jellyfin.androidtv.enhanced.catchup

/**
 * The Catch Up deck as an immutable value: the cards still to show (the first is on top), the ids already dealt
 * this session, the locally dismissed ids and the undo stack. Network calls happen elsewhere; this only decides
 * what's shown next.
 */
data class CatchUpDeck(
	val cards: List<CatchUpItem> = emptyList(),
	/** Normalized ids dealt (or skipped as dismissed) this session, so random batches don't repeat them. */
	val seen: Set<String> = emptySet(),
	/** Normalized ids dismissed on this device for this server and user. */
	val dismissed: Set<String> = emptySet(),
	/** Applied actions, most recent last. */
	val history: List<CatchUpDone> = emptyList(),
	/** Consecutive batches that brought nothing new. */
	val emptyBatches: Int = 0,
	/** No more unseen titles to fetch. */
	val exhausted: Boolean = false,
) {
	companion object {
		/** Titles per random batch. */
		const val BATCH_SIZE = 20

		/** Fetch more when this few cards are left (the web uses 6). */
		const val REFILL_AT = 6

		/** Random batches that only repeat seen titles before the deck gives up. */
		const val MAX_EMPTY_BATCHES = 3

		/** Undo depth. */
		const val MAX_HISTORY = 50
	}

	val current: CatchUpItem? get() = cards.firstOrNull()
	val canUndo: Boolean get() = history.isNotEmpty()
	val needsMore: Boolean get() = !exhausted && cards.size < REFILL_AT

	/**
	 * Adds a random batch: drops repeats and dismissed titles. A short batch with nothing new means the server
	 * returned everything left; full batches of repeats are retried a few times before giving up.
	 */
	fun withBatch(items: List<CatchUpItem>, requested: Int = BATCH_SIZE): CatchUpDeck {
		val newSeen = seen.toMutableSet()
		val added = mutableListOf<CatchUpItem>()
		for (item in items) {
			val key = item.key
			if (!newSeen.add(key)) continue
			if (key !in dismissed) added += item
		}
		val nothingNew = added.isEmpty()
		val empties = if (nothingNew) emptyBatches + 1 else 0
		return copy(
			cards = cards + added,
			seen = newSeen,
			emptyBatches = empties,
			exhausted = nothingNew && (items.size < requested || empties >= MAX_EMPTY_BATCHES),
		)
	}

	/** [item] (normally the top card) was handled with [action]: it leaves the deck and goes on the undo stack. */
	fun after(item: CatchUpItem, action: CatchUpAction): CatchUpDeck {
		return copy(
			cards = cards.filterNot { it.key == item.key },
			dismissed = if (action is CatchUpAction.Dismiss) dismissed + item.key else dismissed,
			history = (history + CatchUpDone(item, action)).takeLast(MAX_HISTORY),
		)
	}

	/** Takes the last action back: its card returns to the top and a dismissal is forgotten. */
	fun undo(): Pair<CatchUpDeck, CatchUpDone>? {
		val last = history.lastOrNull() ?: return null
		val deck = copy(
			cards = listOf(last.item) + cards.filterNot { it.key == last.item.key },
			dismissed = if (last.action is CatchUpAction.Dismiss) dismissed - last.item.key else dismissed,
			history = history.dropLast(1),
		)
		return deck to last
	}

	/** Removes a card without an undo entry (e.g. it was watched from its details page). */
	fun drop(itemId: String): CatchUpDeck {
		val key = normalizeItemId(itemId)
		return copy(cards = cards.filterNot { it.key == key })
	}

	/**
	 * Starts dealing for another filter: cards that don't match it go back to the pool (so they can come up again
	 * under the old filter), dismissals and undo are kept.
	 */
	fun restart(filter: CatchUpFilter): CatchUpDeck {
		val (kept, returned) = cards.partition(filter::matches)
		return copy(cards = kept, seen = seen - returned.map { it.key }.toSet(), emptyBatches = 0, exhausted = false)
	}

	/** Deals from scratch ("look again"): everything not dismissed can come up again; undo is kept. */
	fun reshuffle(): CatchUpDeck = copy(cards = emptyList(), seen = emptySet(), emptyBatches = 0, exhausted = false)

	/** Forgets every dismissal so those titles can be dealt again. */
	fun clearDismissed(): CatchUpDeck = copy(
		seen = seen - dismissed,
		dismissed = emptySet(),
		history = history.filterNot { it.action is CatchUpAction.Dismiss },
		emptyBatches = 0,
		exhausted = false,
	)
}

/** The season picker's selection rules, as on the web. Indexes are positions in the season list. */
object SeasonSelection {
	/**
	 * Selecting a season also selects every earlier one (people watch in order); selecting an already selected
	 * season unticks only that one.
	 */
	fun toggle(selected: Set<Int>, index: Int): Set<Int> =
		if (index in selected) selected - index else selected + (0..index)

	/** Seasons a show offers in the picker: numbered seasons (specials excluded), in order. */
	fun pickable(seasons: List<CatchUpSeason>): List<CatchUpSeason> =
		seasons.filter { (it.indexNumber ?: 0) > 0 }.sortedBy { it.indexNumber }

	/**
	 * The watched action for the chosen seasons: all of them marks the whole show (logged as `all`), otherwise
	 * each chosen season.
	 */
	fun action(series: CatchUpItem, seasons: List<CatchUpSeason>, selected: Set<Int>): CatchUpAction.Watched? {
		val picked = selected.filter { it in seasons.indices }.sorted()
		if (picked.isEmpty()) return null
		if (picked.size == seasons.size) return wholeShow(series)
		return CatchUpAction.Watched(
			targetIds = picked.map { seasons[it].id },
			seasonNumbers = picked.map { seasons[it].indexNumber.toString() },
		)
	}

	fun wholeShow(series: CatchUpItem) = CatchUpAction.Watched(targetIds = listOf(series.id), seasonNumbers = listOf("all"))
}
