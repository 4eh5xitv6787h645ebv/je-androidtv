package org.jellyfin.androidtv.enhanced.management.spoiler

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardEntry
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardPendingEntry
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.management.ManagementNavigation
import org.jellyfin.androidtv.enhanced.management.ManagementRow
import org.jellyfin.androidtv.enhanced.management.ManagementSectionHeader
import org.jellyfin.androidtv.enhanced.management.errorMessage
import org.jellyfin.androidtv.enhanced.management.formatDate
import org.jellyfin.androidtv.enhanced.management.libraryPosterUrls
import org.jellyfin.androidtv.enhanced.ui.hub.EnhancedScreen
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.enhanced.ui.hub.LoadStateContent
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.UUID

/**
 * The Spoiler Guard hub page: every protected show, movie and collection, plus pending opt-ins for titles that
 * aren't in the library yet. Selecting a title opens it; "Turn off" removes protection (asking first unless the
 * user chose to skip that confirmation).
 */
class SpoilerGuardFragment : Fragment() {
	private val enhancedRepository by inject<EnhancedRepository>()
	private val spoilerGuardRepository by inject<SpoilerGuardRepository>()
	private val enhancedApi by inject<EnhancedApi>()

	private data class Page(
		val entries: List<SpoilerGuardEntry>,
		val pending: List<SpoilerGuardPendingEntry>,
		val posters: Map<UUID, String>,
		val skipConfirm: Boolean,
		val seerrUsable: Boolean,
	) {
		/** Row keys in display order, to move focus to a neighbour when a row goes away. */
		val rowKeys: List<String>
			get() = SpoilerGuardRepository.Kind.entries.flatMap { kind -> entries.filter { it.kind == kind }.map { rowKey(it) } } +
				pending.map { rowKey(it) }
	}

	private val state = MutableStateFlow<LoadState<Page>>(LoadState.Loading)
	private val busy = MutableStateFlow<Set<String>>(emptySet())
	private val focusTarget = MutableStateFlow<String?>(null)
	private var started = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		load()
	}

	override fun onStart() {
		super.onStart()
		// Back from a title's details: its protection may have changed there
		if (started && state.value is LoadState.Ready) load(showLoading = false)
		started = true
	}

	private fun load(showLoading: Boolean = true) {
		if (showLoading) state.value = LoadState.Loading
		lifecycleScope.launch {
			try {
				val enhanced = enhancedRepository.ensureLoaded()
				if (!enhanced.spoilerGuard) {
					state.value = LoadState.Error(getString(R.string.je_management_sg_unavailable)) { load() }
					return@launch
				}
				val document = spoilerGuardRepository.getState()
				val entries = SpoilerGuardRepository.entries(document)
				state.value = LoadState.Ready(
					Page(
						entries = entries,
						pending = SpoilerGuardRepository.pendingEntries(document),
						posters = libraryPosterUrls(enhancedApi, entries.mapNotNull { it.itemId }),
						skipConfirm = document.prefs.skipDisableConfirm,
						seerrUsable = enhanced.seerrUsable,
					)
				)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to load Spoiler Guard list")
				val context = context ?: return@launch
				state.value = LoadState.Error(errorMessage(context, err)) { load() }
			}
		}
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		val load by state.collectAsState()
		val busyKeys by busy.collectAsState()
		val target by focusTarget.collectAsState()
		val requesters = remember { mutableMapOf<String, FocusRequester>() }
		val ready = (load as? LoadState.Ready)?.value

		EnhancedScreen(
			title = stringResource(R.string.je_spoiler_guard),
			subtitle = ready?.let { pluralStringResource(R.plurals.je_management_sg_count, it.entries.size + it.pending.size, it.entries.size + it.pending.size) },
		) {
			LoadStateContent(
				state = load,
				empty = { it.entries.isEmpty() && it.pending.isEmpty() },
				emptyMessage = stringResource(R.string.je_management_sg_empty),
			) { page ->
				fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }

				LazyColumn(
					contentPadding = PaddingValues(bottom = 32.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					for (kind in SpoilerGuardRepository.Kind.entries) {
						val group = page.entries.filter { it.kind == kind }
						if (group.isEmpty()) continue
						item(key = "header-$kind") { ManagementSectionHeader(stringResource(kind.sectionTitle())) }
						items(group, key = { rowKey(it) }) { entry ->
							val key = rowKey(entry)
							ManagementRow(
								title = entry.name.ifBlank { stringResource(R.string.je_management_untitled) },
								subtitle = formatDate(entry.enabledAt)?.let { stringResource(R.string.je_management_sg_since, it) },
								posterUrl = entry.itemId?.let { page.posters[it] },
								fallbackIcon = kind.icon(),
								actionLabel = stringResource(R.string.je_management_sg_turn_off),
								busy = key in busyKeys,
								onAction = { confirmRemove(page, key, entry.name) { spoilerGuardRepository.removeEntry(entry) } },
								onOpen = entry.itemId?.let { id ->
									{
										if (kind == SpoilerGuardRepository.Kind.COLLECTION) ManagementNavigation.openCollection(this@SpoilerGuardFragment, id)
										else ManagementNavigation.openDetails(id)
									}
								},
								mainModifier = Modifier.focusRequester(requester(key)),
							)
						}
					}

					if (page.pending.isNotEmpty()) {
						item(key = "header-pending") { ManagementSectionHeader(stringResource(R.string.je_management_sg_pending)) }
						items(page.pending, key = { rowKey(it) }) { entry ->
							val key = rowKey(entry)
							val tmdbId = entry.tmdbId
							ManagementRow(
								title = entry.displayName.ifBlank { stringResource(R.string.je_management_untitled) },
								subtitle = listOfNotNull(
									stringResource(if (entry.mediaType == "tv") R.string.je_management_type_show else R.string.je_management_type_movie),
									formatDate(entry.requestedAt)?.let { stringResource(R.string.je_management_sg_since, it) },
								).joinToString(" · "),
								// Pending titles are protected by intent: no TMDB artwork
								posterUrl = null,
								fallbackIcon = if (entry.mediaType == "tv") R.drawable.ic_tv else R.drawable.ic_clapperboard,
								actionLabel = stringResource(R.string.je_management_sg_turn_off),
								busy = key in busyKeys,
								onAction = {
									if (tmdbId != null) confirmRemove(page, key, entry.displayName) {
										spoilerGuardRepository.setPending(entry.mediaType, tmdbId, null, enabled = false)
									}
								},
								onOpen = if (page.seerrUsable && tmdbId != null) {
									{ ManagementNavigation.openSeerr(entry.mediaType, tmdbId) }
								} else null,
								mainModifier = Modifier.focusRequester(requester(key)),
							)
						}
					}
				}

				LaunchedEffect(Unit) {
					page.rowKeys.firstOrNull()?.let { key -> runCatching { requester(key).requestFocus() } }
				}
				LaunchedEffect(target) {
					val key = target ?: return@LaunchedEffect
					runCatching { requester(key).requestFocus() }
					focusTarget.value = null
				}
			}
		}
	}

	private fun confirmRemove(page: Page, key: String, name: String, remove: suspend () -> Unit) {
		val context = context ?: return
		if (key in busy.value) return

		fun apply() {
			busy.update { it + key }
			lifecycleScope.launch {
				try {
					remove()
					val keys = page.rowKeys
					val index = keys.indexOf(key)
					focusTarget.value = keys.getOrNull(index + 1) ?: keys.getOrNull(index - 1)
					state.update { current ->
						if (current !is LoadState.Ready) current
						else LoadState.Ready(current.value.copy(
							entries = current.value.entries.filterNot { rowKey(it) == key },
							pending = current.value.pending.filterNot { rowKey(it) == key },
						))
					}
					val ctx = this@SpoilerGuardFragment.context ?: return@launch
					Toast.makeText(ctx, ctx.getString(R.string.je_management_sg_removed, name), Toast.LENGTH_SHORT).show()
				} catch (err: CancellationException) {
					throw err
				} catch (err: Exception) {
					Timber.w(err, "Failed to turn off Spoiler Guard for %s", key)
					val ctx = this@SpoilerGuardFragment.context ?: return@launch
					Toast.makeText(ctx, errorMessage(ctx, err), Toast.LENGTH_LONG).show()
				} finally {
					busy.update { it - key }
				}
			}
		}

		if (page.skipConfirm) apply()
		else AlertDialog.Builder(context)
			.setTitle(R.string.je_spoiler_disable_title)
			.setMessage(R.string.je_spoiler_disable_message)
			.setPositiveButton(R.string.je_spoiler_disable_confirm) { _, _ -> apply() }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private companion object {
		fun rowKey(entry: SpoilerGuardEntry) = "${entry.kind.path}/${entry.key}"
		fun rowKey(entry: SpoilerGuardPendingEntry) = "pending/${entry.key}"

		fun SpoilerGuardRepository.Kind.sectionTitle() = when (this) {
			SpoilerGuardRepository.Kind.SERIES -> R.string.je_management_sg_shows
			SpoilerGuardRepository.Kind.MOVIE -> R.string.je_management_sg_movies
			SpoilerGuardRepository.Kind.COLLECTION -> R.string.je_management_sg_collections
		}

		fun SpoilerGuardRepository.Kind.icon() = when (this) {
			SpoilerGuardRepository.Kind.SERIES -> R.drawable.ic_tv
			SpoilerGuardRepository.Kind.MOVIE -> R.drawable.ic_clapperboard
			SpoilerGuardRepository.Kind.COLLECTION -> R.drawable.ic_folder
		}
	}
}
