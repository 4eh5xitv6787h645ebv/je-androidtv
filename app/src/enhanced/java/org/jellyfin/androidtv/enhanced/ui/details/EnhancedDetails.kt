package org.jellyfin.androidtv.enhanced.ui.details

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.DiffCallback
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.seerr.SeerrSource
import org.jellyfin.androidtv.enhanced.ui.EnhancedDestinations
import org.jellyfin.androidtv.enhanced.ui.HideFlow
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrCardPresenter
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrViewAllCard
import org.jellyfin.androidtv.ui.TextUnderButton
import org.jellyfin.androidtv.ui.itemdetail.MyDetailsOverviewRow
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.util.MenuBuilder
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PersonKind
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

/**
 * Jellyfin Enhanced additions to the item details screen (FullDetailsFragment):
 * - Spoiler Guard toggle on movies and shows
 * - Hide / Unhide
 * - "More from…" menu that opens Seerr discovery for the cast, genres, studios and tags
 * - Seerr rows (recommended/similar titles, a person's other work, missing collection parts) that can be requested
 */
object EnhancedDetails {
	private val enhancedRepository by inject<EnhancedRepository>(EnhancedRepository::class.java)
	private val spoilerGuardRepository by inject<SpoilerGuardRepository>(SpoilerGuardRepository::class.java)
	private val hiddenContentRepository by inject<HiddenContentRepository>(HiddenContentRepository::class.java)
	private val seerrRepository by inject<SeerrRepository>(SeerrRepository::class.java)
	private val navigationRepository by inject<NavigationRepository>(NavigationRepository::class.java)

	/** Feature contributions registered with Koin (see [DetailsContributor]). */
	private val contributors: List<DetailsContributor>
		get() = org.koin.java.KoinJavaComponent.getKoin().getAll()

	private const val ROW_PREVIEW_SIZE = 20

	private val Fragment.isAlive get() = lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED) && context != null

	/** One of our action buttons, plus how to run it from upstream's "⋯" overflow menu when it doesn't fit. */
	private class EnhancedAction(
		val button: TextUnderButton,
		val run: (anchor: View) -> Unit,
	) {
		var wanted = false
	}

	private val actionsByFragment = HashMap<Fragment, List<EnhancedAction>>()
	private val busyButtons = java.util.Collections.newSetFromMap(java.util.WeakHashMap<TextUnderButton, Boolean>())

	/**
	 * Add the Jellyfin Enhanced action buttons. They are created hidden and revealed once the plugin's state for the
	 * user is known, so nothing changes on servers without the plugin. [reload] refreshes the details screen.
	 */
	@JvmStatic
	fun addButtons(fragment: Fragment, row: MyDetailsOverviewRow, item: BaseItemDto, buttonSize: Int, reload: Runnable) {
		val context = fragment.requireContext()
		val actions = mutableListOf<EnhancedAction>()

		fun action(icon: Int, label: Int, run: (button: TextUnderButton, anchor: View) -> Unit): EnhancedAction {
			lateinit var action: EnhancedAction
			val button = TextUnderButton.create(context, icon, buttonSize, 2, context.getString(label)) { view -> action.run(view) }
			button.isVisible = false
			action = EnhancedAction(button) { anchor -> run(button, anchor) }
			row.addAction(button)
			actions += action
			return action
		}

		val spoilerKind = SpoilerGuardRepository.kindOf(item)
		val spoilerAction = spoilerKind?.let { kind ->
			action(R.drawable.je_ic_blur_off, R.string.je_spoiler_guard) { button, _ -> toggleSpoilerGuard(fragment, button, item, kind, reload) }
		}

		val canHide = item.type in setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE, BaseItemKind.SEASON, BaseItemKind.PERSON)
		val hideAction = if (canHide) {
			action(R.drawable.je_ic_visibility, R.string.je_hide) { button, _ -> toggleHidden(fragment, button, item) }
		} else null

		val canDiscover = item.type == BaseItemKind.MOVIE || item.type == BaseItemKind.SERIES
		val moreFromAction = if (canDiscover) {
			action(R.drawable.je_ic_travel_explore, R.string.je_more_from) { _, anchor -> showMoreFromMenu(fragment, anchor, item) }
		} else null

		// The actions capture the fragment, so drop them with it (a WeakHashMap alone would keep it alive)
		if (actionsByFragment.put(fragment, actions) == null) {
			fragment.lifecycle.addObserver(LifecycleEventObserver { _, event ->
				if (event == Lifecycle.Event.ON_DESTROY) actionsByFragment.remove(fragment)
			})
		}
		fragment.lifecycleScope.launch {
			val state = enhancedRepository.ensureLoaded()
			if (!state.available || !fragment.isAlive) return@launch

			if (spoilerAction != null && spoilerKind != null && state.spoilerGuard) {
				runCatching { spoilerGuardRepository.getState() }
					.onSuccess { spoilerState ->
						setSpoilerButtonState(spoilerAction.button, spoilerGuardRepository.isGuarded(spoilerState, spoilerKind, item.id))
						spoilerAction.button.tag = spoilerState.prefs.skipDisableConfirm
						spoilerAction.wanted = true
					}
					.onFailure { Timber.w(it, "Failed to load Spoiler Guard state") }
			}

			if (hideAction != null && state.hiddenContent) {
				runCatching { hiddenContentRepository.get() }
					.onSuccess { hidden ->
						val settings = hidden.settings
						val allowed = settings.enabled && settings.showHideButtons &&
							if (item.type == BaseItemKind.PERSON) settings.showButtonCast else settings.showButtonDetails
						if (allowed) {
							setHideButtonState(hideAction.button, hidden.isHidden(item.id))
							hideAction.wanted = true
						}
					}
					.onFailure { Timber.w(it, "Failed to load hidden content") }
			}

			if (moreFromAction != null && moreFromEntries(context, item, state).isNotEmpty()) {
				moreFromAction.wanted = true
			}

			// Feature contributions: least important (highest priority number) last, so they overflow first
			val detailsContext = DetailsContext(fragment, item, state) { reload.run() }
			val contributed = contributors.flatMap { contributor ->
				runCatching { contributor.actions(detailsContext) }
					.onFailure { Timber.w(it, "Details actions from %s failed", contributor::class.simpleName) }
					.getOrDefault(emptyList())
			}.sortedBy { it.priority }
			if (!fragment.isAlive) return@launch
			for (contribution in contributed) {
				lateinit var action: EnhancedAction
				val button = TextUnderButton.create(context, contribution.icon, buttonSize, 2, contribution.label) { view -> action.run(view) }
				button.isVisible = false
				action = EnhancedAction(button) { anchor -> contribution.onClick(anchor) }
				action.wanted = true
				row.addAction(button)
				actions += action
			}
			// The upstream "⋯" button must stay last in the row
			if (contributed.isNotEmpty()) {
				val more = row.actions.firstOrNull { it !in actions.map { a -> a.button } && it.binding.label.text?.toString() == context.getString(R.string.lbl_other_options) }
				if (more != null) {
					val ordered = row.actions.filter { it !== more } + more
					row.clearActions()
					ordered.forEach(row::addAction)
					(more.parent as? ViewGroup)?.let { parent ->
						parent.removeView(more)
						actions.forEach { a -> if (a.button.parent == null) parent.addView(a.button) }
						parent.addView(more)
					}
				}
			}

			if (fragment.isAlive && actions.isNotEmpty()) fitActions(row, actions)
		}
	}

	/**
	 * Show the wanted buttons and keep everything on the one-line button row. Like FullDetailsFragment itself,
	 * overflowing buttons are hidden and offered from upstream's "⋯" button instead. Upstream's own collapsible
	 * buttons go first (its ⋯ menu already lists any of them that are hidden), then ours, least important first.
	 *
	 * Upstream shows and hides some buttons asynchronously (resume, previous episode), so the fit is recomputed
	 * whenever the row lays out. It only applies differences, so it settles after one pass.
	 */
	private fun fitActions(row: MyDetailsOverviewRow, actions: List<EnhancedAction>) {
		val parent = actions.first().button.parent as? ViewGroup
		if (parent == null) {
			// Not on screen yet (the state was already cached): fit once the row is attached
			actions.first().button.doOnAttach { fitActions(row, actions) }
			return
		}

		val context = parent.context
		val ours = actions.map { it.button }.toSet()
		// Upstream adds its overflow button last
		val moreButton = row.actions.lastOrNull()?.takeIf { it !in ours }
		val upstreamCollapsible = listOf(
			R.string.lbl_goto_series,
			R.string.lbl_favorite,
			R.string.lbl_shuffle_all,
			R.string.lbl_play_trailers,
			R.string.lbl_add_to_queue,
		).map(context::getString)

		val collapsedByUs = mutableSetOf<View>()
		var moreShownByUs = false

		fun refit() {
			val buttonWidth = row.actions.firstOrNull { it.isVisible && it.width > 0 }?.width ?: return
			val capacity = parent.width / buttonWidth
			if (capacity <= 0) return

			// What would be visible without our collapsing
			val baseline = row.actions.filter { button ->
				when {
					button in ours -> actions.first { it.button === button }.wanted
					button === moreButton -> button.isVisible && !moreShownByUs
					else -> button.isVisible || button in collapsedByUs
				}
			}.toMutableSet()

			val collapseOrder = upstreamCollapsible.flatMap { label ->
				row.actions.filter { it !in ours && it !== moreButton && it.binding.label.text?.toString() == label }
			} + actions.reversed().map { it.button }

			val collapsed = mutableSetOf<View>()
			var showMore = false
			for (button in collapseOrder) {
				val visible = baseline.size - collapsed.size + (if (showMore) 1 else 0)
				if (visible <= capacity) break
				if (button !in baseline) continue
				collapsed += button
				if (moreButton != null && moreButton !in baseline) showMore = true
			}

			for (button in row.actions) {
				val target = when {
					button === moreButton -> button in baseline || showMore
					else -> button in baseline && button !in collapsed
				}
				if (button.isVisible != target) button.isVisible = target
			}
			collapsedByUs.clear()
			collapsedByUs += collapsed.filter { it !in ours }
			moreShownByUs = showMore
		}

		actions.forEach { it.button.isVisible = it.wanted }
		parent.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
			override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
				// The details row was rebuilt with new buttons
				if (actions.first().button.parent !== parent) {
					parent.removeOnLayoutChangeListener(this)
					return
				}
				parent.post { refit() }
			}
		})
		parent.post { refit() }
	}

	/** Add menu entries for our buttons that were collapsed into upstream's "⋯" overflow menu. */
	@JvmStatic
	fun addOverflowItems(fragment: Fragment, menu: MenuBuilder, anchor: View) {
		actionsByFragment[fragment].orEmpty()
			.filter { it.wanted && !it.button.isVisible }
			.forEach { action -> menu.item(action.button.binding.label.text.toString()) { action.run(anchor) } }
	}

	private fun setSpoilerButtonState(button: TextUnderButton, guarded: Boolean) {
		button.isActivated = guarded
		button.setIcon(if (guarded) R.drawable.je_ic_blur_on else R.drawable.je_ic_blur_off, button.binding.imageButton.maxHeight)
			}

	private fun setHideButtonState(button: TextUnderButton, hidden: Boolean) {
		button.isActivated = hidden
		button.setIcon(if (hidden) R.drawable.je_ic_visibility_off else R.drawable.je_ic_visibility, button.binding.imageButton.maxHeight)
		button.setLabel(button.context.getString(if (hidden) R.string.je_unhide else R.string.je_hide))
	}

	private fun toggleSpoilerGuard(fragment: Fragment, button: TextUnderButton, item: BaseItemDto, kind: SpoilerGuardRepository.Kind, reload: Runnable) {
		val context = fragment.requireContext()
		val enable = !button.isActivated

		fun apply() {
			// Not isEnabled: disabling the focused button would throw focus to the toolbar
			if (!busyButtons.add(button)) return
			fragment.lifecycleScope.launch {
				runCatching { spoilerGuardRepository.setGuarded(kind, item.id, item.name, enable) }
					.onSuccess {
						Toast.makeText(context, if (enable) R.string.je_spoiler_guard_on else R.string.je_spoiler_guard_off, Toast.LENGTH_SHORT).show()
						setSpoilerButtonState(button, enable)
						// The plugin rewrites overviews, titles and images server side; reload to pick that up
						if (fragment.isAlive) reload.run()
					}
					.onFailure {
						Timber.w(it, "Failed to toggle Spoiler Guard")
						Toast.makeText(context, R.string.je_error_generic, Toast.LENGTH_SHORT).show()
					}
				busyButtons.remove(button)
			}
		}

		val skipConfirm = button.tag as? Boolean ?: false
		if (!enable && !skipConfirm) {
			AlertDialog.Builder(context)
				.setTitle(R.string.je_spoiler_disable_title)
				.setMessage(R.string.je_spoiler_disable_message)
				.setPositiveButton(R.string.je_spoiler_disable_confirm) { _, _ -> apply() }
				.setNegativeButton(android.R.string.cancel, null)
				.show()
		} else apply()
	}

	private fun toggleHidden(fragment: Fragment, button: TextUnderButton, item: BaseItemDto) {
		val context = fragment.requireContext()

		fun apply(hide: Boolean, wholeSeries: Boolean = false) {
			// Not isEnabled: disabling the focused button would throw focus to the toolbar
			if (!busyButtons.add(button)) return
			fragment.lifecycleScope.launch {
				runCatching {
					if (hide) hiddenContentRepository.hide(item, wholeSeries)
					else hiddenContentRepository.unhide(item.id)
				}.onSuccess {
					val name = if (wholeSeries) item.seriesName ?: item.name else item.name
					Toast.makeText(context, context.getString(if (hide) R.string.je_hidden_toast else R.string.je_unhidden_toast, name.orEmpty()), Toast.LENGTH_SHORT).show()
					// A whole-show hide doesn't hide this episode itself, so its button stays as it was
					if (!wholeSeries) setHideButtonState(button, hide)
				}.onFailure {
					Timber.w(it, "Failed to update hidden content")
					Toast.makeText(context, R.string.je_error_generic, Toast.LENGTH_SHORT).show()
				}
				busyButtons.remove(button)
			}
		}

		if (button.isActivated) {
			apply(hide = false)
			return
		}

		val askConfirmation = enhancedRepository.current.hiddenSettings?.showHideConfirmation ?: true
		HideFlow.confirm(context, item, askConfirmation) { wholeSeries -> apply(hide = true, wholeSeries = wholeSeries) }
	}

	// "More from…"

	private fun tmdbId(item: BaseItemDto): Int? =
		item.providerIds?.entries?.firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }?.value?.toIntOrNull()

	private fun mediaType(item: BaseItemDto) = if (item.type == BaseItemKind.SERIES) SeerrRepository.MEDIA_TV else SeerrRepository.MEDIA_MOVIE

	private fun moreFromEntries(context: Context, item: BaseItemDto, state: EnhancedState): List<Pair<String, SeerrSource>> {
		if (!state.seerrUsable) return emptyList()
		val config = state.config
		val entries = mutableListOf<Pair<String, SeerrSource>>()

		if (config.jellyseerrShowPersonDiscovery) {
			val people = item.people.orEmpty()
			val directors = people.filter { it.type == PersonKind.DIRECTOR }
			val actors = people.filter { it.type == PersonKind.ACTOR }.take(8)
			val writers = people.filter { it.type == PersonKind.WRITER }.take(2)
			for ((person, role) in directors.map { it to R.string.je_role_director } + actors.map { it to R.string.je_role_actor } + writers.map { it to R.string.je_role_writer }) {
				val name = person.name ?: continue
				val roleLabel = if (person.type == PersonKind.ACTOR && !person.role.isNullOrBlank()) person.role!! else context.getString(role)
				entries += context.getString(R.string.je_menu_person, name, roleLabel) to SeerrSource.Person(
					jellyfinPersonId = person.id.toString(),
					tmdbPersonId = null,
					name = name,
					title = context.getString(if (person.type == PersonKind.ACTOR) R.string.je_more_with_title else R.string.je_more_from_title, name),
				)
			}
		}

		if (config.jellyseerrShowGenreDiscovery) {
			item.genres.orEmpty().forEach { genre ->
				entries += context.getString(R.string.je_menu_genre, genre) to SeerrSource.Genre(genre, context.getString(R.string.je_genre_title, genre))
			}
		}

		if (config.jellyseerrShowNetworkDiscovery) {
			item.studios.orEmpty().forEach { studio ->
				val name = studio.name ?: return@forEach
				entries += context.getString(R.string.je_menu_studio, name) to SeerrSource.Studio(name, studio.id.toString(), context.getString(R.string.je_more_from_title, name))
			}
		}

		if (config.jellyseerrShowTagDiscovery) {
			item.tags.orEmpty().take(6).forEach { tag ->
				entries += context.getString(R.string.je_menu_tag, tag) to SeerrSource.Keyword(tag, context.getString(R.string.je_genre_title, tag))
			}
		}

		tmdbId(item)?.let { id ->
			val type = mediaType(item)
			if (config.jellyseerrShowRecommended) entries += context.getString(R.string.je_menu_recommended) to
				SeerrSource.Recommended(type, id, context.getString(R.string.je_menu_recommended))
			if (config.jellyseerrShowSimilar) entries += context.getString(R.string.je_menu_similar) to
				SeerrSource.Similar(type, id, context.getString(R.string.je_menu_similar))
		}

		return entries
	}

	private fun showMoreFromMenu(fragment: Fragment, anchor: View, item: BaseItemDto) {
		val context = fragment.requireContext()
		val entries = moreFromEntries(context, item, enhancedRepository.current)
		if (entries.isEmpty()) return

		PopupMenu(context, anchor, Gravity.END).apply {
			entries.forEachIndexed { index, (label, _) -> menu.add(0, index, index, label) }
			setOnMenuItemClickListener { menuItem ->
				navigationRepository.navigate(EnhancedDestinations.seerrDiscovery(entries[menuItem.itemId].second))
				true
			}
		}.show()
	}

	// Seerr rows

	/** Append Seerr rows below the regular rows of the details screen. */
	@JvmStatic
	fun addRows(fragment: Fragment, rowsAdapter: MutableObjectAdapter<Row>, item: BaseItemDto, reload: Runnable) {
		val context = fragment.requireContext()

		fragment.lifecycleScope.launch {
			val state = enhancedRepository.ensureLoaded()
			if (!state.available || !fragment.isAlive) return@launch
			val config = state.config

			// Feature contributions first (info chips, reviews…), then the Seerr rows. Rows placed at the top go directly
			// under the overview, above upstream's own rows, in contribution order
			val detailsContext = DetailsContext(fragment, item, state) { reload.run() }
			var topRows = 0
			for (contributor in contributors) {
				val rows = runCatching { contributor.rows(detailsContext) }
					.onFailure { Timber.w(it, "Details rows from %s failed", contributor::class.simpleName) }
					.getOrDefault(emptyList())
				if (!fragment.isAlive) return@launch
				for (row in rows) {
					if ((row as? EnhancedComposeRow)?.placement == DetailsRowPlacement.TOP) {
						rowsAdapter.add(topRowIndex(rowsAdapter) + topRows, row)
						topRows++
					} else {
						rowsAdapter.add(row)
					}
				}
			}

			if (state.seerrUsable) when (item.type) {
				BaseItemKind.MOVIE, BaseItemKind.SERIES -> tmdbId(item)?.let { id ->
					val type = mediaType(item)
					if (config.jellyseerrShowRecommended) addSourceRow(fragment, rowsAdapter, context.getString(R.string.je_row_recommended),
						SeerrSource.Recommended(type, id, context.getString(R.string.je_menu_recommended)))
					if (config.jellyseerrShowSimilar) addSourceRow(fragment, rowsAdapter, context.getString(R.string.je_row_similar),
						SeerrSource.Similar(type, id, context.getString(R.string.je_menu_similar)))
				}

				BaseItemKind.PERSON -> if (config.jellyseerrShowPersonDiscovery) {
					val name = item.name.orEmpty()
					addSourceRow(fragment, rowsAdapter, context.getString(R.string.je_row_more_from, name), SeerrSource.Person(
						jellyfinPersonId = item.id.toString(),
						tmdbPersonId = tmdbId(item),
						name = name,
						title = context.getString(R.string.je_more_from_title, name),
					))
				}

				BaseItemKind.BOX_SET -> if (config.jellyseerrShowCollectionDiscovery) {
					val missing = runCatching { seerrRepository.collectionMissing(item.id.toString()) }.getOrDefault(emptyList())
					if (missing.isNotEmpty() && fragment.isAlive) addRow(fragment, rowsAdapter, context.getString(R.string.je_row_collection_missing), missing, null, recommendations = false)
				}

				else -> Unit
			}

		}
	}

	private suspend fun addSourceRow(fragment: Fragment, rowsAdapter: MutableObjectAdapter<Row>, title: String, source: SeerrSource) {
		val (results, hasMore) = runCatching {
			val cursor = SeerrRepository.FeedCursor(seerrRepository.feedsFor(source))
			if (!cursor.hasMore) emptyList<SeerrResult>() to false
			else seerrRepository.loadNext(cursor, recommendations = source is SeerrSource.Recommended || source is SeerrSource.Similar)
				.let { batch -> batch.results to (cursor.hasMore || batch.partialFailure) }
		}.onFailure { Timber.w(it, "Failed to load Seerr row %s", title) }.getOrDefault(emptyList<SeerrResult>() to false)

		if (results.isEmpty() || !fragment.isAlive) return
		val recommendations = source is SeerrSource.Recommended || source is SeerrSource.Similar
		addRow(fragment, rowsAdapter, title, results.take(ROW_PREVIEW_SIZE), if (hasMore || results.size > ROW_PREVIEW_SIZE) source else null, recommendations)
	}

	/** The index right under the item's overview row (upstream adds it first). */
	private fun topRowIndex(rowsAdapter: MutableObjectAdapter<Row>): Int =
		rowsAdapter.indexOfFirst { it is MyDetailsOverviewRow } + 1

	private fun addRow(
		fragment: Fragment,
		rowsAdapter: MutableObjectAdapter<Row>,
		title: String,
		results: List<SeerrResult>,
		viewAll: SeerrSource?,
		recommendations: Boolean,
	) {
		lateinit var row: ListRow
		fun titles(cards: ArrayObjectAdapter) = (0 until cards.size()).mapNotNull { cards.get(it) as? SeerrResult }
		// A card hidden from its menu leaves the row at once; the row goes when no title is left in it
		val adapter = ArrayObjectAdapter(SeerrCardPresenter { removed ->
			val cards = row.adapter as ArrayObjectAdapter
			cards.remove(removed)
			if (titles(cards).isEmpty()) rowsAdapter.remove(row)
		})
		adapter.addAll(0, results)
		if (viewAll != null) adapter.add(SeerrViewAllCard(viewAll))
		row = ListRow(HeaderItem(title), adapter)
		rowsAdapter.add(row)

		// Back from a Seerr details screen: the title may have been hidden or protected there. Filter the cards again
		// (Hidden Content, current artwork), dropping what's gone, until the screen rebuilds its rows
		fragment.lifecycle.addObserver(object : LifecycleEventObserver {
			private var resumed = false

			override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
				if (event != Lifecycle.Event.ON_RESUME) return
				if (rowsAdapter.indexOf(row) < 0) {
					fragment.lifecycle.removeObserver(this)
					return
				}
				if (!resumed) {
					resumed = true
					return
				}
				fragment.lifecycleScope.launch {
					val shown = titles(adapter)
					val filtered = try {
						seerrRepository.filterResults(shown, recommendations)
					} catch (err: CancellationException) {
						throw err
					} catch (err: Exception) {
						Timber.w(err, "Failed to re-filter Seerr row %s", title)
						return@launch
					}
					if (rowsAdapter.indexOf(row) < 0) return@launch
					if (filtered.isEmpty()) {
						rowsAdapter.remove(row)
						return@launch
					}
					adapter.setItems(filtered + listOfNotNull(viewAll?.let(::SeerrViewAllCard)), SeerrCardDiff)
				}
			}
		})
	}

	/** Seerr cards and the trailing "View all" card, matched by identity so focus stays put. */
	private object SeerrCardDiff : DiffCallback<Any>() {
		override fun areItemsTheSame(oldItem: Any, newItem: Any) = when {
			oldItem is SeerrResult && newItem is SeerrResult -> oldItem.dedupeKey == newItem.dedupeKey
			else -> oldItem == newItem
		}

		override fun areContentsTheSame(oldItem: Any, newItem: Any) = oldItem == newItem
	}

	/** Handle clicks on Seerr cards in the details rows. Returns true when the click was handled. */
	@JvmStatic
	fun onItemClicked(item: Any?): Boolean = when (item) {
		is SeerrResult -> {
			navigationRepository.navigate(EnhancedDestinations.seerrDetails(item.mediaType ?: SeerrRepository.MEDIA_MOVIE, item.id))
			true
		}

		is SeerrViewAllCard -> {
			navigationRepository.navigate(EnhancedDestinations.seerrDiscovery(item.source))
			true
		}

		else -> false
	}
}
