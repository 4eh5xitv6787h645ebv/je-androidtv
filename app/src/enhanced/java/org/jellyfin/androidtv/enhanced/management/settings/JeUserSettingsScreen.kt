package org.jellyfin.androidtv.enhanced.management.settings

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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedSettingsRepository
import org.jellyfin.androidtv.enhanced.management.errorMessage
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject
import timber.log.Timber

/** The "Details & tags" page: details statistics, poster tags, home-row removal and the random button. */
@Composable
fun JeDetailsSettingsScreen() = JeUserSettingsScreen(R.string.je_management_set_details_title, JeUserSettings.details)

/** The "Playback" page: pause screen, in-player rating and the web client's auto-skip choices. */
@Composable
fun JePlaybackSettingsScreen() = JeUserSettingsScreen(R.string.je_management_set_playback_title, JeUserSettings.playback)

private class Documents(val user: JsonObject, val public: JsonObject)

/**
 * A page of `settings.json` fields. Each change goes through [EnhancedSettingsRepository], which re-reads the whole
 * document, changes the one field and saves it all, and publishes the result to the rest of the app.
 */
@Composable
private fun JeUserSettingsScreen(@StringRes title: Int, sections: List<Pair<Int, List<JeUserSetting>>>) {
	val enhancedRepository = koinInject<EnhancedRepository>()
	val settingsRepository = koinInject<EnhancedSettingsRepository>()
	val enhancedApi = koinInject<EnhancedApi>()
	val enhanced by enhancedRepository.state.collectAsState()
	val context = LocalContext.current
	val router = LocalRouter.current
	val scope = rememberCoroutineScope()

	var reload by remember { mutableIntStateOf(0) }
	var documents by remember { mutableStateOf<LoadState<Documents>>(LoadState.Loading) }
	var saving by remember { mutableStateOf<String?>(null) }

	LaunchedEffect(reload) {
		documents = LoadState.Loading
		documents = try {
			coroutineScope {
				val user = async { settingsRepository.read(EnhancedSettingsRepository.SETTINGS).json }
				// Defaults only; without them the web client's built-in defaults apply
				val public = async { runCatching { enhancedApi.get("public-config") as? JsonObject }.getOrNull() ?: JsonObject(emptyMap()) }
				LoadState.Ready(Documents(user.await(), public.await()))
			}
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Failed to load Jellyfin Enhanced settings")
			LoadState.Error(errorMessage(context, err)) { reload++ }
		}
	}

	fun save(setting: JeUserSetting, value: JsonElement) {
		if (saving != null) return
		saving = setting.key
		scope.launch {
			try {
				val saved = settingsRepository.edit(EnhancedSettingsRepository.SETTINGS) { it[setting.key] = value }
				val current = (documents as? LoadState.Ready)?.value
				documents = LoadState.Ready(Documents(saved.json, current?.public ?: JsonObject(emptyMap())))
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to save Jellyfin Enhanced setting %s", setting.key)
				Toast.makeText(context, errorMessage(context, err), Toast.LENGTH_LONG).show()
			} finally {
				saving = null
			}
		}
	}

	SettingsColumn {
		enhancedSettingsHeader(title = { stringResource(title) })

		if (!enhanced.available) {
			enhancedSettingsUnavailable { stringResource(R.string.je_hub_unavailable) }
			return@SettingsColumn
		}

		val docs = enhancedSettingsLoad(documents) ?: return@SettingsColumn
		// The focus starts on the first setting, not on the page heading
		val firstKey = sections.firstOrNull()?.second?.firstOrNull()?.key

		for ((section, settings) in sections) {
			enhancedSettingsSection(title = { stringResource(section) })
			for (setting in settings) item(key = setting.key) {
				val busy = saving == setting.key
				val first = setting.key == firstKey
				val caption = setting.caption?.let { stringResource(it) }
				when (setting) {
					is JeUserSetting.Toggle -> {
						val value = setting.value(docs.user, docs.public)
						EnhancedToggleRow(setting.key, stringResource(setting.title), caption, value, busy, first) { save(setting, JsonPrimitive(!value)) }
					}

					is JeUserSetting.Choice -> {
						val value = setting.value(docs.user, docs.public)
						val label = setting.options.firstOrNull { it.first == value }?.let { stringResource(it.second) } ?: value
						EnhancedChoiceRow(setting.key, stringResource(setting.title), label, busy, first) { save(setting, JsonPrimitive(setting.next(value))) }
					}

					is JeUserSetting.Number -> {
						val value = setting.value(docs.user, docs.public)
						EnhancedChoiceRow(setting.key, stringResource(setting.title), stringResource(R.string.je_management_set_seconds, value), busy, first) {
							save(setting, JsonPrimitive(setting.next(value)))
						}
					}

					is JeUserSetting.TriState -> {
						val value = setting.value(docs.user)
						val label = stringResource(
							when (value) {
								null -> R.string.je_management_set_server_default
								true -> R.string.je_management_set_on
								false -> R.string.je_management_set_off
							}
						)
						EnhancedChoiceRow(setting.key, stringResource(setting.title), listOfNotNull(label, caption).joinToString(" · "), busy, first) {
							save(setting, setting.next(value)?.let(::JsonPrimitive) ?: JsonNull)
						}
					}
				}
			}
			// The web's auto-skip fields don't drive this app's player: lead to the setting that does
			if (section == R.string.je_management_set_section_skip) item(key = "media_segments") {
				EnhancedChoiceRow(
					key = "media_segments",
					title = stringResource(R.string.je_management_set_media_segments),
					value = stringResource(R.string.je_management_set_media_segments_desc),
					saving = false,
				) { router.push(Routes.PLAYBACK_MEDIA_SEGMENTS) }
			}
		}
	}
}
