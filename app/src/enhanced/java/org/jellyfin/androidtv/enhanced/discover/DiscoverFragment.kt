package org.jellyfin.androidtv.enhanced.discover

import org.jellyfin.androidtv.enhanced.ui.secondaryTextColor
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.AndroidFragment
import androidx.fragment.compose.content
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrErrors
import org.jellyfin.androidtv.enhanced.seerr.SeerrImages
import org.jellyfin.androidtv.enhanced.seerr.SeerrLink
import org.jellyfin.androidtv.enhanced.seerr.SeerrLinkManager
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.seerr.SeerrSource
import org.jellyfin.androidtv.enhanced.ui.EnhancedDestinations
import org.jellyfin.androidtv.enhanced.ui.hub.EnhancedScreen
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrCardPresenter
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrLinkNotice
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrLinkTexts
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrViewAllCard
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.CustomListRowPresenter
import org.jellyfin.design.Tokens
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * The Discover page, the native take on the web's Seerr recommendations page: Trending, Popular and Upcoming rows
 * (each ending in "View all"), genre tiles, and curated studio and network tiles, all opening the discovery grid.
 * The header has "Pick something for me", a random title from the user's own library.
 *
 * The rows live in this (retained) fragment rather than in the leanback rows fragment, which Compose recreates on
 * every return from details: keeping them here keeps the loaded cards and the row/card the user came back from.
 */
class DiscoverFragment : Fragment() {
	private val enhancedRepository by inject<EnhancedRepository>()
	private val discoverRepository by inject<DiscoverRepository>()
	private val seerrRepository by inject<SeerrRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val seerrLinkManager by inject<SeerrLinkManager>()

	private val rowsAdapter = ArrayObjectAdapter(CustomListRowPresenter())
	private val sections = mutableListOf<Section>()

	/** Null until the gate is known, then whether the page applies to this server/user. */
	private val available = MutableStateFlow<Boolean?>(null)

	/** Why the page is unavailable when that's the user's Seerr link (then it shows the link notice). */
	private enum class LinkNotice { NONE, STATE, CALL }

	private val linkNotice = MutableStateFlow(LinkNotice.NONE)
	private val randomEnabled = MutableStateFlow(false)
	private val picking = MutableStateFlow(false)

	private var selectedRow = 0
	private var selectedItem = 0
	private var restoreSelection = false

	/**
	 * One row: a loading/error/empty placeholder until [loader] answers, then its cards. [loader] null means the
	 * row's [items] are fixed.
	 */
	private inner class Section(title: String, private val loader: (suspend () -> List<Any>)?, items: List<Any> = emptyList()) {
		val adapter = ArrayObjectAdapter(ClassPresenterSelector().apply {
			val seerrPresenter = SeerrCardPresenter(onRemoveCard = ::remove)
			addClassPresenter(SeerrResult::class.java, seerrPresenter)
			addClassPresenter(SeerrViewAllCard::class.java, seerrPresenter)
			val discoverPresenter = DiscoverCardPresenter(onRemoveCard = ::remove)
			addClassPresenter(DiscoverUpcomingCard::class.java, discoverPresenter)
			addClassPresenter(DiscoverTile::class.java, discoverPresenter)
			addClassPresenter(DiscoverRowStatus.Loading::class.java, discoverPresenter)
			addClassPresenter(DiscoverRowStatus.Empty::class.java, discoverPresenter)
			addClassPresenter(DiscoverRowStatus.Error::class.java, discoverPresenter)
		})
		val row = ListRow(HeaderItem(title), adapter)
		private var job: Job? = null

		fun cancel() {
			job?.cancel()
		}

		init {
			adapter.setItems(items, null)
		}

		fun load() {
			val loader = loader ?: return
			job?.cancel()
			adapter.setItems(listOf(DiscoverRowStatus.Loading), null)
			job = lifecycleScope.launch {
				val items = try {
					loader()
				} catch (err: CancellationException) {
					throw err
				} catch (err: Exception) {
					Timber.w(err, "Discover row failed")
					// The account isn't in Seerr (any more): the whole page says so, with the import for admins
					if (SeerrErrors.isUnlinked(err)) {
						seerrLinkManager.onUnlinkedError()
						linkNotice.value = LinkNotice.CALL
						available.value = false
						return@launch
					}
					// The server's own message when it sent one (Seerr down, parental block…), as a full sentence
					val context = context ?: return@launch
					val message = SeerrLinkTexts.error(context, err, enhancedRepository.current.isAdmin)
					adapter.setItems(listOf(DiscoverRowStatus.Error(message) { load() }), null)
					return@launch
				}
				adapter.setItems(items.ifEmpty { listOf(DiscoverRowStatus.Empty) }, null)
			}
		}

		/** A card menu entry (Hide) took the title away. */
		private fun remove(result: SeerrResult) {
			adapter.remove(result)
			adapter.remove(DiscoverUpcomingCard(result))
			if (adapter.size() == 0 || (adapter.size() == 1 && adapter.get(0) is SeerrViewAllCard)) {
				adapter.setItems(listOf(DiscoverRowStatus.Empty), null)
			}
		}

		/**
		 * Back from details: Spoiler Guard may have changed meanwhile, so library posters are withheld at once and
		 * re-resolved with the server's current image tags (as the discovery grid does).
		 */
		fun refreshArtwork() {
			val results = adapter.unmodifiableList<Any>().mapNotNull(::resultOf)
			if (results.none { it.inLibrary }) return
			replaceResults { if (it.inLibrary) it.copy(libraryArtwork = true, libraryPosterUrl = null) else it }
			lifecycleScope.launch {
				val refreshed = try {
					seerrRepository.refreshLibraryArtwork(results).associateBy { it.dedupeKey }
				} catch (err: CancellationException) {
					throw err
				} catch (err: Exception) {
					Timber.w(err, "Failed to refresh library artwork")
					return@launch
				}
				replaceResults { refreshed[it.dedupeKey] ?: it }
			}
		}

		private fun replaceResults(transform: (SeerrResult) -> SeerrResult) {
			for (index in 0 until adapter.size()) {
				when (val item = adapter.get(index)) {
					is SeerrResult -> adapter.replace(index, transform(item))
					is DiscoverUpcomingCard -> adapter.replace(index, DiscoverUpcomingCard(transform(item.result)))
				}
			}
		}
	}

	private val SeerrResult.inLibrary get() = mediaInfo?.jellyfinMediaId != null || mediaInfo?.jellyfinMediaId4k != null

	private fun resultOf(item: Any?): SeerrResult? = when (item) {
		is SeerrResult -> item
		is DiscoverUpcomingCard -> item.result
		else -> null
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		lifecycleScope.launch { open() }
	}

	/** Check the gate and, when the page applies, build and load the rows. */
	private suspend fun open() {
		val state = enhancedRepository.ensureLoaded()
		if (!state.discover) {
			if (SeerrLink.blocksPage(state, state.config.recommendationsPageEnabled)) linkNotice.value = LinkNotice.STATE
			available.value = false
			return
		}
		linkNotice.value = LinkNotice.NONE
		buildRows()
		available.value = true
		randomEnabled.value = try {
			discoverRepository.randomSettings().enabled
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Failed to resolve the random pick settings")
			false
		}
	}

	/** The Seerr link works now (imported, or checked again): start over with fresh rows. */
	private fun reopen() {
		sections.forEach { it.cancel() }
		sections.clear()
		rowsAdapter.clear()
		available.value = null
		linkNotice.value = LinkNotice.NONE
		lifecycleScope.launch { open() }
	}

	private fun buildRows() {
		val context = context ?: return
		for ((feed, title) in DiscoverCatalog.feeds) {
			val source = SeerrSource.Discover(feed, context.getString(title))
			sections += Section(source.title, loader = { feedItems(source) })
		}
		sections += Section(context.getString(R.string.je_discover_movie_genres), loader = { genreTiles(SeerrRepository.MEDIA_MOVIE) })
		sections += Section(context.getString(R.string.je_discover_tv_genres), loader = { genreTiles(SeerrRepository.MEDIA_TV) })
		sections += Section(context.getString(R.string.je_discover_studios), loader = null, items = DiscoverCatalog.studios.map { studio ->
			DiscoverTile(studio.name, DiscoverCatalog.logoUrl(studio.logoPath), logo = true, source = DiscoverCatalog.studioSource(studio))
		})
		sections += Section(context.getString(R.string.je_discover_networks), loader = null, items = DiscoverCatalog.networks.map { network ->
			DiscoverTile(network.name, DiscoverCatalog.logoUrl(network.logoPath), logo = true, source = DiscoverCatalog.networkSource(network))
		})

		sections.forEach { rowsAdapter.add(it.row) }
		sections.forEach { it.load() }
	}

	private suspend fun feedItems(source: SeerrSource.Discover): List<Any> {
		val preview = discoverRepository.rowPreview(source)
		if (preview.results.isEmpty()) return emptyList()
		val cards: List<Any> = if (source.feed.upcoming) preview.results.map(::DiscoverUpcomingCard) else preview.results
		return if (preview.hasMore) cards + SeerrViewAllCard(source) else cards
	}

	private suspend fun genreTiles(mediaType: String): List<Any> {
		val genres = discoverRepository.genreSlider(mediaType)
		val backdrops = if (discoverRepository.genreBackdropsAllowed()) assignGenreBackdrops(genres) else genres.map { null }
		return genres.zip(backdrops) { genre, backdrop ->
			DiscoverTile(
				title = genre.name,
				imageUrl = SeerrImages.backdrop(backdrop, "w780"),
				logo = false,
				source = SeerrSource.DiscoverGenre(mediaType, genre.id, genre.name),
			)
		}
	}

	private fun onItemClicked(item: Any?) {
		when (item) {
			is SeerrResult -> openDetails(item)
			is DiscoverUpcomingCard -> openDetails(item.result)
			is SeerrViewAllCard -> navigationRepository.navigate(EnhancedDestinations.seerrDiscovery(item.source))
			is DiscoverTile -> navigationRepository.navigate(EnhancedDestinations.seerrDiscovery(item.source))
			is DiscoverRowStatus.Error -> item.retry()
		}
	}

	private fun openDetails(result: SeerrResult) {
		navigationRepository.navigate(EnhancedDestinations.seerrDetails(result.mediaType ?: SeerrRepository.MEDIA_MOVIE, result.id))
	}

	private fun pickRandom() {
		if (picking.value) return
		picking.value = true
		lifecycleScope.launch {
			try {
				val pick = discoverRepository.randomPick()
				val context = context ?: return@launch
				showRandomPickNotice(context, pick.notice)
				navigationRepository.navigate(Destinations.itemDetails(pick.itemId))
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Random pick failed")
				val context = context ?: return@launch
				Toast.makeText(context, randomPickErrorMessage(context, err), Toast.LENGTH_LONG).show()
			} finally {
				picking.value = false
			}
		}
	}

	/** Hook the (possibly new) rows fragment up to the retained rows, and put the selection back where it was. */
	private fun bindRows(fragment: RowsSupportFragment) {
		if (fragment.adapter !== rowsAdapter) fragment.adapter = rowsAdapter
		fragment.onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ -> onItemClicked(item) }
		fragment.onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, row ->
			val listRow = row as? ListRow ?: return@OnItemViewSelectedListener
			selectedRow = rowsAdapter.indexOf(listRow).coerceAtLeast(0)
			selectedItem = (listRow.adapter as? ArrayObjectAdapter)?.indexOf(item)?.coerceAtLeast(0) ?: 0
		}
		if (restoreSelection && rowsAdapter.size() > 0) {
			restoreSelection = false
			fragment.setSelectedPosition(selectedRow.coerceAtMost(rowsAdapter.size() - 1), false, ListRowPresenter.SelectItemViewHolderTask(selectedItem))
		}
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		val isAvailable by available.collectAsState()
		val notice by linkNotice.collectAsState()
		val showRandom by randomEnabled.collectAsState()
		val isPicking by picking.collectAsState()
		val rowsFocus = remember { FocusRequester() }
		val rowsFragment = remember { arrayOfNulls<RowsSupportFragment>(1) }

		EnhancedScreen(
			title = stringResource(R.string.je_discover_title),
			headerActions = if (showRandom && isAvailable == true) {
				{
					Button(onClick = ::pickRandom, enabled = !isPicking) {
						Icon(painter = painterResource(R.drawable.je_ic_discover_casino), contentDescription = null, modifier = Modifier.size(20.dp))
						Text(
							text = stringResource(if (isPicking) R.string.je_discover_random_picking else R.string.je_discover_random),
							modifier = Modifier.padding(start = 8.dp),
						)
					}
				}
			} else null,
		) {
			when (isAvailable) {
				null -> Text(
					text = stringResource(R.string.je_loading),
					color = secondaryTextColor(),
					fontSize = 18.sp,
					modifier = Modifier.padding(horizontal = 48.dp),
				)

				false -> if (notice != LinkNotice.NONE) {
					SeerrLinkNotice(
						modifier = Modifier.padding(horizontal = 48.dp),
						unlinkedCall = notice == LinkNotice.CALL,
						onLinked = ::reopen,
					)
				} else {
					Text(
						text = stringResource(R.string.je_discover_unavailable),
						color = secondaryTextColor(),
						fontSize = 18.sp,
						modifier = Modifier.padding(horizontal = 48.dp),
					)
				}

				true -> {
					LaunchedEffect(Unit) { runCatching { rowsFocus.requestFocus() } }
					// Same focus handling as the home screen: leanback only lets focus leave upwards (to the header and
					// toolbar) from the first row, and forgets its own focus state when it does
					AndroidFragment<RowsSupportFragment>(
						modifier = Modifier
							.focusGroup()
							.focusRequester(rowsFocus)
							.focusProperties {
								onExit = {
									val fragment = rowsFragment[0]
									if (fragment == null || requestedFocusDirection != FocusDirection.Up || fragment.selectedPosition > 0) {
										cancelFocusChange()
									} else {
										fragment.selectedPosition = 0
										fragment.verticalGridView?.clearFocus()
									}
								}
							}
							.fillMaxSize(),
						maxLifecycle = Lifecycle.State.RESUMED,
						onUpdate = { fragment ->
							rowsFragment[0] = fragment
							bindRows(fragment)
						},
					)
				}
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		// Coming back (the rows were already built): restore the selection and re-check library artwork
		if (sections.isNotEmpty()) {
			restoreSelection = true
			sections.forEach { it.refreshArtwork() }
		}
	}
}
