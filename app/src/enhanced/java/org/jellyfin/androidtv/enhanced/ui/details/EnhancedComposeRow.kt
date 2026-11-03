package org.jellyfin.androidtv.enhanced.ui.details

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.core.view.isVisible
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.presentation.CustomRowHeaderPresenter

/** Where a contributed row goes on the details screen. */
enum class DetailsRowPlacement {
	/** Directly under the item's overview, above upstream's rows (cast, seasons, "More like this"…). */
	TOP,

	/** After upstream's rows. */
	BELOW,
}

/**
 * A details-screen row whose content is a composable: ratings strips, metadata lines, review previews and so on.
 * Add it to the rows adapter like any other row; [EnhancedComposeRowPresenter] renders it with the same header and
 * left inset as upstream's rows. The composable gets the row so it can read [EnhancedComposeRow.tag] for per-row state.
 */
class EnhancedComposeRow(
	title: String?,
	/** Arbitrary data for the content (e.g. the item id) so one composable can serve many rows. */
	val tag: Any? = null,
	val placement: DetailsRowPlacement = DetailsRowPlacement.BELOW,
	val content: @Composable (row: EnhancedComposeRow) -> Unit,
) : Row(title?.let { HeaderItem(it) })

/** Renders [EnhancedComposeRow]s. Registered on the details screen's presenter selector by the fork hook. */
class EnhancedComposeRowPresenter : RowPresenter() {
	init {
		// Upstream's header presenter, so the title looks and lines up like the "Cast & crew" header
		headerPresenter = CustomRowHeaderPresenter()
		selectEffectEnabled = false
	}

	override fun createRowViewHolder(parent: ViewGroup): ViewHolder {
		val view = ComposeView(parent.context).apply {
			setParentCompositionContext(parent.findViewTreeCompositionContext())
			setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
			setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
			layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		}
		return ViewHolder(view)
	}

	override fun onBindRowViewHolder(vh: ViewHolder, item: Any) {
		super.onBindRowViewHolder(vh, item)
		val row = item as? EnhancedComposeRow ?: return
		vh.headerViewHolder?.view?.isVisible = row.headerItem != null
		val view = vh.view as ComposeView
		val (start, end) = browsePadding(view.context)
		// Keyed by the row so a recycled view never carries another row's remembered state
		view.setContent {
			key(row) {
				JellyfinTheme {
					// The same overscan-safe inset as upstream's card rows (leanback's browse padding)
					val density = LocalDensity.current
					Box(modifier = Modifier.padding(start = with(density) { start.toDp() }, end = with(density) { end.toDp() })) {
						row.content(row)
					}
				}
			}
		}
	}

	override fun onUnbindRowViewHolder(vh: ViewHolder) {
		(vh.view as ComposeView).disposeComposition()
		super.onUnbindRowViewHolder(vh)
	}

	/** The theme's `browsePaddingStart`/`End` in pixels, what leanback's row grids use as their padding. */
	private fun browsePadding(context: Context): Pair<Int, Int> {
		val fallback = context.resources.getDimensionPixelSize(androidx.leanback.R.dimen.lb_browse_padding_start)
		val attrs = context.obtainStyledAttributes(intArrayOf(androidx.leanback.R.attr.browsePaddingStart, androidx.leanback.R.attr.browsePaddingEnd))
		return try {
			attrs.getDimensionPixelSize(0, fallback) to attrs.getDimensionPixelSize(1, fallback)
		} finally {
			attrs.recycle()
		}
	}
}
