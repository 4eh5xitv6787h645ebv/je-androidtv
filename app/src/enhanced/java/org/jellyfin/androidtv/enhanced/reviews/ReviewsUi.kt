package org.jellyfin.androidtv.enhanced.reviews

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.metadata.MetadataDialogPanel
import org.jellyfin.androidtv.enhanced.metadata.displayMessage
import org.jellyfin.androidtv.enhanced.metadata.formatDate
import org.jellyfin.androidtv.enhanced.ui.design.JeButton
import org.jellyfin.androidtv.enhanced.ui.design.JeColors
import org.jellyfin.androidtv.enhanced.ui.design.JeShapes
import org.jellyfin.androidtv.enhanced.ui.design.JeSpace
import org.jellyfin.androidtv.enhanced.ui.design.JeType
import org.jellyfin.androidtv.enhanced.ui.design.LoadingRow
import org.jellyfin.androidtv.enhanced.ui.design.Notice
import org.jellyfin.androidtv.enhanced.ui.design.NoticeKind
import org.jellyfin.androidtv.enhanced.ui.design.jeRingContainer
import org.jellyfin.androidtv.enhanced.ui.design.rememberFocusedState
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import timber.log.Timber

private val starColor = JeColors.ratingStar

/** Close to AniList's brand blue, as the web client tints AniList cards. */
private val anilistColor = JeColors.accentOnDark

/** Five stars for [rating] out of five, with a half star where needed. */
@Composable
fun StarRow(rating: Double, size: Dp = 16.dp, modifier: Modifier = Modifier) {
	val stars = starsOf(rating)
	Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
		repeat(stars.full) { Icon(painterResource(R.drawable.je_ic_reviews_star), null, tint = starColor, modifier = Modifier.size(size)) }
		if (stars.half) Icon(painterResource(R.drawable.je_ic_reviews_star_half), null, tint = starColor, modifier = Modifier.size(size))
		repeat(stars.empty) { Icon(painterResource(R.drawable.je_ic_reviews_star_outline), null, tint = starColor, modifier = Modifier.size(size)) }
	}
}

/** The date part of an ISO-8601 instant, formatted for the user's locale. */
internal fun reviewDate(value: String?): String? = formatDate(value)

/**
 * A review card of the reviews row: `surface` card with the ring focus (it holds coloured stars, which inversion
 * would wash out), fixed width so the row reads as cards.
 */
@Composable
private fun ReviewCardSurface(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
	val focus = rememberFocusedState()
	Column(
		modifier = modifier
			.width(340.dp)
			.heightIn(min = 120.dp)
			.clickable(interactionSource = focus.interactionSource, indication = null, onClick = onClick)
			.jeRingContainer(focus.focused)
			.padding(JeSpace.rowPadding),
		verticalArrangement = Arrangement.spacedBy(6.dp),
		content = content,
	)
}

@Composable
private fun ReviewCard(entry: ReviewEntry, expanded: Boolean, onClick: () -> Unit) = ReviewCardSurface(onClick) {
	Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = when {
				entry.isOwn -> stringResource(R.string.je_reviews_you)
				entry.author.isBlank() -> stringResource(R.string.je_reviews_anonymous)
				else -> entry.author
			},
			style = JeType.cardTitle,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f, fill = false),
		)
		ReviewRating(entry, 14.dp)
	}
	val meta = listOfNotNull(reviewSourceName(entry.source), reviewDate(entry.date)).joinToString(" · ")
	if (meta.isNotEmpty()) Text(text = meta, style = JeType.caption, color = JeColors.textTertiary, maxLines = 1)
	// AniList reviews lead with the reviewer's headline; the body is for the reader
	if (entry.summary != null) {
		Text(
			text = entry.summary,
			style = JeType.body,
			color = JeColors.onSurface,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
	}
	if (entry.content.isNotBlank()) {
		Text(
			text = entry.content,
			style = JeType.body,
			maxLines = when {
				entry.summary != null -> if (expanded) 6 else 1
				expanded -> 8
				else -> 3
			},
			overflow = TextOverflow.Ellipsis,
		)
	}
}

/** Stars for JE reviews, `8/10` for TMDB ones, `Score 85` for AniList ones. */
@Composable
internal fun ReviewRating(entry: ReviewEntry, size: Dp) {
	val rating = entry.rating?.takeIf { it > 0 } ?: return
	when (entry.source) {
		ReviewEntry.Source.JELLYFIN -> StarRow(rating, size)
		ReviewEntry.Source.TMDB -> Text(stringResource(R.string.je_reviews_tmdb_rating, formatRating(rating)), style = JeType.caption, color = starColor)
		ReviewEntry.Source.ANILIST -> Text(stringResource(R.string.je_reviews_anilist_score, rating.toInt()), style = JeType.caption, color = anilistColor)
	}
}

/** The source label of external reviews; JE reviews have none. */
@Composable
internal fun reviewSourceName(source: ReviewEntry.Source): String? = when (source) {
	ReviewEntry.Source.JELLYFIN -> null
	ReviewEntry.Source.TMDB -> stringResource(R.string.je_reviews_source_tmdb)
	ReviewEntry.Source.ANILIST -> stringResource(R.string.je_reviews_source_anilist)
}

/**
 * The details-screen reviews row: a summary card (average stars, opens the rating dialog when [onWrite] is set),
 * then one card per review that opens the reader. [expanded] shows longer excerpts (`ReviewsExpandedByDefault`).
 */
@Composable
fun ReviewsRowContent(
	data: ReviewsData,
	expanded: Boolean,
	onWrite: (() -> Unit)?,
	onOpen: (index: Int) -> Unit,
	onRetry: () -> Unit,
) {
	LazyRow(
		horizontalArrangement = Arrangement.spacedBy(JeSpace.gridGap),
		contentPadding = PaddingValues(vertical = 4.dp),
		modifier = Modifier.fillMaxWidth(),
	) {
		val summary = data.summary
		if (summary != null || onWrite != null) item(key = "summary") {
			ReviewCardSurface(onClick = { onWrite?.invoke() }) {
				val average = summary?.average
				if (average != null) {
					StarRow(average, 20.dp)
					Text(stringResource(R.string.je_reviews_average, formatRating(Math.round(average * 10) / 10.0)), style = JeType.cardTitle)
					Text(pluralStringResource(R.plurals.je_reviews_ratings_count, summary.count, summary.count), style = JeType.caption)
				} else {
					Text(stringResource(R.string.je_reviews_none_yet), style = JeType.body, color = JeColors.textSecondary)
				}
				if (onWrite != null) {
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
						Icon(painterResource(R.drawable.je_ic_reviews_star_outline), contentDescription = null, tint = JeColors.accentOnDark, modifier = Modifier.size(18.dp))
						Text(stringResource(if (data.own != null) R.string.je_reviews_edit else R.string.je_reviews_write), style = JeType.body, color = JeColors.accentOnDark)
					}
				}
			}
		}
		itemsIndexed(data.entries, key = { index, entry -> "${entry.source}:$index" }) { index, entry ->
			ReviewCard(entry, expanded) { onOpen(index) }
		}
		val error = data.firstError
		if (error != null) item(key = "retry") {
			ReviewCardSurface(onClick = onRetry) {
				Notice(kind = NoticeKind.Error, title = stringResource(R.string.je_reviews_load_failed), inline = true)
				error.message?.let { Text(it, style = JeType.caption, maxLines = 3, overflow = TextOverflow.Ellipsis) }
				Text(stringResource(R.string.je_retry), style = JeType.body, color = JeColors.accentOnDark)
			}
		}
	}
}

/**
 * Rate and review a title: a half-star picker driven by D-pad left/right (0 = no rating), optional text, Save,
 * Delete (when there is a review) and Cancel. The user's current review is loaded fresh when the dialog opens.
 */
@Composable
fun RateDialog(
	title: String,
	target: ReviewTarget,
	repository: ReviewsRepository,
	onChanged: () -> Unit,
	dismiss: () -> Unit,
) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var existing by remember { mutableStateOf<LoadState<JeReview?>>(LoadState.Loading) }
	var rating by remember { mutableDoubleStateOf(0.0) }
	var text by remember { mutableStateOf("") }
	var busy by remember { mutableStateOf(false) }
	val starsFocus = remember { FocusRequester() }

	fun load() {
		existing = LoadState.Loading
		scope.launch {
			existing = try {
				val me = repository.currentUserKey
				val own = me?.let { key -> repository.userReviews(target.mediaType, target.key).firstOrNull { sameUser(it.userId, key) } }
				rating = own?.rating ?: 0.0
				text = own?.content.orEmpty()
				LoadState.Ready(own)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to load the user's review")
				LoadState.Error(err.displayMessage(), ::load)
			}
		}
	}

	fun run(action: suspend () -> Unit, done: Int) {
		if (busy) return
		busy = true
		scope.launch {
			try {
				action()
				Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
				onChanged()
				dismiss()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to update the review")
				val message = err.displayMessage() ?: context.getString(R.string.je_error_generic)
				Toast.makeText(context, context.getString(R.string.je_reviews_failed, message), Toast.LENGTH_LONG).show()
			} finally {
				busy = false
			}
		}
	}

	MetadataDialogPanel(title = stringResource(R.string.je_reviews_dialog_title), subtitle = title) {
		when (val state = existing) {
			LoadState.Loading -> LoadingRow()
			is LoadState.Error -> {
				Notice(kind = NoticeKind.Error, title = state.message ?: stringResource(R.string.je_error_generic), inline = true)
				JeButton(text = stringResource(R.string.je_retry), onClick = state.retry, modifier = Modifier.focusRequester(starsFocus))
			}

			is LoadState.Ready -> {
				StarPicker(rating, { rating = it }, Modifier.focusRequester(starsFocus))
				Text(stringResource(R.string.je_reviews_stars_hint), style = JeType.caption)

				// Busy rather than disabled while saving, so the focus stays on the pressed button
				Row(horizontalArrangement = Arrangement.spacedBy(JeSpace.actionGap), modifier = Modifier.padding(top = 8.dp)) {
					JeButton(
						text = stringResource(R.string.je_reviews_save),
						icon = R.drawable.je_ic_reviews_star,
						busy = busy,
						onClick = {
							if (rating <= 0 && text.isBlank()) {
								Toast.makeText(context, R.string.je_reviews_empty, Toast.LENGTH_SHORT).show()
							} else {
								run({ repository.saveReview(target.mediaType, target.key, text, rating.takeIf { it > 0 }) }, R.string.je_reviews_saved)
							}
						},
					)
					if (state.value != null) {
						JeButton(
							text = stringResource(R.string.je_reviews_delete),
							busy = busy,
							onClick = { run({ repository.deleteReview(target.mediaType, target.key) }, R.string.je_reviews_deleted) },
						)
					}
					JeButton(text = stringResource(R.string.je_reviews_cancel), onClick = dismiss)
				}

				// Below the buttons: focusing the field opens the keyboard, which a rating-only visit shouldn't need
				Text(stringResource(R.string.je_reviews_text_label), style = JeType.body, modifier = Modifier.padding(top = 8.dp))
				ReviewTextField(text, { text = it.take(ReviewsRepository.MAX_CONTENT_LENGTH) })
				Text(
					stringResource(R.string.je_reviews_text_count, text.length, ReviewsRepository.MAX_CONTENT_LENGTH),
					style = JeType.caption,
					color = JeColors.textTertiary,
				)
			}
		}
	}

	LaunchedEffect(Unit) { load() }
	LaunchedEffect(existing is LoadState.Loading) {
		if (existing !is LoadState.Loading) runCatching { starsFocus.requestFocus() }
	}
}

/** Stars that change in half steps with D-pad left/right while focused. */
@Composable
private fun StarPicker(rating: Double, onChange: (Double) -> Unit, modifier: Modifier = Modifier) {
	val focus = rememberFocusedState()
	Row(
		modifier = modifier
			.onPreviewKeyEvent { event ->
				if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
				when (event.key.nativeKeyCode) {
					KeyEvent.KEYCODE_DPAD_LEFT -> {
						onChange(stepRating(rating, up = false))
						true
					}

					KeyEvent.KEYCODE_DPAD_RIGHT -> {
						onChange(stepRating(rating, up = true))
						true
					}

					else -> false
				}
			}
			.focusable(interactionSource = focus.interactionSource)
			// Ring, not inversion: the stars keep their colour; the outline at rest says "this is a control"
			.jeRingContainer(focus.focused)
			.then(if (focus.focused) Modifier else Modifier.border(1.dp, JeColors.outline, JeShapes.container))
			.padding(horizontal = 16.dp, vertical = 12.dp),
		horizontalArrangement = Arrangement.spacedBy(16.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		StarRow(rating, 32.dp)
		Text(
			text = if (rating > 0) stringResource(R.string.je_reviews_rating_value, formatRating(rating)) else stringResource(R.string.je_reviews_no_rating),
			style = JeType.sectionHeader,
		)
	}
}

@Composable
private fun ReviewTextField(value: String, onValueChange: (String) -> Unit) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val colors = if (focused) JellyfinTheme.colorScheme.inputFocused else JellyfinTheme.colorScheme.input
	BasicTextField(
		value = value,
		onValueChange = onValueChange,
		interactionSource = interactionSource,
		textStyle = LocalTextStyle.current.merge(JeType.bodyLarge).copy(color = JeColors.onSurface),
		cursorBrush = SolidColor(colors),
		keyboardOptions = KeyboardOptions.Default.copy(
			capitalization = KeyboardCapitalization.Sentences,
			imeAction = ImeAction.Done,
			// Compose can't open the keyboard on click, only on focus (same as upstream's search field)
			showKeyboardOnFocus = true,
		),
		modifier = Modifier
			.fillMaxWidth()
			.height(140.dp)
			.border(2.dp, colors, JeShapes.container)
			.padding(12.dp),
	)
}
