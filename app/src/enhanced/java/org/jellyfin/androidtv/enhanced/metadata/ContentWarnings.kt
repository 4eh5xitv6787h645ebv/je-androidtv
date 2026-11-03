package org.jellyfin.androidtv.enhanced.metadata

import android.widget.Toast
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject
import timber.log.Timber

/**
 * The server's message for a failed call, when it sent one: the plugin's `message` field, or a bare JSON string body
 * (e.g. "DoesTheDogDie is not enabled."). Null otherwise, so callers fall back to a generic message.
 */
fun Throwable.displayMessage(): String? = when (this) {
	is EnhancedApi.EnhancedApiException -> serverMessage ?: (body as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
	else -> null
}

/**
 * The user's DoesTheDogDie triggers and the topic catalogue to pick them from. Each toggle is saved straight away
 * (through [MetadataRepository.saveTriggers], which keeps the rest of the document); a failed save reloads the
 * saved selection so the screen never shows a state the server doesn't have.
 */
@Stable
class TriggerPickerState(
	private val repository: MetadataRepository,
	private val scope: CoroutineScope,
	private val onSaveFailed: (String?) -> Unit,
) {
	var triggers: LoadState<Set<Int>> by mutableStateOf(LoadState.Loading)
		private set
	var catalog: LoadState<List<DddTopic>> by mutableStateOf(LoadState.Loading)
		private set

	val selected: Set<Int> get() = (triggers as? LoadState.Ready)?.value.orEmpty()

	fun loadTriggers() {
		triggers = LoadState.Loading
		scope.launch {
			triggers = try {
				LoadState.Ready(repository.triggers())
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to load content warning triggers")
				LoadState.Error(err.displayMessage(), ::loadTriggers)
			}
		}
	}

	fun loadCatalog() {
		catalog = LoadState.Loading
		scope.launch {
			catalog = try {
				LoadState.Ready(
					repository.contentWarningTopics().topics.sortedWith(
						compareBy<DddTopic> { it.group.lowercase() }.thenBy { it.category.lowercase() }.thenBy { it.name.lowercase() }
					)
				)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to load content warning topics")
				LoadState.Error(err.displayMessage(), ::loadCatalog)
			}
		}
	}

	fun toggle(id: Int) {
		val current = (triggers as? LoadState.Ready)?.value ?: return
		val updated = if (id in current) current - id else current + id
		triggers = LoadState.Ready(updated)
		scope.launch {
			try {
				repository.saveTriggers(updated)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to save content warning triggers")
				onSaveFailed(err.displayMessage())
				loadTriggers()
			}
		}
	}
}

@Composable
fun rememberTriggerPickerState(): TriggerPickerState {
	val repository = koinInject<MetadataRepository>()
	val scope = rememberCoroutineScope()
	val context = LocalContext.current
	return remember {
		TriggerPickerState(repository, scope) { message ->
			Toast.makeText(context, context.getString(R.string.je_metadata_save_failed, message ?: context.getString(R.string.je_error_generic)), Toast.LENGTH_LONG).show()
		}.apply { loadTriggers() }
	}
}

/** Loading/error lines for list screens built from [LoadState]s. */
private fun LazyListScope.loadStateItem(state: LoadState<*>) {
	when (state) {
		LoadState.Loading -> item { ListButton(onClick = {}, enabled = false, headingContent = { Text(stringResource(R.string.je_loading)) }) }
		is LoadState.Error -> item {
			ListButton(
				onClick = state.retry,
				headingContent = { Text(stringResource(R.string.je_retry)) },
				captionContent = { Text(state.message ?: stringResource(R.string.je_error_generic)) },
			)
		}

		is LoadState.Ready -> Unit
	}
}

/** The topic checkboxes, grouped by "Group / Category". */
fun LazyListScope.triggerPickerItems(state: TriggerPickerState) {
	loadStateItem(state.triggers)
	loadStateItem(state.catalog)
	val topics = (state.catalog as? LoadState.Ready)?.value ?: return
	if (state.triggers !is LoadState.Ready) return

	topics.groupBy { if (it.category.isNotBlank()) "${it.group} / ${it.category}" else it.group }.forEach { (group, groupTopics) ->
		item(key = "group:$group") { ListSection(headingContent = { Text(group) }) }
		items(groupTopics, key = { "topic:${it.id}" }) { topic ->
			ListButton(
				headingContent = { Text(capitalizeFirst(topic.name)) },
				trailingContent = { Checkbox(checked = topic.id in state.selected) },
				onClick = { state.toggle(topic.id) },
			)
		}
	}
}

/** Settings route: pick the content-warning topics to highlight. */
@Composable
fun ContentWarningTriggersSettingsScreen() {
	val state = rememberTriggerPickerState()
	LaunchedEffect(Unit) { state.loadCatalog() }

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.je_settings_title).uppercase()) },
				headingContent = { Text(stringResource(R.string.je_metadata_triggers_title)) },
				captionContent = { Text(stringResource(R.string.je_metadata_triggers_caption)) },
			)
		}
		triggerPickerItems(state)
	}
}

/**
 * The content warnings of one title. Shown on request only: some topics give away the plot. Topics matching the
 * user's triggers come first and are highlighted; "Choose triggers" switches the panel to the picker.
 */
@Composable
fun ContentWarningsDialog(initial: LoadState<DddEntry>, load: suspend () -> DddEntry) {
	val scope = rememberCoroutineScope()
	val pickerState = rememberTriggerPickerState()
	var entry by remember { mutableStateOf(initial) }
	var picking by remember { mutableStateOf(false) }
	val buttonFocus = remember { FocusRequester() }

	fun reload() {
		entry = LoadState.Loading
		scope.launch {
			entry = try {
				LoadState.Ready(load())
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to load content warnings")
				LoadState.Error(err.displayMessage(), ::reload)
			}
		}
	}

	MetadataDialogPanel(
		title = stringResource(if (picking) R.string.je_metadata_triggers_title else R.string.je_metadata_content_warnings),
		subtitle = stringResource(if (picking) R.string.je_metadata_triggers_caption else R.string.je_metadata_ddd_subtitle),
	) {
		Button(
			onClick = {
				picking = !picking
				if (picking && pickerState.catalog !is LoadState.Ready) pickerState.loadCatalog()
			},
			modifier = Modifier.focusRequester(buttonFocus),
		) {
			Text(stringResource(if (picking) R.string.je_metadata_ddd_done else R.string.je_metadata_ddd_choose))
		}

		LazyColumn {
			if (picking) {
				triggerPickerItems(pickerState)
				return@LazyColumn
			}

			val current = entry
			if (current is LoadState.Error) {
				item {
					ListButton(
						onClick = { reload() },
						headingContent = { Text(stringResource(R.string.je_retry)) },
						captionContent = { Text(current.message ?: stringResource(R.string.je_error_generic)) },
					)
				}
				return@LazyColumn
			}
			val ready = (current as? LoadState.Ready)?.value
			val triggers = (pickerState.triggers as? LoadState.Ready)?.value
			if (ready == null || triggers == null) {
				loadStateItem(if (ready == null) current else pickerState.triggers)
				return@LazyColumn
			}

			val warnings = ContentWarnings.of(ready, triggers)
			if (warnings.isEmpty) {
				item { ListButton(onClick = {}, headingContent = { Text(stringResource(R.string.je_metadata_ddd_none)) }) }
				return@LazyColumn
			}
			for ((title, topics) in listOf(
				R.string.je_metadata_ddd_triggered to warnings.triggered,
				R.string.je_metadata_ddd_clear to warnings.clear,
				R.string.je_metadata_ddd_others to warnings.others,
			)) {
				if (topics.isEmpty()) continue
				item(key = "section:$title") { ListSection(headingContent = { Text(stringResource(title)) }) }
				items(topics, key = { "$title:${it.id}" }) { topic ->
					ListButton(
						onClick = {},
						headingContent = { Text(capitalizeFirst(topic.name)) },
						captionContent = { Text(stringResource(R.string.je_metadata_ddd_votes, topic.percent, topic.votes)) },
					)
				}
			}
		}
	}

	LaunchedEffect(Unit) {
		if (initial is LoadState.Loading) reload()
		runCatching { buttonFocus.requestFocus() }
	}
}
