package org.jellyfin.androidtv.enhanced.management.hidden

import org.jellyfin.androidtv.enhanced.ui.secondaryTextColor
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedSettingsRepository
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.management.ManagementNavigation
import org.jellyfin.androidtv.enhanced.management.ManagementRow
import org.jellyfin.androidtv.enhanced.management.errorMessage
import org.jellyfin.androidtv.enhanced.management.formatDate
import org.jellyfin.androidtv.enhanced.management.libraryPosterUrls
import org.jellyfin.androidtv.enhanced.seerr.SeerrImages
import org.jellyfin.androidtv.enhanced.ui.hub.EnhancedScreen
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.enhanced.ui.hub.LoadStateContent
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.design.Tokens
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.UUID

/**
 * The Hidden Content hub page: everything the user hid, everywhere or from Continue Watching / Next Up only, with
 * scope and type filters, per-entry Unhide/Restore and "Restore all" for the filtered list. Entries are removed by
 * their stored key and unknown fields and scopes are left as they are.
 */
class HiddenContentFragment : Fragment() {
	private val enhancedRepository by inject<EnhancedRepository>()
	private val settingsRepository by inject<EnhancedSettingsRepository>()
	private val hiddenContentRepository by inject<HiddenContentRepository>()
	private val spoilerGuardRepository by inject<SpoilerGuardRepository>()
	private val enhancedApi by inject<EnhancedApi>()

	private data class Page(
		val entries: List<HiddenEntry>,
		val posters: Map<UUID, String>,
		/** Dash-less ids of guarded shows; null when Spoiler Guard is on but its state couldn't be read. */
		val guardedSeries: Set<String>?,
		/** `tv:123` keys of pending Spoiler Guard opt-ins (no TMDB posters for those); null when unknown. */
		val pendingGuarded: Set<String>?,
		val seerrUsable: Boolean,
	)

	private data class Filters(
		val scope: HiddenScopeFilter = HiddenScopeFilter.ALL,
		val type: HiddenTypeFilter = HiddenTypeFilter.ALL,
	)

	private val state = MutableStateFlow<LoadState<Page>>(LoadState.Loading)
	private val filters = MutableStateFlow(Filters())
	private val busy = MutableStateFlow<Set<String>>(emptySet())
	private val focusTarget = MutableStateFlow<String?>(null)
	private var started = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		load()
	}

	override fun onStart() {
		super.onStart()
		// Back from a title's details: it may have been unhidden there
		if (started && state.value is LoadState.Ready) load(showLoading = false)
		started = true
	}

	private fun load(showLoading: Boolean = true) {
		if (showLoading) state.value = LoadState.Loading
		lifecycleScope.launch {
			try {
				val enhanced = enhancedRepository.ensureLoaded()
				if (!enhanced.hiddenContent) {
					state.value = LoadState.Error(getString(R.string.je_management_hc_unavailable)) { load() }
					return@launch
				}
				// The same pinned whole-document read the settings screen uses, so both see the same user
				val document = settingsRepository.read(EnhancedSettingsRepository.HIDDEN_CONTENT).json
				val entries = HiddenEntries.parse(document)

				val spoiler = if (enhanced.spoilerGuard) {
					try {
						spoilerGuardRepository.getState()
					} catch (err: CancellationException) {
						throw err
					} catch (err: Exception) {
						Timber.w(err, "Failed to load Spoiler Guard state; withholding episode titles")
						null
					}
				} else null
				val guardedSeries = when {
					!enhanced.spoilerGuard -> emptySet()
					spoiler == null -> null
					else -> SpoilerGuardRepository.entries(spoiler)
						.filter { it.kind == SpoilerGuardRepository.Kind.SERIES }
						.map { it.itemId?.let(SpoilerGuardRepository::key) ?: it.key.lowercase() }
						.toSet()
				}
				val pendingGuarded = when {
					!enhanced.spoilerGuard -> emptySet()
					spoiler == null -> null
					else -> spoiler.pendingTmdb.keys
				}

				state.value = LoadState.Ready(
					Page(
						entries = entries,
						posters = libraryPosterUrls(enhancedApi, entries.mapNotNull { posterItemId(it) }),
						guardedSeries = guardedSeries,
						pendingGuarded = pendingGuarded,
						seerrUsable = enhanced.seerrUsable,
					)
				)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to load hidden content")
				val context = context ?: return@launch
				state.value = LoadState.Error(errorMessage(context, err)) { load() }
			}
		}
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		val load by state.collectAsState()
		val currentFilters by filters.collectAsState()
		val busyKeys by busy.collectAsState()
		val target by focusTarget.collectAsState()
		val requesters = remember { mutableMapOf<String, FocusRequester>() }
		val page = (load as? LoadState.Ready)?.value
		val visible = page?.let { HiddenEntries.filter(it.entries, currentFilters.scope, currentFilters.type) }.orEmpty()

		EnhancedScreen(
			title = stringResource(R.string.je_management_hc_title),
			subtitle = page?.let { pluralStringResource(R.plurals.je_management_hc_count, it.entries.size, it.entries.size) },
			headerActions = if (page != null && page.entries.isNotEmpty()) {
				{
					Button(onClick = { filters.update { it.copy(scope = it.scope.next()) } }) {
						Text(stringResource(R.string.je_management_hc_filter_scope, stringResource(currentFilters.scope.label())))
					}
					Button(onClick = { filters.update { it.copy(type = it.type.next()) } }) {
						Text(stringResource(R.string.je_management_hc_filter_type, stringResource(currentFilters.type.label())))
					}
					if (visible.isNotEmpty()) Button(onClick = { confirmRestoreAll(visible) }) {
						Text(stringResource(R.string.je_management_hc_restore_all, visible.size))
					}
				}
			} else null,
		) {
			LoadStateContent(
				state = load,
				empty = { it.entries.isEmpty() },
				emptyMessage = stringResource(R.string.je_management_hc_empty),
			) { ready ->
				fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }

				if (visible.isEmpty()) {
					Text(
						text = stringResource(R.string.je_management_hc_filter_empty),
						color = secondaryTextColor(),
						modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp),
					)
					return@LoadStateContent
				}

				LazyColumn(
					contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					items(visible, key = { it.key }) { entry ->
						val title = HiddenEntries.displayTitle(entry, ready.guardedSeries).ifBlank { stringResource(R.string.je_management_untitled) }
						HiddenRow(ready, entry, title, entry.key in busyKeys, Modifier.focusRequester(requester(entry.key))) {
							unhide(entry, title, visible.map { it.key })
						}
					}
				}

				LaunchedEffect(Unit) {
					visible.firstOrNull()?.let { runCatching { requester(it.key).requestFocus() } }
				}
				LaunchedEffect(target) {
					val key = target ?: return@LaunchedEffect
					runCatching { requester(key).requestFocus() }
					focusTarget.value = null
				}
			}
		}
	}

	@Composable
	private fun HiddenRow(page: Page, entry: HiddenEntry, title: String, busy: Boolean, mainModifier: Modifier, onAction: () -> Unit) {
		val itemId = entry.itemId
		val tmdbId = entry.tmdbId
		val subtitle = listOfNotNull(
			stringResource(entry.typeLabel()),
			formatDate(entry.hiddenAt)?.let { stringResource(R.string.je_management_hc_hidden_on, it) },
		).joinToString(" · ")

		ManagementRow(
			title = title,
			subtitle = subtitle,
			posterUrl = posterUrl(page, entry),
			fallbackIcon = if (entry.mediaType == "tv") R.drawable.ic_tv else R.drawable.ic_clapperboard,
			actionLabel = stringResource(if (entry.isGlobal) R.string.je_unhide else R.string.je_management_hc_restore),
			busy = busy,
			onAction = onAction,
			onOpen = when {
				itemId != null -> {
					{ ManagementNavigation.openDetails(itemId) }
				}

				page.seerrUsable && tmdbId != null && (entry.type.equals("Movie", true) || entry.type.equals("Series", true)) -> {
					{ ManagementNavigation.openSeerr(entry.mediaType, tmdbId) }
				}

				else -> null
			},
			mainModifier = mainModifier,
			chips = listOf(stringResource(entry.scopeLabel())),
		)
	}

	private fun posterItemId(entry: HiddenEntry): UUID? =
		if ((entry.isEpisode || entry.isSeason) && entry.seriesId != null) entry.seriesId else entry.itemId

	private fun posterUrl(page: Page, entry: HiddenEntry): String? {
		posterItemId(entry)?.let { return page.posters[it] }
		// TMDB-only entries: TMDB artwork, except for titles protected by a pending Spoiler Guard opt-in
		val tmdbId = entry.tmdbId ?: return null
		val pending = page.pendingGuarded ?: return null
		if ("${entry.mediaType}:$tmdbId" in pending) return null
		return SeerrImages.poster(entry.posterPath, "w185")
	}

	private fun unhide(entry: HiddenEntry, title: String, order: List<String>) {
		if (entry.key in busy.value) return
		busy.update { it + entry.key }
		lifecycleScope.launch {
			try {
				when (val plan = HiddenEntries.unhidePlan(entry)) {
					is UnhidePlan.ScopedRestore -> try {
						hiddenContentRepository.undoRemoveFromHome(plan.itemId, plan.surface)
					} catch (err: EnhancedApi.EnhancedApiException) {
						// 404: no entry of exactly that scope under the canonical ids; remove it by its key instead
						if (err.status == 404) hiddenContentRepository.removeEntries(listOf(entry.key)) else throw err
					}

					is UnhidePlan.RemoveKey -> hiddenContentRepository.removeEntries(listOf(plan.key))
				}
				val index = order.indexOf(entry.key)
				focusTarget.value = order.getOrNull(index + 1) ?: order.getOrNull(index - 1)
				removeLocally(setOf(entry.key))
				val ctx = context ?: return@launch
				Toast.makeText(ctx, ctx.getString(R.string.je_unhidden_toast, title), Toast.LENGTH_SHORT).show()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to unhide %s", entry.key)
				val ctx = context ?: return@launch
				Toast.makeText(ctx, errorMessage(ctx, err), Toast.LENGTH_LONG).show()
			} finally {
				busy.update { it - entry.key }
			}
		}
	}

	private fun confirmRestoreAll(entries: List<HiddenEntry>) {
		val context = context ?: return
		val keys = entries.map { it.key }.toSet()
		AlertDialog.Builder(context)
			.setTitle(context.resources.getQuantityString(R.plurals.je_management_hc_restore_all_title, keys.size, keys.size))
			.setMessage(R.string.je_management_hc_restore_all_message)
			.setPositiveButton(R.string.je_management_hc_restore) { _, _ ->
				busy.update { it + keys }
				lifecycleScope.launch {
					try {
						// One whole-document write for the lot; entries added meanwhile are kept
						hiddenContentRepository.removeEntries(keys)
						removeLocally(keys)
						load(showLoading = false)
					} catch (err: CancellationException) {
						throw err
					} catch (err: Exception) {
						Timber.w(err, "Failed to restore hidden content")
						val ctx = this@HiddenContentFragment.context ?: return@launch
						Toast.makeText(ctx, errorMessage(ctx, err), Toast.LENGTH_LONG).show()
					} finally {
						busy.update { it - keys }
					}
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun removeLocally(keys: Set<String>) = state.update { current ->
		if (current !is LoadState.Ready) current
		else LoadState.Ready(current.value.copy(entries = current.value.entries.filterNot { it.key in keys }))
	}

	private companion object {
		fun HiddenScopeFilter.next() = HiddenScopeFilter.entries[(ordinal + 1) % HiddenScopeFilter.entries.size]
		fun HiddenTypeFilter.next() = HiddenTypeFilter.entries[(ordinal + 1) % HiddenTypeFilter.entries.size]

		@StringRes
		fun HiddenScopeFilter.label() = when (this) {
			HiddenScopeFilter.ALL -> R.string.je_management_filter_all
			HiddenScopeFilter.EVERYWHERE -> R.string.je_management_hc_scope_global
			HiddenScopeFilter.CONTINUE_WATCHING -> R.string.je_management_hc_scope_continue_watching
			HiddenScopeFilter.NEXT_UP -> R.string.je_management_hc_scope_next_up
			HiddenScopeFilter.HOME -> R.string.je_management_hc_scope_home
			HiddenScopeFilter.OTHER -> R.string.je_management_hc_scope_other
		}

		@StringRes
		fun HiddenTypeFilter.label() = when (this) {
			HiddenTypeFilter.ALL -> R.string.je_management_filter_all
			HiddenTypeFilter.MOVIES -> R.string.je_management_type_movies
			HiddenTypeFilter.SHOWS -> R.string.je_management_type_shows
			HiddenTypeFilter.SEASONS -> R.string.je_management_type_seasons
			HiddenTypeFilter.EPISODES -> R.string.je_management_type_episodes
			HiddenTypeFilter.OTHER -> R.string.je_management_type_other
		}

		@StringRes
		fun HiddenEntry.scopeLabel() = when (scope) {
			HiddenScope.GLOBAL -> R.string.je_management_hc_scope_global
			HiddenScope.CONTINUE_WATCHING -> R.string.je_management_hc_scope_continue_watching
			HiddenScope.NEXT_UP -> R.string.je_management_hc_scope_next_up
			HiddenScope.HOME_SECTIONS -> R.string.je_management_hc_scope_home
			else -> R.string.je_management_hc_scope_other
		}

		@StringRes
		fun HiddenEntry.typeLabel() = when (type.lowercase()) {
			"movie" -> R.string.je_management_type_movie
			"series" -> R.string.je_management_type_show
			"season" -> R.string.je_management_type_season
			"episode" -> R.string.je_management_type_episode
			"person" -> R.string.je_management_type_person
			"boxset" -> R.string.je_management_type_collection
			else -> R.string.je_management_type_item
		}
	}
}
