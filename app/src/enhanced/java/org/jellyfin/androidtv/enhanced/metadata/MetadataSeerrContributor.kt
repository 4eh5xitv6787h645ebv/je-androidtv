package org.jellyfin.androidtv.enhanced.metadata

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrDetailsContext
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrDetailsContributor
import org.jellyfin.androidtv.ui.base.dialog.DialogBase
import timber.log.Timber

/**
 * Ratings, awards and where-to-watch on the Seerr (not-in-library) details screen, as one line of chips under the
 * overview. Ratings are left out when the library copy is Spoiler Guarded.
 */
class MetadataSeerrContributor(private val repository: MetadataRepository) : SeerrDetailsContributor {
	override fun section(context: SeerrDetailsContext): (@Composable () -> Unit)? {
		val state = context.state
		val config = state.config
		if (!state.available) return null
		val target = TmdbTarget(if (context.mediaType == SeerrRepository.MEDIA_TV) TmdbTarget.TV else TmdbTarget.MOVIE, context.tmdbId)

		val mdblist = config.mdblistRatingsEnabled && config.mdblistRatingsShowOnItemDetails && !context.spoilerGuarded
		val seerrRatings = state.seerrUsable && !context.spoilerGuarded
		val providers = config.showElsewhereOnJellyseerr && config.elsewhereEnabled && config.tmdbEnabled
		val awards = config.showAwards
		if (!mdblist && !seerrRatings && !providers && !awards) return null

		return {
			val info by produceState<MetadataInfo?>(null, context.tmdbId, context.mediaType) {
				value = load(target, mdblist, seerrRatings, providers, awards, config.mdblistRatingsShowPercentSymbol)
			}
			info?.let { SeerrMetadataSection(it) }
		}
	}

	private suspend fun <T> optional(block: suspend () -> T): T? = try {
		block()
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.w(err, "Seerr details metadata source failed")
		null
	}

	private suspend fun load(
		target: TmdbTarget,
		mdblist: Boolean,
		seerrRatings: Boolean,
		providers: Boolean,
		awards: Boolean,
		showPercent: Boolean,
	) = coroutineScope {
		val config = optional { repository.publicConfig() } ?: MetadataPublicConfig()
		val mdblistResult = if (mdblist) async {
			optional { mdblistChips(repository.mdblistRatings(target), config.mdblistRatingsSources, showPercent) }
		} else null
		val seerrResult = if (seerrRatings) async { optional { repository.seerrRatings(target) } } else null
		val awardsResult = if (awards) async { optional { repository.awards(target) } } else null
		val providerResult = if (providers) async {
			optional {
				val elsewhere = optional { repository.elsewhereSettings() }
				val region = (elsewhere?.region ?: config.defaultRegion)?.trim()?.uppercase()?.ifEmpty { null } ?: "US"
				region to repository.watchProviders(target, region, elsewhere?.services.orEmpty(), config)
			}
		} else null

		val provider = providerResult?.await()
		MetadataInfo(
			ratings = mergeRatingChips(mdblistResult?.await().orEmpty(), seerrResult?.await().orEmpty()),
			awards = awardsResult?.await(),
			providers = provider?.second.orEmpty(),
			providerRegion = provider?.first,
		)
	}
}

/** The Seerr details variant of the info row: ratings and awards on one line, providers on the next. */
@Composable
private fun SeerrMetadataSection(info: MetadataInfo) {
	if (info.isEmpty) return
	var awards by remember { mutableStateOf<Awards?>(null) }
	MetadataInfoContent(
		initial = info,
		onProgressMode = {},
		onAwards = { awards = it },
		reload = { info },
	)
	DialogBase(
		visible = awards != null,
		onDismissRequest = { awards = null },
		contentAlignment = Alignment.CenterEnd,
	) {
		awards?.let { AwardsDialog(it) }
	}
}
