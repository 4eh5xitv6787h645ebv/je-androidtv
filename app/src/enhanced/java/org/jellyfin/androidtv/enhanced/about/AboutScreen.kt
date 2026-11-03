package org.jellyfin.androidtv.enhanced.about

import android.content.ClipData
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.calendar.attempt
import org.jellyfin.androidtv.enhanced.calendar.serverMessage
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.ui.settings.util.copyAction
import org.koin.compose.koinInject

/** Settings routes of the About screen that aren't in [org.jellyfin.androidtv.enhanced.ui.settings.EnhancedSettingsRoutes]. */
object AboutRoutes {
	const val RELEASE_NOTES = "/enhanced/about/release-notes"
}

/**
 * Loads [block] when first shown (and again when [keys] change); an [LoadState.Error]'s retry runs it again.
 */
@Composable
private fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): LoadState<T> {
	var attemptNumber by remember { mutableIntStateOf(0) }
	var state by remember(*keys) { mutableStateOf<LoadState<T>>(LoadState.Loading) }
	LaunchedEffect(attemptNumber, *keys) {
		state = LoadState.Loading
		state = attempt { block() }.fold(
			onSuccess = { LoadState.Ready(it) },
			onFailure = { LoadState.Error(it.serverMessage ?: it.message) { attemptNumber++ } },
		)
	}
	return state
}

/** Caption text for a section that is loading or failed; null when ready. */
@Composable
private fun loadCaption(state: LoadState<*>): String? = when (state) {
	LoadState.Loading -> stringResource(R.string.je_about_loading)
	is LoadState.Error -> stringResource(R.string.je_about_failed, state.message ?: stringResource(R.string.je_error_generic))
	is LoadState.Ready -> null
}

/** The features the plugin has turned on for this server and user, for the About screen. */
internal fun enabledFeatures(state: EnhancedState): List<Int> = buildList {
	if (state.spoilerGuard) add(R.string.je_about_feature_spoiler_guard)
	if (state.hiddenContent) add(R.string.je_about_feature_hidden_content)
	if (state.removeFromHome) add(R.string.je_about_feature_remove_from_home)
	if (state.seerrUsable) add(R.string.je_about_feature_seerr)
	if (state.discover) add(R.string.je_about_feature_discover)
	if (state.requests) add(R.string.je_about_feature_requests)
	if (state.calendar) add(R.string.je_about_feature_calendar)
	if (state.bookmarks) add(R.string.je_about_feature_bookmarks)
	if (state.activity) add(R.string.je_about_feature_activity)
	if (state.activeStreams) add(R.string.je_about_feature_active_streams)
	if (state.available && state.config.showUserReviews) add(R.string.je_about_feature_reviews)
	if (state.available && state.config.elsewhereEnabled) add(R.string.je_about_feature_elsewhere)
	if (state.available && state.config.catchUpEnabled) add(R.string.je_about_feature_catch_up)
}

/** "About Jellyfin Enhanced": versions, Seerr link, enabled features and, for admins, plugin diagnostics. */
@Composable
fun AboutSettingsScreen() {
	val enhancedRepository = koinInject<EnhancedRepository>()
	val aboutRepository = koinInject<AboutRepository>()
	val state by enhancedRepository.state.collectAsState()
	val router = LocalRouter.current

	val serverVersion = rememberLoad { aboutRepository.serverVersion() }
	val release = rememberLoad { aboutRepository.latestRelease() }
	val hostCompat = rememberLoad(state.isAdmin) { if (state.isAdmin) aboutRepository.hostCompat() else null }
	val whatsNew = rememberLoad(state.isAdmin) { if (state.isAdmin) aboutRepository.whatsNew() else null }

	val unknown = stringResource(R.string.je_about_unknown)
	val pluginVersion = state.pluginVersion ?: unknown
	val serverCaption = loadCaption(serverVersion) ?: (serverVersion as LoadState.Ready).value ?: unknown
	val seerrCaption = when {
		!state.config.jellyseerrEnabled -> stringResource(R.string.je_about_seerr_off)
		state.seerrAdvanced -> stringResource(R.string.je_about_seerr_linked_advanced)
		state.seerr.linked -> stringResource(R.string.je_about_seerr_linked)
		else -> stringResource(R.string.je_about_seerr_not_linked, state.seerr.reason ?: unknown)
	}
	val features = enabledFeatures(state).map { stringResource(it) }
		.ifEmpty { listOf(stringResource(R.string.je_about_features_none)) }
		.joinToString(", ")

	val compatCaption = loadCaption(hostCompat) ?: (hostCompat as LoadState.Ready).value?.let { compat ->
		when {
			compat.mismatch -> compat.message ?: stringResource(R.string.je_about_compat_mismatch, compat.expectedAsset ?: unknown)
			compat.stale -> compat.message ?: stringResource(R.string.je_about_compat_stale)
			else -> stringResource(R.string.je_about_compat_ok, compat.builtFor ?: unknown, compat.hostVersion ?: unknown)
		}
	} ?: unknown
	val whatsNewCaption = loadCaption(whatsNew) ?: (whatsNew as LoadState.Ready).value?.let { news ->
		if (news.newSettings.isEmpty()) stringResource(R.string.je_about_whats_new_none)
		else stringResource(R.string.je_about_whats_new_count, news.newSettings.size, news.newSettings.keys.sorted().joinToString(", ", limit = 6))
	} ?: unknown

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.je_settings_title).uppercase()) },
				headingContent = { Text(stringResource(R.string.je_about_title)) },
			)
		}

		infoItem("plugin_version", R.string.je_about_plugin_version, pluginVersion, R.drawable.je_ic_about_info)
		infoItem("server_version", R.string.je_about_server_version, serverCaption, onClick = (serverVersion as? LoadState.Error)?.retry)
		infoItem("seerr", R.string.je_about_seerr, seerrCaption)
		infoItem("features", R.string.je_about_features, features)

		item {
			val caption = when (val loaded = release) {
				LoadState.Loading -> stringResource(R.string.je_about_loading)
				is LoadState.Error -> stringResource(R.string.je_about_release_unavailable)
				is LoadState.Ready -> loaded.value?.let { it.name ?: it.tagName } ?: stringResource(R.string.je_about_release_unavailable)
			}
			val available = (release as? LoadState.Ready)?.value != null
			ListButton(
				headingContent = { Text(stringResource(R.string.je_about_release_notes)) },
				captionContent = { Text(caption) },
				enabled = available,
				onClick = { router.push(AboutRoutes.RELEASE_NOTES) },
				modifier = Modifier.focusKey("je_about_release_notes"),
			)
		}

		if (state.isAdmin) {
			item {
				ListSection(headingContent = { Text(stringResource(R.string.je_about_admin)) })
			}

			infoItem("host_compat", R.string.je_about_compat, compatCaption, onClick = (hostCompat as? LoadState.Error)?.retry)

			infoItem("whats_new", R.string.je_about_whats_new, whatsNewCaption, onClick = (whatsNew as? LoadState.Error)?.retry)
		}
	}
}

/** A read-only info row; selecting it copies the value, or runs [onClick] (e.g. a retry) when given. */
private fun androidx.compose.foundation.lazy.LazyListScope.infoItem(
	key: String,
	title: Int,
	caption: String,
	icon: Int? = null,
	onClick: (() -> Unit)? = null,
) = item(key = key) {
	val heading = stringResource(title)
	ListButton(
		leadingContent = icon?.let { { Icon(painterResource(it), contentDescription = null) } },
		headingContent = { Text(heading) },
		captionContent = { Text(caption) },
		onClick = onClick ?: copyAction(ClipData.newPlainText(heading, caption)),
		modifier = Modifier.focusKey("je_about_$key"),
	)
}

/** The latest GitHub release notes as focusable lines, so the D-pad scrolls through them. */
@Composable
fun ReleaseNotesSettingsScreen() {
	val aboutRepository = koinInject<AboutRepository>()
	val release = rememberLoad { aboutRepository.cachedRelease ?: aboutRepository.latestRelease() }
	val loaded = (release as? LoadState.Ready)?.value
	val lines = remember(loaded) { ReleaseNotes.lines(loaded?.body) }

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.je_about_release_notes).uppercase()) },
				headingContent = { Text(loaded?.name ?: loaded?.tagName ?: stringResource(R.string.je_about_title)) },
				captionContent = {
					Text(
						when {
							release == LoadState.Loading -> stringResource(R.string.je_about_loading)
							loaded == null -> stringResource(R.string.je_about_release_unavailable)
							else -> loaded.tagName.orEmpty()
						}
					)
				},
			)
		}

		itemsIndexed(lines) { index, line ->
			ListButton(
				headingContent = { Text(line, fontSize = 15.sp) },
				onClick = {},
				modifier = Modifier.focusKey("je_release_line_$index"),
			)
		}
	}
}
