package org.jellyfin.androidtv.enhanced.ui.design

import android.widget.ImageView
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton

/**
 * Whether something on the current [PageScaffold] below the toolbar has the focus; null outside one. [EmptyState]
 * reads it so its action only takes the focus when the page's own controls (tabs, filters) don't have it.
 */
internal val LocalPageFocused = staticCompositionLocalOf<State<Boolean>?> { null }

/**
 * The page shell of the Enhanced list pages (spec §6.1): main toolbar (Enhanced active), a fixed [PageHeader] that
 * never scrolls away, then [content]. [subtitle] is the count / range / progress line ("12 requests",
 * "Nov 3 – Nov 16, 2026"); [actions] sit right-aligned in the header row (≤ 2 buttons or a few chips).
 * [backdropUrl] draws a dimmed [Backdrop] behind everything. [showToolbar] false is for full-screen readers.
 *
 * Put tabs ([JeTabRow]) and filter rows ([ChipRow]) at the top of [content]; scroll only the list below them.
 */
@Composable
fun PageScaffold(
	title: String,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	actions: (@Composable RowScope.() -> Unit)? = null,
	backdropUrl: String? = null,
	backdropBlurHash: String? = null,
	showToolbar: Boolean = true,
	content: @Composable () -> Unit,
) {
	val pageFocused = remember { mutableStateOf(false) }
	JellyfinTheme {
		Box(modifier = modifier.fillMaxSize()) {
			if (backdropUrl != null || backdropBlurHash != null) {
				Backdrop(url = backdropUrl, blurHash = backdropBlurHash, dim = 0.5f, modifier = Modifier.matchParentSize())
			}
			Column(modifier = Modifier.fillMaxSize()) {
				if (showToolbar) MainToolbar(MainToolbarActiveButton.Enhanced)
				else Spacer(Modifier.height(JeSpace.pageBottom))
				// Everything under the toolbar: whether the page itself (header actions included) holds the focus
				Column(modifier = Modifier.fillMaxSize().onFocusChanged { pageFocused.value = it.hasFocus }) {
					PageHeader(title = title, subtitle = subtitle, actions = actions)
					CompositionLocalProvider(LocalPageFocused provides pageFocused) {
						Box(modifier = Modifier.fillMaxSize()) { content() }
					}
				}
			}
		}
	}
}

/**
 * The header row of a page: [title] with the [subtitle] baseline-aligned after it, [actions] on the right of the
 * same row. Use it alone above leanback grids that can't live inside a [PageScaffold].
 */
@Composable
fun PageHeader(
	title: String,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	actions: (@Composable RowScope.() -> Unit)? = null,
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, bottom = JeSpace.headerBottom),
		horizontalArrangement = Arrangement.spacedBy(JeSpace.actionGap),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			Text(
				text = title,
				style = JeType.pageTitle,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.alignByBaseline().weight(1f, fill = false),
			)
			if (subtitle != null) {
				Text(text = subtitle, style = JeType.pageSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alignByBaseline())
			}
		}
		if (actions != null) {
			Row(
				modifier = Modifier.focusRestorer().focusGroup(),
				horizontalArrangement = Arrangement.spacedBy(JeSpace.chipGap),
				verticalAlignment = Alignment.CenterVertically,
				content = actions,
			)
		}
	}
}

/**
 * A full-bleed backdrop (spec §4): centre-cropped, with the two background-coloured scrims drawn once per size
 * (left → right for the text column, top → bottom for the rows below). Text on it belongs in the left
 * [HeroScrimEnd] of the screen (at least 75 % background over the art there) or on [JeColors.surfaceOverArt]. Request the image at ≤ 1280 px wide. [blurHash] shows while it loads (and is all that
 * shows when [url] is null). [dim] darkens the whole image further, for pages that put lists on top.
 */
@Composable
fun Backdrop(
	url: String?,
	modifier: Modifier = Modifier,
	blurHash: String? = null,
	dim: Float = 0f,
) {
	Box(modifier = modifier.fillMaxSize()) {
		AsyncImage(
			url = url,
			blurHash = blurHash,
			aspectRatio = 16f / 9f,
			scaleType = ImageView.ScaleType.CENTER_CROP,
			modifier = Modifier.fillMaxSize(),
		)
		Box(
			modifier = Modifier
				.fillMaxSize()
				.drawWithCache {
					val bg = JeColors.background
					val horizontal = Brush.horizontalGradient(
						0f to bg.copy(alpha = 0.96f),
						HeroScrimEnd to bg.copy(alpha = 0.75f),
						0.82f to bg.copy(alpha = 0.15f),
						1f to bg.copy(alpha = 0f),
					)
					val vertical = Brush.verticalGradient(
						0f to bg.copy(alpha = 0f),
						0.38f to bg.copy(alpha = 0f),
						0.70f to bg.copy(alpha = 0.85f),
						1f to bg,
					)
					onDrawBehind {
						if (dim > 0f) drawRect(bg.copy(alpha = dim))
						drawRect(horizontal)
						drawRect(vertical)
					}
				},
		)
	}
}

/**
 * The backdrop-led layout of Seerr details and Catch Up (spec §6.1, §7). A fixed hero ([header], then the
 * [actions] row) over a [Backdrop]; [below] is a lazy column of rows (seasons, cast, recommendations) that scrolls
 * under the hero, never the hero itself, so the title stays on screen. While the focus is in [below], [header] is
 * called with `collapsed = true`: drop the long parts (overview, info chips) and keep title and meta; the action row
 * stays so D-pad up always has somewhere to go.
 *
 * Without [below] (Catch Up) the hero sits at the bottom of the screen. [topEnd] holds controls at the top right
 * under the toolbar (Catch Up's count and filter chips), on a [JeColors.surfaceOverArt] panel so they stay readable
 * on bright art; [aside] is drawn over the whole screen for decorations such as the
 * up-next stack (align it yourself). Items in [below] set their own horizontal padding, so rows can scroll edge to
 * edge.
 */
@Composable
fun HeroScaffold(
	backdropUrl: String?,
	header: @Composable ColumnScope.(collapsed: Boolean) -> Unit,
	actions: @Composable RowScope.() -> Unit,
	modifier: Modifier = Modifier,
	backdropBlurHash: String? = null,
	showToolbar: Boolean = true,
	topEnd: (@Composable RowScope.() -> Unit)? = null,
	aside: (@Composable BoxScope.() -> Unit)? = null,
	listState: LazyListState = rememberLazyListState(),
	below: (LazyListScope.() -> Unit)? = null,
) {
	var collapsed by remember { mutableStateOf(false) }
	JellyfinTheme {
		Box(modifier = modifier.fillMaxSize().background(JeColors.background)) {
			Backdrop(url = backdropUrl, blurHash = backdropBlurHash, modifier = Modifier.matchParentSize())
			Column(modifier = Modifier.fillMaxSize()) {
				if (showToolbar) MainToolbar(MainToolbarActiveButton.Enhanced)
				if (topEnd != null) {
					Row(
						modifier = Modifier
							.align(Alignment.End)
							.padding(end = JeSpace.pageHorizontal)
							// Nothing else darkens the top right, where the art is often brightest
							.background(JeColors.surfaceOverArt, JeShapes.container)
							.padding(horizontal = 12.dp, vertical = 6.dp)
							.focusRestorer()
							.focusGroup(),
						horizontalArrangement = Arrangement.spacedBy(JeSpace.chipGap),
						verticalAlignment = Alignment.CenterVertically,
						content = topEnd,
					)
				}
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.then(if (below == null) Modifier.weight(1f) else Modifier)
						.padding(
							start = JeSpace.pageHorizontal,
							end = JeSpace.pageHorizontal,
							top = if (showToolbar) JeSpace.headerBottom else JeSpace.heroTop,
							bottom = if (below == null) HeroBottom else 0.dp,
						)
						.animateContentSize(tween(200)),
					verticalArrangement = if (below == null) Arrangement.Bottom else Arrangement.Top,
				) {
					header(collapsed)
					Spacer(Modifier.height(JeSpace.sectionGap))
					Row(
						modifier = Modifier.focusRestorer().focusGroup(),
						horizontalArrangement = Arrangement.spacedBy(JeSpace.labeledActionGap),
						verticalAlignment = Alignment.Top,
						content = actions,
					)
				}
				if (below != null) {
					LazyColumn(
						state = listState,
						modifier = Modifier
							.weight(1f)
							.fillMaxWidth()
							.onFocusChanged { collapsed = it.hasFocus },
						contentPadding = PaddingValues(top = JeSpace.sectionGap, bottom = JeSpace.pageBottom),
						verticalArrangement = Arrangement.spacedBy(JeSpace.sectionGap),
						content = below,
					)
				}
			}
			aside?.invoke(this)
		}
	}
}

private val HeroBottom = 64.dp

/**
 * The text block of a [HeroScaffold] header: [overline] ("CATCH UP · SHOW · 1 OF 47"), a [status] line (a
 * [StatusBadge] and the request line), the [title] (48 sp, 40 sp once it wraps, max 2 lines), the [meta] row
 * ([MetaLine], [AgeRatingTag], [MetaRating]…), the [overview] (max 4 lines) and [extra] facts (info chips, the
 * streaming row). [collapsed] hides the overview and [extra].
 */
@Composable
fun HeroHeader(
	title: String,
	modifier: Modifier = Modifier,
	overline: String? = null,
	status: (@Composable RowScope.() -> Unit)? = null,
	meta: (@Composable RowScope.() -> Unit)? = null,
	overview: String? = null,
	collapsed: Boolean = false,
	extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
	Column(modifier = modifier.fillMaxWidth(HeroTextWidth), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		if (overline != null) Overline(overline)
		if (status != null) {
			ProvideTextStyle(JeType.meta) {
				Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = status)
			}
		}
		HeroTitle(title)
		if (meta != null) {
			ProvideTextStyle(JeType.meta) {
				Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically, content = meta)
			}
		}
		if (!collapsed && overview != null) {
			Text(text = overview, style = JeType.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
		}
		if (!collapsed && extra != null) extra()
	}
}

/**
 * Text width of a hero, as a fraction of the width between the page margins. With the margins it must end inside
 * [HeroScrimEnd] so long titles and overviews keep 4.5:1 over bright art (checked in `JeContrastTest`).
 */
internal const val HeroTextWidth = 0.52f

/** Where the backdrop's horizontal scrim stops being at least 75 % background, as a fraction of the screen width. */
internal const val HeroScrimEnd = 0.56f

/** The hero title: 48 sp on one line, 40 sp once it wraps, never more than two lines. */
@Composable
fun HeroTitle(title: String, modifier: Modifier = Modifier) {
	var wrapped by remember(title) { mutableStateOf(false) }
	Text(
		text = title,
		style = if (wrapped) JeType.heroTitleWrapped else JeType.heroTitle,
		maxLines = 2,
		overflow = TextOverflow.Ellipsis,
		onTextLayout = { if (!wrapped && it.lineCount > 1) wrapped = true },
		modifier = modifier,
	)
}

/** A meta line, "2025 · 1 season · Crime · Drama", skipping blank parts. */
@Composable
fun MetaLine(items: List<String?>, modifier: Modifier = Modifier) {
	val text = items.filterNot { it.isNullOrBlank() }.joinToString(" · ")
	if (text.isEmpty()) return
	Text(text = text, style = JeType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** The outlined age rating in a meta row ("TV-MA"). */
@Composable
fun AgeRatingTag(text: String, modifier: Modifier = Modifier) {
	Text(
		text = text,
		style = JeType.meta,
		color = JeColors.onSurface,
		maxLines = 1,
		modifier = modifier
			.border(1.dp, JeColors.onSurface.copy(alpha = 0.7f), JeShapes.badge)
			.padding(horizontal = 6.dp),
	)
}

/** A rating inside a meta row, without a container: dot / icon and value ("● 75%", "★ 8.3"). */
@Composable
fun MetaRating(text: String, leading: InfoLeading, modifier: Modifier = Modifier) {
	Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
		when (leading) {
			is InfoLeading.Dot -> Box(Modifier.size(12.dp).background(leading.color, CircleShape))
			is InfoLeading.Icon -> Icon(
				painterResource(leading.res),
				contentDescription = null,
				tint = if (leading.tint == Color.Unspecified) JeColors.onSurface else leading.tint,
				modifier = Modifier.size(18.dp),
			)

			is InfoLeading.Image -> AsyncImage(url = leading.url, scaleType = ImageView.ScaleType.FIT_CENTER, modifier = Modifier.size(20.dp))
		}
		Text(text = text, style = JeType.meta, color = JeColors.onSurface, maxLines = 1)
	}
}
