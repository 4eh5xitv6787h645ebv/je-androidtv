package org.jellyfin.androidtv.enhanced.management

import org.jellyfin.androidtv.enhanced.ui.secondaryTextColor
import android.content.Context
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.design.Tokens
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

/** A small 2:3 poster, or [fallbackIcon] when there is no (safe) artwork. */
@Composable
fun ManagementPoster(url: String?, @DrawableRes fallbackIcon: Int, modifier: Modifier = Modifier) {
	Box(
		modifier = modifier
			.size(48.dp, 72.dp)
			.background(Tokens.Color.colorGrey800, JellyfinTheme.shapes.extraSmall),
	) {
		if (url != null) {
			AsyncImage(url = url, aspectRatio = 2f / 3f, scaleType = ImageView.ScaleType.CENTER_CROP, modifier = Modifier.fillMaxSize())
		} else {
			Image(
				painter = painterResource(fallbackIcon),
				contentDescription = null,
				modifier = Modifier
					.fillMaxSize(0.5f)
					.align(Alignment.Center),
			)
		}
	}
}

/**
 * One row of a management list: the main button (poster, title, details) opens the title when [onOpen] is set, the
 * trailing button runs the row's action ([actionLabel]). Both are focusable so the D-pad can move between them.
 */
@Composable
fun ManagementRow(
	title: String,
	subtitle: String?,
	posterUrl: String?,
	@DrawableRes fallbackIcon: Int,
	actionLabel: String,
	onAction: () -> Unit,
	modifier: Modifier = Modifier,
	/** Applied to the main (open) button, e.g. a focus requester. */
	mainModifier: Modifier = Modifier,
	/** Applied to the trailing action button. */
	actionModifier: Modifier = Modifier,
	onOpen: (() -> Unit)? = null,
	busy: Boolean = false,
	chips: List<String> = emptyList(),
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 48.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Button(
			onClick = { onOpen?.invoke() },
			shape = JellyfinTheme.shapes.medium,
			contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
			colors = ButtonDefaults.colors(),
			modifier = mainModifier.weight(1f),
		) {
			Row(
				horizontalArrangement = Arrangement.spacedBy(16.dp),
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.fillMaxWidth(),
			) {
				ManagementPoster(posterUrl, fallbackIcon)
				Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
					Text(text = title, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
					if (subtitle != null) Text(text = subtitle, fontSize = 13.sp, color = secondaryTextColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
				}
				for (chip in chips) Chip(chip)
			}
		}
		// Not enabled=false while busy: disabling the focused button would throw focus elsewhere
		Button(onClick = { if (!busy) onAction() }, modifier = actionModifier) {
			Text(if (busy) "…" else actionLabel)
		}
	}
}

@Composable
fun Chip(text: String, color: Color = Tokens.Color.colorGrey700) {
	Box(
		modifier = Modifier
			.background(color, JellyfinTheme.shapes.extraSmall)
			.padding(horizontal = 8.dp, vertical = 2.dp),
	) {
		Text(text = text, fontSize = 12.sp, color = Color.White, maxLines = 1)
	}
}

/** A section heading inside a management list. */
@Composable
fun ManagementSectionHeader(text: String) {
	Text(
		text = text,
		fontSize = 20.sp,
		color = Tokens.Color.colorGrey100,
		modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 4.dp),
	)
}

/** The [org.jellyfin.androidtv.enhanced.ui.hub.ListFocus] key of a [ManagementRow]'s action button. */
fun actionFocusKey(rowKey: String) = "$rowKey#action"
