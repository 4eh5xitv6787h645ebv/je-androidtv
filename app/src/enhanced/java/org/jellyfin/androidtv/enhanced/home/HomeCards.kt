package org.jellyfin.androidtv.enhanced.home

import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/**
 * A library card of a Jellyfin Enhanced home row: upstream's card for the server's item (so it looks and focuses like
 * the other home rows, and Spoiler Guard's artwork applies), with the row's own title and subtitle. Episodes show
 * their series' poster. Clicking opens the item's details through upstream's launcher unless the card says otherwise
 * ([BookmarkCard]).
 *
 * Equality is by [key], not by item: two releases of the same show are different cards.
 */
open class HomeItemCard(
	item: BaseItemDto,
	private val title: String?,
	private val subtitle: String?,
	private val key: String,
) : BaseItemDtoBaseRowItem(item, preferSeriesPoster = true) {
	override fun getCardName(context: Context) = title
	override fun getFullName(context: Context) = title
	override fun getSubText(context: Context) = subtitle

	override fun equals(other: Any?) = other is HomeItemCard && other.javaClass == javaClass && other.key == key
	override fun hashCode() = key.hashCode()
}

/** A "Jump back in" card; clicking plays the item from the bookmark's position. */
class BookmarkCard(
	item: BaseItemDto,
	title: String?,
	subtitle: String?,
	bookmarkId: String,
	val positionTicks: Long,
) : HomeItemCard(item, title, subtitle, bookmarkId) {
	val playItemId: UUID = item.id
}

/** The first card of the Catch Up row, opening the Catch Up deck. */
class OpenCatchUpButton(text: String) : GridButton(ID, text, R.drawable.je_home_catchup_tile) {
	private companion object {
		/** Outside upstream's Live TV button ids, which the home screen handles itself. */
		const val ID = 0x4A45_0001
	}
}
