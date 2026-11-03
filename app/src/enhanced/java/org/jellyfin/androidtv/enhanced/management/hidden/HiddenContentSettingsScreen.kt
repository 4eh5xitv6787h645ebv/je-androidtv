package org.jellyfin.androidtv.enhanced.management.hidden

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedSettingsRepository
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import org.jellyfin.androidtv.enhanced.data.HiddenContentSettings
import org.jellyfin.androidtv.enhanced.management.errorMessage
import org.jellyfin.androidtv.enhanced.management.settings.EnhancedToggleRow
import org.jellyfin.androidtv.enhanced.management.settings.enhancedSettingsHeader
import org.jellyfin.androidtv.enhanced.management.settings.enhancedSettingsLoad
import org.jellyfin.androidtv.enhanced.management.settings.enhancedSettingsSection
import org.jellyfin.androidtv.enhanced.management.settings.enhancedSettingsUnavailable
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject
import timber.log.Timber

/** One `Settings` flag of `hidden-content.json`. */
class HiddenContentToggle(
	val key: String,
	@StringRes val title: Int,
	@StringRes val caption: Int?,
	val value: (HiddenContentSettings) -> Boolean,
)

object HiddenContentToggles {
	val master = HiddenContentToggle("Enabled", R.string.je_management_hc_enabled, R.string.je_management_hc_enabled_desc) { it.enabled }

	/** Where hidden titles are filtered out. */
	val filters = listOf(
		HiddenContentToggle("FilterLibrary", R.string.je_management_hc_filter_library, null) { it.filterLibrary },
		HiddenContentToggle("FilterDiscovery", R.string.je_management_hc_filter_discovery, null) { it.filterDiscovery },
		HiddenContentToggle("FilterSearch", R.string.je_management_hc_filter_search, null) { it.filterSearch },
		HiddenContentToggle("FilterUpcoming", R.string.je_management_hc_filter_upcoming, null) { it.filterUpcoming },
		HiddenContentToggle("FilterCalendar", R.string.je_management_hc_filter_calendar, null) { it.filterCalendar },
		HiddenContentToggle("FilterRecommendations", R.string.je_management_hc_filter_recommendations, null) { it.filterRecommendations },
		HiddenContentToggle("FilterRequests", R.string.je_management_hc_filter_requests, null) { it.filterRequests },
		HiddenContentToggle("FilterNextUp", R.string.je_management_hc_filter_next_up, null) { it.filterNextUp },
		HiddenContentToggle("FilterContinueWatching", R.string.je_management_hc_filter_continue_watching, null) { it.filterContinueWatching },
	)

	/** Where Hide buttons are offered. */
	val buttons = listOf(
		HiddenContentToggle("ShowHideButtons", R.string.je_management_hc_show_buttons, R.string.je_management_hc_show_buttons_desc) { it.showHideButtons },
		HiddenContentToggle("ShowHideConfirmation", R.string.je_management_hc_confirm, null) { it.showHideConfirmation },
		HiddenContentToggle("ShowButtonDetails", R.string.je_management_hc_button_details, null) { it.showButtonDetails },
		HiddenContentToggle("ShowButtonLibrary", R.string.je_management_hc_button_library, R.string.je_management_hc_button_library_desc) { it.showButtonLibrary },
		HiddenContentToggle("ShowButtonCast", R.string.je_management_hc_button_cast, null) { it.showButtonCast },
		HiddenContentToggle("ShowButtonJellyseerr", R.string.je_management_hc_button_seerr, null) { it.showButtonJellyseerr },
		HiddenContentToggle("ExperimentalHideCollections", R.string.je_management_hc_collections, R.string.je_management_hc_collections_desc) { it.experimentalHideCollections },
	)
}

/**
 * Hidden Content settings: the master switch, the surfaces hidden titles are filtered from and where Hide buttons
 * appear. Every change goes through [EnhancedSettingsRepository.edit], which re-reads the whole document and keeps
 * the hidden items and any unknown fields, and publishes the new settings to the rest of the app.
 */
@Composable
fun HiddenContentSettingsScreen() {
	val enhancedRepository = koinInject<EnhancedRepository>()
	val settingsRepository = koinInject<EnhancedSettingsRepository>()
	val enhanced by enhancedRepository.state.collectAsState()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()

	var reload by remember { mutableIntStateOf(0) }
	var settings by remember { mutableStateOf<LoadState<HiddenContentSettings>>(LoadState.Loading) }
	var saving by remember { mutableStateOf<String?>(null) }

	LaunchedEffect(reload) {
		settings = LoadState.Loading
		settings = try {
			LoadState.Ready(HiddenContentRepository.Snapshot(settingsRepository.read(EnhancedSettingsRepository.HIDDEN_CONTENT).json).settings)
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Failed to load Hidden Content settings")
			LoadState.Error(errorMessage(context, err)) { reload++ }
		}
	}

	fun save(toggle: HiddenContentToggle, value: Boolean) {
		if (saving != null) return
		saving = toggle.key
		scope.launch {
			try {
				val saved = settingsRepository.edit(EnhancedSettingsRepository.HIDDEN_CONTENT) { fields ->
					val current = (fields["Settings"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
					current[toggle.key] = JsonPrimitive(value)
					fields["Settings"] = JsonObject(current)
				}
				settings = LoadState.Ready(HiddenContentRepository.Snapshot(saved.json).settings)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to save Hidden Content setting %s", toggle.key)
				Toast.makeText(context, errorMessage(context, err), Toast.LENGTH_LONG).show()
			} finally {
				saving = null
			}
		}
	}

	SettingsColumn {
		enhancedSettingsHeader(
			title = { stringResource(R.string.je_management_hc_title) },
			caption = { stringResource(R.string.je_management_hc_settings_caption) },
		)

		if (!enhanced.hiddenContent) {
			enhancedSettingsUnavailable { stringResource(R.string.je_management_hc_unavailable) }
			return@SettingsColumn
		}

		val current = enhancedSettingsLoad(settings) ?: return@SettingsColumn

		fun toggleItem(toggle: HiddenContentToggle) = item(key = toggle.key) {
			EnhancedToggleRow(
				key = "hc_${toggle.key}",
				title = stringResource(toggle.title),
				caption = toggle.caption?.let { stringResource(it) },
				checked = toggle.value(current),
				saving = saving == toggle.key,
				// The master switch comes first: the focus starts there, not on the page heading
				initialFocus = toggle == HiddenContentToggles.master,
				onToggle = { save(toggle, !toggle.value(current)) },
			)
		}

		toggleItem(HiddenContentToggles.master)
		enhancedSettingsSection(title = { stringResource(R.string.je_management_hc_filters) })
		HiddenContentToggles.filters.forEach { toggleItem(it) }
		enhancedSettingsSection(title = { stringResource(R.string.je_management_hc_buttons) })
		HiddenContentToggles.buttons.forEach { toggleItem(it) }
	}
}
