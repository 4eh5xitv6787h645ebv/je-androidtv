package org.jellyfin.androidtv.enhanced.playback

import java.util.UUID
import kotlin.math.abs

/**
 * When the pause screen shows, as in the web's `pausescreen.js`: once playback has been paused with no remote input
 * for the delay. It hides on resume, on any input, on a seek and when the item changes, and stays hidden while
 * something else is open on top of the player ([Input.blocked]: track pickers, dialogs, the chapter panel). Back
 * dismisses it for the rest of that pause; other input only restarts the idle delay, like the web's interaction reset.
 *
 * Pure state fed with the player state and the clock, so it can be unit tested.
 */
class PauseScreenTimer {
	/** A snapshot of the player. */
	data class Input(
		/** The user's pause screen setting (and anything else that rules it out, like live TV). */
		val enabled: Boolean,
		val delayMs: Long,
		val itemId: UUID?,
		val paused: Boolean,
		val positionMs: Long,
		/** Something else is on top of the player or has focus. */
		val blocked: Boolean,
	)

	/** Whether the pause screen should be showing. */
	var visible = false
		private set

	private var itemId: UUID? = null
	private var paused = false
	private var idleSinceMs = 0L
	private var pausedPositionMs = 0L
	private var dismissed = false

	/** Feed the current player state; returns [visible]. */
	fun update(input: Input, nowMs: Long): Boolean {
		if (input.itemId != itemId) {
			itemId = input.itemId
			paused = false
			visible = false
		}

		if (!input.paused || input.itemId == null) {
			paused = false
			visible = false
			return false
		}

		if (!paused) {
			// A new pause: the delay starts now and an earlier dismissal no longer applies
			paused = true
			dismissed = false
			idleSinceMs = nowMs
			pausedPositionMs = input.positionMs
		}

		// A seek while paused (skip button, a remote-control client) is input too
		if (abs(input.positionMs - pausedPositionMs) > SEEK_TOLERANCE_MS) {
			pausedPositionMs = input.positionMs
			onInput(nowMs, dismiss = false)
		}

		if (!input.enabled || input.blocked) {
			// Count idle time from when the other UI closes, not from the pause
			visible = false
			idleSinceMs = nowMs
			return false
		}

		if (!visible && !dismissed && nowMs - idleSinceMs >= input.delayMs) visible = true
		return visible
	}

	/**
	 * Remote input while paused: hides the screen and restarts the idle delay. [dismiss] (Back) keeps it hidden until
	 * the next pause. Returns whether the screen was showing.
	 */
	fun onInput(nowMs: Long, dismiss: Boolean): Boolean {
		val wasVisible = visible
		visible = false
		idleSinceMs = nowMs
		if (dismiss && wasVisible) dismissed = true
		return wasVisible
	}

	private companion object {
		/** Position drift while paused that isn't a seek (position reports settle after pausing). */
		const val SEEK_TOLERANCE_MS = 2_000L
	}
}
