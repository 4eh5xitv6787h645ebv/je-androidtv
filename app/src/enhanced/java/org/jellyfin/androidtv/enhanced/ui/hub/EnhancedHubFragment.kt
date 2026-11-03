package org.jellyfin.androidtv.enhanced.ui.hub

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.design.Tokens
import org.koin.android.ext.android.inject

/**
 * The Jellyfin Enhanced hub: a grid of the feature pages that apply to this server and user. Opened from the
 * toolbar's "Enhanced" button; with [ARG_PAGE] it goes straight to that page instead.
 */
class EnhancedHubFragment : Fragment() {
	companion object {
		const val ARG_PAGE = "page"
	}

	private val enhancedRepository by inject<EnhancedRepository>()
	private val navigationRepository by inject<NavigationRepository>()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		val state by enhancedRepository.state.collectAsState()
		val entries = remember(state) { EnhancedHub.available(state) }
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
			runCatching { firstFocus.requestFocus() }
		}

		EnhancedScreen(
			title = stringResource(R.string.je_hub_title),
			subtitle = state.pluginVersion?.let { stringResource(R.string.je_hub_version, it) },
		) {
			if (!state.available) {
				Text(
					text = stringResource(R.string.je_hub_unavailable),
					color = Tokens.Color.colorGrey300,
					fontSize = 18.sp,
					modifier = Modifier.padding(horizontal = 48.dp),
				)
				return@EnhancedScreen
			}

			LazyVerticalGrid(
				columns = GridCells.Fixed(3),
				contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
				horizontalArrangement = Arrangement.spacedBy(16.dp),
				verticalArrangement = Arrangement.spacedBy(16.dp),
			) {
				items(entries, key = { it.id }) { entry ->
					val modifier = if (entry === entries.firstOrNull()) Modifier.focusRequester(firstFocus) else Modifier
					Button(
						onClick = { navigationRepository.navigate(entry.destination()) },
						shape = JellyfinTheme.shapes.medium,
						contentPadding = PaddingValues(20.dp),
						colors = ButtonDefaults.colors(),
						modifier = modifier.fillMaxWidth(),
					) {
						Row(
							horizontalArrangement = Arrangement.spacedBy(16.dp),
							verticalAlignment = Alignment.CenterVertically,
							modifier = Modifier.fillMaxWidth(),
						) {
							Icon(painter = painterResource(entry.icon), contentDescription = null, modifier = Modifier.size(32.dp))
							Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
								Text(text = stringResource(entry.title), fontSize = 18.sp)
								Text(text = stringResource(entry.description), fontSize = 13.sp, color = Tokens.Color.colorGrey300)
							}
						}
					}
				}
			}
		}
	}
}
