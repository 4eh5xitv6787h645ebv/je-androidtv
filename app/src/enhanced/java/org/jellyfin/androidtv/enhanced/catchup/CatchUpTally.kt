package org.jellyfin.androidtv.enhanced.catchup

/**
 * How many titles are left to go through, for the "1 of 47" overline and the "47 left" line. The server's count of
 * unwatched titles ([unwatchedTotal], read with every batch) still includes titles this client already took out of
 * the deck: dismissed ones (kept on this device), ones put on the watchlist this round (still unwatched) and ones
 * marked watched since the count was read. It's an estimate: titles dismissed under another filter, or shows marked
 * only partly watched, can make it a little low, so it never goes under the cards already dealt.
 */
data class CatchUpTally(
	val unwatchedTotal: Int? = null,
	val watchedSinceRead: Set<String> = emptySet(),
	val watchlisted: Set<String> = emptySet(),
	/** Titles whose watched mark was undone after a count that already left them out, so they count again. */
	val restoredSinceRead: Set<String> = emptySet(),
) {
	/** A batch came back with the server's current count. */
	fun read(total: Int) = copy(unwatchedTotal = total, watchedSinceRead = emptySet(), restoredSinceRead = emptySet())

	/** [item] left the deck through [action]. */
	fun handled(item: CatchUpItem, action: CatchUpAction) = when (action) {
		is CatchUpAction.Watched -> copy(watchedSinceRead = watchedSinceRead + item.key)
		is CatchUpAction.Watchlist -> copy(watchlisted = watchlisted + item.key)
		CatchUpAction.Dismiss -> this
	}

	/** [item]'s [action] was undone: it's back on top of the deck. */
	fun undone(item: CatchUpItem, action: CatchUpAction) = when (action) {
		// Marked after the last count: just forget the mark. Marked before it: the server's count no longer includes
		// the title, so it has to be added back
		is CatchUpAction.Watched ->
			if (item.key in watchedSinceRead) copy(watchedSinceRead = watchedSinceRead - item.key)
			else copy(restoredSinceRead = restoredSinceRead + item.key)
		is CatchUpAction.Watchlist -> copy(watchlisted = watchlisted - item.key)
		CatchUpAction.Dismiss -> this
	}

	/** Titles left including the current card, or null before the first count. */
	fun left(deck: CatchUpDeck): Int? {
		val total = unwatchedTotal ?: return null
		if (deck.exhausted) return deck.cards.size
		return (total + restoredSinceRead.size - deck.dismissed.size - watchlisted.size - watchedSinceRead.size).coerceAtLeast(deck.cards.size)
	}
}
