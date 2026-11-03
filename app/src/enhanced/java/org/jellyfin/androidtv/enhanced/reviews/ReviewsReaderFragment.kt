package org.jellyfin.androidtv.enhanced.reviews

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.metadata.MetadataDialogPanel
import org.jellyfin.androidtv.enhanced.metadata.displayMessage
import org.jellyfin.androidtv.enhanced.metadata.showComposeDialog
import org.jellyfin.androidtv.enhanced.ui.design.JeColors
import org.jellyfin.androidtv.enhanced.ui.design.JeShapes
import org.jellyfin.androidtv.enhanced.ui.design.JeSpace
import org.jellyfin.androidtv.enhanced.ui.design.JeType
import org.jellyfin.androidtv.enhanced.ui.design.LoadStateContent
import org.jellyfin.androidtv.enhanced.ui.design.LoadingRow
import org.jellyfin.androidtv.enhanced.ui.design.PageScaffold
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.navigation.fragmentDestination
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * A full-screen reader for a title's reviews: one review at a time in large type, D-pad left/right for the previous
 * or next review (more TMDB pages load as you reach the end), up/down to scroll a long one, Back to return. When the
 * server translates reviews, OK translates the review (asking for the language the first time) and toggles back to
 * the original; holding OK, or Menu, picks another language.
 */
class ReviewsReaderFragment : Fragment() {
	companion object {
		private const val ARG_MEDIA_TYPE = "media_type"
		private const val ARG_KEY = "key"
		private const val ARG_TMDB_ID = "tmdb_id"
		private const val ARG_TITLE = "title"
		private const val ARG_USER = "include_user"
		private const val ARG_TMDB = "include_tmdb"
		private const val ARG_ANILIST_ID = "anilist_id"
		private const val ARG_START = "start"

		fun destination(target: ReviewTarget, start: Int) = fragmentDestination<ReviewsReaderFragment> {
			putString(ARG_MEDIA_TYPE, target.mediaType)
			putString(ARG_KEY, target.key)
			putInt(ARG_TMDB_ID, target.tmdbId)
			putString(ARG_TITLE, target.title)
			putBoolean(ARG_USER, target.includeUser)
			putBoolean(ARG_TMDB, target.includeTmdb)
			putInt(ARG_ANILIST_ID, target.anilistId ?: 0)
			putInt(ARG_START, start)
		}
	}

	private val repository by inject<ReviewsRepository>()
	private val translator by inject<ReviewTranslator>()
	private val targetStore by inject<TranslationTargetStore>()

	private val target by lazy {
		val args = requireArguments()
		ReviewTarget(
			mediaType = args.getString(ARG_MEDIA_TYPE).orEmpty(),
			key = args.getString(ARG_KEY).orEmpty(),
			tmdbId = args.getInt(ARG_TMDB_ID),
			title = args.getString(ARG_TITLE).orEmpty(),
			includeUser = args.getBoolean(ARG_USER),
			includeTmdb = args.getBoolean(ARG_TMDB),
			anilistId = args.getInt(ARG_ANILIST_ID).takeIf { it > 0 },
		)
	}

	private fun toast(failure: TranslationFailure) {
		val context = context ?: return
		val message = when (failure) {
			TranslationFailure.Disabled -> context.getString(R.string.je_reviews_translate_disabled)
			TranslationFailure.ServiceDown -> context.getString(R.string.je_reviews_translate_unavailable)
			is TranslationFailure.Other -> context.getString(
				R.string.je_reviews_translate_failed,
				failure.message ?: context.getString(R.string.je_error_generic),
			)
		}
		Toast.makeText(context, message, Toast.LENGTH_LONG).show()
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		var state by remember { mutableStateOf<LoadState<ReviewsData>>(LoadState.Loading) }
		// Survives a recreation (rotation, process death): the reader reopens on the review that was showing
		var index by rememberSaveable { mutableIntStateOf(arguments?.getInt(ARG_START) ?: 0) }
		var loadingMore by remember { mutableStateOf(false) }
		val scope = rememberCoroutineScope()

		// Translation: offered only when the server can
		var canTranslate by remember { mutableStateOf(false) }
		val translations = remember {
			ReaderTranslations(translator, scope, targetStore.get()) { failure ->
				if (failure == TranslationFailure.Disabled) canTranslate = false
				toast(failure)
			}
		}
		var refocus by remember { mutableIntStateOf(0) }

		fun load() {
			state = LoadState.Loading
			scope.launch {
				state = try {
					val data = repository.load(target)
					val failure = data.firstError
					// A restored index may point past the first page that a fresh load brings back: keep it on a real review
					index = index.coerceIn(0, (data.entries.size - 1).coerceAtLeast(0))
					if (data.entries.isEmpty() && failure != null) LoadState.Error(failure.message, ::load) else LoadState.Ready(data)
				} catch (err: CancellationException) {
					throw err
				} catch (err: Exception) {
					Timber.w(err, "Failed to load reviews")
					LoadState.Error(err.displayMessage(), ::load)
				}
			}
		}

		fun move(delta: Int) {
			val data = (state as? LoadState.Ready)?.value ?: return
			val next = index.coerceIn(0, (data.entries.size - 1).coerceAtLeast(0)) + delta
			if (next in data.entries.indices) {
				index = next
			} else if (next == data.entries.size && data.hasMoreTmdb && !loadingMore) {
				loadingMore = true
				scope.launch {
					try {
						val more = repository.loadMoreTmdb(target, data)
						state = LoadState.Ready(more)
						if (next in more.entries.indices) index = next
					} catch (err: CancellationException) {
						throw err
					} catch (err: Exception) {
						Timber.w(err, "Failed to load more TMDB reviews")
					} finally {
						loadingMore = false
					}
				}
			}
		}

		fun pickLanguage(then: (String) -> Unit) {
			showComposeDialog(this@ReviewsReaderFragment, alignment = Alignment.Center) { dismiss ->
				TranslationLanguageDialog(
					translator = translator,
					current = translations.language,
					onPick = { code ->
						dismiss()
						refocus++
						targetStore.set(code)
						translations.changeLanguage(code)
						then(code)
					},
					onFailure = { failure ->
						dismiss()
						refocus++
						if (failure == TranslationFailure.Disabled) canTranslate = false
						toast(failure)
					},
				)
			}
		}

		LaunchedEffect(Unit) { load() }
		LaunchedEffect(Unit) {
			canTranslate = try {
				repository.translationAvailable()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to read the review translation settings")
				false
			}
		}

		val loaded = (state as? LoadState.Ready)?.value?.entries
		PageScaffold(
			title = target.title,
			subtitle = loaded?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.je_reviews_position, index.coerceIn(it.indices) + 1, it.size) },
			showToolbar = false,
		) {
			LoadStateContent(state, empty = { it.entries.isEmpty() }, emptyMessage = stringResource(R.string.je_reviews_none)) { data ->
					val entry = data.entries[index.coerceIn(data.entries.indices)]
					val translation = translations.shown(entry)
					val translatable = canTranslate && (entry.content.isNotBlank() || entry.summary != null)
					ReviewPage(
						entry = entry,
						translation = translation,
						translationStatus = when {
							!translatable -> TranslationStatus.UNAVAILABLE
							translations.translating == entry -> TranslationStatus.TRANSLATING
							translation != null -> TranslationStatus.SHOWN
							else -> TranslationStatus.ORIGINAL
						},
						languageName = translations.language?.let(::translationLanguageName),
						refocus = refocus,
						onMove = ::move,
						onTranslate = { translations.toggle(entry) { pickLanguage { translations.translate(entry, it) } } },
						onChangeLanguage = { pickLanguage { translations.translate(entry, it) } },
					)
			}
		}
	}
}

/** Where the review on screen stands with translation. */
private enum class TranslationStatus { UNAVAILABLE, ORIGINAL, TRANSLATING, SHOWN }

@Composable
private fun ReviewPage(
	entry: ReviewEntry,
	translation: TranslatedReview?,
	translationStatus: TranslationStatus,
	languageName: String?,
	refocus: Int,
	onMove: (Int) -> Unit,
	onTranslate: () -> Unit,
	onChangeLanguage: () -> Unit,
) {
	val scroll = rememberScrollState()
	val scope = rememberCoroutineScope()
	val focus = remember { FocusRequester() }
	val canTranslate = translationStatus != TranslationStatus.UNAVAILABLE
	// OK acts on key up, so a hold (picking the language) can be told from a press and the picker opens only once
	// OK is released: opened mid-hold, its focused entry would take the key repeats and the release as a click.
	// Only a press that started here counts.
	var okDown by remember { mutableStateOf(false) }
	var okHeld by remember { mutableStateOf(false) }

	// A new review starts at the top
	LaunchedEffect(entry) { scroll.scrollTo(0) }
	LaunchedEffect(refocus) { runCatching { focus.requestFocus() } }

	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, bottom = JeSpace.pageBottom)
			.focusRequester(focus)
			.onPreviewKeyEvent { event ->
				val code = event.key.nativeKeyCode
				if (canTranslate && code in OK_KEYS) {
					when (event.type) {
						KeyEventType.KeyDown -> if (event.nativeKeyEvent.repeatCount == 0) {
							okDown = true
							okHeld = false
						} else {
							okHeld = true
						}

						KeyEventType.KeyUp -> if (okDown) {
							okDown = false
							if (okHeld) onChangeLanguage() else onTranslate()
						}
					}
					return@onPreviewKeyEvent true
				}
				if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
				when (code) {
					KeyEvent.KEYCODE_DPAD_LEFT -> onMove(-1)
					KeyEvent.KEYCODE_DPAD_RIGHT -> onMove(1)
					KeyEvent.KEYCODE_DPAD_UP -> scope.launch { scroll.animateScrollBy(-SCROLL_STEP) }
					KeyEvent.KEYCODE_DPAD_DOWN -> scope.launch { scroll.animateScrollBy(SCROLL_STEP) }
					KeyEvent.KEYCODE_MENU -> if (canTranslate) onChangeLanguage() else return@onPreviewKeyEvent false
					else -> return@onPreviewKeyEvent false
				}
				true
			}
			.focusable(),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			Text(
				text = when {
					entry.isOwn -> stringResource(R.string.je_reviews_you)
					entry.author.isBlank() -> stringResource(R.string.je_reviews_anonymous)
					else -> entry.author
				},
				style = JeType.sectionHeader,
			)
			ReviewRating(entry, 22.dp)
			val meta = listOfNotNull(
				reviewSourceName(entry.source),
				reviewDate(entry.date),
				if (entry.votes > 0) pluralStringResource(R.plurals.je_reviews_anilist_helpful, entry.votes, entry.helpful, entry.votes) else null,
			).joinToString(" · ")
			if (meta.isNotEmpty()) Text(text = meta, style = JeType.meta, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
			if (canTranslate) TranslateChip(translationStatus)
		}
		if (translation != null) {
			Text(
				text = listOfNotNull(
					languageName?.let { stringResource(R.string.je_reviews_translated_to, it) },
					stringResource(R.string.je_reviews_translated_partly).takeIf { translation.truncated },
				).joinToString(" · "),
				style = JeType.caption,
			)
		}
		Box(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth(),
		) {
			Column(
				modifier = Modifier
					.widthIn(max = 1100.dp)
					.verticalScroll(scroll),
				verticalArrangement = Arrangement.spacedBy(16.dp),
			) {
				val summary = if (translation != null) translation.summary ?: entry.summary else entry.summary
				if (summary != null) Text(text = summary, style = ReadingStyle.copy(fontWeight = FontWeight.W500, color = JeColors.onSurface))
				Text(text = translation?.content ?: entry.content, style = ReadingStyle)
			}
		}
		Text(
			text = stringResource(if (canTranslate) R.string.je_reviews_reader_hint_translate else R.string.je_reviews_reader_hint),
			style = JeType.caption,
			color = JeColors.textTertiary,
		)
	}
}

/** Review text in the reader: read from across the room, so larger than body text (20 sp, airy lines). */
private val ReadingStyle = JeType.bodyLarge.copy(fontSize = JeType.dialogTitle.fontSize, lineHeight = JeType.dialogTitle.fontSize * 1.6f)

/** The Translate action's state next to the review's details; OK on the page acts on it. */
@Composable
private fun TranslateChip(status: TranslationStatus) {
	// Pill-shaped like a chip (OK on the page presses it), but never focused itself
	Row(
		modifier = Modifier
			.height(30.dp)
			.border(1.dp, JeColors.outline, JeShapes.pill)
			.padding(horizontal = 13.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Icon(painterResource(R.drawable.je_ic_reviews_translate), null, tint = JeColors.onSurface, modifier = Modifier.size(16.dp))
		Text(
			text = stringResource(
				when (status) {
					TranslationStatus.TRANSLATING -> R.string.je_reviews_translating
					TranslationStatus.SHOWN -> R.string.je_reviews_show_original
					else -> R.string.je_reviews_translate
				}
			),
			style = JeType.chip,
			color = JeColors.onSurface,
		)
	}
}

/**
 * Pick the language to translate reviews into: the server's targets by name, the remembered (or the device's)
 * language first focused. A failure to read the list closes the dialog with [onFailure].
 */
@Composable
private fun TranslationLanguageDialog(
	translator: ReviewTranslator,
	current: String?,
	onPick: (String) -> Unit,
	onFailure: (TranslationFailure) -> Unit,
) {
	var languages by remember { mutableStateOf<List<String>?>(null) }
	val initialFocus = remember { FocusRequester() }
	val listState = rememberLazyListState()

	LaunchedEffect(Unit) {
		try {
			val codes = translator.languages()
			languages = codes.sortedBy { translationLanguageName(it).lowercase() }
			if (codes.isEmpty()) onFailure(TranslationFailure.ServiceDown)
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Failed to load the translation languages")
			onFailure(TranslationFailure.of(err))
		}
	}

	MetadataDialogPanel(title = stringResource(R.string.je_reviews_translate_to)) {
		val list = languages
		if (list == null) {
			LoadingRow()
		} else {
			val selected = current?.takeIf { it in list } ?: defaultTranslationTarget(list) ?: list.firstOrNull()
			LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false)) {
				items(list, key = { it }) { code ->
					ListButton(
						headingContent = { Text(translationLanguageName(code)) },
						trailingContent = if (code == current) {
							{ Text(stringResource(R.string.je_reviews_translate_current), style = JeType.caption) }
						} else null,
						onClick = { onPick(code) },
						modifier = if (code == selected) Modifier.focusRequester(initialFocus) else Modifier,
					)
				}
			}
			LaunchedEffect(list) {
				val position = list.indexOf(selected)
				if (position < 0) return@LaunchedEffect
				// A language further down isn't composed (so can't take focus) until it has been scrolled to
				listState.scrollToItem(position)
				withFrameNanos { }
				runCatching { initialFocus.requestFocus() }
			}
		}
	}
}

private val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

private const val SCROLL_STEP = 320f
