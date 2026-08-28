# Screen State lives in `:core`

Status: accepted

`ScreenState` — what one of the four destinations needs in order to draw itself — is a UI-shaped type in `:core`, the
module `:server` depends on. **That reads as a layering mistake from outside**, which is the whole reason this file
exists: the justification is not reconstructible from the code.

The split this project draws is not "UI types up, domain types down". It is **the choice belongs to `:core` and the
drawing belongs to `:app:shared`** — exactly as for `AnimeListLayout` and `AnimeListSortOrder`, both of which are in
`:core` while their labels, their column counts and their `Dp` caps are in `:app:shared`. `ScreenState` is the same
shape of thing one level up: `ScreenStateSource` decides *which* screen and *what it says*, and `SessionRoute` decides
what that looks like. Nothing in `:core` imports Compose, and nothing in `ScreenState` is a `Dp`, a `Modifier` or a
lambda.

The operational half is `./gradlew :core:allTests`, which is the one task in this repository that runs a test on jvm,
js, wasmJs and android in one command. The mapping is the part of this feature that can be wrong the same way on four
Targets; the drawing is the part that can only be tested where a Compose harness already runs.

## Considered options

- **The source in `:app:shared`.** The obvious placement, and the one that costs the guarantee above: `:app:shared`'s
  tests are four separate Gradle invocations (`testAndroidHostTest`, `jvmTest`, `jsTest`, `wasmJsTest`) rather than one,
  and the module carries Compose, so nothing stops the next field being a `Dp`. It also puts the value one module away
  from `AnimeListState` and `SessionState`, which are two of its six inputs and which it nests verbatim.
- **The mapping as a composable.** A `when` inside `SessionRoute` that reads six flows and builds the screen inline.
  Rejected because it is untestable except by rendering: every assertion about *which* screen a state produces, or about
  what the four Signed Out Reasons say, becomes a Compose test on jvm — which is exactly what `SessionRouteTest` was, at
  1255 lines, standing up a repository, a store, a fake MAL engine and port 18040 per case.
- **`ScreenState` carrying its own actions.** One record per screen, callbacks included, which reads better at every
  call site. Rejected on Compose's skipping rule: a composable is skipped when its arguments are `equals` and stable,
  and a `data class` holding `() -> Unit` fields is neither. Every emission — a page landing, a spinner starting —
  would then recompose the whole signed-in screen including the grid. The actions live in separate `@Immutable` records
  in `:app:shared`, built once with `remember`.
- **`ScreenState` replacing `SessionState`.** Rejected: `SessionState` is the *Session's* own state, owned by
  `MalSessionRepository`, and is one of this value's inputs. Collapsing them would put an Anime List and a Layout inside
  the thing that models whether there are tokens.

## The known cost

**`ScreenStateSource` takes six flows.** The caller sees one; the constructor is wide, and that is the objection a
review will raise. It is defensible because each input has exactly one owner and the combine is total over
`SessionState` by the compiler's own exhaustiveness check — but the answer is not "it is fine", it is that every
alternative puts the mapping back in a composable. Two of the six are deliberately not the objects that own them:

- `animeList` is `AnimeListPager.state` and not the pager, because the pager stays ViewModel-scoped. **Loaded pages
  must not outlive a sign-out**, so needing to hand the source an object is not a reason to promote one to a `single`.
- `layout` is `LayoutPreference.value`, and the preference *is* process-scoped, because a Layout change issues no
  request and the pager has never heard of it.

A `(PendingAuthorization) -> String` adapter for the authorization URL was considered as a seventh parameter and
rejected: one adapter with one implementation is a hypothetical seam, and `StateFlow<MalAuthConfig>` keeps the combine
pure. The URL is rebuilt through `authorizationUrl(config, verifier, state)`, which has no `require` in it — a total
mapping cannot throw, or a combine that threw would take the collecting scope with it and leave the app on whichever
frame it last drew.

## What came with it

`MalRouting` — this build's endpoints and Redirect URI, with `usesRelay` derived — is a second new `:core` type, carried
by the `SignedOut` and `SignedIn` variants. It was not asked for: it is what the screens lost when they stopped taking
`MalSessionViewModel`, which exposed `endpoints`, `redirectUri` and `usesRelay` directly. Both readers need it and
neither can compute it — the sign-in screen says so up front because a browser with no relay running fails at its first
request with a bare "Failed to fetch", and the debug panel says so afterwards. It is *not* a seventh constructor
parameter: `platformMalEndpoints()` is an `expect fun` and so already this Target's answer, and injecting it would be
the same one-implementation seam that kept a `(PendingAuthorization) -> String` adapter out.

## Consequences

- Screens take a `ScreenState` variant plus an actions record, never a ViewModel. `App()` resolves both ViewModels;
  `AppScreen` wires them to the value and binds the `AuthRedirectChannel`; `SessionRoute` switches.
- The rendering tests build their state as a literal. The one exception is
  `SignInScreenTest.signing_in_arms_this_targets_capture_and_still_offers_paste_the_code`, which binds 18040 on purpose
  because the stack is what it asserts.
- `ScreenState::class.sealedSubclasses` is jvm-only reflection, so the *enumeration* against `SessionState` lives in
  `:core`'s `jvmTest` while the mapping it guards runs on four Targets.
