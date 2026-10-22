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
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.design.Tokens
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.UUID

/** Details for a Seerr movie or show with request controls, the TV counterpart of JE's "More info" modal. */
class SeerrDetailsFragment : Fragment() {
	companion object {
		const val ARG_MEDIA_TYPE = "media_type"
		const val ARG_TMDB_ID = "tmdb_id"
	}

	private val seerrRepository by inject<SeerrRepository>()
	private val enhancedRepository by inject<EnhancedRepository>()
	private val spoilerGuardRepository by inject<SpoilerGuardRepository>()
	private val navigationRepository by inject<NavigationRepository>()

	private data class UiState(
		val loading: Boolean = true,
		val error: String? = null,
		val details: SeerrDetails? = null,
		val enhanced: EnhancedState = EnhancedState(),
		val partial: SeerrPartialRequestSettings? = null,
		val quota: SeerrQuota? = null,
		val spoilerPending: Boolean? = null,
		/** The title is in the library and Spoiler Guard protects it, so hide its story details here too. */
		val spoilerGuarded: Boolean = false,
		val busy: Boolean = false,
	)

	private val state = MutableStateFlow(UiState())
	private val mediaType by lazy { requireArguments().getString(ARG_MEDIA_TYPE) ?: SeerrRepository.MEDIA_MOVIE }
	private val tmdbId by lazy { requireArguments().getInt(ARG_TMDB_ID) }
	private val isTv get() = mediaType == SeerrRepository.MEDIA_TV

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		reload()
	}

	private fun reload() {
		lifecycleScope.launch {
			try {
				// coroutineScope so a failed call surfaces here instead of cancelling (and crashing) lifecycleScope
				val loaded = coroutineScope {
					val enhanced = enhancedRepository.ensureLoaded()
					val detailsCall = async { seerrRepository.details(mediaType, tmdbId) }
					val partialCall = async { if (isTv) seerrRepository.partialRequestSettings() else null }
					val quotaCall = async { if (enhanced.config.jellyseerrShowQuotaInfo) seerrRepository.quota() else null }
					val spoilerCall = async {
						if (enhanced.spoilerGuard) runCatching { spoilerGuardRepository.getState() }.getOrNull() else null
					}
					val details = detailsCall.await()
					val spoilerState = spoilerCall.await()
					// Seerr's JSON doesn't go through the plugin's Spoiler Guard filter, so apply it here for library titles
					val libraryId = (details.mediaInfo?.jellyfinMediaId ?: details.mediaInfo?.jellyfinMediaId4k)?.let(::parseUuid)
					val guardKind = if (isTv) SpoilerGuardRepository.Kind.SERIES else SpoilerGuardRepository.Kind.MOVIE
					UiState(
						loading = false,
						details = details,
						enhanced = enhanced,
						partial = partialCall.await(),
						quota = quotaCall.await(),
						spoilerPending = spoilerState?.let { spoilerGuardRepository.isPending(it, mediaType, tmdbId) },
						spoilerGuarded = spoilerState != null && libraryId != null &&
							spoilerGuardRepository.isGuarded(spoilerState, guardKind, libraryId),
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
			details?.backdropPath?.let { path ->
				coil3.compose.AsyncImage(
					model = SeerrImages.backdrop(path),
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
		val spoilerConfig = ui.enhanced.config
		val hideStory = ui.spoilerGuarded && (if (isTv) spoilerConfig.spoilerStripSeriesOverview else spoilerConfig.spoilerStripOverview)
		val hideRatings = ui.spoilerGuarded && spoilerConfig.spoilerStripRatings

		Row(
			modifier = Modifier
				.fillMaxSize()
				.padding(horizontal = 48.dp, vertical = 40.dp),
			horizontalArrangement = Arrangement.spacedBy(32.dp),
		) {
			Box(modifier = Modifier.size(200.dp, 300.dp)) {
				AsyncImage(
					url = SeerrImages.poster(details.posterPath, "w500"),
					aspectRatio = 2f / 3f,
					scaleType = ImageView.ScaleType.CENTER_CROP,
					modifier = Modifier.fillMaxSize(),
				)
			}

			Column(
				modifier = Modifier.fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				Text(text = details.displayTitle, fontSize = 32.sp, color = Tokens.Color.colorWhite, maxLines = 2, overflow = TextOverflow.Ellipsis)

				val meta = listOfNotNull(
					details.year,
					details.runtime?.takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}m" },
					details.seasons.count { it.seasonNumber > 0 }.takeIf { isTv && it > 0 }?.let { "$it ${if (it == 1) "season" else "seasons"}" },
					details.voteAverage?.takeIf { it > 0 && !hideRatings }?.let { "★ %.1f".format(it) },
					details.genres.joinToString(", ") { it.name }.ifEmpty { null },
				).joinToString("   ·   ")
				if (meta.isNotEmpty()) Text(text = meta, color = Tokens.Color.colorGrey300, fontSize = 16.sp)

				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					SeerrStatusBadge(media?.status)
					if (media?.status4k != null && media.status4k != SeerrMediaStatus.UNKNOWN && seerrStatusLabel(media.status4k) != null) {
						Row(verticalAlignment = Alignment.CenterVertically) {
							Text("4K ", fontSize = 12.sp, color = Tokens.Color.colorGrey300)
							SeerrStatusBadge(media.status4k)
						}
					}
				}

				details.tagline?.takeIf { it.isNotBlank() && !hideStory }?.let { Text(text = it, color = Tokens.Color.colorGrey300, fontSize = 16.sp) }
				(if (hideStory) stringResource(R.string.je_spoiler_overview_placeholder) else details.overview)?.takeIf { it.isNotBlank() }?.let {
					Text(text = it, color = Tokens.Color.colorGrey100, fontSize = 16.sp, maxLines = 7, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(900.dp))
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

					val config = ui.enhanced.config
					if (ui.enhanced.seerrUsable) {
						requestLabel(media?.status, standard = true)?.let { label ->
							Button(onClick = { startRequest(details, is4k = false) }, enabled = !ui.busy, modifier = Modifier.firstFocus()) {
								Text(stringResource(label))
							}
						}
						val allow4k = if (isTv) config.jellyseerrEnable4KTvRequests else config.jellyseerrEnable4KRequests
						if (allow4k) requestLabel(media?.status4k, standard = false)?.let {
							Button(onClick = { startRequest(details, is4k = true) }, enabled = !ui.busy, modifier = Modifier.firstFocus()) {
								Text(stringResource(R.string.je_request_4k))
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
			}
		}

		// Buttons come and go as the status changes; keep focus on the first one rather than losing it
		LaunchedEffect(details.id, media?.status, media?.status4k, ui.spoilerPending) { runCatching { focusRequester.requestFocus() } }
	}

	/** The label for the request button for a given status, or null when nothing can be requested. */
	private fun requestLabel(status: Int?, standard: Boolean): Int? = when {
		SeerrMediaStatus.isRequestable(status) -> if (standard) R.string.je_request else R.string.je_request_4k
		// TV shows can still request seasons that aren't requested yet
		isTv && status == SeerrMediaStatus.PARTIALLY_AVAILABLE -> R.string.je_request_missing
		isTv && (status == SeerrMediaStatus.PENDING || status == SeerrMediaStatus.PROCESSING) -> R.string.je_request_more
		else -> null
	}

	private fun startRequest(details: SeerrDetails, is4k: Boolean) {
		if (!isTv) {
			submitRequest(details, null, is4k)
			return
		}

		val partial = state.value.partial
		// Without partial requests Seerr wants the whole show: every regular season regardless of status, like the web
		if (partial?.partialRequestsEnabled == false) {
			val all = details.seasons.map { it.seasonNumber }.filter { it > 0 }.distinct().sorted()
			submitRequest(details, all.ifEmpty { null }, is4k)
			return
		}

		val requestable = requestableSeasons(details, is4k, partial)
		if (requestable.isEmpty()) {
			toast(getString(R.string.je_nothing_found))
			return
		}

		val labels = requestable.map { season ->
			val name = if (season.seasonNumber == 0) getString(R.string.je_specials) else season.name ?: getString(R.string.je_season_number, season.seasonNumber)
			"$name  (${getString(R.string.je_episodes_count, season.episodeCount)})"
		}.toTypedArray()

		if (state.value.enhanced.config.jellyseerrSingleSeasonRequestOnly) {
			AlertDialog.Builder(requireContext())
				.setTitle(R.string.je_request_seasons)
				.setItems(labels) { _, which -> submitRequest(details, listOf(requestable[which].seasonNumber), is4k) }
				.setNegativeButton(android.R.string.cancel, null)
				.show()
			return
		}

		val checked = BooleanArray(requestable.size) { true }
		AlertDialog.Builder(requireContext())
			.setTitle(R.string.je_request_seasons)
			.setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
			.setPositiveButton(R.string.je_request) { _, _ ->
				val seasons = requestable.filterIndexed { index, _ -> checked[index] }.map { it.seasonNumber }
				if (seasons.isNotEmpty()) submitRequest(details, seasons, is4k)
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun requestableSeasons(details: SeerrDetails, is4k: Boolean, partial: SeerrPartialRequestSettings?): List<SeerrSeason> {
		val statuses = details.mediaInfo?.seasons.orEmpty().associate { it.seasonNumber to if (is4k) it.status4k else it.status }
		return details.seasons
			.filter { it.episodeCount > 0 }
			.filter { it.seasonNumber > 0 || partial?.enableSpecialEpisodes == true }
			.filter { SeerrMediaStatus.isRequestable(statuses[it.seasonNumber]) }
			.sortedBy { it.seasonNumber }
	}

	private fun toast(text: String, long: Boolean = false) {
		val context = context ?: return
		Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
	}

	private fun submitRequest(details: SeerrDetails, seasons: List<Int>?, is4k: Boolean) {
		state.value = state.value.copy(busy = true)
		lifecycleScope.launch {
			try {
				seerrRepository.request(mediaType, details.id, seasons, is4k, details.externalIds?.tvdbId)
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
