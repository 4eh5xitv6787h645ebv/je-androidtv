package org.jellyfin.androidtv.enhanced.issues

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.metadata.showDialogHost
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.ui.design.JeButton
import org.jellyfin.androidtv.enhanced.ui.design.JeChoiceDialog
import org.jellyfin.androidtv.enhanced.ui.design.JeColors
import org.jellyfin.androidtv.enhanced.ui.design.JeDialog
import org.jellyfin.androidtv.enhanced.ui.design.JeShapes
import org.jellyfin.androidtv.enhanced.ui.design.JeType
import org.jellyfin.androidtv.enhanced.ui.design.rememberFocusedState
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrLinkTexts
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import timber.log.Timber

/** Where the season and episode choices of a show report come from. */
interface IssueEpisodeSource {
	/** Regular season numbers, ascending. */
	suspend fun seasons(): List<Int>

	/** Episode numbers of [season] with their titles where known, ascending. */
	suspend fun episodes(season: Int): List<Pair<Int, String?>>
}

/**
 * Seasons and episodes of a library series as the server returns them for this user. Titles are the server's own,
 * so Spoiler Guard's title replacement applies.
 */
class LibraryEpisodeSource(private val api: EnhancedApi, private val seriesId: String) : IssueEpisodeSource {
	override suspend fun seasons(): List<Int> = items("/Shows/$seriesId/Seasons", emptyMap())
		.mapNotNull { (it["IndexNumber"] as? JsonPrimitive)?.intOrNull }
		.filter { it > 0 }
		.distinct()
		.sorted()

	override suspend fun episodes(season: Int): List<Pair<Int, String?>> = items("/Shows/$seriesId/Episodes", mapOf("season" to season))
		.mapNotNull { item ->
			val number = (item["IndexNumber"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
			number to (item["Name"] as? JsonPrimitive)?.contentOrNull
		}
		.distinctBy { it.first }
		.sortedBy { it.first }

	private suspend fun items(path: String, query: Map<String, Any?>) =
		((api.get(path, query) as? JsonObject)?.get("Items") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
}

/** Seasons and episode numbers from Seerr's details, for shows that aren't in the library. No titles. */
class SeerrEpisodeSource(private val episodeCounts: Map<Int, Int>) : IssueEpisodeSource {
	override suspend fun seasons() = episodeCounts.filter { (season, count) -> season > 0 && count > 0 }.keys.sorted()
	override suspend fun episodes(season: Int) = (1..(episodeCounts[season] ?: 0)).map { it to null }
}

/**
 * The "Report a problem" dialogs, a TV take on the web's issue reporter (`js/jellyseerr/issue-reporter.js`): issue type,
 * then for shows the season/episode (prefilled from where the user came from), then an optional message.
 */
class IssueReportFlow(
	private val fragment: Fragment,
	private val repository: SeerrIssueRepository,
	private val title: String,
	private val mediaType: String,
	private val tmdbId: Int,
	/** Seerr's internal media id when already known; otherwise resolved first. */
	private val knownMediaId: Int?,
	/** For shows: where to pick seasons/episodes from. */
	private val episodeSource: IssueEpisodeSource?,
	/** The season/episode the user came from, offered first. */
	private val prefill: SeerrIssueScope = SeerrIssueScope.WHOLE,
	private val onReported: () -> Unit = {},
) {
	private companion object {
		const val MAX_MESSAGE_LENGTH = 1000
	}

	private val isTv get() = mediaType == SeerrRepository.MEDIA_TV

	// The media id lookup and the report go out as the same user
	private val api by lazy { repository.pinnedApi() }

	/**
	 * Start reporting. Resolves Seerr's media id first, so a title Seerr doesn't know is explained before the user
	 * fills anything in.
	 */
	fun start() {
		if (knownMediaId != null) {
			pickType(knownMediaId)
			return
		}
		fragment.lifecycleScope.launch {
			val mediaId = try {
				repository.resolveMediaId(mediaType, tmdbId, api)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to resolve the Seerr media id for an issue report")
				val context = fragment.context ?: return@launch
				val text = (err as? EnhancedApi.EnhancedApiException)?.let { errorMessage(context, it) }
					?: context.getString(R.string.je_error_generic)
				Toast.makeText(context, text, Toast.LENGTH_LONG).show()
				return@launch
			}
			val context = fragment.context ?: return@launch
			if (mediaId == null) Toast.makeText(context, R.string.je_issues_unknown_title, Toast.LENGTH_LONG).show()
			else pickType(mediaId)
		}
	}

	/** One step of the flow: pick one of [options]; [onPick] gets its index. */
	private fun choose(title: String, options: List<String>, onPick: (Int) -> Unit) {
		showDialogHost(fragment) { visible, dismiss ->
			JeChoiceDialog(
				visible = visible,
				title = title,
				options = options,
				selected = emptySet(),
				onConfirm = { picked -> picked.firstOrNull()?.let(onPick) },
				onDismissRequest = dismiss,
			)
		}
	}

	private fun pickType(mediaId: Int) {
		val context = fragment.context ?: return
		val types = SeerrIssueType.entries
		choose(context.getString(R.string.je_issues_report_title, title), types.map { context.getString(it.label) }) { which ->
			if (isTv && episodeSource != null) pickScope(mediaId, types[which]) else enterMessage(mediaId, types[which], SeerrIssueScope.WHOLE)
		}
	}

	private fun pickScope(mediaId: Int, type: SeerrIssueType) {
		val context = fragment.context ?: return
		val options = mutableListOf<Pair<String, () -> Unit>>()
		if (prefill.season > 0 && prefill.episode > 0) {
			options += context.getString(R.string.je_issues_scope_this_episode, prefill.season, prefill.episode) to
				{ enterMessage(mediaId, type, prefill) }
		}
		if (prefill.season > 0) {
			options += context.getString(R.string.je_issues_scope_whole_season, prefill.season) to
				{ enterMessage(mediaId, type, SeerrIssueScope(prefill.season)) }
		}
		options += context.getString(R.string.je_issues_scope_whole_show) to { enterMessage(mediaId, type, SeerrIssueScope.WHOLE) }
		options += context.getString(R.string.je_issues_scope_other) to { pickSeason(mediaId, type) }

		choose(context.getString(R.string.je_issues_scope_title), options.map { it.first }) { which -> options[which].second() }
	}

	private fun pickSeason(mediaId: Int, type: SeerrIssueType) {
		val source = episodeSource ?: return
		fragment.lifecycleScope.launch {
			val seasons = load { source.seasons() } ?: return@launch
			val context = fragment.context ?: return@launch
			if (seasons.isEmpty()) {
				enterMessage(mediaId, type, SeerrIssueScope.WHOLE)
				return@launch
			}
			choose(context.getString(R.string.je_issues_scope_title), seasons.map { context.getString(R.string.je_issues_season, it) }) { which ->
				pickEpisode(mediaId, type, seasons[which])
			}
		}
	}

	private fun pickEpisode(mediaId: Int, type: SeerrIssueType, season: Int) {
		val source = episodeSource ?: return
		fragment.lifecycleScope.launch {
			val episodes = load { source.episodes(season) } ?: return@launch
			val context = fragment.context ?: return@launch
			val labels = listOf(context.getString(R.string.je_issues_scope_whole_season, season)) + episodes.map { (number, name) ->
				if (name.isNullOrBlank()) context.getString(R.string.je_issues_episode, number)
				else context.getString(R.string.je_issues_episode_named, number, name)
			}
			choose(context.getString(R.string.je_issues_season, season), labels) { which ->
				val episode = if (which == 0) 0 else episodes[which - 1].first
				enterMessage(mediaId, type, SeerrIssueScope(season, episode))
			}
		}
	}

	private fun enterMessage(mediaId: Int, type: SeerrIssueType, scope: SeerrIssueScope) {
		val context = fragment.context ?: return
		val scopeLabel = when {
			!isTv || scope.season == 0 -> null
			scope.episode == 0 -> context.getString(R.string.je_issues_season, scope.season)
			else -> context.getString(R.string.je_issues_season_episode, scope.season, scope.episode)
		}
		val dialogTitle = listOfNotNull(context.getString(type.label), scopeLabel).joinToString(" · ")
		showDialogHost(fragment) { visible, dismiss ->
			var message by remember { mutableStateOf("") }
			val submitFocus = remember { FocusRequester() }
			// Submit and IME Done both stay actionable while the dialog fades out: send once
			val sent = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
			fun send() {
				if (!sent.compareAndSet(false, true)) return
				dismiss()
				submit(mediaId, type, scope, message)
			}
			JeDialog(
				visible = visible,
				onDismissRequest = dismiss,
				title = dialogTitle,
				body = stringResource(R.string.je_issues_message_label),
				actions = {
					JeButton(text = stringResource(R.string.je_design_cancel), onClick = dismiss)
					JeButton(
						text = stringResource(R.string.je_issues_submit),
						icon = R.drawable.je_ic_issue_report,
						onClick = ::send,
						modifier = Modifier.focusRequester(submitFocus),
					)
				},
			) {
				IssueMessageField(
					value = message,
					onValueChange = { message = it.take(MAX_MESSAGE_LENGTH) },
					hint = stringResource(R.string.je_issues_message_hint),
					onDone = ::send,
				)
			}
			// The message is optional: start on Submit, so the keyboard only opens when the user moves up to the field
			LaunchedEffect(Unit) {
				withFrameNanos { }
				withFrameNanos { }
				runCatching { submitFocus.requestFocus() }
			}
		}
	}

	private fun submit(mediaId: Int, type: SeerrIssueType, scope: SeerrIssueScope, message: String) {
		val appContext = fragment.context?.applicationContext ?: return
		fragment.lifecycleScope.launch {
			val text = try {
				repository.report(api, mediaId, type, scope, message)
				onReported()
				appContext.getString(R.string.je_issues_reported)
			} catch (err: CancellationException) {
				throw err
			} catch (err: EnhancedApi.EnhancedApiException) {
				Timber.w(err, "Issue report failed")
				errorMessage(appContext, err)
			} catch (err: Exception) {
				Timber.w(err, "Issue report failed")
				appContext.getString(R.string.je_issues_report_failed, err.message.orEmpty())
			}
			Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
		}
	}

	/** Runs a lookup for the season/episode pickers; null (after telling the user) when it failed. */
	private suspend fun <T> load(block: suspend () -> T): T? = try {
		block()
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.w(err, "Failed to load seasons or episodes for the issue report")
		fragment.context?.let { Toast.makeText(it, R.string.je_error_generic, Toast.LENGTH_SHORT).show() }
		null
	}
}

/** A user-facing message for a failed Seerr issue call, preferring the plugin's own message. */
internal fun errorMessage(context: android.content.Context, err: EnhancedApi.EnhancedApiException): String = when {
	SeerrLinkTexts.error(context, err, false) != null -> SeerrLinkTexts.error(context, err, false)!!
	!err.serverMessage.isNullOrBlank() -> err.serverMessage
	err.code == "no_issue_permission" || err.status == 403 -> context.getString(R.string.je_issues_no_permission)
	err.status == 503 || err.code == "unreachable" -> context.getString(R.string.je_issues_seerr_unreachable)
	else -> context.getString(R.string.je_issues_report_failed, "HTTP ${err.status}")
}

/**
 * The optional message of an issue report: a few wrapping lines, the keyboard's action key submits (like the web's
 * reporter, a newline isn't needed). Opens the keyboard when focused, as upstream's search field does.
 */
@Composable
private fun IssueMessageField(value: String, onValueChange: (String) -> Unit, hint: String, onDone: () -> Unit) {
	val focus = rememberFocusedState()
	val colors = if (focus.focused) JellyfinTheme.colorScheme.inputFocused else JellyfinTheme.colorScheme.input
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = 96.dp)
			.border(2.dp, colors, JeShapes.container)
			.padding(12.dp),
	) {
		if (value.isEmpty()) Text(text = hint, style = JeType.body, color = JeColors.textTertiary)
		BasicTextField(
			value = value,
			onValueChange = onValueChange,
			interactionSource = focus.interactionSource,
			textStyle = LocalTextStyle.current.merge(JeType.body).copy(color = JeColors.onSurface),
			cursorBrush = SolidColor(colors),
			maxLines = 4,
			keyboardOptions = KeyboardOptions.Default.copy(
				capitalization = KeyboardCapitalization.Sentences,
				imeAction = ImeAction.Done,
				showKeyboardOnFocus = true,
			),
			keyboardActions = KeyboardActions(onDone = { onDone() }),
			modifier = Modifier.fillMaxWidth(),
		)
	}
}
