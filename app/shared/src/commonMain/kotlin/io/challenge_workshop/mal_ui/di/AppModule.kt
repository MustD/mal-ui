package io.challenge_workshop.mal_ui.di

import io.challenge_workshop.mal_ui.animelist.AnimeListViewModel
import io.challenge_workshop.mal_ui.auth.MalSessionViewModel
import io.challenge_workshop.mal_ui.auth.StartupRedirect
import io.challenge_workshop.mal_ui.mal.HttpClientFactory
import io.challenge_workshop.mal_ui.mal.MAL_CLIENT_ID
import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.session.JsonTokenStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import kotlinx.serialization.json.Json
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * Everything shared by all four targets.
 *
 * `:core` stays Koin-free — no annotations, no module declarations, no `koin-core` dependency — so
 * `:server` and `./gradlew :core:allTests` are untouched by dependency injection existing at all.
 */
val appModule: Module = module {
    single<Clock> { Clock.System }

    // Lenient because MAL adds fields, and `ignoreUnknownKeys` is what stops a MAL-side addition
    // turning a stored Session into a corrupt blob on the next launch.
    single { Json { ignoreUnknownKeys = true; isLenient = true } }

    single { JsonTokenStore(kv = get(), json = get(), clock = get()) }

    single { HttpClientFactory.Default }

    // A `single`, and it matters: two repositories would mean two Ktor `AuthTokenHolder` caches over
    // one store, and therefore a refresh race that the plugin's own mutex cannot see.
    single {
        MalSessionRepository(
            store = get(),
            clock = get(),
            // The build-time default only. A Client ID the user typed is remembered per device and
            // replaces this in `restore()`; with neither, the sign-in screen prompts for one. This is
            // the only place the generated constant is read — `:core` keeps `clientId` a required
            // parameter so its own tests cannot pick up whatever this machine's build was configured
            // with.
            initialConfig = MalAuthConfig(clientId = MAL_CLIENT_ID),
            clientFactory = get(),
        )
    }

    // Resolved with `koinViewModel()` from `App()`. A `viewModel` rather than a `single`, so it is
    // scoped to the composition's ViewModelStore like any other ViewModel; everything durable it
    // touches lives in the repository singleton above, so being recreated costs nothing.
    viewModel { MalSessionViewModel(repository = get(), startupRedirect = get()) }

    // Also a `viewModel`, and it takes the repository rather than a client of its own: the pager it
    // builds must ride the one authenticated `HttpClient` that owns refresh. The store is the same
    // singleton the repository writes the Session to — the Layout is its fourth record.
    viewModel { AnimeListViewModel(repository = get(), store = get()) }
}

/**
 * The per-target half of the graph: whatever `KeyValueStore` this platform can actually open, and
 * whichever [StartupRedirect] it can be launched with.
 *
 * A `Module` rather than a `expect fun platformKeyValueStore(namespace)` because the Android
 * implementation needs a `Context`, and a Koin definition is the one place that reliably has one.
 */
expect val platformModule: Module

/**
 * Starts Koin. Called by every entry point, so the four of them cannot drift.
 *
 * @param extra additional modules, used by tests to override a binding.
 * @param declaration runs before the modules are loaded. Android uses it for `androidContext(this)`,
 * which has to happen before anything resolves a `Context`.
 */
fun initKoin(
    extra: List<Module> = emptyList(),
    declaration: KoinApplication.() -> Unit = {},
): KoinApplication = startKoin {
    declaration()
    modules(listOf(appModule, platformModule) + extra)
}
