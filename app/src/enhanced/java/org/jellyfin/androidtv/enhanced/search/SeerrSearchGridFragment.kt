package org.jellyfin.androidtv.enhanced.search

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.ui.EnhancedDestinations
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrCardPresenter
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrResultDiff
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.dp
import org.jellyfin.design.Tokens
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * Grid of Seerr titles opened from search: every page of a search's movie and show results ("View all"), or the
 * parts of a TMDB collection. Library titles keep their server posters while Spoiler Guard is on.
 */
class SeerrSearchGridFragment : Fragment() {
	companion object {
		const val ARG_QUERY = "query"
		const val ARG_COLLECTION_ID = "collection_id"
		const val ARG_TITLE = "title"

		private const val COLUMNS = 8
		private const val LOAD_MORE_THRESHOLD = COLUMNS * 2

		// Stop chaining page loads after this many pages in a row added nothing visible
		private const val MAX_EMPTY_PAGES = 5
	}

	private val enhancedRepository by inject<EnhancedRepository>()
	private val searchRepository by inject<SeerrSearchRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val spoilerGuardRepository by inject<SpoilerGuardRepository>()

	/** Search mode: the box's text, "Title (YYYY)" included. Null in collection mode. */
	private var query: SeerrSearchQuery? = null
	private var tmdbCollectionId: Int = 0
	private lateinit var title: String

	private val adapter: ArrayObjectAdapter = ArrayObjectAdapter(SeerrCardPresenter { removed ->
		adapter.remove(removed)
		// The last card hidden: the grid is empty for good when nothing more can load
		if (adapter.size() == 0 && !hasMore) {
			empty.value = true
			status.value = context?.getString(R.string.je_nothing_found)
		}
	})
	private val seen = HashSet<String>()
	private var filter: SeerrSearchFilter? = null
	private var nextPage = 1
	private var totalPages = 1
	private var loadedOnce = false
	private var emptyPages = 0
	private var loadJob: Job? = null

	private val status = MutableStateFlow<String?>(null)
	private val failed = MutableStateFlow(false)
	private val paused = MutableStateFlow(false)
	/** Loaded to the end with no card to show. */
	private val empty = MutableStateFlow(false)
	/** [SpoilerGuardRepository.generation] when the cards' artwork was last resolved. */
	private var artworkGeneration = -1L

	private val hasMore get() = if (query != null) nextPage <= totalPages else !loadedOnce

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val args = requireArguments()
		query = args.getString(ARG_QUERY)?.let(SeerrSearchQuery::parse)
		tmdbCollectionId = args.getInt(ARG_COLLECTION_ID)
		title = args.getString(ARG_TITLE).orEmpty()
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
		if (!loadedOnce && adapter.size() == 0) loadMore() else showAgain()
	}

	private fun loadMore(userTriggered: Boolean = true) {
		if (!hasMore || loadJob?.isActive == true) return
		if (userTriggered) emptyPages = 0
		failed.value = false
		paused.value = false
		// Strings through the application context: results can land after the screen was left
		val appContext = context?.applicationContext ?: return
		loadJob = lifecycleScope.launch {
			if (adapter.size() == 0) status.value = appContext.getString(R.string.je_loading)
			try {
				val state = enhancedRepository.ensureLoaded()
				if (!SeerrSearchRepository.enabled(state)) {
					status.value = appContext.getString(R.string.je_seerr_unavailable)
					empty.value = adapter.size() == 0
					return@launch
				}
				val generation = spoilerGuardRepository.generation
				val filter = filter ?: searchRepository.filter(state, query?.year).let { filter ->
					// A collection is a fixed set: its library parts stay, marked Available
					if (query == null) filter.copy(excludeLibraryItems = false) else filter
				}.also { filter = it }

				val results = when (val query = query) {
					null -> searchRepository.collection(tmdbCollectionId).parts.also { loadedOnce = true }
					else -> searchRepository.search(query.title, nextPage).let { page ->
						nextPage++
						totalPages = minOf(page.totalPages, SeerrSearchRepository.MAX_PAGE)
						loadedOnce = true
						page.results
					}
				}
				val visible = searchRepository.withLibraryArtwork(
					results.filter { (it.isMovie || it.isTv) && filter.accepts(it) && seen.add(it.dedupeKey) }
				)
				if (adapter.size() == 0) artworkGeneration = generation
				adapter.addAll(adapter.size(), visible)

				// A page that was entirely filtered out adds no cards to scroll onto, so nothing would trigger the next
				// load; keep going until something shows up (or the results end)
				emptyPages = if (visible.isEmpty()) emptyPages + 1 else 0
				if (hasMore && emptyPages < MAX_EMPTY_PAGES && (adapter.size() < LOAD_MORE_THRESHOLD || visible.isEmpty())) {
					loadJob = null
					loadMore(userTriggered = false)
					return@launch
				}
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
				Timber.w(err, "Seerr search grid failed")
				// Nothing advanced, so Retry loads the same page again
				status.value = (err as? EnhancedApi.EnhancedApiException)?.serverMessage ?: appContext.getString(R.string.je_error_generic)
				failed.value = true
			}
		}
	}

	/**
	 * Coming back to this screen: Spoiler Guard may have changed meanwhile, so library posters are withheld at once
	 * (every poster, when a Spoiler Guard write happened since they were resolved: a pending opt-in protects titles
	 * that aren't in the library) and re-resolved before they show again.
	 */
	private fun showAgain() {
		val items = (0 until adapter.size()).mapNotNull { adapter.get(it) as? SeerrResult }
		if (items.isEmpty()) return
		val generation = spoilerGuardRepository.generation
		val protectionChanged = generation != artworkGeneration
		val withheld = items.map {
			if (SeerrSearchFilter.libraryId(it) != null || protectionChanged) it.copy(libraryArtwork = true, libraryPosterUrl = null) else it
		}
		adapter.setItems(withheld, SeerrResultDiff)
		lifecycleScope.launch {
			val refreshed = try {
				searchRepository.withLibraryArtwork(withheld)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to refresh library artwork")
				return@launch
			}
			artworkGeneration = generation
			adapter.setItems(refreshed, SeerrResultDiff)
		}
	}

	@Composable
	private fun Header() {
		val currentStatus by status.collectAsState()
		val currentFailed by failed.collectAsState()
		val currentPaused by paused.collectAsState()
		val currentEmpty by empty.collectAsState()

		Column(
			modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 27.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(text = title, fontSize = 28.sp, color = Tokens.Color.colorWhite)
			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.horizontalScroll(rememberScrollState()),
			) {
				currentStatus?.let { Text(text = it, color = secondaryTextColor()) }
				if (currentFailed) {
					val retryFocus = remember { FocusRequester() }
					// With no cards to land on, the remote needs somewhere to go
					LaunchedEffect(Unit) { if (adapter.size() == 0) retryFocus.requestFocus() }
					Button(onClick = { loadMore() }, modifier = Modifier.focusRequester(retryFocus)) { Text(stringResource(R.string.je_retry)) }
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
