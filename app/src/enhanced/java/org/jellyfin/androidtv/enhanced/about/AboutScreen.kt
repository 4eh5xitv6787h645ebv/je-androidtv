package org.jellyfin.androidtv.enhanced.about

import android.content.ClipData
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.calendar.attempt
import org.jellyfin.androidtv.enhanced.calendar.serverMessage
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.seerr.SeerrErrors
import org.jellyfin.androidtv.enhanced.seerr.SeerrLink
import org.jellyfin.androidtv.enhanced.seerr.SeerrLinkKind
import org.jellyfin.androidtv.enhanced.seerr.SeerrLinkManager
import org.jellyfin.androidtv.enhanced.ui.design.JeType
import org.jellyfin.androidtv.enhanced.ui.design.SettingsIntro
import org.jellyfin.androidtv.enhanced.ui.design.SettingsNav
import org.jellyfin.androidtv.enhanced.ui.design.SettingsSection
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrLinkTexts
import org.jellyfin.androidtv.enhanced.ui.seerr.resolve
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
	const val WHATS_NEW = "/enhanced/about/whats-new"

	/** New settings listed on the About screen itself; the rest are behind "Show all". */
	const val WHATS_NEW_PREVIEW = 5
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
	val whatsNewRevision by aboutRepository.whatsNewRevision.collectAsState()
	val whatsNew = rememberLoad(state.isAdmin, whatsNewRevision) { if (state.isAdmin) aboutRepository.whatsNew() else null }

	val unknown = stringResource(R.string.je_about_unknown)
	val pluginVersion = state.pluginVersion ?: unknown
	val serverCaption = loadCaption(serverVersion) ?: (serverVersion as LoadState.Ready).value ?: unknown
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

	SettingsColumn {
		item { SettingsSection(overline = stringResource(R.string.je_settings_title), heading = stringResource(R.string.je_about_title)) }

		item { SettingsSection(overline = stringResource(R.string.je_about_section_versions)) }
		// The focus starts on the first row, not on the page heading
		infoItem("plugin_version", R.string.je_about_plugin_version, pluginVersion, R.drawable.je_ic_about_info, initialFocus = true)
		infoItem("server_version", R.string.je_about_server_version, serverCaption, onClick = (serverVersion as? LoadState.Error)?.retry)
		item {
			val caption = when (val loaded = release) {
				LoadState.Loading -> stringResource(R.string.je_about_loading)
				is LoadState.Error -> stringResource(R.string.je_about_release_unavailable)
				is LoadState.Ready -> loaded.value?.let { it.name ?: it.tagName } ?: stringResource(R.string.je_about_release_unavailable)
			}
			val available = (release as? LoadState.Ready)?.value != null
			SettingsNav(
				title = stringResource(R.string.je_about_release_notes),
				caption = caption,
				enabled = available,
				onClick = { router.push(AboutRoutes.RELEASE_NOTES) },
				modifier = Modifier.focusKey("je_about_release_notes"),
			)
		}

		item { SettingsSection(overline = stringResource(R.string.je_about_seerr)) }
		item(key = "seerr") { SeerrLinkRow(state) }
		if (SeerrLink.importOffered(SeerrLink.kind(state), state.isAdmin)) {
			item(key = "seerr_import") { SeerrImportRow() }
		}

		// A long list: a section caption, not a paragraph in a row
		item(key = "features") {
			ListSection(
				overlineContent = { Text(stringResource(R.string.je_about_features).uppercase()) },
				headingContent = {},
				captionContent = { Text(features) },
			)
		}

		if (state.isAdmin) {
			item { SettingsSection(overline = stringResource(R.string.je_about_admin)) }

			infoItem("host_compat", R.string.je_about_compat, compatCaption, onClick = (hostCompat as? LoadState.Error)?.retry)

			// Only when there is something new; loading and errors show nothing (it's a marker, not diagnostics)
			val newSettings = (whatsNew as? LoadState.Ready)?.value?.newSettings.orEmpty()
			if (newSettings.isNotEmpty()) {
				whatsNewItems(newSettings, preview = AboutRoutes.WHATS_NEW_PREVIEW, onShowAll = { router.push(AboutRoutes.WHATS_NEW) })
			}
		}
	}
}

/**
 * The "New in this plugin version" section: a heading, the new settings (the first [preview] of them, with a
 * "Show all" row, when given) and a Dismiss row. Dismissing is server-wide, so it's only ever done from that row.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.whatsNewItems(
	newSettings: Map<String, String>,
	preview: Int? = null,
	onShowAll: () -> Unit = {},
) {
	val sorted = WhatsNewSettings.sorted(newSettings)
	val shown = if (preview != null) sorted.take(preview) else sorted

	item(key = "whats_new_section") {
		ListSection(
			overlineContent = { Text(stringResource(R.string.je_about_whats_new_section).uppercase()) },
			headingContent = {},
			captionContent = { Text(pluralStringResource(R.plurals.je_about_whats_new_section_caption, sorted.size, sorted.size)) },
		)
	}
	if (preview == null) item(key = "whats_new_dismiss") { WhatsNewDismissButton() }
	items(shown, key = { "whats_new_${it.first}" }) { (key, version) ->
		ListButton(
			headingContent = { Text(WhatsNewSettings.label(key)) },
			captionContent = { Text(stringResource(R.string.je_about_whats_new_since, version, key)) },
			onClick = copyAction(ClipData.newPlainText(key, key)),
			modifier = Modifier.focusKey("je_about_whats_new_$key"),
		)
	}
	if (preview != null) {
		if (sorted.size > shown.size) {
			item(key = "whats_new_all") {
				SettingsNav(
					title = stringResource(R.string.je_about_whats_new_show_all, sorted.size),
					onClick = onShowAll,
					modifier = Modifier.focusKey("je_about_whats_new_all"),
				)
			}
		}
		item(key = "whats_new_dismiss") { WhatsNewDismissButton() }
	}
}

/** Calls `whats-new/dismiss`; on success every screen showing the list reloads (and the section disappears). */
@Composable
private fun WhatsNewDismissButton() {
	val aboutRepository = koinInject<AboutRepository>()
	val scope = rememberCoroutineScope()
	var running by remember { mutableStateOf(false) }
	var failure by remember { mutableStateOf<String?>(null) }
	val generic = stringResource(R.string.je_error_generic)

	ListButton(
		leadingContent = { Icon(painterResource(R.drawable.je_ic_catchup_check), contentDescription = null) },
		headingContent = { Text(stringResource(R.string.je_about_whats_new_dismiss)) },
		captionContent = {
			val failed = failure
			Text(
				when {
					running -> stringResource(R.string.je_about_whats_new_dismissing)
					failed != null -> stringResource(R.string.je_about_whats_new_dismiss_failed, failed)
					else -> stringResource(R.string.je_about_whats_new_dismiss_caption)
				}
			)
		},
		onClick = {
			if (running) return@ListButton
			running = true
			failure = null
			scope.launch {
				attempt { aboutRepository.dismissWhatsNew() }
					.onFailure { failure = it.serverMessage ?: it.message ?: generic }
				running = false
			}
		},
		modifier = Modifier.focusKey("je_about_whats_new_dismiss"),
	)
}

/** All new plugin settings, with the Dismiss row first so it's reachable without scrolling the whole list. */
@Composable
fun WhatsNewSettingsScreen() {
	val enhancedRepository = koinInject<EnhancedRepository>()
	val aboutRepository = koinInject<AboutRepository>()
	val state by enhancedRepository.state.collectAsState()
	val revision by aboutRepository.whatsNewRevision.collectAsState()
	val router = LocalRouter.current
	val whatsNew = rememberLoad(state.isAdmin, revision) { if (state.isAdmin) aboutRepository.whatsNew() else null }
	val newSettings = (whatsNew as? LoadState.Ready)?.value?.newSettings.orEmpty()

	// Dismissed (here or elsewhere): nothing left to list
	LaunchedEffect(whatsNew) {
		if (whatsNew is LoadState.Ready && newSettings.isEmpty()) router.back()
	}

	SettingsColumn {
		item { SettingsSection(overline = stringResource(R.string.je_about_title), heading = stringResource(R.string.je_about_whats_new)) }
		item { loadCaption(whatsNew)?.let { SettingsIntro(it) } }
		if (newSettings.isNotEmpty()) whatsNewItems(newSettings)
	}
}

/**
 * The Seerr row: "Linked as <Seerr user>" (the name needs the admin-only user list, so others see "Linked to your
 * Seerr account"), "Not linked", "Seerr unreachable" or "Seerr disabled", each with the reason as a sentence. When a
 * second look can change anything, selecting the row checks again (the automatic check runs only once per session).
 */
@Composable
private fun SeerrLinkRow(state: EnhancedState) {
	val manager = koinInject<SeerrLinkManager>()
	val scope = rememberCoroutineScope()
	val kind = SeerrLink.kind(state)
	var checking by remember { mutableStateOf(false) }
	var displayName by remember(state.seerr.jellyseerrUserId) { mutableStateOf<String?>(null) }
	LaunchedEffect(state.seerr.jellyseerrUserId, state.isAdmin) {
		displayName = manager.linkedDisplayName(state)
	}

	val reason = SeerrErrors.forLink(kind, state.isAdmin).text.resolve()
	val status = when (kind) {
		SeerrLinkKind.LINKED -> when {
			displayName != null -> stringResource(
				if (state.seerrAdvanced) R.string.je_about_seerr_linked_as_advanced else R.string.je_about_seerr_linked_as,
				displayName.orEmpty(),
			)
			state.seerrAdvanced -> stringResource(R.string.je_about_seerr_linked_advanced)
			else -> stringResource(R.string.je_about_seerr_linked)
		}
		SeerrLinkKind.OFF, SeerrLinkKind.BLOCKED -> stringResource(R.string.je_about_seerr_disabled, reason)
		SeerrLinkKind.UNREACHABLE -> stringResource(R.string.je_about_seerr_unreachable, reason)
		SeerrLinkKind.UNLINKED, SeerrLinkKind.NO_USER, SeerrLinkKind.UNKNOWN -> stringResource(R.string.je_about_seerr_not_linked, reason)
	}
	val canCheck = kind != SeerrLinkKind.LINKED && kind != SeerrLinkKind.OFF && kind != SeerrLinkKind.BLOCKED
	val caption = when {
		checking -> stringResource(R.string.je_seerr_link_checking)
		canCheck -> stringResource(R.string.je_about_seerr_check_again, status)
		else -> status
	}
	val heading = stringResource(R.string.je_about_seerr)

	ListButton(
		headingContent = { Text(heading) },
		captionContent = { Text(caption) },
		onClick = if (canCheck) {
			{
				if (!checking) {
					checking = true
					// The state reloads when the link changed, which updates this row
					scope.launch {
						manager.recheck()
						checking = false
					}
				}
			}
		} else copyAction(ClipData.newPlainText(heading, caption)),
		modifier = Modifier.focusKey("je_about_seerr"),
	)
}

/**
 * Administrators: "Import Jellyfin users into Seerr" (`POST jellyseerr/import-users`), so nobody has to sign in to Seerr.
 * The caption tells the outcome ("3 users imported into Seerr.") or the server's refusal (it allows one run per 30 s).
 */
@Composable
private fun SeerrImportRow() {
	val manager = koinInject<SeerrLinkManager>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var running by remember { mutableStateOf(false) }
	var outcome by remember { mutableStateOf<String?>(null) }

	ListButton(
		headingContent = { Text(stringResource(R.string.je_seerr_link_import)) },
		captionContent = {
			Text(
				when {
					running -> stringResource(R.string.je_seerr_link_importing)
					else -> outcome ?: stringResource(R.string.je_seerr_link_import_caption)
				}
			)
		},
		onClick = {
			if (running) return@ListButton
			running = true
			outcome = null
			scope.launch {
				outcome = attempt { manager.importUsers() }.fold(
					onSuccess = { SeerrLinkTexts.importResult(context, it) },
					onFailure = { SeerrLinkTexts.importFailure(context, it) },
				)
				running = false
			}
		},
		modifier = Modifier.focusKey("je_about_seerr_import"),
	)
}

/** A read-only info row; selecting it copies the value, or runs [onClick] (e.g. a retry) when given. */
private fun androidx.compose.foundation.lazy.LazyListScope.infoItem(
	key: String,
	title: Int,
	caption: String,
	icon: Int? = null,
	onClick: (() -> Unit)? = null,
	initialFocus: Boolean = false,
) = item(key = key) {
	val heading = stringResource(title)
	ListButton(
		leadingContent = icon?.let { { Icon(painterResource(it), contentDescription = null) } },
		headingContent = { Text(heading) },
		captionContent = { Text(caption) },
		onClick = onClick ?: copyAction(ClipData.newPlainText(heading, caption)),
		modifier = Modifier.focusKey("je_about_$key", initialFocus = initialFocus),
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
			SettingsSection(
				overline = stringResource(R.string.je_about_release_notes),
				heading = loaded?.name ?: loaded?.tagName ?: stringResource(R.string.je_about_title),
			)
		}
		item {
			SettingsIntro(
				when {
					release == LoadState.Loading -> stringResource(R.string.je_about_loading)
					loaded == null -> stringResource(R.string.je_about_release_unavailable)
					else -> loaded.tagName.orEmpty()
				}
			)
		}

		itemsIndexed(lines) { index, line ->
			ListButton(
				headingContent = { Text(line, fontSize = JeType.body.fontSize, lineHeight = JeType.body.lineHeight) },
				onClick = {},
				modifier = Modifier.focusKey("je_release_line_$index"),
			)
		}
	}
}
