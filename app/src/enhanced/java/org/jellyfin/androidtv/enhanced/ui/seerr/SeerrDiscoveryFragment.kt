package org.jellyfin.androidtv.enhanced.ui.seerr

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.seerr.SeerrSource
import org.jellyfin.androidtv.enhanced.ui.EnhancedDestinations
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.dp
import org.jellyfin.design.Tokens
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * Grid of Seerr results for a [SeerrSource] ("More from <person>", a genre, a studio...). Movie and TV feeds are
 * interleaved and paged in as the user scrolls, like the web's discovery sections.
 */
class SeerrDiscoveryFragment : Fragment() {
	companion object {
		const val ARG_SOURCE = "source"
		private const val COLUMNS = 8
		private const val LOAD_MORE_THRESHOLD = COLUMNS * 2
		// Stop chaining page loads after this many pages in a row added nothing visible
		private const val MAX_EMPTY_PAGES = 5
	}

	private enum class Filter { ALL, MOVIES, TV }

	private val seerrRepository by inject<SeerrRepository>()
	private val navigationRepository by inject<NavigationRepository>()

	private lateinit var source: SeerrSource
	private val adapter = ArrayObjectAdapter(SeerrCardPresenter())
	private val allResults = mutableListOf<SeerrResult>()
	private var feeds: List<SeerrRepository.Feed>? = null
	private var nextPage = 1
	private var hasMore = true
	private var emptyPages = 0
	private var loadJob: Job? = null

	private val filter = MutableStateFlow(Filter.ALL)
	private val status = MutableStateFlow<String?>(null)
	private val failed = MutableStateFlow(false)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		source = Json.decodeFromString(SeerrSource.serializer(), requireArguments().getString(ARG_SOURCE)!!)
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val context = requireContext()
		val root = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
		}

		val header = ComposeView(context).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent { JellyfinTheme { Header() } }
		}
		root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

		val gridContainer = FrameLayout(context)
		root.addView(gridContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

		val presenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_MEDIUM, false).apply {
			numberOfColumns = COLUMNS
			shadowEnabled = false
			onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
				if (item is SeerrResult) navigationRepository.navigate(EnhancedDestinations.seerrDetails(item.mediaType ?: SeerrRepository.MEDIA_MOVIE, item.id))
			}
			onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
				val index = adapter.indexOf(item)
				if (index >= 0 && index >= adapter.size() - LOAD_MORE_THRESHOLD) loadMore()
			}
		}
		val viewHolder = presenter.onCreateViewHolder(gridContainer)
		viewHolder.gridView.apply {
			setGravity(Gravity.CENTER_HORIZONTAL)
			setPadding(36.dp(context), 16.dp(context), 36.dp(context), 24.dp(context))
			horizontalSpacing = 12.dp(context)
			verticalSpacing = 12.dp(context)
		}
		gridContainer.addView(viewHolder.view)
		presenter.onBindViewHolder(viewHolder, adapter)

		return root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		if (feeds == null) loadMore() else applyFilter()
	}

	private fun loadMore(userTriggered: Boolean = true) {
		if (!hasMore || loadJob?.isActive == true) return
		if (userTriggered) emptyPages = 0
		failed.value = false
		loadJob = lifecycleScope.launch {
			if (allResults.isEmpty()) status.value = getString(R.string.je_loading)
			try {
				val resolved = feeds ?: seerrRepository.feedsFor(source).also { feeds = it }
				if (resolved.isEmpty()) {
					hasMore = false
				} else {
					val (results, more) = seerrRepository.loadPage(resolved, nextPage, recommendations = source is SeerrSource.Recommended || source is SeerrSource.Similar)
					nextPage++
					hasMore = more
					val known = allResults.mapTo(HashSet()) { it.dedupeKey }
					val fresh = results.filter { it.dedupeKey !in known }
					allResults += fresh
					val visible = fresh.filter(::matchesFilter)
					adapter.addAll(adapter.size(), visible)
					// A page that was entirely filtered out adds no cards to scroll onto, so nothing would trigger the next
					// load; keep going until something shows up (or the feed ends)
					emptyPages = if (visible.isEmpty()) emptyPages + 1 else 0
					if (hasMore && emptyPages < MAX_EMPTY_PAGES && (adapter.size() < LOAD_MORE_THRESHOLD || visible.isEmpty())) {
						loadJob = null
						loadMore(userTriggered = false)
						return@launch
					}
				}
				status.value = if (allResults.isEmpty()) getString(R.string.je_nothing_found) else null
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Seerr discovery failed")
				// Leave hasMore set so Retry (or scrolling) can try the same page again
				status.value = getString(R.string.je_error_generic)
				failed.value = true
			}
		}
	}

	private fun matchesFilter(item: SeerrResult) = when (filter.value) {
		Filter.ALL -> true
		Filter.MOVIES -> item.isMovie
		Filter.TV -> item.isTv
	}

	private fun applyFilter() {
		adapter.clear()
		adapter.addAll(0, allResults.filter(::matchesFilter))
		if (adapter.size() < LOAD_MORE_THRESHOLD) loadMore()
	}

	@androidx.compose.runtime.Composable
	private fun Header() {
		val currentFilter by filter.collectAsState()
		val currentStatus by status.collectAsState()
		val currentFailed by failed.collectAsState()

		Column(
			modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 27.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(text = source.title, fontSize = 28.sp, color = Tokens.Color.colorWhite)
			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				listOf(
					Filter.ALL to R.string.je_filter_all,
					Filter.MOVIES to R.string.je_filter_movies,
					Filter.TV to R.string.je_filter_tv,
				).forEach { (value, label) ->
					Button(
						onClick = {
							if (filter.value != value) {
								filter.value = value
								applyFilter()
							}
						},
						colors = if (currentFilter == value) ButtonDefaults.colors(
							containerColor = JellyfinTheme.colorScheme.buttonActive,
							contentColor = JellyfinTheme.colorScheme.onButtonActive,
						) else ButtonDefaults.colors(),
					) {
						Text(stringResource(label))
					}
				}
				currentStatus?.let { Text(text = it, color = Tokens.Color.colorGrey300, modifier = Modifier.padding(start = 16.dp)) }
				if (currentFailed) {
					Button(onClick = { loadMore() }) { Text(stringResource(R.string.je_retry)) }
				}
			}
		}
	}
}
