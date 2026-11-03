package org.jellyfin.androidtv.enhanced.requests

import org.jellyfin.androidtv.enhanced.data.HiddenContentSettings
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult

/**
 * The Requests page's view of the protections its rows were filtered with: the write generations of Spoiler Guard and
 * Hidden Content in this client and the Hidden Content settings. A stamp that differs from the one the rows were
 * shown with means a title may have been protected or hidden meanwhile (on a details page, say).
 */
internal data class ProtectionStamp(
	val spoilerGeneration: Long,
	val hiddenGeneration: Long,
	val hiddenSettings: HiddenContentSettings?,
)

/**
 * Rows with everything a Spoiler Guard or Hidden Content change may now cover withheld (artwork, years, release
 * dates, episode titles), shown while the rows are revalidated. Each keeps its row key, so the focused card stays
 * focused. The title stays: the revalidation drops the row if it is now hidden, and fails closed if it can't.
 */
internal object RequestsNeutral {
	fun request(card: RequestsRepository.RequestCard) = card.copy(posterUrl = null, protectedTitle = true)

	fun watchlist(result: SeerrResult) = result.copy(libraryArtwork = true, libraryPosterUrl = null)

	fun issue(card: RequestsRepository.IssueCard) = card.copy(posterUrl = null, year = null)

	fun history(item: HistoryItem) = item.copy(posterUrl = null, subtitle = ArrSpoilerPolicy.withoutEpisodeTitle(item.subtitle))

	fun queue(result: RequestsRepository.QueueResult) = result.copy(entries = result.entries.map(::queueEntry))

	private fun queueItem(item: QueueItem) = item.copy(posterUrl = null, subtitle = ArrSpoilerPolicy.withoutEpisodeTitle(item.subtitle))

	private fun queueEntry(entry: QueueEntry): QueueEntry = when (entry) {
		is QueueEntry.Single -> QueueEntry.Single(queueItem(entry.item))
		is QueueEntry.SeasonPack -> entry.copy(episodes = entry.episodes.map(::queueItem))
	}

	/**
	 * The lazy key of a history row. The episode title is left out: Spoiler Guard strips it, and the row must keep its
	 * key (and the focus) when it is withheld.
	 */
	fun historyKey(item: HistoryItem): String = listOf(
		item.source,
		item.instanceName,
		item.title,
		ArrSpoilerPolicy.withoutEpisodeTitle(item.subtitle),
		item.eventType,
		item.date,
	).joinToString("|")
}
