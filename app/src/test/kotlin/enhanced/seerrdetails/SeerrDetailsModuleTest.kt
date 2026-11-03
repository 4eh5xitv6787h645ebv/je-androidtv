package org.jellyfin.androidtv.enhanced.seerrdetails

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.mockk
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.issues.IssueDetailsContributor
import org.jellyfin.androidtv.enhanced.issues.IssueSeerrDetailsContributor
import org.jellyfin.androidtv.enhanced.requests.RequestMoreAction
import org.jellyfin.androidtv.enhanced.ui.details.DetailsContributor
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrDetailsContributor
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class SeerrDetailsModuleTest : FunSpec({
	test("the module binds its contributors for the details hooks") {
		val koin = koinApplication {
			modules(
				module {
					single { mockk<EnhancedApi>(relaxed = true) }
					single { mockk<NavigationRepository>(relaxed = true) }
				},
				seerrDetailsModule,
			)
		}.koin

		koin.getAll<DetailsContributor>().map { it::class } shouldContainExactlyInAnyOrder
			listOf(RequestMoreAction::class, IssueDetailsContributor::class)
		koin.getAll<SeerrDetailsContributor>().map { it::class } shouldContainExactlyInAnyOrder
			listOf(IssueSeerrDetailsContributor::class)
	}
})
