package org.jellyfin.androidtv.enhanced.catchup

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** What a run of per-item writes got done: [done] succeeded, [remaining] didn't (or weren't tried), [error] is the first failure. */
data class PlayedWriteResult(
	val done: List<String>,
	val remaining: List<String>,
	val error: Exception?,
)

/**
 * Per-item played-state writes for Catch Up. Each id is written on its own (never a parent folder), a few at a time,
 * and a run stops at the first failed batch so the caller knows exactly which ids are left. The writes are
 * idempotent, so a run can be repeated with [PlayedWriteResult.remaining] to finish it.
 */
object PlayedWrites {
	/** Writes in flight at once; big seasons would otherwise take one round trip per episode. */
	const val CONCURRENCY = 4

	suspend fun run(ids: List<String>, write: suspend (String) -> Unit): PlayedWriteResult = coroutineScope {
		val unique = ids.distinct()
		val done = mutableListOf<String>()
		var error: Exception? = null
		for (batch in unique.chunked(CONCURRENCY)) {
			val results = batch.map { id ->
				async {
					try {
						write(id)
						null
					} catch (err: Exception) {
						err
					}
				}
			}.awaitAll()
			batch.zip(results).forEach { (id, failure) -> if (failure == null) done += id else if (error == null) error = failure }
			if (error != null) break
		}
		val doneSet = done.toSet()
		PlayedWriteResult(done = done, remaining = unique.filterNot { it in doneSet }, error = error)
	}
}

/**
 * A Watched undo that unmarked only part of its episodes. [remaining] is the same action reduced to what's still
 * marked; it stays on the undo stack so pressing Undo again finishes the job.
 */
class CatchUpPartialUndoException(
	val remaining: CatchUpAction.Watched,
	val undone: Int,
	cause: Exception,
) : Exception(cause.message, cause)
