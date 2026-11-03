package org.jellyfin.androidtv.enhanced.metadata

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.leanback.widget.Row as LeanbackRow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedSettingsRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.SpoilerProtection
import org.jellyfin.androidtv.enhanced.ui.details.DetailsAction
import org.jellyfin.androidtv.enhanced.ui.details.DetailsContext
import org.jellyfin.androidtv.enhanced.ui.details.DetailsContributor
import org.jellyfin.androidtv.enhanced.ui.details.DetailsRowPlacement
import org.jellyfin.androidtv.enhanced.ui.details.EnhancedComposeRow
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PersonKind
import timber.log.Timber
import java.util.UUID

/**
 * Details-screen enrichments: one "Enhanced info" row (size, watched progress, audio languages, release dates,
 * ratings, awards, where to watch, a person's birth facts), a "Cast ages" row, and a "Content warnings" button.
 * Every source loads independently: one failing never hides the others, and each needs its own gate. External data
 * follows the item's Spoiler Guard protection category by category ([SpoilerProtection]).
 */
class MetadataDetailsContributor(
	private val repository: MetadataRepository,
	private val settingsRepository: EnhancedSettingsRepository,
	private val navigationRepository: NavigationRepository,
	private val spoilerProtection: SpoilerProtection,
) : DetailsContributor {
	companion object {
		private const val SOURCE_TIMEOUT_MS = 15_000L
		private const val ACTION_PRIORITY = 60

		/** Types that show the size/progress chips (the web's FEATURES_SUPPORTED_TYPES, minus playlists). */
		private val STATS_TYPES = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE, BaseItemKind.BOX_SET)
		private val TV_TYPES = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE)
		private val TITLE_TYPES = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
		private val CAST_KINDS = setOf(PersonKind.ACTOR, PersonKind.GUEST_STAR)

		/** Guest stars give away who turns up; the plugin's default cast strip drops only them. */
		private val PROTECTED_CAST_KINDS = setOf(PersonKind.ACTOR)
	}

	/** Run one optional source; a failure is logged and recorded under [label] instead of failing the row. */
	private suspend fun <T> attempt(failures: MutableList<Int>, @StringRes label: Int, block: suspend () -> T): T? = try {
		withTimeout(SOURCE_TIMEOUT_MS) { block() }
	} catch (err: TimeoutCancellationException) {
		Timber.w(err, "Metadata source timed out")
		synchronized(failures) { failures += label }
		null
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.w(err, "Metadata source failed")
		synchronized(failures) { failures += label }
		null
	}

	override suspend fun actions(context: DetailsContext): List<DetailsAction> {
		val state = context.state
		val item = context.item
		if (!state.available || !state.config.doesTheDogDieEnabled || item.type !in TV_TYPES) return emptyList()
		// Content warnings are spoiler phrases ("a main character dies"), the same kind of data as stripped tags
		if (spoilerProtection.effectiveFor(item).hideTags) return emptyList()
		val target = runCatching { repository.tmdbTarget(item) }.getOrNull() ?: return emptyList()

		val (entry, triggers) = coroutineScope {
			val entry = async {
				try {
					LoadState.Ready(repository.contentWarnings(target))
				} catch (err: CancellationException) {
					throw err
				} catch (err: Exception) {
					Timber.w(err, "Failed to load content warnings")
					LoadState.Error(err.displayMessage()) {}
				}
			}
			val triggers = async { runCatching { repository.triggers() }.getOrDefault(emptySet()) }
			entry.await() to triggers.await()
		}

		val fragment = context.fragment
		val label = when (entry) {
			is LoadState.Ready -> {
				val warnings = ContentWarnings.of(entry.value, triggers)
				if (!entry.value.found || warnings.isEmpty) return emptyList()
				if (warnings.triggered.isNotEmpty()) fragment.getString(R.string.je_metadata_content_warnings_count, warnings.triggered.size)
				else fragment.getString(R.string.je_metadata_content_warnings)
			}

			// Still offer the list so the user sees why it failed and can retry
			else -> fragment.getString(R.string.je_metadata_content_warnings)
		}
		val initial: LoadState<DddEntry> = if (entry is LoadState.Ready) entry else LoadState.Loading

		return listOf(DetailsAction(R.drawable.je_ic_metadata_warning, label, ACTION_PRIORITY) {
			showComposeDialog(fragment) { ContentWarningsDialog(initial) { repository.contentWarnings(target) } }
		})
	}

	override suspend fun rows(context: DetailsContext): List<LeanbackRow> {
		val state = context.state
		if (!state.available) return emptyList()
		val config = runCatching { repository.publicConfig() }
			.onFailure { Timber.w(it, "Failed to load public config for metadata") }
			.getOrNull() ?: return emptyList()

		val rows = mutableListOf<LeanbackRow>()
		val info = loadInfo(context.item, state, config)
		if (!info.isEmpty) {
			val fragment = context.fragment
			val infoState = mutableStateOf(info)
			val progressModeState = mutableStateOf(info.progressMode)
			// Under the overview: it's about the item itself, like the overview's own info line
			rows += EnhancedComposeRow(fragment.getString(R.string.je_metadata_info_row), placement = DetailsRowPlacement.TOP) {
				MetadataInfoContent(
					infoState = infoState,
					progressModeState = progressModeState,
					onProgressMode = { mode -> saveProgressMode(fragment, mode) },
					onAwards = { awards -> showComposeDialog(fragment) { AwardsDialog(awards) } },
					reload = { loadInfo(context.item, state, config) },
				)
			}
		}
		castAgesRow(context, state, config)?.let { rows += it }
		return rows
	}

	/** Remember the chosen watch-progress display, as the web does when the chip is clicked. */
	private fun saveProgressMode(fragment: Fragment, mode: WatchProgressMode) {
		val owner = fragment.viewLifecycleOwnerLiveData.value ?: return
		owner.lifecycleScope.launch {
			try {
				settingsRepository.setSetting("WatchProgressMode", mode.key)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to save the watch progress mode")
			}
		}
	}

	private suspend fun loadInfo(item: BaseItemDto, state: EnhancedState, config: MetadataPublicConfig): MetadataInfo = coroutineScope {
		val failures = mutableListOf<Int>()
		val settings = state.userSettings
		val publicConfig = state.config
		val protection = spoilerProtection.effectiveFor(item)
		val target = if (item.type in TV_TYPES || item.type == BaseItemKind.PERSON) {
			attempt(failures, R.string.je_metadata_source_releases) { repository.tmdbTarget(item) }
		} else null

		val showSize = (settings.showFileSizes ?: config.showFileSizes) && item.type in STATS_TYPES
		val showProgress = (settings.showWatchProgress ?: config.showWatchProgress) && item.type in STATS_TYPES
		val stats = if (showSize || showProgress) async {
			attempt(failures, R.string.je_metadata_source_stats) { repository.itemStats(item.id) }
		} else null

		val showAudio = (settings.showAudioLanguages ?: config.showAudioLanguages) && item.type in TV_TYPES
		val audio = if (showAudio) async {
			attempt(failures, R.string.je_metadata_source_audio) {
				repository.audioLanguages(item.id) ?: audioLanguages(fallbackStreams(item))
			}
		} else null

		val tmdb = publicConfig.tmdbEnabled
		// Region and services for release dates and providers; a failed read falls back to the server defaults
		val elsewhere = if (target != null && tmdb && (publicConfig.showReleaseDates || publicConfig.elsewhereEnabled)) async {
			try {
				repository.elsewhereSettings()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to read Elsewhere settings")
				null
			}
		} else null
		val releases = if (target != null && tmdb && publicConfig.showReleaseDates && item.type in TV_TYPES && !protection.hideAirDate) async {
			attempt(failures, R.string.je_metadata_source_releases) {
				repository.releaseInfo(target, releaseRegions(elsewhere?.await()?.region, config.defaultRegion))
			}
		} else null

		val titleTarget = target?.takeIf { item.type in TITLE_TYPES }
		val mdblist = if (titleTarget != null && publicConfig.mdblistRatingsEnabled && publicConfig.mdblistRatingsShowOnItemDetails && !protection.hideRatings) async {
			attempt(failures, R.string.je_metadata_source_ratings) {
				mdblistChips(repository.mdblistRatings(titleTarget), config.mdblistRatingsSources, publicConfig.mdblistRatingsShowPercentSymbol)
			}
		} else null
		val seerr = if (titleTarget != null && state.seerrUsable && !protection.hideRatings) async {
			attempt(failures, R.string.je_metadata_source_ratings) { repository.seerrRatings(titleTarget) }
		} else null

		val awardsTarget = target?.takeIf { item.type in TITLE_TYPES || item.type == BaseItemKind.PERSON }
		val awards = if (awardsTarget != null && publicConfig.showAwards) async {
			attempt(failures, R.string.je_metadata_source_awards) { repository.awards(awardsTarget) }
		} else null

		val providers = if (titleTarget != null && tmdb && publicConfig.elsewhereEnabled) async {
			attempt(failures, R.string.je_metadata_source_providers) {
				val settings = elsewhere?.await()
				val region = (settings?.region ?: config.defaultRegion)?.trim()?.uppercase()?.ifEmpty { null } ?: "US"
				region to repository.watchProviders(titleTarget, region, settings?.services.orEmpty(), config)
			}
		} else null

		val peopleTags = settings.peopleTagsEnabled ?: config.peopleTagsEnabled
		val person = if (item.type == BaseItemKind.PERSON && peopleTags) async {
			attempt(failures, R.string.je_metadata_source_person) { repository.peopleInfo(listOf(item.id), null)[key(item.id)] }
		} else null

		val providerResult = providers?.await()
		MetadataInfo(
			stats = stats?.await(),
			showSize = showSize,
			showProgress = showProgress,
			progressMode = WatchProgressMode.of(settings.watchProgressMode ?: config.watchProgressDefaultMode),
			timeFormat = settings.watchProgressTimeFormat ?: config.watchProgressTimeFormat,
			audioLanguages = audio?.await().orEmpty(),
			releases = releases?.await().orEmpty(),
			releaseIsEpisode = item.type == BaseItemKind.EPISODE,
			ratings = mergeRatingChips(mdblist?.await().orEmpty(), seerr?.await().orEmpty()),
			awards = awards?.await(),
			providers = providerResult?.second.orEmpty(),
			providerRegion = providerResult?.first,
			person = person?.await()?.takeIf { it.birthDate != null || it.deathDate != null || !it.birthPlace.isNullOrBlank() },
			failures = synchronized(failures) { failures.distinct() },
		)
	}

	/** The item's own streams, for when the plugin's `tag-data` didn't return it. */
	private fun fallbackStreams(item: BaseItemDto): List<TagDataStream> =
		(item.mediaStreams.orEmpty() + item.mediaSources.orEmpty().flatMap { it.mediaStreams.orEmpty() })
			.map { TagDataStream(type = it.type.serialName, language = it.language) }

	private fun key(id: UUID) = id.toString().replace("-", "").lowercase()

	/** A compact row of the cast with their age when the title came out (and now, or at death). */
	private suspend fun castAgesRow(context: DetailsContext, state: EnhancedState, config: MetadataPublicConfig): LeanbackRow? {
		val item = context.item
		if (item.type !in TITLE_TYPES && item.type != BaseItemKind.EPISODE) return null
		if (!(state.userSettings.peopleTagsEnabled ?: config.peopleTagsEnabled)) return null
		val protection = spoilerProtection.effectiveFor(item)
		val kinds = if (protection.hideCast) PROTECTED_CAST_KINDS else CAST_KINDS
		val cast = item.people.orEmpty().filter { it.type in kinds }.distinctBy { it.id }.take(MetadataRepository.MAX_PEOPLE)
		if (cast.isEmpty()) return null

		val info = runCatching { repository.peopleInfo(cast.map { it.id }, item.id) }
			.onFailure { Timber.w(it, "Failed to load cast ages") }
			.getOrNull() ?: return null
		val entries = cast.mapNotNull { person -> info[key(person.id)]?.let { person to it } }
			// The age at release gives the air date away
			.map { (person, facts) -> person to if (protection.hideAirDate) facts.copy(ageAtItemRelease = null) else facts }
			.filter { (_, facts) -> facts.ageAtItemRelease != null || facts.currentAge != null || facts.ageAtDeath != null }
		if (entries.isEmpty()) return null

		val fragment = context.fragment
		return EnhancedComposeRow(fragment.getString(R.string.je_metadata_cast_ages)) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.horizontalScroll(rememberScrollState())
					.padding(vertical = 4.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				for ((person, facts) in entries) {
					val parts = listOfNotNull(
						person.name,
						facts.ageAtItemRelease?.let { fragment.getString(R.string.je_metadata_age_at_release, it) },
						if (facts.isDeceased) facts.ageAtDeath?.let { fragment.getString(R.string.je_metadata_age_died, it) }
						else facts.currentAge?.let { fragment.getString(R.string.je_metadata_age_now, it) },
					)
					MetadataChip(
						text = parts.joinToString(" · "),
						onClick = { navigationRepository.navigate(Destinations.itemDetails(person.id)) },
					)
				}
			}
		}
	}
}
