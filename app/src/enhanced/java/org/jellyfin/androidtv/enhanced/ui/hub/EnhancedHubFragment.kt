package org.jellyfin.androidtv.enhanced.ui.hub

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.ui.LocalOnEnhancedHub
import org.jellyfin.androidtv.enhanced.ui.design.EmptyState
import org.jellyfin.androidtv.enhanced.ui.design.HubTile
import org.jellyfin.androidtv.enhanced.ui.design.JeSpace
import org.jellyfin.androidtv.enhanced.ui.design.PageScaffold
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.android.ext.android.get
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.android.inject

/**
 * The Jellyfin Enhanced hub: a grid of the feature pages that apply to this server and user. Opened from the
 * toolbar's "Enhanced" button; with [ARG_PAGE] it goes straight to that page instead.
 */
class EnhancedHubFragment : Fragment() {
	companion object {
		const val ARG_PAGE = "page"

		/** Kept for the whole session so the next visit shows the last known counts at once. */
		private var sharedCounts: HubCounts? = null
	}

	private val enhancedRepository by inject<EnhancedRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val notices by lazy { getKoin().getAll<HubNotice>() }
	private val hubCounts by lazy { sharedCounts ?: HubCounts(get(), get(), get(), get()).also { sharedCounts = it } }

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		val state by enhancedRepository.state.collectAsState()
		val entries = remember(state) { EnhancedHub.available(state) }
		val counts by hubCounts.counts.collectAsState()
		val firstFocus = remember { FocusRequester() }

		LaunchedEffect(Unit) {
			enhancedRepository.ensureLoaded()
			val page = arguments?.getString(ARG_PAGE)
			if (page != null) {
				val entry = EnhancedHub.entry(page)
				if (entry != null && entry.enabled(enhancedRepository.current)) {
					navigationRepository.navigate(entry.destination(), replace = true)
					return@LaunchedEffect
				}
			}
		}

		// Focus the first tile once the grid has composed it; the toolbar grabs focus otherwise
		LaunchedEffect(entries) {
			if (entries.isEmpty()) return@LaunchedEffect
			repeat(10) {
				withFrameNanos { }
				if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
			}
		}

		// Counts load after the grid has drawn and never hold it up
		LaunchedEffect(entries) {
			if (entries.isEmpty()) return@LaunchedEffect
			withFrameNanos { }
			hubCounts.refresh(state, entries.mapTo(HashSet()) { it.id })
		}

		CompositionLocalProvider(LocalOnEnhancedHub provides true) {
			PageScaffold(title = stringResource(R.string.je_hub_title)) {
				if (!state.available) {
					EmptyState(title = stringResource(R.string.je_hub_unavailable))
					return@PageScaffold
				}

				Column(modifier = Modifier.fillMaxSize()) {
					for (notice in notices) notice.Content(state)

					LazyVerticalGrid(
						columns = GridCells.Fixed(3),
						contentPadding = PaddingValues(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, top = 4.dp, bottom = JeSpace.pageBottom),
						horizontalArrangement = Arrangement.spacedBy(16.dp),
						verticalArrangement = Arrangement.spacedBy(16.dp),
					) {
						items(entries, key = { it.id }) { entry ->
							val modifier = if (entry === entries.firstOrNull()) Modifier.focusRequester(firstFocus) else Modifier
							val count = counts[entry.id]?.takeIf { it.value > 0 }
							HubTile(
								icon = entry.icon,
								title = stringResource(entry.title),
								description = stringResource(entry.description),
								onClick = { navigationRepository.navigate(entry.destination()) },
								count = count?.let {
									if (it.pending) pluralStringResource(R.plurals.je_hub_count_pending, it.value, it.value)
									else it.value.toString()
								},
								modifier = modifier.fillMaxWidth(),
							)
						}
					}
				}
			}
		}
	}
}
