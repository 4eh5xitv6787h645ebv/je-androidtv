package org.jellyfin.androidtv.enhanced.metadata

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject
import timber.log.Timber

/** Settings routes of this feature. */
object MetadataSettingsRoutes {
	const val ELSEWHERE = "/enhanced/elsewhere"
	const val ELSEWHERE_REGION = "/enhanced/elsewhere/region"
	const val ELSEWHERE_SERVICES = "/enhanced/elsewhere/services"
	const val CONTENT_WARNINGS = "/enhanced/content-warnings"
}

/** The user's Elsewhere preferences plus the server's default region, for the settings screens. */
private data class ElsewhereScreenState(val settings: ElsewhereSettings, val defaultRegion: String) {
	val effectiveRegion get() = settings.region?.uppercase() ?: defaultRegion
}

private suspend fun loadElsewhere(repository: MetadataRepository, retry: () -> Unit): LoadState<ElsewhereScreenState> = try {
	val config = repository.publicConfig()
	val default = config.defaultRegion?.trim()?.uppercase()?.ifEmpty { null } ?: "US"
	LoadState.Ready(ElsewhereScreenState(repository.elsewhereSettings(), default))
} catch (err: CancellationException) {
	throw err
} catch (err: Exception) {
	Timber.w(err, "Failed to load Elsewhere settings")
	LoadState.Error(err.displayMessage(), retry)
}

private fun Context.saveFailed(err: Throwable) = Toast.makeText(
	this,
	getString(R.string.je_metadata_save_failed, err.displayMessage() ?: getString(R.string.je_error_generic)),
	Toast.LENGTH_LONG,
).show()

private fun regionName(code: String): String =
	java.util.Locale.Builder().setRegion(code).build().displayCountry.ifEmpty { code }

@Composable
private fun ElsewhereHeader(caption: String? = null) = ListSection(
	overlineContent = { Text(stringResource(R.string.je_settings_title).uppercase()) },
	headingContent = { Text(stringResource(R.string.je_metadata_elsewhere_title)) },
	captionContent = caption?.let { { Text(it) } },
)

/** Settings route [MetadataSettingsRoutes.ELSEWHERE]: region and streaming services for "where to watch". */
@Composable
fun ElsewhereSettingsScreen() {
	val repository = koinInject<MetadataRepository>()
	val router = LocalRouter.current
	var attempt by remember { mutableIntStateOf(0) }
	val state by produceState<LoadState<ElsewhereScreenState>>(LoadState.Loading, attempt) {
		value = loadElsewhere(repository) { attempt++ }
	}

	SettingsColumn {
		item { ElsewhereHeader(stringResource(R.string.je_metadata_elsewhere_caption)) }
		loadStateItem(state)
		val ready = (state as? LoadState.Ready)?.value ?: return@SettingsColumn

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.je_metadata_elsewhere_region)) },
				captionContent = {
					Text(
						ready.settings.region?.let { "${regionName(it)} ($it)" }
							?: stringResource(R.string.je_metadata_elsewhere_region_default, ready.defaultRegion)
					)
				},
				onClick = { router.push(MetadataSettingsRoutes.ELSEWHERE_REGION) },
				modifier = Modifier.focusKey(MetadataSettingsRoutes.ELSEWHERE_REGION),
			)
		}
		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.je_metadata_elsewhere_services)) },
				captionContent = {
					Text(ready.settings.services.joinToString(", ").ifEmpty { stringResource(R.string.je_metadata_elsewhere_services_all) }, maxLines = 2)
				},
				onClick = { router.push(MetadataSettingsRoutes.ELSEWHERE_SERVICES) },
				modifier = Modifier.focusKey(MetadataSettingsRoutes.ELSEWHERE_SERVICES),
			)
		}
	}
}

/** Settings route [MetadataSettingsRoutes.ELSEWHERE_REGION]: pick the region, or the server default. */
@Composable
fun ElsewhereRegionSettingsScreen() {
	val repository = koinInject<MetadataRepository>()
	val router = LocalRouter.current
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var attempt by remember { mutableIntStateOf(0) }
	var saving by remember { mutableStateOf(false) }
	val state by produceState<LoadState<Pair<ElsewhereScreenState, List<Pair<String, String>>>>>(LoadState.Loading, attempt) {
		value = when (val loaded = loadElsewhere(repository) { attempt++ }) {
			is LoadState.Ready -> try {
				LoadState.Ready(loaded.value to repository.providerRegions())
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to load provider regions")
				LoadState.Error(err.displayMessage()) { attempt++ }
			}

			is LoadState.Error -> loaded
			LoadState.Loading -> LoadState.Loading
		}
	}

	fun select(code: String?) {
		if (saving) return
		saving = true
		scope.launch {
			try {
				repository.editElsewhere { it["Region"] = JsonPrimitive(code.orEmpty()) }
				router.back()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to save the Elsewhere region")
				context.saveFailed(err)
			} finally {
				saving = false
			}
		}
	}

	SettingsColumn {
		item { ElsewhereHeader(stringResource(R.string.je_metadata_elsewhere_region)) }
		loadStateItem(state)
		val (screen, regions) = (state as? LoadState.Ready)?.value ?: return@SettingsColumn
		val current = screen.settings.region?.uppercase()

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.je_metadata_elsewhere_region_default, screen.defaultRegion)) },
				trailingContent = { RadioButton(checked = current == null) },
				onClick = { select(null) },
				modifier = Modifier.focusKey("region_default", initialFocus = current == null),
			)
		}
		items(regions, key = { it.first }) { (code, name) ->
			ListButton(
				headingContent = { Text(name) },
				captionContent = { Text(code) },
				trailingContent = { RadioButton(checked = current == code) },
				onClick = { select(code) },
				modifier = Modifier.focusKey("region_$code", initialFocus = current == code),
			)
		}
	}
}

/** Settings route [MetadataSettingsRoutes.ELSEWHERE_SERVICES]: the services to show; none selected shows all. */
@Composable
fun ElsewhereServicesSettingsScreen() {
	val repository = koinInject<MetadataRepository>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var attempt by remember { mutableIntStateOf(0) }
	var selected by remember { mutableStateOf<List<String>?>(null) }
	val state by produceState<LoadState<Pair<ElsewhereScreenState, List<String>>>>(LoadState.Loading, attempt) {
		value = when (val loaded = loadElsewhere(repository) { attempt++ }) {
			is LoadState.Ready -> try {
				// Keep services the user picked earlier even if TMDB no longer lists them, so they can be removed
				val services = repository.regionServices(loaded.value.effectiveRegion)
				selected = loaded.value.settings.services
				LoadState.Ready(loaded.value to (loaded.value.settings.services.filter { it !in services } + services))
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to load streaming services")
				LoadState.Error(err.displayMessage()) { attempt++ }
			}

			is LoadState.Error -> loaded
			LoadState.Loading -> LoadState.Loading
		}
	}

	fun toggle(name: String) {
		val current = selected ?: return
		val updated = if (name in current) current - name else current + name
		selected = updated
		scope.launch {
			try {
				repository.editElsewhere { it["Services"] = JsonArray(updated.map(::JsonPrimitive)) }
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to save Elsewhere services")
				context.saveFailed(err)
				attempt++
			}
		}
	}

	SettingsColumn {
		item { ElsewhereHeader(stringResource(R.string.je_metadata_elsewhere_services_caption)) }
		loadStateItem(state)
		val (screen, services) = (state as? LoadState.Ready)?.value ?: return@SettingsColumn
		val chosen = selected.orEmpty()

		item { ListSection(headingContent = { Text(regionName(screen.effectiveRegion)) }) }
		items(services, key = { it }) { name ->
			ListButton(
				headingContent = { Text(name) },
				trailingContent = { Checkbox(checked = name in chosen) },
				onClick = { toggle(name) },
			)
		}
	}
}
