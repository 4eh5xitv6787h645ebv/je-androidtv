package org.jellyfin.androidtv.enhanced.ui.seerr

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerProtection
import org.jellyfin.androidtv.enhanced.seerr.SeerrDetails
import org.jellyfin.androidtv.enhanced.seerr.SeerrErrors
import org.jellyfin.androidtv.enhanced.seerr.SeerrImages
import org.jellyfin.androidtv.enhanced.seerr.SeerrLink
import org.jellyfin.androidtv.enhanced.seerr.SeerrLinkKind
import org.jellyfin.androidtv.enhanced.seerr.SeerrLinkManager
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
import org.jellyfin.androidtv.enhanced.ui.design.AgeRatingTag
import org.jellyfin.androidtv.enhanced.ui.design.ErrorState
import org.jellyfin.androidtv.enhanced.ui.design.HeroHeader
import org.jellyfin.androidtv.enhanced.ui.design.HeroScaffold
import org.jellyfin.androidtv.enhanced.ui.design.InfoLeading
import org.jellyfin.androidtv.enhanced.ui.design.JeButton
import org.jellyfin.androidtv.enhanced.ui.design.JeChoiceDialog
import org.jellyfin.androidtv.enhanced.ui.design.JeColors
import org.jellyfin.androidtv.enhanced.ui.design.JeSpace
import org.jellyfin.androidtv.enhanced.ui.design.JeStatuses
import org.jellyfin.androidtv.enhanced.ui.design.JeType
import org.jellyfin.androidtv.enhanced.ui.design.LabeledIconButton
import org.jellyfin.androidtv.enhanced.ui.design.LoadingSkeleton
import org.jellyfin.androidtv.enhanced.ui.design.MediaCard
import org.jellyfin.androidtv.enhanced.ui.design.MetaLine
import org.jellyfin.androidtv.enhanced.ui.design.MetaRating
import org.jellyfin.androidtv.enhanced.ui.design.Notice
import org.jellyfin.androidtv.enhanced.ui.design.NoticeKind
import org.jellyfin.androidtv.enhanced.ui.design.SeasonCard
import org.jellyfin.androidtv.enhanced.ui.design.SectionHeader
import org.jellyfin.androidtv.enhanced.ui.design.SkeletonShape
import org.jellyfin.androidtv.enhanced.ui.design.StatusBadge
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.android.ext.android.inject
import timber.log.Timber

/** Details for a Seerr movie or show with request controls, the TV counterpart of JE's "More info" modal. */
class SeerrDetailsFragment : Fragment() {
	companion object {
		const val ARG_MEDIA_TYPE = "media_type"
		const val ARG_TMDB_ID = "tmdb_id"

		private const val MAX_CAST = 20
	}

	private val seerrRepository by inject<SeerrRepository>()
	private val enhancedRepository by inject<EnhancedRepository>()
	private val seerrLinkManager by inject<SeerrLinkManager>()
	private val spoilerGuardRepository by inject<SpoilerGuardRepository>()
	private val spoilerProtection by inject<SpoilerProtection>()
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
		/** A request or Spoiler Guard write in flight; its button says so and the others ignore clicks meanwhile. */
		val busy: Busy? = null,
	)

	private enum class Busy { REQUEST, PENDING }

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

	/** A choice the user is making (TVDB match, seasons to request), shown as a [JeChoiceDialog]. */
	private class ChoiceDialog(
		val title: String,
		val options: List<String>,
		val captions: List<String?>? = null,
		val multi: Boolean = false,
		val selected: Set<Int> = emptySet(),
		val confirmLabel: String? = null,
		val onConfirm: (Set<Int>) -> Unit,
	)

	private val dialog = MutableStateFlow<ChoiceDialog?>(null)

	/** [SpoilerGuardRepository.generation] when the shown protection was resolved; a change means it may be stale. */
	private var spoilerGeneration = -1L
	private val mediaType by lazy { requireArguments().getString(ARG_MEDIA_TYPE) ?: SeerrRepository.MEDIA_MOVIE }
	private val tmdbId by lazy { requireArguments().getInt(ARG_TMDB_ID) }
	private val isTv get() = mediaType == SeerrRepository.MEDIA_TV

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		reload()
	}

	override fun onStart() {
		super.onStart()
		// Coming back from the back stack: Spoiler Guard may have changed (e.g. via "Open in library", or a pending
		// opt-in made elsewhere), so withhold the story fields and artwork until protection is resolved again
		val current = state.value
		val media = current.details?.mediaInfo
		if (current.details == null || !current.enhanced.spoilerGuardMayApply) return
		val inLibrary = media?.jellyfinMediaId != null || media?.jellyfinMediaId4k != null
		if (inLibrary || current.libraryStory != null || spoilerGeneration != spoilerGuardRepository.generation) {
			state.value = current.copy(libraryStory = withheldStory(requireContext()))
		}
		reload()
	}

	/** Everything story-related withheld: protection applies but the server's filtered copy isn't (yet) known. */
	private fun withheldStory(context: Context) = LibraryStory(
		overview = context.getString(R.string.je_spoiler_overview_placeholder),
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
		// Strings for results that arrive after the screen is gone (it stays loaded on the back stack)
		val appContext = requireContext().applicationContext
		lifecycleScope.launch {
			try {
				// coroutineScope so a failed call surfaces here instead of cancelling (and crashing) lifecycleScope
				val loaded = coroutineScope {
					val enhanced = enhancedRepository.ensureLoaded()
					val generation = spoilerGuardRepository.generation
					val detailsCall = async { SeerrDetailsLoader.load(enhancedApi, mediaType, tmdbId) }
					val partialCall = async { if (isTv) seerrRepository.partialRequestSettings() else null }
					val quotaCall = async { if (enhanced.config.jellyseerrShowQuotaInfo) seerrRepository.quota() else null }
					// Never throws: lists that can't be read give a snapshot that protects everything
					val spoilerCall = async { if (enhanced.spoilerGuardMayApply) spoilerProtection.snapshot() else null }
					val bundle = detailsCall.await()
					val details = bundle.details
					val snapshot = spoilerCall.await()
					// Seerr's JSON doesn't go through the plugin's Spoiler Guard filter. For titles in the library, show the
					// story fields from the server's own (filtered) copy instead. Titles that aren't there yet are protected
					// by a pending opt-in, and withheld when the user's lists couldn't be read
					val libraryId = (details.mediaInfo?.jellyfinMediaId ?: details.mediaInfo?.jellyfinMediaId4k)?.let(::parseUuid)
					val librarySeasonsCall = async { if (isTv && libraryId != null) librarySeasons(libraryId) else null }
					val libraryStory = when {
						snapshot == null -> null
						libraryId != null -> libraryStory(libraryId)
						snapshot.forTitle(isMovie = !isTv, libraryId = null, tmdbId = tmdbId).guarded -> withheldStory(appContext)
						else -> null
					}
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
						// The opt-in button needs the lists themselves (not a fail-closed guess)
						spoilerPending = snapshot?.state?.takeIf { enhanced.spoilerGuard }?.let { snapshot.isPending(mediaType, tmdbId) },
						libraryStory = libraryStory,
						extraActions = extraActions,
						sections = sections,
					).also { spoilerGeneration = generation }
				}
				state.value = loaded
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to load Seerr details")
				state.value = state.value.copy(loading = false, busy = null, error = seerrError(appContext, err) ?: appContext.getString(R.string.je_error_generic))
			}
		}
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		val ui by state.collectAsState()
		val choice by dialog.collectAsState()
		val details = ui.details

		JellyfinTheme {
			if (details != null) {
				DetailsContent(ui, details)
			} else {
				Box(modifier = Modifier.fillMaxSize().background(JeColors.background)) {
					val error = ui.error
					if (error != null) ErrorState(message = error, onRetry = { reload() })
					else LoadingSkeleton(SkeletonShape.Hero)
				}
			}

			choice?.let { current ->
				// Keyed so a dialog that follows another (TVDB pick → seasons) starts with its own selection
				key(current) {
					JeChoiceDialog(
						visible = true,
						title = current.title,
						options = current.options,
						selected = current.selected,
						multi = current.multi,
						captions = current.captions,
						confirmLabel = current.confirmLabel ?: stringResource(R.string.je_design_done),
						onConfirm = current.onConfirm,
						// Only this dialog: a choice may already have opened the next one
						onDismissRequest = { dialog.compareAndSet(current, null) },
					)
				}
			}
		}
	}

	@Composable
	private fun DetailsContent(ui: UiState, details: SeerrDetails) {
		val firstAction = remember { FocusRequester() }
		val media = details.mediaInfo
		val status = media?.effectiveStatus
		val story = ui.libraryStory
		val overview = if (story != null) story.overview else details.overview
		val rating = if (story != null) story.rating else details.voteAverage
		val year = if (story != null) story.year else details.year
		val requests = requesterLines(ui.extras.standardRequests)
		val seasons = if (isTv) seasonEntries(ui, details) else emptyList()
		val cast = castEntries(ui)
		val statusSpec = JeStatuses.seerrMedia(status)
		// Age rating and original title come from Seerr/TMDB only, so not for protected library titles
		val certification = if (story == null) ui.extras.certification(certificationCountries()) else null
		val originalTitle = if (story == null) ui.extras.originalTitleIfDifferent(details.displayTitle) else null

		HeroScaffold(
			backdropUrl = if (story != null) story.backdropUrl else SeerrImages.backdrop(details.backdropPath),
			showToolbar = false,
			header = { collapsed ->
				HeroHeader(
					title = details.displayTitle,
					status = if (statusSpec == null && requests.isEmpty()) null else {
						{
							statusSpec?.let { StatusBadge(it) }
							requests.firstOrNull()?.let { Text(text = it, color = JeColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
						}
					},
					meta = {
						val seasonCount = details.seasons.count { it.seasonNumber > 0 }
						listOfNotNull(
							year,
							details.runtime?.takeIf { it > 0 }?.let { stringResource(R.string.je_seerrdetails_runtime, it / 60, it % 60) },
							seasonCount.takeIf { isTv && it > 0 }?.let { pluralStringResource(R.plurals.je_seerrdetails_season_count, it, it) },
						).forEach { Text(text = it, color = JeColors.onSurface, maxLines = 1) }
						certification?.let { AgeRatingTag(it) }
						rating?.takeIf { it > 0 }?.let {
							MetaRating(String.format(Locale.getDefault(), "%.1f", it), InfoLeading.Icon(R.drawable.ic_star, JeColors.ratingStar))
						}
						MetaLine(details.genres.map { it.name })
					},
					overview = overview?.takeIf { it.isNotBlank() },
					collapsed = collapsed,
					extra = {
						HeroNotes(ui, requests.drop(1), originalTitle)
					},
				)
				ui.error?.let { error ->
					// A reload failed while the last details stay on screen
					Spacer(Modifier.height(JeSpace.sectionHeaderGap))
					Row(horizontalArrangement = Arrangement.spacedBy(JeSpace.actionGap), verticalAlignment = Alignment.CenterVertically) {
						Notice(kind = NoticeKind.Error, title = error, inline = true, modifier = Modifier.weight(1f, fill = false))
						val retryFocus = remember { FocusRequester() }
						JeButton(text = stringResource(R.string.je_retry), onClick = { reload() }, busy = ui.loading, modifier = Modifier.focusRequester(retryFocus))
						LaunchedEffect(error) { runCatching { retryFocus.requestFocus() } }
					}
				}
			},
			actions = { DetailsActions(ui, details, firstAction) },
			below = {
				// Feature sections (ratings and providers, issues…), each handling its own loading and failures
				ui.sections.forEachIndexed { index, section ->
					item(key = "section-$index") {
						Box(modifier = Modifier.padding(horizontal = JeSpace.pageHorizontal)) { section() }
					}
				}
				if (seasons.isNotEmpty()) item(key = "seasons") {
					DetailsRow(stringResource(R.string.je_seerrdetails_seasons)) {
						items(seasons, key = { it.seasonNumber }) { season ->
							SeasonCard(
								name = season.label,
								episodes = season.episodeCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.je_seerrdetails_episodes, it, it) },
								status = JeStatuses.seerrSeason(season.status),
								onClick = {
									if (season.requestable && ui.enhanced.seerrUsable && ui.busy == null && requestLabel(ui, details) != null) startRequest(details)
								},
							)
						}
					}
				}
				if (cast.isNotEmpty()) item(key = "cast") {
					DetailsRow(stringResource(R.string.je_seerrdetails_cast)) {
						items(cast, key = { "${it.tmdbPersonId}-${it.jellyfinPersonId}-${it.name}" }) { person ->
							MediaCard(
								imageUrl = person.imageUrl,
								title = person.name,
								subtitle = person.role?.takeIf { it.isNotBlank() },
								fallbackIcon = R.drawable.ic_user,
								onClick = { openPerson(person) },
							)
						}
					}
				}
			},
		)

		// Buttons come and go as the status changes; keep focus on the first one rather than losing it
		LaunchedEffect(details.id, status, ui.spoilerPending, ui.loading, ui.error) {
			if (!ui.loading && ui.error == null) runCatching { firstAction.requestFocus() }
		}
	}

	/** The hero's secondary lines, hidden while the rows below have the focus: more requests, quota, why Seerr is off. */
	@Composable
	private fun HeroNotes(ui: UiState, moreRequests: List<String>, originalTitle: String?) {
		val lines = buildList {
			originalTitle?.let { add(stringResource(R.string.je_seerrdetails_original_title, it)) }
			addAll(moreRequests)
			ui.quota?.let { quota ->
				val side = if (isTv) quota.tv else quota.movie
				val limit = side?.limit ?: 0
				if (side != null && limit > 0) add(
					stringResource(
						R.string.je_quota,
						stringResource(if (isTv) R.string.je_quota_tv else R.string.je_quota_movie),
						side.remaining ?: (limit - (side.used ?: 0)),
						limit,
						side.days ?: 0,
					)
				)
			}
		}
		for (line in lines) Text(text = line, style = JeType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
		if (!ui.enhanced.seerrUsable) {
			// Why, when it's the user's Seerr link (not linked, blocked, unreachable)
			val link = SeerrLink.kind(ui.enhanced)
			Notice(
				kind = NoticeKind.Warning,
				title = if (link == SeerrLinkKind.OFF || link == SeerrLinkKind.LINKED) stringResource(R.string.je_seerr_unavailable)
				else SeerrErrors.forLink(link, ui.enhanced.isAdmin).text.resolve(),
				inline = true,
			)
		}
	}

	/**
	 * The hero's action row: the main action as a text button (Request / Request more, else Open in library), then the
	 * rest as labelled icon buttons. [firstAction] goes to whichever comes first.
	 */
	@Composable
	private fun RowScope.DetailsActions(ui: UiState, details: SeerrDetails, firstAction: FocusRequester) {
		val media = details.mediaInfo
		val jellyfinId = (media?.jellyfinMediaId ?: media?.jellyfinMediaId4k)?.let(::parseUuid)
		val requestLabel = if (ui.enhanced.seerrUsable) requestLabel(ui, details) else null
		val openInLibrary = jellyfinId?.let { id -> { navigationRepository.navigate(Destinations.itemDetails(id)) } }
		var first = true
		fun Modifier.firstFocus(): Modifier = if (first) {
			first = false
			focusRequester(firstAction)
		} else this

		// Primary: centred on the 48 dp icon circles like ActionRow does
		if (requestLabel != null || openInLibrary != null) {
			Box(modifier = Modifier.height(48.dp).padding(end = 4.dp), contentAlignment = Alignment.CenterStart) {
				if (requestLabel != null) {
					// Stays focusable while busy so focus doesn't jump away; the label says what's happening
					JeButton(
						text = stringResource(if (ui.busy == Busy.REQUEST) R.string.je_seerr_requesting else requestLabel),
						icon = R.drawable.ic_add,
						busy = ui.busy == Busy.REQUEST,
						onClick = { if (ui.busy == null) startRequest(details) },
						modifier = Modifier.firstFocus(),
					)
				} else if (openInLibrary != null) {
					JeButton(text = stringResource(R.string.je_open_in_library), icon = R.drawable.ic_folder, onClick = openInLibrary, modifier = Modifier.firstFocus())
				}
			}
		}

		if (requestLabel != null && openInLibrary != null) {
			LabeledIconButton(icon = R.drawable.ic_folder, label = stringResource(R.string.je_open_in_library), onClick = openInLibrary, modifier = Modifier.firstFocus())
		}

		// Pre-acquisition Spoiler Guard opt-in, promoted by the plugin when the title arrives
		val pending = ui.spoilerPending
		if (pending != null && jellyfinId == null) {
			LabeledIconButton(
				icon = if (pending) R.drawable.je_ic_blur_on else R.drawable.je_ic_blur_off,
				label = stringResource(when {
					ui.busy == Busy.PENDING -> R.string.je_seerr_saving
					pending -> R.string.je_spoiler_pending
					else -> R.string.je_spoiler_enable_pending
				}),
				busy = ui.busy == Busy.PENDING,
				onClick = { if (ui.busy == null) toggleSpoilerPending(details, !pending) },
				modifier = Modifier.firstFocus(),
			)
		}

		for (action in ui.extraActions) {
			LabeledIconButton(
				icon = action.icon ?: fallbackIcon(action),
				label = action.label,
				onClick = { if (ui.busy == null) action.onClick() },
				modifier = Modifier.firstFocus(),
			)
		}
	}

	/**
	 * An icon for a contributed action that doesn't bring one. Hide/Unhide (Hidden Content's contributor) is known by
	 * its label until it passes its own icon.
	 */
	private fun fallbackIcon(action: SeerrDetailsAction): Int = when (action.label) {
		getString(R.string.je_hide) -> R.drawable.je_ic_visibility_off
		getString(R.string.je_unhide) -> R.drawable.je_ic_visibility
		else -> R.drawable.ic_more
	}

	/** A titled horizontal row under the hero; the cards scroll edge to edge, the header keeps the page inset. */
	@Composable
	private fun DetailsRow(title: String, content: LazyListScope.() -> Unit) {
		Column(verticalArrangement = Arrangement.spacedBy(JeSpace.sectionHeaderGap)) {
			SectionHeader(text = title, modifier = Modifier.padding(horizontal = JeSpace.pageHorizontal))
			LazyRow(
				modifier = Modifier.focusRestorer().focusGroup(),
				horizontalArrangement = Arrangement.spacedBy(JeSpace.gridGap),
				contentPadding = PaddingValues(horizontal = JeSpace.pageHorizontal),
				content = content,
			)
		}
	}

	private fun openPerson(person: SeerrDetailsCastEntry) {
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

	/** Where the age rating is looked up: the TV's country, then the US. */
	private fun certificationCountries() = listOf(Locale.getDefault().country, "US").filter { it.isNotBlank() }.distinct()

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
			).joinToString(" · ")
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
					label = seasonLabel(season, guarded),
					episodeCount = season.episodeCount,
					status = status,
					requestable = season.episodeCount > 0 && SeerrMediaStatus.isRequestable(status),
				)
			}
	}

	/** A season's name; Seerr's own names (which can give the story away) only for titles that aren't protected. */
	private fun seasonLabel(season: SeerrSeason, guarded: Boolean): String =
		SeerrSeasonLabels.customName(season, guarded)
			?: if (season.seasonNumber == 0) getString(R.string.je_specials) else getString(R.string.je_season_number, season.seasonNumber)

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
			val appContext = requireContext().applicationContext
			state.value = state.value.copy(busy = Busy.REQUEST)
			lifecycleScope.launch {
				val candidates = try {
					seerrRepository.tvdbCandidates(details.id)
				} catch (err: CancellationException) {
					throw err
				} catch (err: Exception) {
					Timber.w(err, "Sonarr lookup failed")
					emptyList()
				} finally {
					state.value = state.value.copy(busy = null)
				}
				if (candidates.isEmpty()) {
					toast(appContext, appContext.getString(R.string.je_tvdb_no_match), long = true)
					return@launch
				}
				// Only offer the choice while the screen is showing; the user has moved on otherwise
				val context = context?.takeIf { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) } ?: return@launch
				dialog.value = ChoiceDialog(
					title = context.getString(R.string.je_tvdb_pick_title),
					options = candidates.map { c -> listOfNotNull(c.title, c.year?.toString(), "TVDB ${c.tvdbId}").joinToString(" · ") },
				) { picked -> picked.firstOrNull()?.let { pickSeasons(details, candidates[it].tvdbId) } }
			}
			return
		}

		pickSeasons(details, null)
	}

	/** Choose seasons and submit. [tvdbId] is the manual TVDB match, only when TMDB doesn't provide one. */
	private fun pickSeasons(details: SeerrDetails, tvdbId: Int?) {
		val context = context ?: return
		val ui = state.value
		val partial = ui.partial
		// Without partial requests Seerr wants the whole show: every regular season regardless of status, like the web
		if (partial?.partialRequestsEnabled == false) {
			val all = details.seasons.map { it.seasonNumber }.filter { it > 0 }.distinct().sorted()
			submitRequest(details, all.ifEmpty { null }, tvdbId)
			return
		}

		val requestable = requestableSeasons(details)
		if (requestable.isEmpty()) {
			toast(context, getString(R.string.je_nothing_found))
			return
		}

		// The same labels as the season cards, so a protected title's season names stay withheld here too
		val guarded = ui.libraryStory != null
		val labels = requestable.map { season -> seasonLabel(season, guarded) }
		val captions = requestable.map { season -> getString(R.string.je_episodes_count, season.episodeCount) }

		dialog.value = if (ui.enhanced.config.jellyseerrSingleSeasonRequestOnly) {
			ChoiceDialog(title = getString(R.string.je_request_seasons), options = labels, captions = captions) { picked ->
				picked.firstOrNull()?.let { submitRequest(details, listOf(requestable[it].seasonNumber), tvdbId) }
			}
		} else {
			ChoiceDialog(
				title = getString(R.string.je_request_seasons),
				options = labels,
				captions = captions,
				multi = true,
				selected = requestable.indices.toSet(),
				confirmLabel = getString(R.string.je_request),
			) { picked ->
				val seasons = requestable.filterIndexed { index, _ -> index in picked }.map { it.seasonNumber }
				if (seasons.isNotEmpty()) submitRequest(details, seasons, tvdbId)
			}
		}
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

	/**
	 * A failed Seerr call as a sentence ("not linked" in the TV's own words), or null for the caller's fallback. A call
	 * that says the account isn't in Seerr reloads a state that still says linked.
	 */
	private fun seerrError(context: Context, err: Throwable): String? {
		if (SeerrErrors.isUnlinked(err)) seerrLinkManager.onUnlinkedError()
		return SeerrLinkTexts.error(context, err, enhancedRepository.current.isAdmin)
	}

	/** Feedback that may outlive the screen: always through the application context, never the fragment's. */
	private fun toast(context: Context, text: String, long: Boolean = false) {
		Toast.makeText(context.applicationContext, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
	}

	private fun submitRequest(details: SeerrDetails, seasons: List<Int>?, tvdbId: Int? = null) {
		// Captured now: the response can arrive after the user has left (the fragment is then detached)
		val appContext = context?.applicationContext ?: return
		state.value = state.value.copy(busy = Busy.REQUEST)
		lifecycleScope.launch {
			try {
				seerrRepository.request(mediaType, details.id, seasons, tvdbId)
				toast(appContext, appContext.getString(R.string.je_request_sent, details.displayTitle), long = true)
			} catch (err: CancellationException) {
				throw err
			} catch (err: EnhancedApi.EnhancedApiException) {
				Timber.w(err, "Seerr request failed")
				toast(appContext, appContext.getString(R.string.je_request_failed, seerrError(appContext, err) ?: "HTTP ${err.status}"), long = true)
			} catch (err: Exception) {
				Timber.w(err, "Seerr request failed")
				toast(appContext, appContext.getString(R.string.je_request_failed, err.message ?: ""), long = true)
			}
			// Never retry blindly: reload to see what actually happened
			reloadIfAttached()
		}
	}

	private fun toggleSpoilerPending(details: SeerrDetails, enable: Boolean) {
		val appContext = context?.applicationContext ?: return
		state.value = state.value.copy(busy = Busy.PENDING)
		lifecycleScope.launch {
			try {
				spoilerGuardRepository.setPending(mediaType, details.id, details.displayTitle, enable)
				toast(appContext, appContext.getString(if (enable) R.string.je_spoiler_guard_on else R.string.je_spoiler_guard_off))
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Spoiler Guard pending toggle failed")
				toast(appContext, appContext.getString(R.string.je_error_generic))
			}
			reloadIfAttached()
		}
	}

	/** Reload now, or (detached on the back stack) leave it to [onStart], which reloads on return anyway. */
	private fun reloadIfAttached() {
		if (context != null) reload() else state.value = state.value.copy(busy = null)
	}

	private fun parseUuid(id: String): UUID? = runCatching {
		val hex = id.replace("-", "")
		if (hex.length != 32) return@runCatching null
		UUID.fromString("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
	}.getOrNull()
}
