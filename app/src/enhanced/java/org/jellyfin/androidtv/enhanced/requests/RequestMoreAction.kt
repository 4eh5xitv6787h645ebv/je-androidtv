package org.jellyfin.androidtv.enhanced.requests

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerrdetails.SeerrDetailsLoader
import org.jellyfin.androidtv.enhanced.seerrdetails.SeerrSeasonAvailability
import org.jellyfin.androidtv.enhanced.ui.EnhancedDestinations
import org.jellyfin.androidtv.enhanced.ui.details.DetailsAction
import org.jellyfin.androidtv.enhanced.ui.details.DetailsContext
import org.jellyfin.androidtv.enhanced.ui.details.DetailsContributor
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/**
 * "Request more" on a library show that still has regular seasons to request, like the web's button next to the
 * Seasons heading (`renderSeriesRequestMoreButton` in `js/jellyseerr/item-details.js`). It opens the Seerr details
 * screen, whose season picker makes the request.
 */
class RequestMoreAction(
	private val enhancedApi: EnhancedApi,
	private val navigationRepository: NavigationRepository,
) : DetailsContributor {
	override suspend fun actions(context: DetailsContext): List<DetailsAction> {
		val state = context.state
		if (!state.seerrUsable || !state.config.jellyseerrShowRequestMoreOnSeries) return emptyList()
		if (context.item.type != BaseItemKind.SERIES) return emptyList()
		val tmdbId = context.tmdbId ?: return emptyList()

		val requestable = try {
			coroutineScope {
				// The library's own seasons tell which "available" seasons are really there
				val librarySeasons = async {
					try {
						SeerrDetailsLoader.librarySeasons(enhancedApi, context.item.id.toString())
					} catch (err: CancellationException) {
						throw err
					} catch (err: Exception) {
						Timber.w(err, "Failed to load library seasons for Request More")
						null
					}
				}
				val bundle = SeerrDetailsLoader.load(enhancedApi, SeerrRepository.MEDIA_TV, tmdbId)
				SeerrSeasonAvailability.requestableSeasons(
					details = bundle.details,
					requests = bundle.extras.requests,
					librarySeasons = librarySeasons.await(),
					includeSpecials = false,
				)
			}
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			// Optional: without Seerr's answer the button just isn't offered
			Timber.w(err, "Request More check failed")
			return emptyList()
		}
		if (requestable.isEmpty()) return emptyList()
		val label = context.fragment.context?.getString(R.string.je_request_more) ?: return emptyList()

		return listOf(
			DetailsAction(
				icon = R.drawable.je_ic_download,
				label = label,
				priority = 30,
			) {
				navigationRepository.navigate(EnhancedDestinations.seerrDetails(SeerrRepository.MEDIA_TV, tmdbId))
			}
		)
	}
}
