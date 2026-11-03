package org.jellyfin.androidtv.enhanced.ui.details

import android.view.View
import androidx.annotation.DrawableRes
import androidx.fragment.app.Fragment
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * What a contributor gets for one visit of the library item details screen (FullDetailsFragment).
 * [reload] re-fetches the item and rebuilds the screen, for actions that change what the server returns.
 */
class DetailsContext(
	val fragment: Fragment,
	val item: BaseItemDto,
	val state: EnhancedState,
	val reload: () -> Unit,
) {
	val context get() = fragment.requireContext()

	/** The item's TMDB id from its provider ids, if any. */
	val tmdbId: Int? = item.providerIds?.entries?.firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }?.value?.toIntOrNull()

	/** Seerr/TMDB media type for the item: `movie` or `tv` (series, seasons and episodes). */
	val mediaType: String = when (item.type) {
		org.jellyfin.sdk.model.api.BaseItemKind.SERIES,
		org.jellyfin.sdk.model.api.BaseItemKind.SEASON,
		org.jellyfin.sdk.model.api.BaseItemKind.EPISODE -> "tv"
		else -> "movie"
	}
}

/**
 * An extra action button on the details screen. Buttons that don't fit the row fold into upstream's "⋯" menu, less
 * important ones first, so give rarely used actions a higher [priority] number.
 */
class DetailsAction(
	@DrawableRes val icon: Int,
	val label: String,
	/** Lower numbers stay visible longer when the row overflows. */
	val priority: Int = 50,
	/** Runs on the main thread; [anchor] is the button or the overflow button for popup menus. */
	val onClick: (anchor: View) -> Unit,
)

/**
 * A feature's additions to the library item details screen. Implementations are registered with Koin as
 * `DetailsContributor` (bind in the feature's module) and collected by [EnhancedDetails].
 *
 * Both methods are called on the main thread after the plugin state is known; they may suspend to fetch data.
 * Anything that fails must throw or return empty: never show stale or unprotected information. Contributors get
 * only items the plugin has already filtered (overview, ratings…), so what's in [DetailsContext.item] is safe to
 * show; separately fetched data (TMDB, reviews) must respect Spoiler Guard themselves.
 */
interface DetailsContributor {
	/** Action buttons for the button row, or empty. Called once per details visit. */
	suspend fun actions(context: DetailsContext): List<DetailsAction> = emptyList()

	/**
	 * Rows appended below the item's own rows ([EnhancedComposeRow] or leanback `ListRow`s), or empty. An
	 * [EnhancedComposeRow] with [DetailsRowPlacement.TOP] goes directly under the overview instead.
	 */
	suspend fun rows(context: DetailsContext): List<Row> = emptyList()
}
