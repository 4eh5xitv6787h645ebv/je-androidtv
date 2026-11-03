package org.jellyfin.androidtv.enhanced.ui.details

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.jellyfin.androidtv.ui.base.JellyfinTheme

/**
 * A details-screen row whose content is a composable: ratings strips, metadata lines, review previews and so on.
 * Add it to the rows adapter like any other row; [EnhancedComposeRowPresenter] renders it. The composable gets the
 * row so it can read [EnhancedComposeRow.tag] for per-row state.
 */
class EnhancedComposeRow(
	title: String?,
	/** Arbitrary data for the content (e.g. the item id) so one composable can serve many rows. */
	val tag: Any? = null,
	val content: @Composable (row: EnhancedComposeRow) -> Unit,
) : Row(title?.let { HeaderItem(it) })

/** Renders [EnhancedComposeRow]s. Registered on the details screen's presenter selector by the fork hook. */
class EnhancedComposeRowPresenter : RowPresenter() {
	init {
		headerPresenter = null
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
		(vh.view as ComposeView).setContent { JellyfinTheme { row.content(row) } }
	}

	override fun onUnbindRowViewHolder(vh: ViewHolder) {
		(vh.view as ComposeView).setContent {}
		super.onUnbindRowViewHolder(vh)
	}
}
