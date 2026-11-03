package org.jellyfin.androidtv.enhanced.ui.hub

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * D-pad focus for a list whose rows can go away under the focus (Delete, Unhide, Turn off). When the focused row is
 * removed, Compose drops the focus and the main toolbar picks it up, so the user has to walk back down the page.
 * Moving the focus to a neighbour *before* the row is removed ([moveTo], then remove) keeps it in the list.
 *
 * Keys are the rows' stable keys; [requester] gives the focus requester to attach to a row's main button. Keys that
 * aren't rows (a header button, say) can be registered the same way.
 */
class ListFocus {
	private val requesters = mutableMapOf<String, FocusRequester>()
	private val pending = MutableStateFlow<String?>(null)

	fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

	/**
	 * Moves the focus to [key] now if its row is placed; otherwise (scrolled out, or about to be composed) once it is,
	 * through [Effect]. Null does nothing: the caller's empty state takes the focus.
	 */
	fun moveTo(key: String?) {
		if (key == null) return
		if (!tryFocus(key)) pending.value = key
	}

	private fun tryFocus(key: String): Boolean = runCatching { requester(key).requestFocus() }.getOrDefault(false)

	/** Place once in the list's composition; retries a pending [moveTo] for a few frames. */
	@Composable
	fun Effect() {
		val key by pending.collectAsState()
		LaunchedEffect(key) {
			val target = key ?: return@LaunchedEffect
			for (attempt in 1..RETRY_FRAMES) {
				withFrameNanos { }
				if (tryFocus(target)) break
			}
			pending.compareAndSet(target, null)
		}
	}

	companion object {
		private const val RETRY_FRAMES = 10

		/**
		 * The row to focus once [removed] leave [order]: the first remaining row after the last removed one, else the
		 * nearest remaining row before it, else null (the list is now empty). If none of [removed] is in [order], the
		 * first row.
		 */
		fun neighbour(order: List<String>, removed: Collection<String>): String? {
			val removedSet = removed.toSet()
			val last = order.indexOfLast { it in removedSet }
			if (last < 0) return order.firstOrNull()
			return order.drop(last + 1).firstOrNull { it !in removedSet }
				?: order.take(last).lastOrNull { it !in removedSet }
		}
	}
}
