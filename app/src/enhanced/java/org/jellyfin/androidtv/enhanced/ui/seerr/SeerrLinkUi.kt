package org.jellyfin.androidtv.enhanced.ui.seerr

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrErrors
import org.jellyfin.androidtv.enhanced.seerr.SeerrImportResult
import org.jellyfin.androidtv.enhanced.seerr.SeerrLink
import org.jellyfin.androidtv.enhanced.seerr.SeerrLinkKind
import org.jellyfin.androidtv.enhanced.seerr.SeerrLinkManager
import org.jellyfin.androidtv.enhanced.seerr.SeerrText
import org.jellyfin.androidtv.enhanced.ui.secondaryTextColor
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.design.Tokens
import org.koin.compose.koinInject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/** [SeerrText] as a string. */
fun SeerrText.resolve(context: Context): String = when (this) {
	is SeerrText.Resource -> context.getString(id)
	is SeerrText.Server -> text
}

/** [SeerrText] as a string, in composition. */
@Composable
fun SeerrText.resolve(): String = when (this) {
	is SeerrText.Resource -> stringResource(id)
	is SeerrText.Server -> text
}

/** User-facing texts of the Seerr link and the admin import. */
object SeerrLinkTexts {
	/**
	 * A failed Seerr call as a full sentence: the link text for `unlinked` (never the server's technical line), the
	 * server's own text for other codes. Null when [err] isn't a plugin error with anything to say.
	 */
	fun error(context: Context, err: Throwable?, isAdmin: Boolean): String? =
		SeerrErrors.forError(err, isAdmin)?.text?.resolve(context)

	/** "3 users imported into Seerr." plus the first refusal, when Seerr refused some. */
	fun importResult(context: Context, result: SeerrImportResult): String {
		val head = if (result.usersImported > 0) {
			context.resources.getQuantityString(R.plurals.je_seerr_link_imported, result.usersImported, result.usersImported)
		} else {
			context.getString(R.string.je_seerr_link_imported_none)
		}
		val errors = result.errors.mapNotNull(SeerrErrors::sentence)
		if (errors.isEmpty()) return head
		return head + " " + context.resources.getQuantityString(R.plurals.je_seerr_link_import_errors, errors.size, errors.size, errors.first())
	}

	/** Why the import failed: the server's own text (throttling, Seerr not set up or unreachable) when it sent one. */
	fun importFailure(context: Context, err: Throwable): String {
		val api = err as? EnhancedApi.EnhancedApiException
		if (api?.status == 403) return context.getString(R.string.je_seerr_link_import_forbidden)
		val detail = ((api?.body as? JsonObject)?.get("errors") as? JsonArray)
			?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.let(SeerrErrors::sentence) }
		val reason = SeerrErrors.sentence(SeerrErrors.serverText(err))
			?: context.getString(
				R.string.je_seerr_link_import_failed,
				if (api != null && api.status > 0) context.getString(R.string.je_seerr_link_import_failed_status, api.status)
				else context.getString(R.string.je_error_generic),
			)
		return if (detail != null) "$reason $detail" else reason
	}
}

/** The admin import from places without room for a notice (row cards, search cards): the outcome goes to a toast. */
object SeerrLinkActions {
	/** One import at a time across the app; the server would refuse a second one within 30 s anyway. */
	private val running = AtomicBoolean(false)

	/** Import Jellyfin users into Seerr in [scope], toast the outcome, then [onDone] (reload what failed). */
	fun importUsers(scope: CoroutineScope, context: Context, manager: SeerrLinkManager, onDone: () -> Unit) {
		if (!running.compareAndSet(false, true)) return
		val appContext = context.applicationContext
		Toast.makeText(appContext, R.string.je_seerr_link_importing, Toast.LENGTH_SHORT).show()
		scope.launch {
			try {
				val result = manager.importUsers()
				Toast.makeText(appContext, SeerrLinkTexts.importResult(appContext, result), Toast.LENGTH_LONG).show()
				onDone()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Seerr user import failed")
				Toast.makeText(appContext, SeerrLinkTexts.importFailure(appContext, err), Toast.LENGTH_LONG).show()
			} finally {
				running.set(false)
			}
		}
	}
}

/**
 * The page-filling notice of a Seerr page the user can't use because of their Seerr link: the reason as a sentence
 * and the one action that can help. Administrators get "Import Jellyfin users into Seerr" where that is the fix;
 * everyone else gets "Check again" where a second look can change anything (never for blocked or Seerr off). The
 * button takes the initial focus.
 *
 * [unlinkedCall] is for a page whose Seerr call just said `unlinked` while the state still says linked: the notice
 * shows "unlinked" until the state (reloaded by [SeerrLinkManager.onUnlinkedError]) says otherwise. [onLinked] runs
 * when the link works afterwards (after an import or a check from here, or when the state turns linked by itself
 * after having been seen not linked); the Jellyfin Enhanced state has been reloaded by then.
 */
@Composable
fun SeerrLinkNotice(
	modifier: Modifier = Modifier,
	unlinkedCall: Boolean = false,
	onLinked: () -> Unit = {},
) {
	val manager = koinInject<SeerrLinkManager>()
	val enhancedRepository = koinInject<EnhancedRepository>()
	val state by enhancedRepository.state.collectAsState()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val stateKind = SeerrLink.kind(state)
	val kind = if (unlinkedCall && stateKind == SeerrLinkKind.LINKED) SeerrLinkKind.UNLINKED else stateKind
	val problem = SeerrErrors.forLink(kind, state.isAdmin)
	val canCheck = kind != SeerrLinkKind.OFF && kind != SeerrLinkKind.BLOCKED && kind != SeerrLinkKind.LINKED
	// Only a change *to* linked counts: a stale "linked" state must not reload the page into the same failure again
	var seenNotLinked by remember { mutableStateOf(false) }
	var everNotLinked by remember { mutableStateOf(false) }
	LaunchedEffect(stateKind) {
		if (stateKind != SeerrLinkKind.LINKED) {
			seenNotLinked = true
			everNotLinked = true
		} else if (seenNotLinked) {
			seenNotLinked = false
			onLinked()
		}
	}
	var busy by remember { mutableStateOf(false) }
	var outcome by remember { mutableStateOf<String?>(null) }
	val focus = remember { FocusRequester() }

	// The state reload after a successful import or check turns the kind to linked, which runs onLinked above. A page
	// whose call said unlinked while the state said linked all along never sees that change, so after the user's own
	// import or check it gets to try again.
	fun finish() {
		busy = false
		if (unlinkedCall && !everNotLinked && SeerrLink.kind(enhancedRepository.current) == SeerrLinkKind.LINKED) onLinked()
	}

	Column(
		modifier = modifier.widthIn(max = 720.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Text(text = problem.text.resolve(), color = Tokens.Color.colorGrey100, fontSize = 18.sp, textAlign = TextAlign.Center)

		when {
			problem.offerImport -> Button(
				onClick = {
					if (busy) return@Button
					busy = true
					outcome = null
					scope.launch {
						outcome = try {
							SeerrLinkTexts.importResult(context, manager.importUsers())
						} catch (err: CancellationException) {
							throw err
						} catch (err: Exception) {
							Timber.w(err, "Seerr user import failed")
							SeerrLinkTexts.importFailure(context, err)
						}
						finish()
					}
				},
				// Stays enabled while busy (clicks are ignored then) so the focus doesn't jump away
				modifier = Modifier.focusRequester(focus),
			) {
				Text(stringResource(if (busy) R.string.je_seerr_link_importing else R.string.je_seerr_link_import))
			}

			canCheck -> Button(
				onClick = {
					if (busy) return@Button
					busy = true
					outcome = null
					scope.launch {
						val fresh = manager.recheck()
						outcome = if (fresh == SeerrLinkKind.LINKED) {
							context.getString(R.string.je_seerr_link_now_linked)
						} else {
							context.getString(R.string.je_seerr_link_still_unlinked, SeerrErrors.forLink(fresh, state.isAdmin).text.resolve(context))
						}
						finish()
					}
				},
				// Stays enabled while busy (clicks are ignored then) so the focus doesn't jump away
				modifier = Modifier.focusRequester(focus),
			) {
				Text(stringResource(if (busy) R.string.je_seerr_link_checking else R.string.je_seerr_link_check_again))
			}
		}

		outcome?.let { Text(text = it, color = secondaryTextColor(), fontSize = 14.sp, textAlign = TextAlign.Center) }
	}

	if (problem.offerImport || canCheck) {
		LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
	}
}
