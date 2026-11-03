package org.jellyfin.androidtv.enhanced.activity

import org.jellyfin.androidtv.enhanced.ui.secondaryTextColor
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.jellyfin.androidtv.enhanced.calendar.PageChip
import org.jellyfin.androidtv.enhanced.calendar.PageImage
import org.jellyfin.androidtv.enhanced.calendar.PageListRow
import org.jellyfin.androidtv.enhanced.calendar.PageNotice
import org.jellyfin.androidtv.enhanced.calendar.attempt
import org.jellyfin.androidtv.enhanced.calendar.canOpen
import org.jellyfin.androidtv.enhanced.calendar.serverMessage
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardState
import org.jellyfin.androidtv.enhanced.ui.hub.EnhancedScreen
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.enhanced.ui.hub.LoadStateContent
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.design.Tokens
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
	private val spoilerGuardRepository by inject<SpoilerGuardRepository>()
	private val enhancedApi by inject<EnhancedApi>()
	private val navigationRepository by inject<NavigationRepository>()

	private companion object {
		val STREAMS_INTERVAL = 30.seconds
	}

	private class Feed(
		/** Null when the feed is turned off (the page then only shows active streams). */
		val entries: List<ActivityEntry>?,
		val config: ActivityConfig,
		val spoilerGuard: Boolean,
		/** Null with [spoilerGuard] on: the guard list failed to load, so names and reviews are withheld. */
		val spoilerState: SpoilerGuardState?,
		val stripReviews: Boolean,
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
					val spoilerCall = async { if (state.spoilerGuardMayApply) attempt { spoilerGuardRepository.getState() }.getOrNull() else null }
					Feed(
						entries = feedCall.await(),
						config = configCall.await(),
						spoilerGuard = state.spoilerGuardMayApply,
						spoilerState = spoilerCall.await(),
						stripReviews = state.config.spoilerStripReviews,
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

		EnhancedScreen(title = stringResource(R.string.je_activity_title)) {
			LoadStateContent(state) { feed -> FeedContent(feed) }
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
			contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 4.dp, bottom = 48.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			when (val streamState = currentStreams) {
				Streams.Hidden, Streams.Loading -> Unit
				is Streams.Failed -> item(key = "streams-failed") {
					PageNotice(stringResource(R.string.je_activity_streams_failed, streamState.message ?: stringResource(R.string.je_error_generic)))
				}
				is Streams.Ready -> {
					item(key = "streams-header") { SectionHeader(stringResource(R.string.je_activity_streams_title, sessions.size)) }
					if (sessions.isEmpty()) item(key = "streams-none") { PageNotice(stringResource(R.string.je_activity_streams_none)) }
					itemsIndexed(sessions, key = { index, _ -> sessionKeys[index] }) { index, session ->
						SessionRow(feed, session, if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
					}
				}
			}

			if (entries == null || entryKeys == null) {
				if (currentStreams !is Streams.Ready) item(key = "feed-off") { PageNotice(stringResource(R.string.je_activity_feed_off)) }
				return@LazyColumn
			}

			item(key = "feed-header") { SectionHeader(stringResource(R.string.je_activity_title)) }
			if (categories.size > 1) {
				item(key = "chips") {
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						for (category in categories) {
							val chipModifier = if (sessions.isEmpty() && category == categories.first()) Modifier.focusRequester(firstFocus) else Modifier
							PageChip(
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
					Button(onClick = { navigationRepository.goBack() }, modifier = Modifier.focusRequester(firstFocus)) {
						Text(stringResource(R.string.je_hub_back))
					}
				}
			}
			if (entries.isEmpty()) {
				item(key = "feed-empty") {
					PageNotice(stringResource(if (currentSelection.isEmpty()) R.string.je_activity_empty else R.string.je_activity_empty_filtered))
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
	private fun SectionHeader(text: String) {
		Text(text = text, fontSize = 20.sp, color = Tokens.Color.colorWhite, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
	}

	private fun categoryLabel(type: ActivityType) = when (type) {
		ActivityType.WATCHED -> R.string.je_activity_filter_watched
		ActivityType.FAVORITED -> R.string.je_activity_filter_favorited
		else -> R.string.je_activity_filter_reviewed
	}

	@Composable
	private fun EntryRow(feed: Feed, entry: ActivityEntry, nowMs: Long, modifier: Modifier) {
		val item = entry.item
		val guarded = ActivityLogic.isGuarded(item?.id, item?.seriesId, feed.spoilerGuard, feed.spoilerState)
		val name = item?.let { ActivityLogic.displayName(it.name, it.seriesName, it.seasonNumber, it.episodeNumber, guarded) }.orEmpty()
		// Review text and stars can give the story away; the plugin strips reviews of protected titles elsewhere
		val hideReview = guarded && feed.stripReviews
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
		).joinToString("  ·  ")

		PageListRow(onClick = { item?.id?.toUUIDOrNull()?.let(::open) }, modifier = modifier) {
			Avatar(entry.userId, entry.userName, entry.userHasPrimaryImage)
			Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(text = line, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
				Text(text = detail, fontSize = 13.sp, color = secondaryTextColor(), maxLines = 1)
				entry.content?.takeIf { it.isNotBlank() && entry.type == ActivityType.REVIEWED && !hideReview }?.let {
					Text(text = it, fontSize = 14.sp, color = secondaryTextColor(), maxLines = 2, overflow = TextOverflow.Ellipsis)
				}
			}
			if (item != null) PageImage(url = posterUrl(item.id, item.seriesId, item.hasPrimaryImage, item.seriesHasPrimaryImage), modifier = Modifier.size(40.dp, 60.dp))
		}
	}

	@Composable
	private fun SessionRow(feed: Feed, session: ActiveSession, modifier: Modifier) {
		val item = session.nowPlaying
		val guarded = item != null && ActivityLogic.isGuarded(item.id, item.seriesId, feed.spoilerGuard, feed.spoilerState)
		val title = item?.let { ActivityLogic.displayName(it.name, it.seriesName, it.parentIndexNumber, it.indexNumber, guarded) }.orEmpty()
		val who = listOfNotNull(
			session.userName.takeIf { it.isNotBlank() },
			listOfNotNull(session.deviceName, session.client?.let { "($it)" }).joinToString(" ").ifBlank { null },
		).joinToString(" · ")
		val playState = session.playState
		val status = listOfNotNull(
			playMethodLabel(playState?.playMethod),
			if (playState?.isPaused == true) stringResource(R.string.je_activity_streams_paused) else null,
			ActivityLogic.sessionPercent(playState?.positionTicks, item?.runTimeTicks)?.let { "$it%" },
		).joinToString("  ·  ")

		PageListRow(onClick = { item?.id?.toUUIDOrNull()?.let(::open) }, modifier = modifier) {
			Avatar(session.userId, session.userName, session.userHasPrimaryImage)
			Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(text = title, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Text(text = who, fontSize = 14.sp, color = secondaryTextColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
				if (status.isNotEmpty()) Text(text = status, fontSize = 13.sp, color = secondaryTextColor(), maxLines = 1)
			}
			if (item != null) PageImage(url = posterUrl(item.id, item.seriesId, true, item.seriesId != null), modifier = Modifier.size(40.dp, 60.dp))
		}
	}

	@Composable
	private fun playMethodLabel(method: String?): String? = when (method?.lowercase()) {
		"directplay" -> stringResource(R.string.je_activity_play_direct)
		"directstream" -> stringResource(R.string.je_activity_play_stream)
		"transcode" -> stringResource(R.string.je_activity_play_transcode)
		else -> method?.takeIf { it.isNotBlank() }
	}

	@Composable
	private fun Avatar(userId: String, userName: String, hasImage: Boolean) {
		val url = if (hasImage && userId.isNotBlank()) enhancedApi.imageUrl("/Users/$userId/Images/Primary", null, 96) else null
		if (url != null) {
			PageImage(url = url, modifier = Modifier.size(44.dp).clip(CircleShape))
		} else {
			Box(
				contentAlignment = Alignment.Center,
				modifier = Modifier
					.size(44.dp)
					.clip(CircleShape)
					.background(Tokens.Color.colorGrey700),
			) {
				Text(text = userName.take(1).uppercase(), fontSize = 18.sp)
			}
		}
	}

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
