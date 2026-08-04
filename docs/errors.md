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
