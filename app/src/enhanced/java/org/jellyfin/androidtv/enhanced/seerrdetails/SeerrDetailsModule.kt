package org.jellyfin.androidtv.enhanced.seerrdetails

import org.jellyfin.androidtv.enhanced.issues.IssueDetailsContributor
import org.jellyfin.androidtv.enhanced.issues.IssueSeerrDetailsContributor
import org.jellyfin.androidtv.enhanced.issues.SeerrIssueRepository
import org.jellyfin.androidtv.enhanced.requests.RequestMoreAction
import org.jellyfin.androidtv.enhanced.ui.details.DetailsContributor
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrDetailsContributor
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Request More, issue reporting and the issue indicator. The richer Seerr details sections are part of
 * `SeerrDetailsFragment` itself and need nothing registered.
 */
val seerrDetailsModule = module {
	single { SeerrIssueRepository(get()) }
	single { RequestMoreAction(get(), get()) } bind DetailsContributor::class
	single { IssueDetailsContributor(get(), get()) } bind DetailsContributor::class
	single { IssueSeerrDetailsContributor(get(), get()) } bind SeerrDetailsContributor::class
}
