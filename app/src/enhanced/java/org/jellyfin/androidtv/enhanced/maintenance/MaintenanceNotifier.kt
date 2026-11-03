package org.jellyfin.androidtv.enhanced.maintenance

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.ui.browsing.MainActivity
import timber.log.Timber
import java.lang.ref.WeakReference
import java.time.Instant
import java.util.UUID

/**
 * The one-time maintenance dialog at app start: once the plugin state is loaded for a signed-in user, the live
 * maintenance state is read and, when a window applies to this user and its dialog wasn't dismissed before
 * ([MaintenanceDismissStore]), a dialog with the message and the end time is shown on the main screen.
 *
 * It never gets in the way of playback: it waits while a player screen is showing and closes (without counting as
 * seen) if one opens. It needs no upstream hook: it follows the app's activities and their fragments itself, so it
 * has to be created at start (`createdAtStart`).
 */
class MaintenanceNotifier(
	context: Context,
	enhancedRepository: EnhancedRepository,
	private val sessionRepository: SessionRepository,
	private val maintenanceRepository: MaintenanceRepository,
	private val store: MaintenanceDismissStore,
) {
	private class Pending(val serverId: UUID, val userId: UUID, val notice: MaintenanceNotice)

	private val scope = MainScope()
	private var activity: WeakReference<FragmentActivity>? = null
	private var pending: Pending? = null
	private var dialog: MaintenanceDialog? = null
	private var dialogFor: Pending? = null

	private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
		override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
			if (MaintenanceLogic.isPlaybackScreen(f.javaClass.name)) close() else tryShow()
		}
	}

	private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
		override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
			if (activity is MainActivity) activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, false)
		}

		override fun onActivityResumed(activity: Activity) {
			if (activity !is MainActivity) return
			this@MaintenanceNotifier.activity = WeakReference(activity)
			tryShow()
		}

		override fun onActivityDestroyed(activity: Activity) {
			if (this@MaintenanceNotifier.activity?.get() === activity) {
				close()
				this@MaintenanceNotifier.activity = null
			}
		}

		override fun onActivityStarted(activity: Activity) = Unit
		override fun onActivityPaused(activity: Activity) = Unit
		override fun onActivityStopped(activity: Activity) = Unit
		override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
	}

	init {
		(context.applicationContext as Application).registerActivityLifecycleCallbacks(activityCallbacks)
		scope.launch {
			// Once per loaded sign-in (the state resets to unavailable on every user change), not on settings edits
			enhancedRepository.state
				.map { state -> state.takeIf { it.available }?.let { sessionRepository.currentSession.value?.userId to it.isAdmin } }
				.distinctUntilChanged()
				.filterNotNull()
				.collect { (_, isAdmin) -> check(isAdmin) }
		}
	}

	private suspend fun check(isAdmin: Boolean) {
		val session = sessionRepository.currentSession.value ?: return
		// Another user's dialog: close it without remembering it for them
		if (dialogFor?.userId != null && dialogFor?.userId != session.userId) close()
		val info = maintenanceRepository.fetch() ?: return
		// Signed out or switched while reading
		if (sessionRepository.currentSession.value?.userId != session.userId) return
		val notice = MaintenanceLogic.notice(info, isAdmin, Instant.now())
		when (MaintenanceLogic.dialogDecision(notice, store.seen(session.serverId, session.userId))) {
			MaintenanceDialogDecision.SHOW -> {
				pending = Pending(session.serverId, session.userId, requireNotNull(notice))
				tryShow()
			}

			MaintenanceDialogDecision.FORGET -> store.forget(session.serverId, session.userId)
			MaintenanceDialogDecision.NONE -> Unit
		}
	}

	private fun tryShow() {
		val next = pending ?: return
		if (dialog != null) return
		val activity = activity?.get() ?: return
		if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
		if (activity.supportFragmentManager.fragments.any { it.isResumed && MaintenanceLogic.isPlaybackScreen(it.javaClass.name) }) return
		if (sessionRepository.currentSession.value?.userId != next.userId) {
			pending = null
			return
		}

		val now = Instant.now()
		// Past its end while waiting (a long playback, say): nothing to tell any more
		if (next.notice.endsAt?.isAfter(now) == false) {
			pending = null
			return
		}
		val text = maintenanceText(activity, next.notice, now)
		try {
			dialogFor = next
			dialog = MaintenanceDialog.show(activity, text) {
				// Closed by the user (OK or Back): this window's dialog is done; close() clears dialogFor first
				val shown = dialogFor
				dialog = null
				dialogFor = null
				if (shown != null) {
					store.remember(shown.serverId, shown.userId, shown.notice.windowKey)
					if (pending === shown) pending = null
				}
			}
		} catch (err: RuntimeException) {
			// A window token gone between the checks above and show(); try again on the next resume
			Timber.w(err, "Failed to show the maintenance notice")
			dialog = null
			dialogFor = null
		}
	}

	/** Closes the dialog without counting it as seen; it shows again on the next suitable screen. */
	private fun close() {
		val shown = dialog ?: return
		dialogFor = null
		shown.dismiss()
	}
}
