package org.jellyfin.androidtv.enhanced.discover

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.ui.design.LoadStateContent
import org.jellyfin.androidtv.enhanced.ui.design.PageScaffold
import org.jellyfin.androidtv.enhanced.ui.design.SkeletonShape
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.android.ext.android.inject
import timber.log.Timber

/** User-facing text for a failed random pick. */
fun randomPickErrorMessage(context: Context, err: Throwable): String = when (err) {
	is DiscoverRepository.RandomPickException -> context.getString(
		when (err.reason) {
			DiscoverRepository.RandomFailure.NO_TYPES -> R.string.je_discover_random_no_types
			DiscoverRepository.RandomFailure.NOTHING -> R.string.je_discover_random_nothing
			DiscoverRepository.RandomFailure.NOTHING_UNWATCHED -> R.string.je_discover_random_nothing_unwatched
		}
	)

	is EnhancedApi.EnhancedApiException -> err.serverMessage ?: context.getString(R.string.je_error_generic)
	else -> context.getString(R.string.je_error_generic)
}

/** Tell the user when the pick fell back to the whole library, as the web's toast does. */
fun showRandomPickNotice(context: Context, notice: DiscoverRepository.RandomNotice?) {
	val text = when (notice) {
		null -> return
		DiscoverRepository.RandomNotice.SourceMissing -> context.getString(R.string.je_discover_random_source_missing)
		is DiscoverRepository.RandomNotice.SourceEmpty -> context.getString(R.string.je_discover_random_source_empty, notice.name)
	}
	Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

/**
 * The hub's "Pick something" page: picks a random library title and replaces itself with its details, so Back
 * returns to the hub. Shows the reason (with Retry) when nothing could be picked.
 */
class RandomPickFragment : Fragment() {
	private val discoverRepository by inject<DiscoverRepository>()
	private val navigationRepository by inject<NavigationRepository>()

	private val state = MutableStateFlow<LoadState<Unit>>(LoadState.Loading)
	private var job: Job? = null

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		val current by state.collectAsState()
		LaunchedEffect(Unit) { if (current is LoadState.Loading) pick() }

		PageScaffold(title = stringResource(R.string.je_discover_random_title)) {
			LoadStateContent(current, loading = SkeletonShape.Hero) { }
		}
	}

	private fun pick() {
		if (job?.isActive == true) return
		state.value = LoadState.Loading
		job = lifecycleScope.launch {
			try {
				val pick = discoverRepository.randomPick()
				val context = context ?: return@launch
				showRandomPickNotice(context, pick.notice)
				navigationRepository.navigate(Destinations.itemDetails(pick.itemId), replace = true)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Random pick failed")
				val context = context ?: return@launch
				state.value = LoadState.Error(randomPickErrorMessage(context, err)) { pick() }
			}
		}
	}
}
