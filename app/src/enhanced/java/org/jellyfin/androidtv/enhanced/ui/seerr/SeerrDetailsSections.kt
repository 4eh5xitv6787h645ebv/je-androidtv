package org.jellyfin.androidtv.enhanced.ui.seerr

import org.jellyfin.androidtv.enhanced.ui.secondaryTextColor
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.design.Tokens

/**
 * A person in the cast row of the Seerr details screen. For titles not in the library it comes from Seerr's credits
 * ([tmdbPersonId]); for library titles from the server's own, Spoiler Guard filtered, people ([jellyfinPersonId]).
 */
data class SeerrDetailsCastEntry(
	val name: String,
	val role: String?,
	val imageUrl: String?,
	val tmdbPersonId: Int?,
	val jellyfinPersonId: String?,
)

/** A season card: its label, episode count and effective status (see `SeerrSeasonAvailability`). */
data class SeerrDetailsSeasonEntry(
	val seasonNumber: Int,
	val label: String,
	val episodeCount: Int,
	val status: Int?,
	val requestable: Boolean,
)

@Composable
fun SeerrDetailsSectionTitle(text: String) {
	Text(text = text, color = Tokens.Color.colorGrey100, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
}

/** Secondary metadata ("Rated TV-MA · Original title: …") on one line. */
@Composable
fun SeerrDetailsMetaStrip(items: List<String>) {
	if (items.isEmpty()) return
	Text(
		text = items.joinToString("   ·   "),
		color = secondaryTextColor(),
		fontSize = 14.sp,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
	)
}

/** "Requested by …" lines. */
@Composable
fun SeerrDetailsRequesters(lines: List<String>) {
	if (lines.isEmpty()) return
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		for (line in lines) {
			Text(text = line, color = secondaryTextColor(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
	}
}

/** A focusable card with a visible focus ring, for the rows below. */
@Composable
private fun SeerrDetailsFocusCard(
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	content: @Composable ColumnScope.(focused: Boolean) -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val shape = JellyfinTheme.shapes.medium
	Column(
		modifier = modifier
			.border(2.dp, if (focused) Tokens.Color.colorWhite else Color.Transparent, shape)
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.padding(4.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		content(focused)
	}
}

@Composable
fun SeerrDetailsCastRow(entries: List<SeerrDetailsCastEntry>, onClick: (SeerrDetailsCastEntry) -> Unit) {
	if (entries.isEmpty()) return
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		SeerrDetailsSectionTitle(stringResource(R.string.je_seerrdetails_cast))
		LazyRow(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			contentPadding = PaddingValues(end = 48.dp),
		) {
			items(entries, key = { "${it.tmdbPersonId}-${it.jellyfinPersonId}-${it.name}" }) { entry ->
				SeerrDetailsFocusCard(onClick = { onClick(entry) }, modifier = Modifier.width(108.dp)) { focused ->
					Box(
						modifier = Modifier
							.size(100.dp, 150.dp)
							.background(JellyfinTheme.colorScheme.surface, JellyfinTheme.shapes.medium),
					) {
						if (entry.imageUrl != null) {
							AsyncImage(
								url = entry.imageUrl,
								aspectRatio = 2f / 3f,
								scaleType = ImageView.ScaleType.CENTER_CROP,
								modifier = Modifier.fillMaxSize(),
							)
						} else {
							Image(
								painter = painterResource(R.drawable.ic_user),
								contentDescription = null,
								modifier = Modifier
									.fillMaxSize(0.4f)
									.align(Alignment.Center),
							)
						}
					}
					Text(
						text = entry.name,
						color = if (focused) Tokens.Color.colorWhite else Tokens.Color.colorGrey100,
						fontSize = 12.sp,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					entry.role?.takeIf { it.isNotBlank() }?.let { role ->
						Text(text = role, color = secondaryTextColor(), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
					}
				}
			}
		}
	}
}

@Composable
fun SeerrDetailsSeasonsRow(entries: List<SeerrDetailsSeasonEntry>, onClick: (SeerrDetailsSeasonEntry) -> Unit) {
	if (entries.isEmpty()) return
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		SeerrDetailsSectionTitle(stringResource(R.string.je_seerrdetails_seasons))
		LazyRow(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			contentPadding = PaddingValues(end = 48.dp),
		) {
			items(entries, key = { it.seasonNumber }) { entry ->
				SeerrDetailsFocusCard(
					onClick = { onClick(entry) },
					modifier = Modifier
						.width(160.dp)
						.background(JellyfinTheme.colorScheme.surface, JellyfinTheme.shapes.medium),
				) { focused ->
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.padding(6.dp),
						verticalArrangement = Arrangement.spacedBy(4.dp),
					) {
						Text(
							text = entry.label,
							color = if (focused) Tokens.Color.colorWhite else Tokens.Color.colorGrey100,
							fontSize = 14.sp,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						if (entry.episodeCount > 0) {
							Text(
								text = pluralStringResource(R.plurals.je_seerrdetails_episodes, entry.episodeCount, entry.episodeCount),
								color = secondaryTextColor(),
								fontSize = 12.sp,
							)
						}
						if (seerrStatusLabel(entry.status) != null) {
							SeerrStatusBadge(entry.status)
						} else {
							Text(text = stringResource(R.string.je_status_not_requested), color = secondaryTextColor(), fontSize = 10.sp)
						}
					}
				}
			}
		}
	}
}
