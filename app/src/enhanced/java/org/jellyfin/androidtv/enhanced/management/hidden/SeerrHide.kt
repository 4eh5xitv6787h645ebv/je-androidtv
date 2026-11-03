package org.jellyfin.androidtv.enhanced.management.hidden

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import org.jellyfin.androidtv.enhanced.management.errorMessage
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrCardMenuContributor
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrCardMenuItem
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrDetailsAction
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrDetailsContext
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrDetailsContributor
import timber.log.Timber

/** Hide for Seerr titles: the web's Hide button on Seerr cards, writing the same records. */
object SeerrHide {
	/** Offered when Hidden Content is on, the user shows Hide buttons and kept them on Seerr cards. */
	fun enabled(state: EnhancedState) = state.hideControls && state.hiddenSettings?.showButtonJellyseerr == true

	/** Ask (when the user wants confirmations), then hide everywhere. [onHidden] runs on success. */
	fun hide(
		context: Context,
		owner: LifecycleOwner,
		repository: HiddenContentRepository,
		state: EnhancedState,
		mediaType: String,
		tmdbId: Int,
		name: String,
		posterPath: String?,
		jellyfinId: String?,
		onHidden: () -> Unit,
	) {
		fun apply() = owner.lifecycleScope.launch {
			try {
				repository.hideTmdb(tmdbId, mediaType, name, posterPath, jellyfinId)
				Toast.makeText(context, context.getString(R.string.je_hidden_toast, name), Toast.LENGTH_SHORT).show()
				onHidden()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to hide %s %d", mediaType, tmdbId)
				Toast.makeText(context, errorMessage(context, err), Toast.LENGTH_LONG).show()
			}
		}

		if (state.hiddenSettings?.showHideConfirmation != false) {
			AlertDialog.Builder(context)
				.setTitle(context.getString(R.string.je_hide_confirm_title, name))
				.setMessage(R.string.je_management_hc_seerr_confirm)
				.setPositiveButton(R.string.je_hide) { _, _ -> apply() }
				.setNegativeButton(android.R.string.cancel, null)
				.show()
		} else apply()
	}
}

/** "Hide" in the long-press menu of Seerr cards; the card leaves its row once hidden. */
class SeerrHideMenuContributor(
	private val hiddenContentRepository: HiddenContentRepository,
) : SeerrCardMenuContributor {
	override fun items(activity: FragmentActivity, result: SeerrResult, state: EnhancedState, onRemoved: () -> Unit): List<SeerrCardMenuItem> {
		if (!SeerrHide.enabled(state) || !(result.isMovie || result.isTv)) return emptyList()
		return listOf(SeerrCardMenuItem(activity.getString(R.string.je_hide)) {
			SeerrHide.hide(
				context = activity,
				owner = activity,
				repository = hiddenContentRepository,
				state = state,
				mediaType = result.mediaType ?: "movie",
				tmdbId = result.id,
				name = result.displayTitle,
				// Protected library titles show server artwork; don't record the TMDB poster for them
				posterPath = result.posterPath.takeIf { !result.libraryArtwork },
				jellyfinId = result.mediaInfo?.jellyfinMediaId,
				onHidden = onRemoved,
			)
		})
	}
}

/** "Hide" / "Unhide" on the Seerr details screen. */
class SeerrHideDetailsContributor(
	private val hiddenContentRepository: HiddenContentRepository,
) : SeerrDetailsContributor {
	override suspend fun actions(context: SeerrDetailsContext): List<SeerrDetailsAction> {
		if (!SeerrHide.enabled(context.state)) return emptyList()
		val jellyfinId = context.details.mediaInfo?.jellyfinMediaId
		// If the document can't be read this throws and the screen shows no button rather than a wrong one
		val hidden = hiddenContentRepository.get().isHiddenTmdb(context.tmdbId, jellyfinId)
		val fragment = context.fragment

		return listOf(SeerrDetailsAction(fragment.getString(if (hidden) R.string.je_unhide else R.string.je_hide)) {
			val ctx = fragment.context ?: return@SeerrDetailsAction
			if (hidden) {
				fragment.lifecycleScope.launch {
					try {
						hiddenContentRepository.unhideTmdb(context.tmdbId, jellyfinId)
						Toast.makeText(ctx, ctx.getString(R.string.je_unhidden_toast, context.details.displayTitle), Toast.LENGTH_SHORT).show()
						context.reload()
					} catch (err: CancellationException) {
						throw err
					} catch (err: Exception) {
						Timber.w(err, "Failed to unhide %s %d", context.mediaType, context.tmdbId)
						Toast.makeText(ctx, errorMessage(ctx, err), Toast.LENGTH_LONG).show()
					}
				}
			} else {
				SeerrHide.hide(
					context = ctx,
					owner = fragment,
					repository = hiddenContentRepository,
					state = context.state,
					mediaType = context.mediaType,
					tmdbId = context.tmdbId,
					name = context.details.displayTitle,
					// Library titles under Spoiler Guard keep their TMDB poster out of the record too
					posterPath = context.details.posterPath.takeIf { !context.spoilerGuarded },
					jellyfinId = jellyfinId,
					onHidden = context.reload,
				)
			}
		})
	}
}
