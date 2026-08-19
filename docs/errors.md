# Known errors

## `Fail to fetch` on web during MAL login — resolved

### Symptom

```log
Uncaught runtime errors:
ERROR
Fail to fetch
Error_0: Fail to fetch
    at eval (webpack-internal:///./kotlin/ktor-ktor-client-core.js:13827:23)
Caused by: TypeError: Failed to fetch
    at commonFetch (webpack-internal:///./kotlin/ktor-ktor-client-core.js:13790:13)
```

Desktop and Android complete the same login without error.

### Cause

MyAnimeList serves **no CORS headers** on the endpoints the app must `fetch`, and rejects the preflight outright.
Verified directly:

```console
$ curl -i -X OPTIONS https://myanimelist.net/v1/oauth2/token \
    -H "Origin: https://mal-ui.localhost" -H "Access-Control-Request-Method: POST"
HTTP/2 405              # no access-control-* headers at all

$ curl -i https://api.myanimelist.net/v2/users/@me -H "Origin: https://mal-ui.localhost"
HTTP/2 401              # no access-control-* headers at all
```

The browser therefore blocks both the token exchange and `/users/@me` *before they are sent*. Because the request never
leaves the page there is no status code and no response body, which is why the error is the bare
`TypeError: Failed to fetch` — it is not MAL rejecting anything. Desktop and Android are not subject to CORS, so they
are unaffected.

The authorize endpoint is exempt: that is a top-level browser navigation, not a `fetch`.

### Fix

`:server` gained a narrow relay (`server/src/main/kotlin/.../MalRelay.kt`) that the web target calls instead of MAL:

| Relay route              | Upstream                                   |
|--------------------------|--------------------------------------------|
| `POST /mal/oauth2/token` | `https://myanimelist.net/v1/oauth2/token`  |
| `GET /mal/v2/{path...}`  | `https://api.myanimelist.net/v2/{path...}` |

Routing is per-platform via `expect fun platformMalEndpoints()` in `:core` — jvm and android keep calling MAL directly,
and only the js/wasmJs actuals point at the relay.

Crucially the relay is reached **on the page's own origin**, so no CORS is involved at all rather than being negotiated.
The browser actuals build the URL from
`window.location.origin`, and both ways of serving the app route the `/mal` prefix to
`:server`:

- the reverse proxy, via a `handle /mal/*` block;
- the webpack dev server, via the proxy in `app/webApp/webpack.config.d/devserver.js`.

That means one build works under both, and putting the relay on its own hostname would bring the original failure
straight back.

Upstream hosts are hardcoded in the relay rather than read from the request, so it cannot be used as an open forwarding
proxy, and only `GET` is exposed under `/v2`. The CORS plugin in
`Application.kt` is now a fallback for hitting the relay directly from another origin, not part of the normal path.

### Running the web target

Both processes are required:

```bash
./gradlew :server:run                                            # relay on :18010
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun --continuous   # app on :18020
```

Forgetting the relay reproduces a `Failed to fetch`. The login screen names the relay URL and the command to start it,
both up front on web builds and in the error text.

`--continuous` is required: the run task starts webpack-dev-server without blocking, so without it Gradle reports
`BUILD SUCCESSFUL` in under a second and takes the dev server down with it. The symptom is a 502 from the reverse proxy
with an apparently successful Gradle run.

### Related gotcha: `Invalid Host header`

If the app is served through a reverse proxy on a hostname like `mal-ui.localhost`, the webpack dev server rejects the
request unless `allowedHosts` accepts that name — it answers
`Invalid Host header` instead of serving the app. Handled by `devserver.js`, which also pins the dev server to
`127.0.0.1`, since `localhost` resolves to both `127.0.0.1` and `::1` and binding the wrong one looks like a working dev
server that the proxy cannot reach.

### Note

The relay forwards client secrets and bearer tokens to MAL unchanged. That is fine on localhost, but it must not be
exposed publicly without authentication of its own.

---

## `plain` PKCE is weaker than it looks — by design, not fixable here

MAL supports `code_challenge_method=plain` only. `Pkce` in `:core` is therefore correct in setting
`code_challenge == code_verifier` and doing no SHA-256 — but that equality has a consequence worth stating plainly,
because it is easy to read "we use PKCE" as "the code is safe".

### Cause

With `plain`, the challenge *is* the verifier, so **the verifier travels in cleartext in the authorization URL**.
RFC 7636 §7.2:

> With the "plain" method, there is a chance that "code_challenge" will be observed by the attacker on
> the device or in the http request. Since the code challenge is the same as the code verifier in this
> case, the "plain" method does not protect against the eavesdropping of the initial request.

Which attacker that stops, and which it does not:

| Attacker sees                                                                            | PKCE helps? |
|------------------------------------------------------------------------------------------|-------------|
| the **redirect** — a local process racing :18040, an app claiming the same custom scheme | yes — they have the code, not the verifier |
| the **authorization URL** — browser history, an extension, a log line, shell history     | **no — none at all** |

### Consequences for this repo

- **Never log the authorization URL, the code, or the verifier.** Pinned by
  `MalSessionViewModelTest.nothing_the_ui_can_see_carries_a_token_a_code_or_a_verifier`.
- **Strip the code from history where the platform allows it**, and both places that do exist for this rather than for
  tidiness: `LoopbackRedirectListener` answers the callback with a 302 to a bare path instead of rendering a page at the
  code-bearing URL, and web calls `history.replaceState` (never `pushState` — a new entry would preserve exactly what
  the call removes) in `AuthRedirectQuery`.
- **The `state` check is load-bearing, not decorative.** It is the only remaining thing standing between a leaked
  authorization URL and a usable code. `MalAuthClient.completeAuthorization()` verifies it; Android's
  `IntentRedirectChannel` filters on it as well, because that intent filter is exported and any app can fire it.

This is MAL's limitation. A different implementation cannot fix it — only MAL adding S256 would. See
[`mal-auth-implementation.md` §2](mal-auth-implementation.md#2-security-plain-pkce-is-weaker-than-it-looks).

---

## `Desktop.Action.BROWSE` is unsupported on this machine — and `LocalUriHandler` is still right

### Symptom

`java.awt.Desktop.isDesktopSupported()` is `true`, but `Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)`
returns `false`. Reproduced on JDK 11, 21, 25 and 26, so it is not a JDK-version problem. A hand-rolled
`Desktop.browse(uri)` would throw `UnsupportedOperationException` and the desktop sign-in would never reach a browser.

### Cause

The JDK registers `BROWSE` only if GIO's default VFS advertises the `http` scheme. With `gvfs` not installed, GIO
reports only `['file', 'resource']` — verified by probing `libgio` directly — so the action is never added. This is a
property of the machine's desktop stack, not of the app.

### Fix — do nothing, and specifically do not hand-roll it

Compose Desktop's `DesktopUriHandler` already falls back to `xdg-open` on Linux when `BROWSE` is unsupported (verified
in `ui-desktop-1.11.1` sources). So `LocalUriHandler` works here and a hand-rolled `Desktop.browse()` would be strictly
worse.

The residual hazard is the reason the flow is built the way it is: **`xdg-open`'s exit code is never checked**, so a
silent failure is indistinguishable from success. Nothing may depend on the launch having worked — the authorization
URL stays on screen in a selectable field and the paste-the-code path stays reachable. That is what makes headless,
SSH-forwarded and broken-`xdg-open` desktops usable rather than dead ends.

---

## Redirect-URI mismatch reports as `401 invalid_client` — and blames the wrong thing

### Symptom

`POST /v1/oauth2/token` returns:

```
HTTP/2 401
{"error":"invalid_client","message":"Client authentication failed"}
```

The obvious reading is a wrong Client ID. It is at least as often a `redirect_uri` that does not byte-match a
registered one — and the body is **identical** in both cases, so only the surrounding context distinguishes them.

### Cause

MAL matches the redirect URI **byte-exactly**, with no RFC 3986 normalization whatsoever. Measured against a URI that
*is* registered, so each row is a rejection rather than an absence:

| Mutation of a registered URI                          | Result                                                     |
|-------------------------------------------------------|------------------------------------------------------------|
| trailing slash appended                               | rejected                                                   |
| host upper-cased (`MAL-UI.localhost`)                 | rejected — though RFC 3986 §3.2.2 makes the host case-insensitive |
| default port made explicit (`:443` on `https`)        | rejected — no port normalization at all                    |
| extra query parameter appended                        | rejected                                                   |
| different loopback port (`:54321` vs registered `:18040`) | rejected                                                |

No normalization means **no RFC 8252 §7.3 loopback-port leniency** either.

### Consequences

- **The desktop port cannot be ephemeral.** 18040 is part of the byte-exact registered URI, which is why
  `DESKTOP_LOOPBACK_PORT` is a constant in `:core` and `LoopbackRedirectListener` binds that one port. Every port an
  app might use has to be registered by hand.
- **The Android intent filter must stay byte-identical to `ANDROID_REDIRECT_URI`.** It is three attributes
  (`scheme`/`host`/`path`) in the manifest against one string in Kotlin, which is exactly how the two drift.
  `AndroidManifestTest` asserts they agree.
- **Omitting `redirect_uri` is not a way out.** With several URIs registered on the client, `/v1/oauth2/authorize`
  answers 401 `invalid_client` when the parameter is absent — where a single-URI app gets a 303 to `login.php`. Always
  send it, byte-identical, on both authorize and token.
- **When a 401 `invalid_client` appears, check the redirect URI before the Client ID.** `MalAuthClient.hintFor()` says
  so in the error text for this reason.

Probes and how to re-run them: [`docs/mal-api/mal-redirect-uri-probes.http`](mal-api/mal-redirect-uri-probes.http).
