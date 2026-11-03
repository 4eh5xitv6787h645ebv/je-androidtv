package org.jellyfin.androidtv.enhanced.management

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/** The plugin's own message for a failed call (e.g. its 503 "data was corrupt" notice), or a generic one. */
fun errorMessage(context: Context, error: Throwable): String =
	(error as? EnhancedApi.EnhancedApiException)?.serverMessage?.takeIf { it.isNotBlank() }
		?: context.getString(R.string.je_error_generic)

private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun formatDate(instant: Instant?): String? = instant?.let { dateFormat.format(it.atZone(ZoneId.systemDefault())) }

/**
 * Poster URLs for library items, through the server so its artwork rules (Spoiler Guard blur) apply: the URL carries
 * the item's current image tag. An item without a Primary image falls back to its Thumb, then its first Backdrop
 * ([artworkPath]). Items without any of them, or all of them if the lookup fails, are left out, so callers show a
 * placeholder instead of possibly stale artwork.
 */
suspend fun libraryPosterUrls(enhancedApi: EnhancedApi, ids: Collection<UUID>): Map<UUID, String> {
	val result = mutableMapOf<UUID, String>()
	for (chunk in ids.distinct().chunked(100)) {
		try {
			val items = enhancedApi.get(
				"/Items",
				mapOf(
					"ids" to chunk.joinToString(",") { SpoilerGuardRepository.key(it) },
					"enableImageTypes" to "Primary,Thumb,Backdrop",
					"enableTotalRecordCount" to false,
				),
			).jsonObject["Items"]?.jsonArray.orEmpty()
			for (item in items) {
				val obj = item as? JsonObject ?: continue
				val id = SpoilerGuardRepository.parseGuid((obj["Id"] as? JsonPrimitive)?.contentOrNull) ?: continue
				val (path, tag) = artworkPath(obj) ?: continue
				result[id] = enhancedApi.imageUrl("/Items/${SpoilerGuardRepository.key(id)}/Images/$path", tag, 200)
			}
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Failed to load poster tags")
		}
	}
	return result
}

/**
 * The image to show for an item DTO and its tag: `Primary`, else `Thumb`, else the first backdrop (`Backdrop/0`), or
 * null when it has none. Some series only have a Thumb (or the server's Spoiler Guard serves only that).
 */
fun artworkPath(item: JsonObject): Pair<String, String>? {
	val tags = item["ImageTags"] as? JsonObject
	fun tag(type: String) = (tags?.get(type) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
	tag("Primary")?.let { return "Primary" to it }
	tag("Thumb")?.let { return "Thumb" to it }
	val backdrop = ((item["BackdropImageTags"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
	return backdrop?.let { "Backdrop/0" to it }
}

/** The [org.jellyfin.androidtv.enhanced.ui.hub.ListFocus] key of a management row's action button. */
fun actionFocusKey(rowKey: String) = "$rowKey#action"
