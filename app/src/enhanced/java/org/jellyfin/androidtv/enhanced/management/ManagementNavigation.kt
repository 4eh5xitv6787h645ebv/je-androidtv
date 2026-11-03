package org.jellyfin.androidtv.enhanced.management

import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.EnhancedDestinations
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.util.UUID

/** Opening titles from the management lists. */
object ManagementNavigation {
	private val navigationRepository by inject<NavigationRepository>(NavigationRepository::class.java)
	private val api by inject<ApiClient>(ApiClient::class.java)

	/** Library details for movies, shows, seasons and episodes. */
	fun openDetails(itemId: UUID) = navigationRepository.navigate(Destinations.itemDetails(itemId))

	fun openSeerr(mediaType: String, tmdbId: Int) = navigationRepository.navigate(EnhancedDestinations.seerrDetails(mediaType, tmdbId))

	/**
	 * Collections open in the collection browser, which needs the full item. Anything that isn't a collection after
	 * all goes to the details screen.
	 */
	fun openCollection(fragment: Fragment, itemId: UUID) {
		fragment.lifecycleScope.launch {
			try {
				val item = withContext(Dispatchers.IO) { api.userLibraryApi.getItem(itemId = itemId).content }
				if (item.type == BaseItemKind.BOX_SET) navigationRepository.navigate(Destinations.collectionBrowser(item))
				else openDetails(itemId)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to open collection %s", itemId)
				val context = fragment.context ?: return@launch
				Toast.makeText(context, R.string.je_error_generic, Toast.LENGTH_SHORT).show()
			}
		}
	}
}
