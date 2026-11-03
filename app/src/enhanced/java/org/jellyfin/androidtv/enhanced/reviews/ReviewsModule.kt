package org.jellyfin.androidtv.enhanced.reviews

import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.enhanced.ui.details.DetailsContributor
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

/** Koin bindings of the reviews feature. Needs the metadata module (for TMDB id resolution). */
val reviewsModule = module {
	single { ReviewsRepository(get(), get(), get()) }
	single<TranslationService> { EnhancedTranslationService(get()) }
	single {
		val sessionRepository = get<SessionRepository>()
		ReviewTranslator(get()) { sessionRepository.currentSession.value }
	}
	single { TranslationTargetStore(androidContext(), get()) }
	single { ReviewsDetailsContributor(get(), get(), get(), get()) } bind DetailsContributor::class
}
