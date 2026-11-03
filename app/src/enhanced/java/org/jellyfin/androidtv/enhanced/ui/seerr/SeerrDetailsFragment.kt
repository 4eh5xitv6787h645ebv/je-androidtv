package org.jellyfin.androidtv.enhanced.ui.seerr

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrDetails
import org.jellyfin.androidtv.enhanced.seerr.SeerrImages
import org.jellyfin.androidtv.enhanced.seerr.SeerrMediaStatus
import org.jellyfin.androidtv.enhanced.seerr.SeerrPartialRequestSettings
import org.jellyfin.androidtv.enhanced.seerr.SeerrQuota
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrSeason
import org.jellyfin.androidtv.enhanced.seerr.SeerrSource
import org.jellyfin.androidtv.enhanced.seerrdetails.SeerrDates
import org.jellyfin.androidtv.enhanced.seerrdetails.SeerrDetailsExtras
import org.jellyfin.androidtv.enhanced.seerrdetails.SeerrDetailsLoader
import org.jellyfin.androidtv.enhanced.seerrdetails.SeerrMediaRequest
import org.jellyfin.androidtv.enhanced.seerrdetails.SeerrRequestStatus
import org.jellyfin.androidtv.enhanced.seerrdetails.SeerrSeasonAvailability
import org.jellyfin.androidtv.enhanced.ui.EnhancedDestinations
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.design.Tokens
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.Locale
import java.util.UUID

/** Details for a Seerr movie or show with request controls, the TV counterpart of JE's "More info" modal. */
class SeerrDetailsFragment : Fragment() {
	companion object {
		const val ARG_MEDIA_TYPE = "media_type"
		const val ARG_TMDB_ID = "tmdb_id"

		private const val MAX_CAST = 20
	}

	private val seerrRepository by inject<SeerrRepository>()
	private val enhancedRepository by inject<EnhancedRepository>()
	private val spoilerGuardRepository by inject<SpoilerGuardRepository>()
	private val enhancedApi by inject<EnhancedApi>()
	private val navigationRepository by inject<NavigationRepository>()

	private data class UiState(
		val loading: Boolean = true,
		val error: String? = null,
		val details: SeerrDetails? = null,
		/** Cast, requests, certifications… of the same details response. */
		val extras: SeerrDetailsExtras = SeerrDetailsExtras(),
		/** Season numbers of the library copy of a show, or null when unknown. */
		val librarySeasons: Set<Int>? = null,
		val enhanced: EnhancedState = EnhancedState(),
		val partial: SeerrPartialRequestSettings? = null,
		val quota: SeerrQuota? = null,
		val spoilerPending: Boolean? = null,
		/**
		 * For titles in the library while Spoiler Guard is on: the story fields as the server returns them for this
		 * user, already filtered by the plugin (direct, collection-inherited and watched-state rules alike).
		 */
		val libraryStory: LibraryStory? = null,
		/** Feature contributions (see [SeerrDetailsContributor]). */
		val extraActions: List<SeerrDetailsAction> = emptyList(),
		val sections: List<@Composable () -> Unit> = emptyList(),
		val busy: Boolean = false,
	)

	private val contributors: List<SeerrDetailsContributor>
		get() = org.koin.java.KoinJavaComponent.getKoin().getAll()

	/**
	 * Story fields and artwork of the library copy of a title, as the server returns them for this user. Null fields
	 * were removed by the server (or couldn't be loaded, in which case everything is withheld).
	 */
	private data class LibraryStory(
		val overview: String?,
		val tagline: String?,
		val rating: Double?,
		val year: String?,
		val posterUrl: String?,
		val backdropUrl: String?,
		/** The library copy's actors, as filtered by the server (empty when it strips cast). */
		val cast: List<SeerrDetailsCastEntry>,
	)

	private val state = MutableStateFlow(UiState())
	private val mediaType by lazy { requireArguments().getString(ARG_MEDIA_TYPE) ?: SeerrRepository.MEDIA_MOVIE }
	private val tmdbId by lazy { requireArguments().getInt(ARG_TMDB_ID) }
	private val isTv get() = mediaType == SeerrRepository.MEDIA_TV

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		reload()
	}

	override fun onStart() {
		super.onStart()
		// Coming back from the back stack: Spoiler Guard may have changed (e.g. via "Open in library"), so withhold
		// the library copy's fields and artwork until they're fetched again
		val current = state.value
		val media = current.details?.mediaInfo
		if (current.details != null && (media?.jellyfinMediaId != null || media?.jellyfinMediaId4k != null)) {
			state.value = current.copy(libraryStory = withheldStory())
			reload()
		}
	}

	private fun withheldStory() = LibraryStory(
		overview = getString(R.string.je_spoiler_overview_placeholder),
		tagline = null,
		rating = null,
		year = null,
		posterUrl = null,
		backdropUrl = null,
		cast = emptyList(),
	)

	private fun reload() {
		Timber.d("Refreshing Seerr details (%s/%d)", mediaType, tmdbId)
		state.value = state.value.copy(loading = true, error = null)
		lifecycleScope.launch {
			try {
				// coroutineScope so a failed call surfaces here instead of cancelling (and crashing) lifecycleScope
				val loaded = coroutineScope {
					val enhanced = enhancedRepository.ensureLoaded()
					val detailsCall = async { SeerrDetailsLoader.load(enhancedApi, mediaType, tmdbId) }
					val partialCall = async { if (isTv) seerrRepository.partialRequestSettings() else null }
					val quotaCall = async { if (enhanced.config.jellyseerrShowQuotaInfo) seerrRepository.quota() else null }
					val spoilerCall = async {
						if (enhanced.spoilerGuard) runCatching { spoilerGuardRepository.getState() }.getOrNull() else null
					}
					val bundle = detailsCall.await()
					val details = bundle.details
					val spoilerState = spoilerCall.await()
					// Seerr's JSON doesn't go through the plugin's Spoiler Guard filter. For titles in the library, show the
					// story fields from the server's own (filtered) copy instead
					val libraryId = (details.mediaInfo?.jellyfinMediaId ?: details.mediaInfo?.jellyfinMediaId4k)?.let(::parseUuid)
					val librarySeasonsCall = async { if (isTv && libraryId != null) librarySeasons(libraryId) else null }
					val libraryStory = if (enhanced.spoilerGuard && libraryId != null) libraryStory(libraryId) else null
					val contributionContext = SeerrDetailsContext(
						fragment = this@SeerrDetailsFragment,
						mediaType = mediaType,
						tmdbId = tmdbId,
						details = details,
						state = enhanced,
						libraryId = libraryId,
						spoilerGuarded = libraryStory != null,
						reload = { reload() },
					)
					val extraActions = contributors.flatMap { contributor ->
						runCatching { contributor.actions(contributionContext) }
							.onFailure { Timber.w(it, "Seerr details actions from %s failed", contributor::class.simpleName) }
							.getOrDefault(emptyList())
					}
					val sections = contributors.mapNotNull { contributor ->
						runCatching { contributor.section(contributionContext) }
							.onFailure { Timber.w(it, "Seerr details section from %s failed", contributor::class.simpleName) }
							.getOrNull()
					}
					UiState(
						loading = false,
						details = details,
						extras = bundle.extras,
						librarySeasons = librarySeasonsCall.await(),
						enhanced = enhanced,
						partial = partialCall.await(),
						quota = quotaCall.await(),
						spoilerPending = spoilerState?.let { spoilerGuardRepository.isPending(it, mediaType, tmdbId) },
						libraryStory = libraryStory,
						extraActions = extraActions,
						sections = sections,
					)
				}
				state.value = loaded
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to load Seerr details")
				state.value = state.value.copy(loading = false, busy = false, error = getString(R.string.je_error_generic))
			}
		}
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		val ui by state.collectAsState()
		val details = ui.details

		JellyfinTheme {
		Box(modifier = Modifier.fillMaxSize()) {
			val story = ui.libraryStory
			(if (story != null) story.backdropUrl else SeerrImages.backdrop(details?.backdropPath))?.let { url ->
				coil3.compose.AsyncImage(
					model = url,
					contentDescription = null,
					contentScale = ContentScale.Crop,
					modifier = Modifier.fillMaxSize(),
				)
			}
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(
						Brush.horizontalGradient(
							0f to Color.Black.copy(alpha = 0.95f),
							0.65f to Color.Black.copy(alpha = 0.75f),
							1f to Color.Black.copy(alpha = 0.45f),
						)
					)
			)

			when {
				details != null -> DetailsContent(ui, details)
				ui.error != null -> Column(
					modifier = Modifier.align(Alignment.Center),
					horizontalAlignment = Alignment.CenterHorizontally,
					verticalArrangement = Arrangement.spacedBy(16.dp),
				) {
					val retryFocus = remember { FocusRequester() }
					Text(ui.error!!, color = Tokens.Color.colorGrey100, fontSize = 20.sp)
					Button(onClick = { state.value = state.value.copy(error = null, loading = true); reload() }, modifier = Modifier.focusRequester(retryFocus)) {
						Text(stringResource(R.string.je_retry))
					}
					LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
				}
				else -> Text(stringResource(R.string.je_loading), color = Tokens.Color.colorGrey100, modifier = Modifier.align(Alignment.Center), fontSize = 20.sp)
			}
		}
		}
	}

	@Composable
	private fun DetailsContent(ui: UiState, details: SeerrDetails) {
		val focusRequester = remember { FocusRequester() }
		val media = details.mediaInfo
		val status = media?.effectiveStatus
		val story = ui.libraryStory
		val overview = if (story != null) story.overview else details.overview
		val tagline = if (story != null) story.tagline else details.tagline
		val rating = if (story != null) story.rating else details.voteAverage
		val year = if (story != null) story.year else details.year

		Row(
			modifier = Modifier
				.fillMaxSize()
				.padding(horizontal = 48.dp, vertical = 40.dp),
			horizontalArrangement = Arrangement.spacedBy(32.dp),
		) {
			Box(modifier = Modifier.size(200.dp, 300.dp)) {
				AsyncImage(
					url = if (ui.libraryStory != null) ui.libraryStory.posterUrl else SeerrImages.poster(details.posterPath, "w500"),
					aspectRatio = 2f / 3f,
					scaleType = ImageView.ScaleType.CENTER_CROP,
					modifier = Modifier.fillMaxSize(),
				)
			}

			// Scrolls so the rows below the buttons can be reached; focus brings them into view
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				Text(text = details.displayTitle, fontSize = 32.sp, color = Tokens.Color.colorWhite, maxLines = 2, overflow = TextOverflow.Ellipsis)

				val meta = listOfNotNull(
					year,
					details.runtime?.takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}m" },
					details.seasons.count { it.seasonNumber > 0 }.takeIf { isTv && it > 0 }?.let { "$it ${if (it == 1) "season" else "seasons"}" },
					rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
					details.genres.joinToString(", ") { it.name }.ifEmpty { null },
				).joinToString("   ·   ")
				if (meta.isNotEmpty()) Text(text = meta, color = Tokens.Color.colorGrey300, fontSize = 16.sp)
				// Age rating and original title come from Seerr/TMDB only, so not for protected library titles
				if (story == null) SeerrDetailsMetaStrip(extraMeta(ui, details))

				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					SeerrStatusBadge(status)
				}

				tagline?.takeIf { it.isNotBlank() }?.let { Text(text = it, color = Tokens.Color.colorGrey300, fontSize = 16.sp) }
				overview?.takeIf { it.isNotBlank() }?.let {
					Text(text = it, color = Tokens.Color.colorGrey100, fontSize = 16.sp, maxLines = 7, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(900.dp))
				}

				ui.error?.let { error ->
					val retryFocus = remember { FocusRequester() }
					Row(
						horizontalArrangement = Arrangement.spacedBy(12.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(error, color = Tokens.Color.colorGrey100, fontSize = 16.sp)
						Button(onClick = { reload() }, enabled = !ui.loading, modifier = Modifier.focusRequester(retryFocus)) {
							Text(stringResource(R.string.je_retry))
						}
					}
					LaunchedEffect(error) { runCatching { retryFocus.requestFocus() } }
				}

				Spacer(modifier = Modifier.height(8.dp))

				Row(
					horizontalArrangement = Arrangement.spacedBy(12.dp),
					modifier = Modifier.focusGroup(),
				) {
					var first = true
					fun Modifier.firstFocus(): Modifier = if (first) {
						first = false
						focusRequester(focusRequester)
					} else this

					if (ui.enhanced.seerrUsable) {
						requestLabel(ui, details)?.let { label ->
							Button(onClick = { startRequest(details) }, enabled = !ui.busy, modifier = Modifier.firstFocus()) {
								Text(stringResource(label))
							}
						}
					}

					val jellyfinId = media?.jellyfinMediaId ?: media?.jellyfinMediaId4k
					jellyfinId?.let(::parseUuid)?.let { id ->
						Button(onClick = { navigationRepository.navigate(Destinations.itemDetails(id)) }, modifier = Modifier.firstFocus()) {
							Text(stringResource(R.string.je_open_in_library))
						}
					}

					// Pre-acquisition Spoiler Guard opt-in, promoted by the plugin when the title arrives
					if (ui.spoilerPending != null && jellyfinId == null) {
						Button(onClick = { toggleSpoilerPending(details, !ui.spoilerPending) }, enabled = !ui.busy, modifier = Modifier.firstFocus()) {
							Text(stringResource(if (ui.spoilerPending) R.string.je_spoiler_pending else R.string.je_spoiler_enable_pending))
						}
					}

					for (action in ui.extraActions) {
						Button(onClick = action.onClick, enabled = !ui.busy, modifier = Modifier.firstFocus()) { Text(action.label) }
					}
				}

				if (!ui.enhanced.seerrUsable) {
					Text(stringResource(R.string.je_seerr_unavailable), color = Tokens.Color.colorGrey300)
				}

				ui.quota?.let { quota ->
					val side = if (isTv) quota.tv else quota.movie
					val limit = side?.limit ?: 0
					if (side != null && limit > 0) {
						Text(
							text = stringResource(
								R.string.je_quota,
								stringResource(if (isTv) R.string.je_quota_tv else R.string.je_quota_movie),
								side.remaining ?: (limit - (side.used ?: 0)),
								limit,
								side.days ?: 0,
							),
							color = Tokens.Color.colorGrey300,
							fontSize = 14.sp,
						)
					}
				}

				SeerrDetailsRequesters(requesterLines(ui.extras.standardRequests))

				// Feature sections (issues…), each handling its own loading and failures
				for (section in ui.sections) section()

				if (isTv) {
					SeerrDetailsSeasonsRow(seasonEntries(ui, details)) { season ->
						if (season.requestable && ui.enhanced.seerrUsable && !ui.busy && requestLabel(ui, details) != null) startRequest(details)
					}
				}

				SeerrDetailsCastRow(castEntries(ui)) { person ->
					navigationRepository.navigate(
						EnhancedDestinations.seerrDiscovery(
							SeerrSource.Person(
								jellyfinPersonId = person.jellyfinPersonId,
								tmdbPersonId = person.tmdbPersonId,
								name = person.name,
								title = getString(R.string.je_more_with_title, person.name),
							)
						)
					)
				}

				Spacer(modifier = Modifier.height(8.dp))
			}
		}

		// Buttons come and go as the status changes; keep focus on the first one rather than losing it
		LaunchedEffect(details.id, status, ui.spoilerPending, ui.loading, ui.error) {
			if (!ui.loading && ui.error == null) runCatching { focusRequester.requestFocus() }
		}
	}

	/** The label for the request button, or null when nothing can be requested. */
	private fun requestLabel(ui: UiState, details: SeerrDetails): Int? {
		val status = details.mediaInfo?.effectiveStatus
		return when {
			SeerrMediaStatus.isRequestable(status) -> R.string.je_request
			// TV shows can still request seasons that aren't available or requested yet, even when Seerr calls the
			// show available
			!isTv || requestableSeasons(details, ui).isEmpty() -> null
			status == SeerrMediaStatus.PARTIALLY_AVAILABLE || status == SeerrMediaStatus.AVAILABLE -> R.string.je_request_missing
			else -> R.string.je_request_more
		}
	}

	/** Secondary metadata from Seerr: age rating and original title. */
	private fun extraMeta(ui: UiState, details: SeerrDetails): List<String> {
		val countries = listOf(Locale.getDefault().country, "US").filter { it.isNotBlank() }.distinct()
		return listOfNotNull(
			ui.extras.certification(countries)?.let { getString(R.string.je_seerrdetails_rated, it) },
			ui.extras.originalTitleIfDifferent(details.displayTitle)?.let { getString(R.string.je_seerrdetails_original_title, it) },
		)
	}

	/** "Requested by X · date · status · seasons" for the latest few standard requests. */
	private fun requesterLines(requests: List<SeerrMediaRequest>): List<String> {
		val shown = 3
		val lines = requests.take(shown).map { request ->
			listOfNotNull(
				request.requestedBy?.label?.let { getString(R.string.je_seerrdetails_requested_by, it) }
					?: getString(R.string.je_seerrdetails_requested),
				SeerrDates.format(request.createdAt),
				when (request.status) {
					SeerrRequestStatus.PENDING -> R.string.je_seerrdetails_request_pending
					SeerrRequestStatus.APPROVED -> R.string.je_seerrdetails_request_approved
					SeerrRequestStatus.DECLINED -> R.string.je_seerrdetails_request_declined
					SeerrRequestStatus.FAILED -> R.string.je_seerrdetails_request_failed
					SeerrRequestStatus.COMPLETED -> R.string.je_seerrdetails_request_completed
					else -> null
				}?.let(::getString),
				request.seasons.map { it.seasonNumber }.filter { it > 0 }.distinct().sorted()
					.takeIf { isTv && it.isNotEmpty() }
					?.let { getString(R.string.je_seerrdetails_request_seasons, it.joinToString(", ")) },
			).joinToString("   ·   ")
		}
		return if (requests.size > shown) lines + getString(R.string.je_seerrdetails_more_requests, requests.size - shown) else lines
	}

	/** Season cards with each season's effective status. Seerr's season names are withheld for protected titles. */
	private fun seasonEntries(ui: UiState, details: SeerrDetails): List<SeerrDetailsSeasonEntry> {
		val guarded = ui.libraryStory != null
		return details.seasons
			.filter { it.seasonNumber > 0 || it.episodeCount > 0 }
			.distinctBy { it.seasonNumber }
			.sortedBy { it.seasonNumber }
			.map { season ->
				val status = SeerrSeasonAvailability.seasonStatus(details, ui.extras.requests, ui.librarySeasons, season.seasonNumber)
				SeerrDetailsSeasonEntry(
					seasonNumber = season.seasonNumber,
					label = when {
						season.seasonNumber == 0 -> getString(R.string.je_specials)
						guarded || season.name.isNullOrBlank() -> getString(R.string.je_season_number, season.seasonNumber)
						else -> season.name
					},
					episodeCount = season.episodeCount,
					status = status,
					requestable = season.episodeCount > 0 && SeerrMediaStatus.isRequestable(status),
				)
			}
	}

	/**
	 * The cast row: Seerr's credits for titles that aren't in the library, the server's own (Spoiler Guard filtered)
	 * people for library titles while Spoiler Guard is on. Nothing while those are withheld.
	 */
	private fun castEntries(ui: UiState): List<SeerrDetailsCastEntry> {
		ui.libraryStory?.let { return it.cast }
		return ui.extras.cast
			.sortedBy { it.order ?: Int.MAX_VALUE }
			.take(MAX_CAST)
			.map { member ->
				SeerrDetailsCastEntry(
					name = member.name.orEmpty(),
					role = member.character,
					imageUrl = SeerrImages.poster(member.profilePath, "w185"),
					tmdbPersonId = member.id,
					jellyfinPersonId = null,
				)
			}
	}

	private fun startRequest(details: SeerrDetails) {
		if (!isTv) {
			submitRequest(details, null)
			return
		}

		// Seerr can't hand a show to Sonarr without a TVDB id. When TMDB has none (common for anime) and Seerr hasn't
		// stored one, have the user pick a Sonarr match first, like the web's season dialog does.
		if (details.externalIds?.tvdbId == null) {
			val stored = details.mediaInfo?.tvdbId?.takeIf { it > 0 }
			if (stored != null) {
				pickSeasons(details, stored)
				return
			}
			lifecycleScope.launch {
				val candidates = try {
					seerrRepository.tvdbCandidates(details.id)
				} catch (err: CancellationException) {
					throw err
				} catch (err: Exception) {
					Timber.w(err, "Sonarr lookup failed")
					emptyList()
				}
				val context = context ?: return@launch
				if (candidates.isEmpty()) {
					toast(getString(R.string.je_tvdb_no_match), long = true)
					return@launch
				}
				val labels = candidates.map { c -> listOfNotNull(c.title, c.year?.toString(), "TVDB ${c.tvdbId}").joinToString(" · ") }.toTypedArray()
				AlertDialog.Builder(context)
					.setTitle(R.string.je_tvdb_pick_title)
					.setItems(labels) { _, which -> pickSeasons(details, candidates[which].tvdbId) }
					.setNegativeButton(android.R.string.cancel, null)
					.show()
			}
			return
		}

		pickSeasons(details, null)
	}

	/** Choose seasons and submit. [tvdbId] is the manual TVDB match, only when TMDB doesn't provide one. */
	private fun pickSeasons(details: SeerrDetails, tvdbId: Int?) {

		val partial = state.value.partial
		// Without partial requests Seerr wants the whole show: every regular season regardless of status, like the web
		if (partial?.partialRequestsEnabled == false) {
			val all = details.seasons.map { it.seasonNumber }.filter { it > 0 }.distinct().sorted()
			submitRequest(details, all.ifEmpty { null }, tvdbId)
			return
		}

		val requestable = requestableSeasons(details)
		if (requestable.isEmpty()) {
			toast(getString(R.string.je_nothing_found))
			return
		}

		val labels = requestable.map { season ->
			val name = if (season.seasonNumber == 0) getString(R.string.je_specials) else season.name ?: getString(R.string.je_season_number, season.seasonNumber)
			"$name  (${getString(R.string.je_episodes_count, season.episodeCount)})"
		}.toTypedArray()

		if (state.value.enhanced.config.jellyseerrSingleSeasonRequestOnly) {
			AlertDialog.Builder(context ?: return)
				.setTitle(R.string.je_request_seasons)
				.setItems(labels) { _, which -> submitRequest(details, listOf(requestable[which].seasonNumber), tvdbId) }
				.setNegativeButton(android.R.string.cancel, null)
				.show()
			return
		}

		val checked = BooleanArray(requestable.size) { true }
		AlertDialog.Builder(context ?: return)
			.setTitle(R.string.je_request_seasons)
			.setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
			.setPositiveButton(R.string.je_request) { _, _ ->
				val seasons = requestable.filterIndexed { index, _ -> checked[index] }.map { it.seasonNumber }
				if (seasons.isNotEmpty()) submitRequest(details, seasons, tvdbId)
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	/** Seasons that can still be requested, considering open requests and the library's own seasons. */
	private fun requestableSeasons(details: SeerrDetails, ui: UiState = state.value): List<SeerrSeason> =
		SeerrSeasonAvailability.requestableSeasons(
			details = details,
			requests = ui.extras.requests,
			librarySeasons = ui.librarySeasons,
			includeSpecials = ui.partial?.enableSpecialEpisodes == true,
		)

	/** Season numbers of the library copy, or null when they can't be loaded (Seerr's statuses apply then). */
	private suspend fun librarySeasons(id: UUID): Set<Int>? = try {
		SeerrDetailsLoader.librarySeasons(enhancedApi, id.toString())
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.w(err, "Failed to load library seasons")
		null
	}

	/** The library copy's story fields; failures reach the screen's Retry UI while cached fields stay withheld. */
	private suspend fun libraryStory(id: UUID): LibraryStory {
		val item = enhancedApi.get("/Items/$id", mapOf("userId" to enhancedApi.currentUserId())).jsonObject
		fun string(key: String) = (item[key] as? JsonPrimitive)?.contentOrNull
		val primaryTag = (item["ImageTags"] as? JsonObject)?.get("Primary")?.let { (it as? JsonPrimitive)?.contentOrNull }
		val backdropTag = (item["BackdropImageTags"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
		return LibraryStory(
			overview = string("Overview"),
			tagline = (item["Taglines"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull },
			rating = (item["CommunityRating"] as? JsonPrimitive)?.doubleOrNull,
			year = string("PremiereDate")?.take(4),
			// Server image routes, so the plugin's artwork protection applies
			posterUrl = primaryTag?.let { enhancedApi.imageUrl("/Items/$id/Images/Primary", it, 500) },
			backdropUrl = backdropTag?.let { enhancedApi.imageUrl("/Items/$id/Images/Backdrop/0", it, 1280) },
			cast = (item["People"] as? JsonArray).orEmpty()
				.mapNotNull { it as? JsonObject }
				.filter { (it["Type"] as? JsonPrimitive)?.contentOrNull == "Actor" }
				.take(MAX_CAST)
				.mapNotNull { person ->
					val name = (person["Name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
					val personId = (person["Id"] as? JsonPrimitive)?.contentOrNull
					val imageTag = (person["PrimaryImageTag"] as? JsonPrimitive)?.contentOrNull
					SeerrDetailsCastEntry(
						name = name,
						role = (person["Role"] as? JsonPrimitive)?.contentOrNull,
						imageUrl = if (personId != null && imageTag != null) enhancedApi.imageUrl("/Items/$personId/Images/Primary", imageTag, 300) else null,
						tmdbPersonId = null,
						jellyfinPersonId = personId,
					)
				},
		)
	}

	private fun toast(text: String, long: Boolean = false) {
		val context = context ?: return
		Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
	}

	private fun submitRequest(details: SeerrDetails, seasons: List<Int>?, tvdbId: Int? = null) {
		state.value = state.value.copy(busy = true)
		lifecycleScope.launch {
			try {
				seerrRepository.request(mediaType, details.id, seasons, tvdbId)
				toast(getString(R.string.je_request_sent, details.displayTitle), long = true)
			} catch (err: CancellationException) {
				throw err
			} catch (err: EnhancedApi.EnhancedApiException) {
				Timber.w(err, "Seerr request failed")
				toast(getString(R.string.je_request_failed, "HTTP ${err.status}"), long = true)
			} catch (err: Exception) {
				Timber.w(err, "Seerr request failed")
				toast(getString(R.string.je_request_failed, err.message ?: ""), long = true)
			}
			// Never retry blindly: reload to see what actually happened
			reload()
		}
	}

	private fun toggleSpoilerPending(details: SeerrDetails, enable: Boolean) {
		state.value = state.value.copy(busy = true)
		lifecycleScope.launch {
			try {
				spoilerGuardRepository.setPending(mediaType, details.id, details.displayTitle, enable)
				toast(getString(if (enable) R.string.je_spoiler_guard_on else R.string.je_spoiler_guard_off))
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Spoiler Guard pending toggle failed")
				toast(getString(R.string.je_error_generic))
			}
			reload()
		}
	}

	private fun parseUuid(id: String): UUID? = runCatching {
		val hex = id.replace("-", "")
		if (hex.length != 32) return@runCatching null
		UUID.fromString("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
	}.getOrNull()
}
