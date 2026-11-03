package org.jellyfin.androidtv.enhanced.subtitles

import android.graphics.Typeface
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.core.graphics.TypefaceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.management.errorMessage
import org.jellyfin.androidtv.enhanced.management.settings.EnhancedToggleRow
import org.jellyfin.androidtv.enhanced.management.settings.enhancedSettingsHeader
import org.jellyfin.androidtv.enhanced.management.settings.enhancedSettingsLoad
import org.jellyfin.androidtv.enhanced.management.settings.enhancedSettingsSection
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.ColorSwatch
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListMessage
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.ui.settings.screen.customization.subtitle.composable.SubtitleStylePreview
import org.jellyfin.design.Tokens
import org.koin.compose.koinInject
import timber.log.Timber
import kotlin.math.roundToInt

private val sizeNames = listOf(
	R.string.je_subtitles_size_tiny,
	R.string.je_subtitles_size_small,
	R.string.je_subtitles_size_normal,
	R.string.je_subtitles_size_large,
	R.string.je_subtitles_size_extra_large,
	R.string.je_subtitles_size_gigantic,
)

private val fontNames = listOf(
	R.string.je_subtitles_font_default,
	R.string.je_subtitles_font_noto_sans,
	R.string.je_subtitles_font_sans_serif,
	R.string.je_subtitles_font_typewriter,
	R.string.je_subtitles_font_roboto,
)

private val effectNames = listOf(
	R.string.je_subtitles_effect_auto,
	R.string.je_subtitles_effect_none_name,
	R.string.je_subtitles_effect_shadow,
	R.string.je_subtitles_effect_outline_name,
	R.string.je_subtitles_effect_outline_shadow,
)

/** A web preset's name; an index this client doesn't know is shown as the preset the mapping falls back to. */
@Composable
private fun presetName(names: List<Int>, index: Int, fallback: Int): String = stringResource(names.getOrElse(index) { names[fallback] })

/**
 * The "Subtitle style" page: the device-local "use my JE style" switch and a read-only preview of how the user's JE
 * subtitle appearance translates to this app's subtitle settings. Opening the page also re-syncs, so a change made in
 * the web client shows up here straight away.
 */
@Composable
fun SubtitlesSettingsScreen() {
	val enhancedRepository = koinInject<EnhancedRepository>()
	val sync = koinInject<SubtitleStyleSync>()
	val enhanced by enhancedRepository.state.collectAsState()
	val enabled by sync.enabled.collectAsState()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val router = LocalRouter.current

	var reload by remember { mutableIntStateOf(0) }
	var style by remember { mutableStateOf<LoadState<JeSubtitleStyle>>(LoadState.Loading) }
	var saving by remember { mutableStateOf(false) }

	LaunchedEffect(reload, enhanced.available) {
		if (!enhanced.available) return@LaunchedEffect
		style = LoadState.Loading
		style = try {
			LoadState.Ready(sync.refresh())
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Failed to load the Jellyfin Enhanced subtitle style")
			LoadState.Error(errorMessage(context, err)) { reload++ }
		}
	}

	fun toggle() {
		if (saving) return
		saving = true
		scope.launch {
			try {
				sync.setEnabled(!enabled)?.let { style = LoadState.Ready(it) }
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to apply the Jellyfin Enhanced subtitle style")
				Toast.makeText(context, errorMessage(context, err), Toast.LENGTH_LONG).show()
			} finally {
				saving = false
			}
		}
	}

	SettingsColumn {
		enhancedSettingsHeader(
			title = { stringResource(R.string.je_subtitles_title) },
			caption = { stringResource(R.string.je_subtitles_page_caption) },
		)

		if (!enhanced.available) {
			item { ListMessage { Text(stringResource(R.string.je_hub_unavailable)) } }
			return@SettingsColumn
		}

		val loaded = (style as? LoadState.Ready)?.value
		item(key = "sync") {
			EnhancedToggleRow(
				key = "subtitles_sync",
				title = stringResource(R.string.je_subtitles_sync),
				caption = stringResource(
					when {
						!enabled -> R.string.je_subtitles_sync_off
						loaded?.disabled == true -> R.string.je_subtitles_sync_paused
						else -> R.string.je_subtitles_sync_on
					}
				),
				checked = enabled,
				saving = saving,
				onToggle = ::toggle,
			)
		}

		val current = enhancedSettingsLoad(style) ?: return@SettingsColumn
		mappingItems(current)

		item(key = "native") {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.je_ic_subtitles), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.je_subtitles_open_native)) },
				captionContent = { Text(stringResource(R.string.je_subtitles_open_native_caption)) },
				onClick = { router.push(Routes.CUSTOMIZATION_SUBTITLES) },
				modifier = Modifier.focusKey("je_subtitles_native"),
			)
		}
	}
}

/** The preview and one read-only row per mapped value, each saying what the web shows and what this device uses. */
private fun LazyListScope.mappingItems(style: JeSubtitleStyle) {
	val native = SubtitleStyleMapper.map(style)
	val gaps = SubtitleStyleMapper.gaps(style)

	enhancedSettingsSection(
		title = { stringResource(R.string.je_subtitles_mapping) },
		caption = { stringResource(if (style.disabled) R.string.je_subtitles_disabled_note else R.string.je_subtitles_mapping_caption) },
	)

	item(key = "preview") { MappedPreview(native) }

	infoItem("size", R.string.je_subtitles_size) {
		val name = presetName(sizeNames, style.fontSizePreset, JeSubtitleStyle.DEFAULT_SIZE_PRESET)
		val text = if (SubtitleMappingGap.SIZE_CLAMPED in gaps) R.string.je_subtitles_size_clamped else R.string.je_subtitles_size_value
		stringResource(text, name, native.textSize.roundToInt())
	}
	colorItem("text_color", R.string.je_subtitles_text_color, native.textColor)
	colorItem("background_color", R.string.je_subtitles_background_color, native.backgroundColor)
	infoItem("effect", R.string.je_subtitles_effect) {
		val name = presetName(effectNames, style.textEffectPreset, JeSubtitleStyle.TEXT_EFFECT_AUTO)
		val text = when {
			SubtitleStyleMapper.alpha(native.strokeColor) == 0 -> R.string.je_subtitles_effect_none
			SubtitleMappingGap.SHADOW in gaps -> R.string.je_subtitles_effect_outline_approx
			else -> R.string.je_subtitles_effect_outline
		}
		stringResource(text, name)
	}
	infoItem("position", R.string.je_subtitles_position) {
		stringResource(R.string.je_subtitles_position_value, style.verticalPosition.coerceIn(0, 100), (native.offsetPosition * 100).roundToInt())
	}
	infoItem("horizontal", R.string.je_subtitles_horizontal) {
		if (SubtitleMappingGap.HORIZONTAL_POSITION in gaps) stringResource(R.string.je_subtitles_horizontal_unmapped, style.horizontalPosition.coerceIn(0, 100))
		else stringResource(R.string.je_subtitles_horizontal_centered)
	}
	infoItem("font", R.string.je_subtitles_font) {
		val name = presetName(fontNames, style.fontFamilyPreset, JeSubtitleStyle.DEFAULT_FONT_PRESET)
		if (SubtitleMappingGap.FONT in gaps) stringResource(R.string.je_subtitles_font_unmapped, name) else name
	}
}

/** Upstream's own subtitle preview, fed the mapped values and the device's text weight (which isn't synced). */
@OptIn(UnstableApi::class)
@Composable
private fun MappedPreview(native: NativeSubtitleStyle) {
	val context = LocalContext.current
	val userPreferences = koinInject<UserPreferences>()
	val weight = userPreferences[UserPreferences.subtitlesTextWeight]
	SubtitleStylePreview(
		text = stringResource(R.string.je_subtitles_preview),
		textColor = native.textColor,
		textSize = native.textSize,
		backgroundColor = native.backgroundColor,
		edgeType = if (SubtitleStyleMapper.alpha(native.strokeColor) > 0) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE,
		edgeColor = native.strokeColor,
		typeface = remember(weight) { TypefaceCompat.create(context, Typeface.DEFAULT, weight, false) },
		modifier = Modifier
			.background(Tokens.Color.colorBluegrey800, JellyfinTheme.shapes.large)
			.fillMaxWidth()
			.height(native.textSize * 3.dp)
			.clip(JellyfinTheme.shapes.large),
	)
	Spacer(Modifier.height(Tokens.Space.spaceSm))
}

/** A read-only row; focusable so the D-pad can scroll the page, selecting it does nothing. */
private fun LazyListScope.infoItem(key: String, @StringRes title: Int, trailing: (@Composable () -> Unit)? = null, caption: @Composable () -> String) =
	item(key = key) {
		ListButton(
			headingContent = { Text(stringResource(title)) },
			captionContent = { Text(caption()) },
			trailingContent = trailing,
			onClick = {},
			modifier = Modifier.focusKey("je_subtitles_$key"),
		)
	}

private fun LazyListScope.colorItem(key: String, @StringRes title: Int, argb: Int) =
	infoItem(key, title, trailing = { ColorSwatch(color = Color(argb)) }) {
		if (SubtitleStyleMapper.alpha(argb) == 0) stringResource(R.string.je_subtitles_transparent)
		else SubtitleStyleMapper.formatColor(argb)
	}
