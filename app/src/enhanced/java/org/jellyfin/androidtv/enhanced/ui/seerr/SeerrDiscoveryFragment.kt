package org.jellyfin.androidtv.enhanced.ui.seerr

import org.jellyfin.androidtv.enhanced.ui.secondaryTextColor
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrErrors
import org.jellyfin.androidtv.enhanced.seerr.SeerrLinkManager
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.seerr.SeerrSort
import org.jellyfin.androidtv.enhanced.seerr.SeerrSource
import org.jellyfin.androidtv.enhanced.seerr.sortable
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
 * interleaved and paged in as the user scrolls, like the web's discovery sections. Sources whose routes take a
 * `sortBy` get the web's sort presets; the chosen one survives a trip to details and back.
 */
class SeerrDiscoveryFragment : Fragment() {
	companion object {
		const val ARG_SOURCE = "source"
		private const val STATE_SORT = "sort"
		private const val COLUMNS = 8
		private const val LOAD_MORE_THRESHOLD = COLUMNS * 2
		// Stop chaining page loads after this many pages in a row added nothing visible
		private const val MAX_EMPTY_PAGES = 5
	}

	private enum class Filter { ALL, MOVIES, TV }

	private val seerrRepository by inject<SeerrRepository>()
	private val enhancedRepository by inject<EnhancedRepository>()
	private val seerrLinkManager by inject<SeerrLinkManager>()
	private val navigationRepository by inject<NavigationRepository>()
	private val spoilerGuardRepository by inject<SpoilerGuardRepository>()

	private lateinit var source: SeerrSource
	// Hide (and other removals) from the card menu take the card out at once
	private val adapter = ArrayObjectAdapter(SeerrCardPresenter { removeCard(it) })
	private val allResults = mutableListOf<SeerrResult>()
	/** Titles removed from the card menu, kept out of pages that were already loading when they were hidden. */
	private val removed = HashSet<String>()
	/** [SpoilerGuardRepository.generation] when the cards' artwork was last resolved. */
	private var artworkGeneration = -1L
	private var cursor: SeerrRepository.FeedCursor? = null
	private var hasMore = true
	private var emptyPages = 0
	private var loadJob: Job? = null

	private val filter = MutableStateFlow(Filter.ALL)
	private val sort = MutableStateFlow(SeerrSort.POPULAR)
	private val status = MutableStateFlow<String?>(null)
	private val failed = MutableStateFlow(false)
	private val paused = MutableStateFlow(false)
	/** Loaded to the end with no card to show. */
	private val empty = MutableStateFlow(false)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		source = Json.decodeFromString(SeerrSource.serializer(), requireArguments().getString(ARG_SOURCE)!!)
		savedInstanceState?.getString(STATE_SORT)?.let { name -> SeerrSort.entries.firstOrNull { it.name == name } }?.let { sort.value = it }
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putString(STATE_SORT, sort.value.name)
	}

	/** Sources whose results are all one media type don't need the All / Movies / TV switch. */
	private val singleMediaType
		get() = when (val current = source) {
			is SeerrSource.Discover -> current.feed.mediaType != null
			is SeerrSource.DiscoverGenre, is SeerrSource.Collection -> true
			else -> false
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
		if (cursor == null) loadMore() else showAgain()
	}

	private val recommendations get() = source is SeerrSource.Recommended || source is SeerrSource.Similar

	private fun loadMore(userTriggered: Boolean = true) {
		if (!hasMore || loadJob?.isActive == true) return
		if (userTriggered) emptyPages = 0
		failed.value = false
		paused.value = false
		// Strings through the application context: results can land after the screen was left
		val appContext = context?.applicationContext ?: return
		loadJob = lifecycleScope.launch {
			if (allResults.isEmpty()) status.value = appContext.getString(R.string.je_loading)
			try {
				val resolved = cursor ?: SeerrRepository.FeedCursor(seerrRepository.feedsFor(source, sort.value)).also { cursor = it }
				if (!resolved.hasMore) {
					hasMore = false
				} else {
					val generation = spoilerGuardRepository.generation
					val batch = seerrRepository.loadNext(resolved, recommendations = recommendations)
					if (allResults.isEmpty()) artworkGeneration = generation
					hasMore = resolved.hasMore
					// Some feed failed: keep what loaded and offer Retry, which reloads that feed's page
					if (batch.partialFailure) failed.value = true
					val results = batch.results
					val known = allResults.mapTo(HashSet()) { it.dedupeKey }
					val fresh = results.filter { it.dedupeKey !in known && it.dedupeKey !in removed }
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
				// Paused at the empty-page cap with more to fetch: offer an explicit way on
				paused.value = hasMore && emptyPages >= MAX_EMPTY_PAGES
				status.value = when {
					adapter.size() > 0 -> null
					hasMore -> appContext.getString(R.string.je_nothing_yet)
					else -> appContext.getString(R.string.je_nothing_found)
				}
				empty.value = adapter.size() == 0 && !hasMore
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Seerr discovery failed")
				// Leave hasMore set so Retry (or scrolling) can try the same page again
				if (SeerrErrors.isUnlinked(err)) seerrLinkManager.onUnlinkedError()
				status.value = SeerrLinkTexts.error(appContext, err, enhancedRepository.current.isAdmin) ?: appContext.getString(R.string.je_error_generic)
				failed.value = true
			}
		}
	}

	/**
	 * Coming back to this screen: Spoiler Guard may have changed meanwhile, so library posters are withheld at once
	 * (every poster, when a Spoiler Guard write happened since they were resolved: a pending opt-in protects titles
	 * that aren't in the library) and re-resolved before they show again. Hidden Content is applied again too, so a
	 * title hidden from its details screen is gone.
	 */
	private fun showAgain() {
		val generation = spoilerGuardRepository.generation
		val protectionChanged = generation != artworkGeneration
		allResults.replaceAll {
			val inLibrary = it.mediaInfo?.jellyfinMediaId != null || it.mediaInfo?.jellyfinMediaId4k != null
			if (inLibrary || protectionChanged) it.copy(libraryArtwork = true, libraryPosterUrl = null) else it
		}
		applyFilter()
		lifecycleScope.launch {
			val refreshed = try {
				seerrRepository.filterResults(allResults.toList(), recommendations).associateBy { it.dedupeKey }
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to re-filter Seerr results")
				return@launch
			}
			artworkGeneration = generation
			// Whatever the filter dropped (hidden meanwhile) leaves the grid
			allResults.retainAll { it.dedupeKey in refreshed }
			allResults.replaceAll { refreshed[it.dedupeKey] ?: it }
			applyFilter()
		}
	}

	private fun removeCard(result: SeerrResult) {
		removed += result.dedupeKey
		allResults.removeAll { it.dedupeKey == result.dedupeKey }
		applyFilter()
	}

	/** Start over with the new order: the loaded pages were fetched in the old one. */
	private fun changeSort(value: SeerrSort) {
		if (sort.value == value) return
		sort.value = value
		loadJob?.cancel()
		loadJob = null
		cursor = null
		hasMore = true
		emptyPages = 0
		allResults.clear()
		adapter.clear()
		loadMore()
	}

	private fun matchesFilter(item: SeerrResult) = when (filter.value) {
		Filter.ALL -> true
		Filter.MOVIES -> item.isMovie
		Filter.TV -> item.isTv
	}

	private fun applyFilter() {
		adapter.setItems(allResults.filter(::matchesFilter), SeerrResultDiff)
		// A filter or removal can empty a fully loaded grid; the load below won't run then, so say so here
		val nothingLeft = adapter.size() == 0 && !hasMore
		empty.value = nothingLeft
		if (nothingLeft && loadJob?.isActive != true && !failed.value) status.value = context?.getString(R.string.je_nothing_found)
		else if (adapter.size() > 0 && !failed.value) status.value = null
		if (adapter.size() < LOAD_MORE_THRESHOLD) loadMore()
	}

	@androidx.compose.runtime.Composable
	private fun Header() {
		val currentFilter by filter.collectAsState()
		val currentSort by sort.collectAsState()
		val currentStatus by status.collectAsState()
		val currentFailed by failed.collectAsState()
		val currentPaused by paused.collectAsState()
		val currentEmpty by empty.collectAsState()

		Column(
			modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 27.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(text = source.title, fontSize = 28.sp, color = Tokens.Color.colorWhite)
			// Scrolls sideways: with every filter, sort, status and Retry the row can be wider than the screen
			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.horizontalScroll(rememberScrollState()),
			) {
				if (!singleMediaType) listOf(
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
				if (source.sortable) {
					Text(
						text = stringResource(R.string.je_discover_sort),
						color = secondaryTextColor(),
						modifier = Modifier.padding(start = if (singleMediaType) 0.dp else 16.dp),
					)
					listOf(
						SeerrSort.POPULAR to R.string.je_discover_sort_popular,
						SeerrSort.TOP_RATED to R.string.je_discover_sort_top_rated,
						SeerrSort.NEWEST to R.string.je_discover_sort_newest,
						SeerrSort.OLDEST to R.string.je_discover_sort_oldest,
					).forEach { (value, label) ->
						Button(
							onClick = { changeSort(value) },
							colors = if (currentSort == value) ButtonDefaults.colors(
								containerColor = JellyfinTheme.colorScheme.buttonActive,
								contentColor = JellyfinTheme.colorScheme.onButtonActive,
							) else ButtonDefaults.colors(),
						) {
							Text(stringResource(label))
						}
					}
				}
				currentStatus?.let { Text(text = it, color = secondaryTextColor(), modifier = Modifier.padding(start = 16.dp)) }
				if (currentFailed) {
					Button(onClick = { loadMore() }) { Text(stringResource(R.string.je_retry)) }
				} else if (currentPaused) {
					Button(onClick = { loadMore() }) { Text(stringResource(R.string.je_load_more)) }
				} else if (currentEmpty) {
					// Nothing to show and nothing more to load: the grid has no card to focus, so offer a way out
					val backFocus = remember { FocusRequester() }
					Button(onClick = { navigationRepository.goBack() }, modifier = Modifier.focusRequester(backFocus)) {
						Text(stringResource(R.string.je_seerr_back))
					}
					LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
				}
			}
		}
	}
}
