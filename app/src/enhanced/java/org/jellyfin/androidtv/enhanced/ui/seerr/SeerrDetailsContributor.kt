package org.jellyfin.androidtv.enhanced.ui.seerr

import androidx.compose.runtime.Composable
import androidx.fragment.app.Fragment
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.seerr.SeerrDetails

/** What a contributor gets on the Seerr (not-in-library) details screen. */
class SeerrDetailsContext(
	val fragment: Fragment,
	val mediaType: String,
	val tmdbId: Int,
	val details: SeerrDetails,
	val state: EnhancedState,
	/** The Jellyfin id of the library copy, when the title is in the library. */
	val libraryId: java.util.UUID?,
	/** True when the library copy is Spoiler Guarded: show nothing story-related from external sources. */
	val spoilerGuarded: Boolean,
	val reload: () -> Unit,
)

/** An extra button on the Seerr details screen. */
class SeerrDetailsAction(
	val label: String,
	val onClick: () -> Unit,
)

/**
 * A feature's additions to the Seerr details screen. Registered with Koin as `SeerrDetailsContributor` and
 * collected by [SeerrDetailsFragment]. [Section] is composed below the overview; keep it compact (one row of
 * chips, a short list) and handle its own loading state.
 */
interface SeerrDetailsContributor {
	suspend fun actions(context: SeerrDetailsContext): List<SeerrDetailsAction> = emptyList()

	/** A composable section, or null when the feature doesn't apply. */
	fun section(context: SeerrDetailsContext): (@Composable () -> Unit)? = null
}
