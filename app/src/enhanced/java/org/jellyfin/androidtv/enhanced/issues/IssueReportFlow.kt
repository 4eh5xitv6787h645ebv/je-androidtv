package org.jellyfin.androidtv.enhanced.issues

import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
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
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrLinkTexts
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
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

	private fun pickType(mediaId: Int) {
		val context = fragment.context ?: return
		val types = SeerrIssueType.entries
		AlertDialog.Builder(context)
			.setTitle(context.getString(R.string.je_issues_report_title, title))
			.setItems(types.map { context.getString(it.label) }.toTypedArray()) { _, which ->
				if (isTv && episodeSource != null) pickScope(mediaId, types[which]) else enterMessage(mediaId, types[which], SeerrIssueScope.WHOLE)
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
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

		AlertDialog.Builder(context)
			.setTitle(R.string.je_issues_scope_title)
			.setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
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
			AlertDialog.Builder(context)
				.setTitle(R.string.je_issues_scope_title)
				.setItems(seasons.map { context.getString(R.string.je_issues_season, it) }.toTypedArray()) { _, which ->
					pickEpisode(mediaId, type, seasons[which])
				}
				.setNegativeButton(android.R.string.cancel, null)
				.show()
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
			AlertDialog.Builder(context)
				.setTitle(context.getString(R.string.je_issues_season, season))
				.setItems(labels.toTypedArray()) { _, which ->
					val episode = if (which == 0) 0 else episodes[which - 1].first
					enterMessage(mediaId, type, SeerrIssueScope(season, episode))
				}
				.setNegativeButton(android.R.string.cancel, null)
				.show()
		}
	}

	private fun enterMessage(mediaId: Int, type: SeerrIssueType, scope: SeerrIssueScope) {
		val context = fragment.context ?: return
		val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, context.resources.displayMetrics).toInt()
		// Single-line input that wraps: the TV keyboard's action key then submits instead of adding a newline
		val input = EditText(context).apply {
			hint = context.getString(R.string.je_issues_message_hint)
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
			imeOptions = EditorInfo.IME_ACTION_DONE
			setHorizontallyScrolling(false)
			maxLines = 4
			filters = arrayOf(InputFilter.LengthFilter(MAX_MESSAGE_LENGTH))
		}
		val container = FrameLayout(context).apply {
			setPadding(padding, padding / 2, padding, 0)
			addView(input)
		}
		val scopeLabel = when {
			!isTv || scope.season == 0 -> null
			scope.episode == 0 -> context.getString(R.string.je_issues_season, scope.season)
			else -> context.getString(R.string.je_issues_season_episode, scope.season, scope.episode)
		}
		val dialog = AlertDialog.Builder(context)
			.setTitle(listOfNotNull(context.getString(type.label), scopeLabel).joinToString(" · "))
			.setMessage(R.string.je_issues_message_label)
			.setView(container)
			.setPositiveButton(R.string.je_issues_submit) { _, _ -> submit(mediaId, type, scope, input.text.toString()) }
			.setNegativeButton(android.R.string.cancel, null)
			.create()
		input.setOnEditorActionListener { _, actionId, _ ->
			if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
			dialog.dismiss()
			submit(mediaId, type, scope, input.text.toString())
			true
		}
		dialog.show()
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
