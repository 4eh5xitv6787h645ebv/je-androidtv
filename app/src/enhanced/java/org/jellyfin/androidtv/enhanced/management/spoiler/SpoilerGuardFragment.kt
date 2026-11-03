package org.jellyfin.androidtv.enhanced.management.spoiler

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import org.jellyfin.androidtv.enhanced.management.actionFocusKey
import org.jellyfin.androidtv.enhanced.management.errorMessage
import org.jellyfin.androidtv.enhanced.management.formatDate
import org.jellyfin.androidtv.enhanced.management.libraryPosterUrls
import org.jellyfin.androidtv.enhanced.ui.design.JeConfirmDialog
import org.jellyfin.androidtv.enhanced.ui.design.JeSpace
import org.jellyfin.androidtv.enhanced.ui.design.ListRow
import org.jellyfin.androidtv.enhanced.ui.design.LoadStateContent
import org.jellyfin.androidtv.enhanced.ui.design.PageScaffold
import org.jellyfin.androidtv.enhanced.ui.design.RowAction
import org.jellyfin.androidtv.enhanced.ui.design.RowLeading
import org.jellyfin.androidtv.enhanced.ui.design.SectionHeader
import org.jellyfin.androidtv.enhanced.ui.hub.ListFocus
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
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
	/** The "Turn off" waiting for confirmation, if any. */
	private val confirming = MutableStateFlow<PendingRemoval?>(null)
	private val listFocus = ListFocus()
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
		val ready = (load as? LoadState.Ready)?.value

		PageScaffold(
			title = stringResource(R.string.je_spoiler_guard),
			subtitle = ready?.let { pluralStringResource(R.plurals.je_management_sg_count, it.entries.size + it.pending.size, it.entries.size + it.pending.size) },
		) {
			LoadStateContent(
				state = load,
				empty = { it.entries.isEmpty() && it.pending.isEmpty() },
				emptyMessage = stringResource(R.string.je_management_sg_empty),
				emptyIcon = R.drawable.je_ic_blur_on,
			) { page ->
				listFocus.Effect()

				LazyColumn(
					contentPadding = PaddingValues(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, bottom = JeSpace.pageBottom),
					verticalArrangement = Arrangement.spacedBy(JeSpace.rowGap),
				) {
					var first = true
					for (kind in SpoilerGuardRepository.Kind.entries) {
						val group = page.entries.filter { it.kind == kind }
						if (group.isEmpty()) continue
						val top = !first
						first = false
						item(key = "header-$kind") { GroupHeader(stringResource(kind.sectionTitle()), group.size, top) }
						items(group, key = { rowKey(it) }) { entry ->
							val key = rowKey(entry)
							ListRow(
								title = entry.name.ifBlank { stringResource(R.string.je_management_untitled) },
								supporting = listOfNotNull(formatDate(entry.enabledAt)?.let { stringResource(R.string.je_management_sg_since, it) }),
								leading = RowLeading.Poster(entry.itemId?.let { page.posters[it] }, kind.icon()),
								actions = listOf(
									RowAction(
										icon = null,
										label = stringResource(R.string.je_management_sg_turn_off),
										onClick = { confirmRemove(page, key, entry.name) { spoilerGuardRepository.removeEntry(entry) } },
										busy = key in busyKeys,
										modifier = Modifier.focusRequester(listFocus.requester(actionFocusKey(key))),
									)
								),
								onClick = {
									val id = entry.itemId ?: return@ListRow
									if (kind == SpoilerGuardRepository.Kind.COLLECTION) ManagementNavigation.openCollection(this@SpoilerGuardFragment, id)
									else ManagementNavigation.openDetails(id)
								},
								mainModifier = Modifier.focusRequester(listFocus.requester(key)),
							)
						}
					}

					if (page.pending.isNotEmpty()) {
						val top = !first
						item(key = "header-pending") { GroupHeader(stringResource(R.string.je_management_sg_pending), page.pending.size, top) }
						items(page.pending, key = { rowKey(it) }) { entry ->
							val key = rowKey(entry)
							val tmdbId = entry.tmdbId
							ListRow(
								title = entry.displayName.ifBlank { stringResource(R.string.je_management_untitled) },
								supporting = listOf(
									listOfNotNull(
										stringResource(if (entry.mediaType == "tv") R.string.je_management_type_show else R.string.je_management_type_movie),
										formatDate(entry.requestedAt)?.let { stringResource(R.string.je_management_sg_since, it) },
									).joinToString(" · ")
								),
								// Pending titles are protected by intent: no TMDB artwork
								leading = RowLeading.Poster(null, if (entry.mediaType == "tv") R.drawable.ic_tv else R.drawable.ic_clapperboard),
								actions = listOf(
									RowAction(
										icon = null,
										label = stringResource(R.string.je_management_sg_turn_off),
										onClick = {
											if (tmdbId != null) confirmRemove(page, key, entry.displayName) {
												spoilerGuardRepository.setPending(entry.mediaType, tmdbId, null, enabled = false)
											}
										},
										busy = key in busyKeys,
										modifier = Modifier.focusRequester(listFocus.requester(actionFocusKey(key))),
									)
								),
								onClick = { if (page.seerrUsable && tmdbId != null) ManagementNavigation.openSeerr(entry.mediaType, tmdbId) },
								mainModifier = Modifier.focusRequester(listFocus.requester(key)),
							)
						}
					}
				}

				LaunchedEffect(Unit) {
					page.rowKeys.firstOrNull()?.let { key -> listFocus.moveTo(key) }
				}
			}

			val removal by confirming.collectAsState()
			JeConfirmDialog(
				visible = removal != null,
				title = stringResource(R.string.je_spoiler_disable_title),
				body = stringResource(R.string.je_spoiler_disable_message),
				confirmLabel = stringResource(R.string.je_spoiler_disable_confirm),
				destructive = true,
				onConfirm = { removal?.let { applyRemove(it) } },
				onDismissRequest = {
					// Back on the row's "Turn off" (applyRemove() moves the focus on once the row goes)
					removal?.let { listFocus.moveTo(actionFocusKey(it.key)) }
					confirming.value = null
				},
			)
		}
	}

	@Composable
	private fun GroupHeader(text: String, count: Int, top: Boolean) {
		SectionHeader(
			text = text,
			count = count,
			modifier = Modifier.padding(top = if (top) JeSpace.sectionGap - JeSpace.rowGap else 0.dp),
		)
	}

	/** A pending "Turn off": the row, the title for the toast, and the write. */
	private class PendingRemoval(val page: Page, val key: String, val name: String, val remove: suspend () -> Unit)

	private fun confirmRemove(page: Page, key: String, name: String, remove: suspend () -> Unit) {
		if (key in busy.value) return
		val removal = PendingRemoval(page, key, name, remove)
		if (page.skipConfirm) applyRemove(removal)
		else confirming.value = removal
	}

	private fun applyRemove(removal: PendingRemoval) {
		val key = removal.key
		if (key in busy.value) return
		busy.update { it + key }
		lifecycleScope.launch {
			try {
				removal.remove()
				// Before the row goes: "Turn off" was pressed on its action button, so land on the next row's. With
				// nothing left, the empty state's Back takes the focus.
				val latest = (state.value as? LoadState.Ready)?.value ?: removal.page
				ListFocus.neighbour(latest.rowKeys, listOf(key))?.let { listFocus.moveTo(actionFocusKey(it)) }
				state.update { current ->
					if (current !is LoadState.Ready) current
					else LoadState.Ready(current.value.copy(
						entries = current.value.entries.filterNot { rowKey(it) == key },
						pending = current.value.pending.filterNot { rowKey(it) == key },
					))
				}
				val ctx = this@SpoilerGuardFragment.context ?: return@launch
				Toast.makeText(ctx, ctx.getString(R.string.je_management_sg_removed, removal.name), Toast.LENGTH_SHORT).show()
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
