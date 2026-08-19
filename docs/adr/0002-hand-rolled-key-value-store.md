# Hand-rolled `KeyValueStore` instead of a settings library

Status: accepted

Token persistence goes through a three-method `KeyValueStore` interface in `:core` with a per-target actual in
`:app:shared` — roughly fifteen lines each — rather than a multiplatform settings or storage library. No library
credibly covers android + jvm + js + wasmJs *with encryption*, and this repo ships both web targets, which is what rules
out the closest candidates.

## Considered options

- **androidx.datastore** — publishes a `wasm` variant of `datastore-preferences-core` but **no `js`** variant before
  `1.3.0-alpha10`. A blocker while both web targets ship.
- **multiplatform-settings 1.3.0** — does publish wasm-js artifacts, but has no encrypted variant; the maintainer's
  answer points at `androidx.security.crypto`, which is now fully deprecated. Its DataStore module has neither js nor
  wasm.
- **KStore** — covers all four targets, no encryption.
- **KVault** — Android + iOS only.
- **OS keychain on desktop** (`credential-secure-storage`, `java-keyring`) — every candidate looks dormant, all pull JNA
  plus native bits that complicate `jpackage`, and headless Linux/CI has no Secret Service anyway, so the plain-file
  fallback is needed regardless. Over-engineering for a personal client.

## Consequences

- Nothing is encrypted at rest, on any target, and that is a smaller change than it sounds. `androidx.security.crypto`
  1.1.0 shipped 2025-07-30 with **every API deprecated** and a Javadoc that literally reads "Use SharedPreferences
  instead": since Android 10 enforces file-based encryption device-wide and the app sandbox already blocks other apps,
  the security delta was approximately zero while adding StrictMode violations and Tink keyset-corruption crashes. On
  Android, **excluding the store from Auto Backup matters more than encrypting it** — see ticket 14.
- Desktop writes a `0600` file under `$XDG_STATE_HOME`. The interface is deliberately narrow enough that an OS-keychain
  implementation can be swapped in later without touching a caller.
- Keys are versioned (`mal.session.v1`), so a format change is a clean re-login rather than a crash loop. The store
  holds three such records, not one: the Session, the Pending Authorization, and — since ticket 17 — the Client ID
  (`mal.clientId.v1`). `JsonTokenStore.clear()` deliberately drops the first two and **keeps** the third: the Client ID
  identifies the app rather than the user, so signing out must not turn the next sign-in into a retyping exercise.
- Web is `sessionStorage` — see [ADR-0001](0001-refresh-token-in-web-session-storage.md).
- The cost is four small implementations to maintain and test ourselves. The benefit is no dependency that can go stale
  underneath a four-target build, which is what happened to every library above.
