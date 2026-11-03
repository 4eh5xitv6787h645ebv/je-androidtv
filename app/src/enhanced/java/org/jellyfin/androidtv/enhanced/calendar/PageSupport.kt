package org.jellyfin.androidtv.enhanced.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.design.Tokens
import java.util.UUID

// Small pieces shared by the Calendar, Bookmarks, Activity and About pages.

/** Like [runCatching], but lets coroutine cancellation through instead of turning it into a failure. */
internal inline fun <T> attempt(block: () -> T): Result<T> = try {
	Result.success(block())
} catch (err: CancellationException) {
	throw err
} catch (err: Exception) {
	Result.failure(err)
}

/** The plugin's own message for a failed call, when it sent one (null lets the page show its generic text). */
internal val Throwable.serverMessage: String?
	get() = (this as? EnhancedApi.EnhancedApiException)?.serverMessage?.takeIf { it.isNotBlank() }

/**
 * Whether the signed-in user can open library item [id]: loads it as the user, since ids from plugin feeds (calendar,
 * active streams) aren't necessarily filtered by library access.
 */
internal suspend fun EnhancedApi.canOpen(id: UUID): Boolean {
	val userId = attempt { currentUserId() }.getOrNull() ?: return false
	return attempt { get("/Items/$id", mapOf("userId" to userId)) }.isSuccess
}

/** A toggle button for a filter; [selected] uses the theme's "active" colours. */
@Composable
internal fun PageChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
	Button(
		onClick = onClick,
		modifier = modifier,
		colors = if (selected) ButtonDefaults.colors(
			containerColor = JellyfinTheme.colorScheme.buttonActive,
			contentColor = JellyfinTheme.colorScheme.onButtonActive,
		) else ButtonDefaults.colors(),
	) {
		Text(label)
	}
}

/** A focusable full-width list row; long-press OK runs [onLongClick]. */
@Composable
internal fun PageListRow(
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	onLongClick: (() -> Unit)? = null,
	content: @Composable RowScope.() -> Unit,
) {
	Button(
		onClick = onClick,
		onLongClick = onLongClick,
		shape = JellyfinTheme.shapes.medium,
		contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
		modifier = modifier.fillMaxWidth(),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(16.dp),
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.fillMaxWidth(),
			content = content,
		)
	}
}

/** An image from a URL (server or external) on a neutral background, so missing artwork keeps the layout. */
@Composable
internal fun PageImage(url: String?, modifier: Modifier = Modifier) {
	Box(
		modifier = modifier
			.clip(JellyfinTheme.shapes.extraSmall)
			.background(Tokens.Color.colorGrey800),
	) {
		if (url != null) {
			coil3.compose.AsyncImage(
				model = url,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier.matchParentSize(),
			)
		}
	}
}

/** A one-line inline notice (a failed source, a partial result). */
@Composable
internal fun PageNotice(text: String, modifier: Modifier = Modifier) {
	Text(
		text = text,
		color = Tokens.Color.colorGrey300,
		fontSize = 14.sp,
		modifier = modifier.padding(vertical = 2.dp),
	)
}
