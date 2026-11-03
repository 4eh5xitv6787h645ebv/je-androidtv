package org.jellyfin.androidtv.enhanced.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.calendar.attempt
import org.jellyfin.androidtv.enhanced.calendar.canOpen
import org.jellyfin.androidtv.enhanced.calendar.serverMessage
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.Protection
import org.jellyfin.androidtv.enhanced.data.SpoilerProtection
import org.jellyfin.androidtv.enhanced.data.SpoilerSnapshot
import org.jellyfin.androidtv.enhanced.ui.design.ChipRow
import org.jellyfin.androidtv.enhanced.ui.design.FilterChip
import org.jellyfin.androidtv.enhanced.ui.design.JeButton
import org.jellyfin.androidtv.enhanced.ui.design.JeSpace
import org.jellyfin.androidtv.enhanced.ui.design.ListRow
import org.jellyfin.androidtv.enhanced.ui.design.LoadStateContent
import org.jellyfin.androidtv.enhanced.ui.design.Notice
import org.jellyfin.androidtv.enhanced.ui.design.NoticeKind
import org.jellyfin.androidtv.enhanced.ui.design.PageScaffold
import org.jellyfin.androidtv.enhanced.ui.design.RowDensity
import org.jellyfin.androidtv.enhanced.ui.design.RowLeading
import org.jellyfin.androidtv.enhanced.ui.design.SectionHeader
import org.jellyfin.androidtv.enhanced.ui.design.SkeletonShape
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.enhanced.ui.hub.LoadStateContent
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

/**
 * The JE activity feed (who watched, favorited and reviewed what) with the server's active streams on top. The
 * streams refresh every 30 seconds while the page is visible and disappear when the server says the user may not
 * see them.
 */
class ActivityFragment : Fragment() {
	private val activityRepository by inject<ActivityRepository>()
	private val enhancedRepository by inject<EnhancedRepository>()
	private val spoilerProtection by inject<SpoilerProtection>()
	private val enhancedApi by inject<EnhancedApi>()
	private val navigationRepository by inject<NavigationRepository>()

	private companion object {
		val STREAMS_INTERVAL = 30.seconds
	}

	private class Feed(
		/** Null when the feed is turned off (the page then only shows active streams). */
		val entries: List<ActivityEntry>?,
		val config: ActivityConfig,
		/** The user's Spoiler Guard lists and policy; unresolvable parts fail closed (names and reviews withheld). */
		val spoilers: SpoilerSnapshot,
	)

	private sealed interface Streams {
		data object Hidden : Streams
		data object Loading : Streams
		data class Ready(val sessions: List<ActiveSession>) : Streams
		data class Failed(val message: String?) : Streams
	}

	private val loadState = MutableStateFlow<LoadState<Feed>>(LoadState.Loading)
	private val streams = MutableStateFlow<Streams>(Streams.Hidden)
	private val selected = MutableStateFlow<Set<ActivityType>>(emptySet())
	private val now = MutableStateFlow(System.currentTimeMillis())
	private var loadJob: Job? = null
	private var opening = false
	/** Focus is placed once per view, so the 30-second stream refresh never moves it. */
	private var initialFocusDone = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		reload()
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) { pollStreams() }
		}
	}

	private fun reload() {
		loadJob?.cancel()
		loadState.value = LoadState.Loading
		loadJob = lifecycleScope.launch {
			attempt {
				val state = enhancedRepository.ensureLoaded()
				coroutineScope {
					val configCall = async { activityRepository.config() }
					val feedCall = async { if (state.activity) activityRepository.feed().items else null }
					val spoilerCall = async { spoilerProtection.snapshot() }
					Feed(
						entries = feedCall.await(),
						config = configCall.await(),
						spoilers = spoilerCall.await(),
					)
				}
			}.onSuccess { feed ->
				now.value = System.currentTimeMillis()
				loadState.value = LoadState.Ready(feed)
			}.onFailure { err ->
				Timber.w(err, "Failed to load the JE activity feed")
				loadState.value = LoadState.Error(err.serverMessage) { reload() }
			}
		}
	}

	/** Runs while the page is started; returns when the user may not see active streams. */
	private suspend fun pollStreams() {
		val state = enhancedRepository.ensureLoaded()
		val config = attempt { activityRepository.config() }.getOrNull()
		if (!state.activeStreams || config?.showActiveStreams == false) {
			streams.value = Streams.Hidden
			return
		}
		if (streams.value == Streams.Hidden) streams.value = Streams.Loading
		while (true) {
			val result = attempt { activityRepository.sessions() }
			now.value = System.currentTimeMillis()
			val err = result.exceptionOrNull()
			if (err is EnhancedApi.EnhancedApiException && (err.status == 403 || err.status == 404)) {
				// Not allowed (or turned off): never fall back to the privileged /Sessions data
				streams.value = Streams.Hidden
				return
			}
			streams.value = result.fold({ Streams.Ready(it) }, {
				Timber.w(it, "Failed to load active streams")
				Streams.Failed(it.serverMessage)
			})
			delay(STREAMS_INTERVAL)
		}
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): ViewGroup {
		initialFocusDone = false
		return createContent()
	}

	private fun createContent() = content {
		val state by loadState.collectAsState()

		PageScaffold(title = stringResource(R.string.je_activity_title)) {
			LoadStateContent(state, loading = SkeletonShape.Rows(density = RowDensity.Compact)) { feed -> FeedContent(feed) }
		}
	}

	@Composable
	private fun FeedContent(feed: Feed) {
		val currentStreams by streams.collectAsState()
		val currentSelection by selected.collectAsState()
		val nowMs by now.collectAsState()
		val entries = remember(feed, currentSelection) { feed.entries?.let { ActivityLogic.filter(it, currentSelection) } }
		val categories = feed.config.categories
		val firstFocus = remember { FocusRequester() }
		val sessions = (currentStreams as? Streams.Ready)?.sessions.orEmpty()
		val sessionKeys = remember(sessions) { ActivityLogic.uniqueKeys(sessions, ActivityLogic::sessionKey) }
		val entryKeys = remember(entries) { entries?.let { ActivityLogic.uniqueKeys(it, ActivityLogic::entryKey) } }

		LazyColumn(
			contentPadding = PaddingValues(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, top = 4.dp, bottom = JeSpace.pageBottom),
			verticalArrangement = Arrangement.spacedBy(JeSpace.rowGap),
		) {
			when (val streamState = currentStreams) {
				Streams.Hidden, Streams.Loading -> Unit
				is Streams.Failed -> item(key = "streams-failed") {
					ActivityNotice(stringResource(R.string.je_activity_streams_failed, streamState.message ?: stringResource(R.string.je_error_generic)))
				}
				is Streams.Ready -> {
					item(key = "streams-header") { GroupHeader(stringResource(R.string.je_activity_streams_title), sessions.size, top = false) }
					if (sessions.isEmpty()) item(key = "streams-none") { ActivityNotice(stringResource(R.string.je_activity_streams_none)) }
					itemsIndexed(sessions, key = { index, _ -> sessionKeys[index] }) { index, session ->
						SessionRow(feed, session, if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
					}
				}
			}

			if (entries == null || entryKeys == null) {
				if (currentStreams !is Streams.Ready) item(key = "feed-off") { ActivityNotice(stringResource(R.string.je_activity_feed_off)) }
				if (sessions.isEmpty()) {
					item(key = "back") {
						JeButton(text = stringResource(R.string.je_hub_back), onClick = { navigationRepository.goBack() }, modifier = Modifier.focusRequester(firstFocus))
					}
				}
				return@LazyColumn
			}

			item(key = "feed-header") { GroupHeader(stringResource(R.string.je_activity_feed_title), null, top = currentStreams is Streams.Ready) }
			if (categories.size > 1) {
				item(key = "chips") {
					ChipRow {
						for (category in categories) {
							val chipModifier = if (sessions.isEmpty() && category == categories.first()) Modifier.focusRequester(firstFocus) else Modifier
							FilterChip(
								label = stringResource(categoryLabel(category)),
								selected = category in currentSelection,
								onClick = { selected.value = currentSelection.let { if (category in it) it - category else it + category } },
								modifier = chipModifier,
							)
						}
					}
				}
			}
			// Nothing else on the page takes the focus: give the D-pad somewhere to land
			val nothingFocusable = sessions.isEmpty() && entries.isEmpty() && categories.size <= 1
			if (nothingFocusable) {
				item(key = "back") {
					JeButton(text = stringResource(R.string.je_hub_back), onClick = { navigationRepository.goBack() }, modifier = Modifier.focusRequester(firstFocus))
				}
			}
			if (entries.isEmpty()) {
				item(key = "feed-empty") {
					ActivityNotice(stringResource(if (currentSelection.isEmpty()) R.string.je_activity_empty else R.string.je_activity_empty_filtered))
				}
			}
			itemsIndexed(entries, key = { index, _ -> entryKeys[index] }) { index, entry ->
				val focusFirst = sessions.isEmpty() && categories.size <= 1 && index == 0
				EntryRow(feed, entry, nowMs, if (focusFirst) Modifier.focusRequester(firstFocus) else Modifier)
			}
		}

		// Wait for the first streams answer so the list doesn't shift under the focus right after it's placed
		val streamsSettled = currentStreams != Streams.Loading
		LaunchedEffect(feed, streamsSettled) {
			if (streamsSettled && !initialFocusDone) initialFocusDone = runCatching { firstFocus.requestFocus() }.isSuccess
		}
	}

	@Composable
	private fun GroupHeader(text: String, count: Int?, top: Boolean) {
		SectionHeader(text = text, count = count, modifier = Modifier.padding(top = if (top) JeSpace.sectionGap - JeSpace.rowGap else 0.dp))
	}

	/** A failed source, an empty section or the feed being off: an inline notice in the list. */
	@Composable
	private fun ActivityNotice(text: String) {
		Notice(NoticeKind.Info, text, inline = true)
	}

	private fun categoryLabel(type: ActivityType) = when (type) {
		ActivityType.WATCHED -> R.string.je_activity_filter_watched
		ActivityType.FAVORITED -> R.string.je_activity_filter_favorited
		else -> R.string.je_activity_filter_reviewed
	}

	@Composable
	private fun EntryRow(feed: Feed, entry: ActivityEntry, nowMs: Long, modifier: Modifier) {
		val item = entry.item
		val protection = item?.let { ActivityLogic.protection(feed.spoilers, it.type, it.id, it.seriesId) } ?: Protection.NONE
		val name = item?.let { ActivityLogic.displayName(it.name, it.seriesName, it.seasonNumber, it.episodeNumber, protection.hideTitles) }.orEmpty()
		// Review text and stars can give the story away; the plugin strips reviews of protected titles elsewhere
		val hideReview = protection.hideReviews
		val rating = entry.rating?.takeIf { it > 0 && !hideReview }
		val line = when {
			entry.type == ActivityType.REVIEWED && rating != null ->
				stringResource(R.string.je_activity_rated, entry.userName, name, ActivityLogic.stars(rating))
			else -> stringResource(
				when (entry.type) {
					ActivityType.WATCHED -> if (entry.completed) R.string.je_activity_completed else R.string.je_activity_watched
					ActivityType.FAVORITED -> R.string.je_activity_favorited
					ActivityType.REVIEWED -> R.string.je_activity_reviewed
					ActivityType.OTHER -> R.string.je_activity_other
				},
				entry.userName,
				name,
			)
		}
		val age = ActivityLogic.compactAge(entry.timestampMs, nowMs)
			?.let { stringResource(R.string.je_activity_ago, it) }
			?: stringResource(R.string.je_activity_just_now)
		val detail = listOfNotNull(
			age,
			if (entry.type == ActivityType.WATCHED && !entry.completed) stringResource(R.string.je_activity_progress, ActivityLogic.percent(entry.progress)) else null,
		).joinToString(" · ")
		val review = entry.content?.takeIf { it.isNotBlank() && entry.type == ActivityType.REVIEWED && !hideReview }

		ListRow(
			title = line,
			leading = RowLeading.Poster(
				item?.let { posterUrl(it.id, it.seriesId, it.hasPrimaryImage, it.seriesHasPrimaryImage) },
				R.drawable.je_ic_activity_timeline,
			),
			supporting = listOfNotNull(detail, review),
			onClick = { item?.id?.toUUIDOrNull()?.let(::open) },
			mainModifier = modifier,
			density = RowDensity.Compact,
		)
	}

	@Composable
	private fun SessionRow(feed: Feed, session: ActiveSession, modifier: Modifier) {
		val item = session.nowPlaying
		val title = item?.let {
			val protection = ActivityLogic.protection(feed.spoilers, it.type, it.id, it.seriesId)
			ActivityLogic.displayName(it.name, it.seriesName, it.parentIndexNumber, it.indexNumber, protection.hideTitles)
		}.orEmpty()
		val who = listOfNotNull(
			session.userName.takeIf { it.isNotBlank() },
			listOfNotNull(session.deviceName, session.client?.let { "($it)" }).joinToString(" ").ifBlank { null },
		).joinToString(" · ")
		val playState = session.playState
		val status = listOfNotNull(
			playMethodLabel(playState?.playMethod),
			if (playState?.isPaused == true) stringResource(R.string.je_activity_streams_paused) else null,
			ActivityLogic.sessionPercent(playState?.positionTicks, item?.runTimeTicks)?.let { "$it%" },
		).joinToString(" · ")
		val percent = ActivityLogic.sessionPercent(playState?.positionTicks, item?.runTimeTicks)

		ListRow(
			title = title,
			leading = RowLeading.Avatar(avatarUrl(session.userId, session.userHasPrimaryImage), session.userName),
			supporting = listOfNotNull(who.ifBlank { null }, status.ifBlank { null }),
			progress = percent?.let { it / 100f },
			onClick = { item?.id?.toUUIDOrNull()?.let(::open) },
			mainModifier = modifier,
			density = RowDensity.Compact,
		)
	}

	@Composable
	private fun playMethodLabel(method: String?): String? = when (method?.lowercase()) {
		"directplay" -> stringResource(R.string.je_activity_play_direct)
		"directstream" -> stringResource(R.string.je_activity_play_stream)
		"transcode" -> stringResource(R.string.je_activity_play_transcode)
		else -> method?.takeIf { it.isNotBlank() }
	}

	private fun avatarUrl(userId: String, hasImage: Boolean): String? =
		if (hasImage && userId.isNotBlank()) enhancedApi.imageUrl("/Users/$userId/Images/Primary", null, 96) else null

	/** Episodes use the show's poster (an episode's own image is often a random frame), like the web feed. */
	private fun posterUrl(itemId: String, seriesId: String?, hasPrimary: Boolean, seriesHasPrimary: Boolean): String? = when {
		seriesId != null && seriesHasPrimary -> enhancedApi.imageUrl("/Items/$seriesId/Images/Primary", null, 160)
		hasPrimary && itemId.isNotBlank() -> enhancedApi.imageUrl("/Items/$itemId/Images/Primary", null, 160)
		else -> null
	}

	/** Opens library details after checking the user can see the item (streams may show other users' libraries). */
	private fun open(id: java.util.UUID) {
		if (opening) return
		opening = true
		lifecycleScope.launch {
			try {
				if (enhancedApi.canOpen(id)) {
					navigationRepository.navigate(Destinations.itemDetails(id))
				} else {
					val context = context ?: return@launch
					Toast.makeText(context, R.string.je_activity_not_accessible, Toast.LENGTH_SHORT).show()
				}
			} finally {
				opening = false
			}
		}
	}
}
