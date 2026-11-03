package org.jellyfin.androidtv.enhanced.requests

import org.jellyfin.androidtv.enhanced.data.EnhancedState

/** The tabs of the Requests page, in display order. */
enum class RequestsTab {
	REQUESTS,
	WATCHLIST,
	QUEUE,
	HISTORY,
	ISSUES;

	companion object {
		/** Polling never runs faster than this, whatever the server says. */
		private const val MIN_POLL_SECONDS = 5

		/**
		 * The tabs the server's settings turn on for this user. These only decide what to show; the server still
		 * answers each call according to the user's rights (an admin-only history comes back `visible:false`).
		 */
		fun available(state: EnhancedState): List<RequestsTab> {
			val config = state.config
			return buildList {
				add(REQUESTS)
				add(WATCHLIST)
				if (config.showDownloadsInRequests) add(QUEUE)
				if (config.downloadsShowHistory && (!config.downloadsHistoryAdminOnly || state.isAdmin)) add(HISTORY)
				if (config.downloadsPageShowIssues) add(ISSUES)
			}
		}

		/** Whether to offer the manual watchlist sync: the server only runs it for admins with the sync turned on. */
		fun canSyncWatchlist(state: EnhancedState) = state.isAdmin && state.config.syncJellyseerrWatchlist

		/** Seconds between queue refreshes while the queue is on screen, or null when polling is off. */
		fun pollIntervalSeconds(state: EnhancedState): Int? =
			if (state.config.downloadsPagePollingEnabled) state.config.downloadsPollIntervalSeconds.coerceAtLeast(MIN_POLL_SECONDS)
			else null
	}
}
