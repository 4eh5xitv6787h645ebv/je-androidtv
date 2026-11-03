package org.jellyfin.androidtv.enhanced.reviews

import org.jellyfin.androidtv.enhanced.ui.details.DetailsContributor
import org.koin.dsl.bind
import org.koin.dsl.module

/** Koin bindings of the reviews feature. Needs the metadata module (for TMDB id resolution). */
val reviewsModule = module {
	single { ReviewsRepository(get(), get()) }
	single { ReviewsDetailsContributor(get(), get(), get()) } bind DetailsContributor::class
}
