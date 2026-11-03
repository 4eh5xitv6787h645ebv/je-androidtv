package org.jellyfin.androidtv.enhanced.bookmarks

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.calendar.attempt
import org.jellyfin.androidtv.enhanced.calendar.serverMessage
import org.jellyfin.androidtv.enhanced.ui.details.DetailsAction
import org.jellyfin.androidtv.enhanced.ui.details.DetailsContext
import org.jellyfin.androidtv.enhanced.ui.details.DetailsContributor
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/**
 * "Add bookmark" on movie and episode details: saves the item's current resume position, with an optional label.
 * Shown only when there is a position to save. Capturing a moment from inside the player is not part of this.
 */
class BookmarkDetailsContributor(
	private val bookmarksRepository: BookmarksRepository,
	private val enhancedApi: EnhancedApi,
) : DetailsContributor {
	override suspend fun actions(context: DetailsContext): List<DetailsAction> {
		val item = context.item
		if (!context.state.bookmarks) return emptyList()
		if (item.type != BaseItemKind.MOVIE && item.type != BaseItemKind.EPISODE) return emptyList()
		val ticks = item.userData?.playbackPositionTicks ?: 0L
		if (ticks <= 0L) return emptyList()

		return listOf(
			DetailsAction(
				icon = R.drawable.je_ic_bookmarks_add,
				label = context.fragment.getString(R.string.je_bookmarks_add),
				// Rarely needed: among the first to fold into "⋯"
				priority = 80,
				onClick = { askLabel(context, item, BookmarkLogic.seconds(ticks)) },
			)
		)
	}

	private fun askLabel(context: DetailsContext, item: BaseItemDto, seconds: Double) {
		val ctx = context.fragment.context ?: return
		val input = EditText(ctx).apply {
			hint = ctx.getString(R.string.je_bookmarks_label_hint)
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
			isSingleLine = true
		}
		val padding = (24 * ctx.resources.displayMetrics.density).toInt()
		val frame = FrameLayout(ctx).apply {
			setPadding(padding, padding / 2, padding, 0)
			addView(input)
		}
		AlertDialog.Builder(ctx)
			.setTitle(ctx.getString(R.string.je_bookmarks_add_title, BookmarkLogic.formatTimestamp(seconds)))
			.setView(frame)
			.setPositiveButton(R.string.je_bookmarks_save) { _, _ -> save(context, item, seconds, input.text?.toString()?.trim()) }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun save(context: DetailsContext, item: BaseItemDto, seconds: Double, label: String?) {
		val fragment = context.fragment
		fragment.lifecycleScope.launch {
			val isEpisode = item.type == BaseItemKind.EPISODE
			// Like the web, episodes carry the show's provider ids (with season/episode) for later re-matching
			val providerIds = if (isEpisode) {
				item.seriesId?.let { seriesId -> attempt { seriesProviderIds(seriesId.toString()) }.getOrNull() }
			} else item.providerIds
			val result = attempt {
				bookmarksRepository.add(
					BookmarksRepository.NewBookmark(
						itemId = item.id,
						name = item.name,
						mediaType = if (isEpisode) "tv" else "movie",
						seconds = seconds,
						label = label?.takeIf { it.isNotEmpty() },
						tmdbId = providerIds?.provider("Tmdb"),
						tvdbId = providerIds?.provider("Tvdb"),
						seasonNumber = if (isEpisode) item.parentIndexNumber else null,
						episodeNumber = if (isEpisode) item.indexNumber else null,
					)
				)
			}
			val ctx = fragment.context ?: return@launch
			result.onSuccess {
				Toast.makeText(ctx, ctx.getString(R.string.je_bookmarks_added, BookmarkLogic.formatTimestamp(seconds)), Toast.LENGTH_SHORT).show()
			}.onFailure { err ->
				Timber.w(err, "Failed to add bookmark")
				val reason = err.serverMessage ?: ctx.getString(R.string.je_error_generic)
				Toast.makeText(ctx, ctx.getString(R.string.je_bookmarks_add_failed, reason), Toast.LENGTH_LONG).show()
			}
		}
	}

	private suspend fun seriesProviderIds(seriesId: String): Map<String, String?> {
		val series = enhancedApi.get("/Items/$seriesId", mapOf("userId" to enhancedApi.currentUserId())).jsonObject
		val ids = series["ProviderIds"] as? JsonObject ?: return emptyMap()
		return ids.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull }
	}

	private fun Map<String, String?>.provider(name: String) =
		entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
}
