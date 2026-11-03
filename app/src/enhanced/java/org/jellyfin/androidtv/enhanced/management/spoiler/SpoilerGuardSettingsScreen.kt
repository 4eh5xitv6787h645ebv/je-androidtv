package org.jellyfin.androidtv.enhanced.management.spoiler

import android.widget.Toast
import androidx.compose.foundation.lazy.items
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
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardPrefs
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
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

/**
 * Spoiler Guard preferences: which of the admin's protections apply to this user, and whether turning protection off
 * asks first. Only categories the admin enabled are listed. Each change re-reads the stored preferences and writes
 * them back whole; a failed read (including the plugin's "data was corrupt" 503) shows the server's message and
 * writes nothing.
 */
@Composable
fun SpoilerGuardSettingsScreen() {
	val enhancedRepository = koinInject<EnhancedRepository>()
	val spoilerGuardRepository = koinInject<SpoilerGuardRepository>()
	val enhanced by enhancedRepository.state.collectAsState()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()

	var reload by remember { mutableIntStateOf(0) }
	var prefs by remember { mutableStateOf<LoadState<SpoilerGuardPrefs>>(LoadState.Loading) }
	var saving by remember { mutableStateOf<String?>(null) }

	LaunchedEffect(reload) {
		prefs = LoadState.Loading
		prefs = try {
			LoadState.Ready(spoilerGuardRepository.getPrefs())
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Failed to load Spoiler Guard preferences")
			LoadState.Error(errorMessage(context, err)) { reload++ }
		}
	}

	fun save(key: String, value: Boolean?) {
		if (saving != null) return
		saving = key
		scope.launch {
			try {
				prefs = LoadState.Ready(spoilerGuardRepository.setPrefs(mapOf(key to value)))
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to save Spoiler Guard preference %s", key)
				Toast.makeText(context, errorMessage(context, err), Toast.LENGTH_LONG).show()
			} finally {
				saving = null
			}
		}
	}

	SettingsColumn {
		enhancedSettingsHeader(
			title = { stringResource(R.string.je_spoiler_guard) },
			caption = { stringResource(R.string.je_management_sg_settings_caption) },
		)

		if (!enhanced.spoilerGuard) {
			enhancedSettingsUnavailable { stringResource(R.string.je_management_sg_unavailable) }
			return@SettingsColumn
		}

		val current = enhancedSettingsLoad(prefs) ?: return@SettingsColumn
		val categories = SpoilerGuardCategories.available(enhanced.config)

		if (categories.isNotEmpty()) {
			enhancedSettingsSection(
				title = { stringResource(R.string.je_management_sg_categories) },
				caption = { stringResource(R.string.je_management_sg_categories_caption) },
			)
			items(categories, key = { it.key }) { category ->
				EnhancedToggleRow(
					key = "sg_${category.key}",
					title = stringResource(category.title),
					caption = stringResource(category.description),
					checked = category.applied(current),
					saving = saving == category.key,
					// The focus starts on the first toggle, not on the page heading
					initialFocus = category == categories.first(),
					onToggle = { save(category.key, category.toggled(current)) },
				)
			}
		}

		enhancedSettingsSection(title = { stringResource(R.string.je_management_sg_turning_off) })
		item {
			EnhancedToggleRow(
				key = "sg_skip_confirm",
				title = stringResource(R.string.je_management_sg_skip_confirm),
				caption = stringResource(R.string.je_management_sg_skip_confirm_desc),
				checked = current.skipDisableConfirm,
				saving = saving == SpoilerGuardRepository.PREF_SKIP_DISABLE_CONFIRM,
				initialFocus = categories.isEmpty(),
				onToggle = { save(SpoilerGuardRepository.PREF_SKIP_DISABLE_CONFIRM, !current.skipDisableConfirm) },
			)
		}
	}
}
