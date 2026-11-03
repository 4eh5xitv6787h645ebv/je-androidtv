package org.jellyfin.androidtv.enhanced.ui.design

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.dialog.DialogBase
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton

/**
 * The dialog panel (spec §6.14) on upstream's `DialogBase` (scrim, Back dismisses, fade): 560 dp wide,
 * [JeColors.surface], 16 dp corners, 24 dp padding, a 20 sp [title], an optional [body] caption, free [content]
 * and right-aligned [actions] ([JeButton]s, affirmative last). The dialog focuses its first focusable element, so put
 * the safe option first. Replaces the framework `AlertDialog`s.
 */
@Composable
fun JeDialog(
	visible: Boolean,
	onDismissRequest: () -> Unit,
	title: String,
	modifier: Modifier = Modifier,
	body: String? = null,
	actions: (@Composable RowScope.() -> Unit)? = null,
	content: (@Composable ColumnScope.() -> Unit)? = null,
) {
	DialogBase(visible = visible, onDismissRequest = onDismissRequest) {
		Column(
			modifier = modifier
				.width(560.dp)
				.background(JeColors.surface, JeShapes.dialog)
				.padding(24.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			Text(text = title, style = JeType.dialogTitle, maxLines = 3, overflow = TextOverflow.Ellipsis)
			if (body != null) Text(text = body, style = JeType.caption)
			content?.invoke(this)
			if (actions != null) {
				Row(
					modifier = Modifier
						.align(Alignment.End)
						.focusGroup(),
					horizontalArrangement = Arrangement.spacedBy(JeSpace.actionGap),
					verticalAlignment = Alignment.CenterVertically,
					content = actions,
				)
			}
		}
	}
}

/**
 * A yes/no question: Cancel then [confirmLabel]. [destructive] (Decline, Delete, Turn off) leaves the focus on
 * Cancel; otherwise the confirm button takes it. [onConfirm] runs before the dialog is dismissed.
 */
@Composable
fun JeConfirmDialog(
	visible: Boolean,
	title: String,
	confirmLabel: String,
	onConfirm: () -> Unit,
	onDismissRequest: () -> Unit,
	modifier: Modifier = Modifier,
	body: String? = null,
	destructive: Boolean = false,
	@DrawableRes confirmIcon: Int? = null,
	dismissLabel: String = stringResource(R.string.je_design_cancel),
) {
	val confirmFocus = remember { FocusRequester() }
	JeDialog(
		visible = visible,
		onDismissRequest = onDismissRequest,
		title = title,
		body = body,
		modifier = modifier,
		actions = {
			JeButton(text = dismissLabel, onClick = onDismissRequest)
			JeButton(
				text = confirmLabel,
				icon = confirmIcon,
				onClick = {
					onConfirm()
					onDismissRequest()
				},
				modifier = Modifier.focusRequester(confirmFocus),
			)
			if (!destructive) LaunchedEffect(Unit) { focusAfterDialog(confirmFocus) }
		},
	)
}

/**
 * Pick one or several of [options] (season pickers, the TVDB pick, translation language, hide scope) as upstream
 * list buttons with radio buttons or checkboxes. Single choice ([multi] false) confirms on the first press;
 * multiple choice toggles and confirms with Done. [selected] is the initial selection; the focus starts on its first
 * entry. [captions] (same size as [options]) adds a line under each option. Without [indicators] the single-choice
 * options carry no radio buttons, for a menu of actions (a row's "⋯" menu) rather than a setting.
 */
@Composable
fun JeChoiceDialog(
	visible: Boolean,
	title: String,
	options: List<String>,
	selected: Set<Int>,
	onConfirm: (Set<Int>) -> Unit,
	onDismissRequest: () -> Unit,
	modifier: Modifier = Modifier,
	multi: Boolean = false,
	body: String? = null,
	captions: List<String?>? = null,
	confirmLabel: String = stringResource(R.string.je_design_done),
	indicators: Boolean = true,
) {
	var checked by remember(visible, selected) { mutableStateOf(selected) }
	val initialFocus = remember { FocusRequester() }
	val focusIndex = selected.minOrNull()?.takeIf { it in options.indices } ?: 0
	JeDialog(
		visible = visible,
		onDismissRequest = onDismissRequest,
		title = title,
		body = body,
		modifier = modifier,
		actions = if (multi) {
			{
				JeButton(text = stringResource(R.string.je_design_cancel), onClick = onDismissRequest)
				JeButton(
					text = confirmLabel,
					enabled = checked.isNotEmpty(),
					onClick = {
						onConfirm(checked)
						onDismissRequest()
					},
				)
			}
		} else null,
	) {
		Column(
			modifier = Modifier
				.heightIn(max = 400.dp)
				.verticalScroll(rememberScrollState()),
		) {
			options.forEachIndexed { index, option ->
				val isChecked = index in checked
				ListButton(
					onClick = {
						if (multi) {
							checked = if (isChecked) checked - index else checked + index
						} else {
							onConfirm(setOf(index))
							onDismissRequest()
						}
					},
					headingContent = { Text(option) },
					captionContent = captions?.getOrNull(index)?.let { caption -> { Text(caption, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
					trailingContent = if (multi || indicators) {
						{ if (multi) Checkbox(checked = isChecked) else RadioButton(checked = isChecked) }
					} else null,
					modifier = if (index == focusIndex) Modifier.focusRequester(initialFocus) else Modifier,
				)
			}
		}
		LaunchedEffect(Unit) { focusAfterDialog(initialFocus) }
	}
}

/**
 * Ask for a line of text (a rename): a single-line field with [initialValue] (the cursor at its end) and a [hint]
 * shown while it is empty, then Cancel and [confirmLabel]. The field takes the focus, which opens the keyboard like
 * upstream's search field; the keyboard's action key confirms too. [maxLength] caps the input. [onConfirm] gets the
 * text as typed and runs before the dialog is dismissed.
 */
@Composable
fun JeTextInputDialog(
	visible: Boolean,
	title: String,
	initialValue: String,
	confirmLabel: String,
	onConfirm: (String) -> Unit,
	onDismissRequest: () -> Unit,
	modifier: Modifier = Modifier,
	body: String? = null,
	hint: String? = null,
	maxLength: Int = Int.MAX_VALUE,
	keyboardCapitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
) {
	var value by remember(visible, initialValue) {
		mutableStateOf(TextFieldValue(initialValue.take(maxLength), selection = TextRange(initialValue.take(maxLength).length)))
	}
	val fieldFocus = remember { FocusRequester() }
	val confirm = {
		onConfirm(value.text)
		onDismissRequest()
	}
	JeDialog(
		visible = visible,
		onDismissRequest = onDismissRequest,
		title = title,
		body = body,
		modifier = modifier,
		actions = {
			JeButton(text = stringResource(R.string.je_design_cancel), onClick = onDismissRequest)
			JeButton(text = confirmLabel, onClick = confirm)
		},
	) {
		val focus = rememberFocusedState()
		val color = if (focus.focused) JellyfinTheme.colorScheme.inputFocused else JellyfinTheme.colorScheme.input
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.border(2.dp, color, JeShapes.container)
				.padding(12.dp),
		) {
			if (value.text.isEmpty() && hint != null) Text(text = hint, style = JeType.body, color = JeColors.textTertiary, maxLines = 1)
			BasicTextField(
				value = value,
				onValueChange = { value = if (it.text.length <= maxLength) it else value },
				interactionSource = focus.interactionSource,
				textStyle = LocalTextStyle.current.merge(JeType.body).copy(color = JeColors.onSurface),
				cursorBrush = SolidColor(color),
				singleLine = true,
				keyboardOptions = KeyboardOptions.Default.copy(
					capitalization = keyboardCapitalization,
					imeAction = ImeAction.Done,
					showKeyboardOnFocus = true,
				),
				keyboardActions = KeyboardActions(onDone = { confirm() }),
				modifier = Modifier
					.fillMaxWidth()
					.focusRequester(fieldFocus),
			)
		}
		LaunchedEffect(Unit) { focusAfterDialog(fieldFocus) }
	}
}

/** `DialogBase` moves the focus into the dialog once its content shows; wait for that, then move it on. */
private suspend fun focusAfterDialog(requester: FocusRequester) {
	withFrameNanos { }
	withFrameNanos { }
	runCatching { requester.requestFocus() }
}
