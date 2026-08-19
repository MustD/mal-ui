# mal_ui

A MyAnimeList client for Android, desktop, and the browser. MAL offers only the OAuth2 authorization code grant with
PKCE, so the user's password is only ever typed on myanimelist.net — everything this project calls a "sign-in" is a
round trip out to MAL and back.

## Language

### Authorization

**Session**:
A signed-in relationship with MAL, comprising the token pair and the cached identity of the user it belongs to. _Avoid_:
Login, account (a Login is one event that starts a Session; MAL Account is the thing on myanimelist.net)

**Pending Authorization**:
An authorization that has been started but not yet completed — the user is away on myanimelist.net. Holds the PKCE code
verifier and `state` that the eventual token exchange needs, and is worthless once either is lost. _Avoid_: Auth
request, in-flight login

**Redirect Capture**:
The platform-specific means by which the authorization code gets from MAL's redirect back into the app: a loopback HTTP
listener on desktop, a custom-scheme intent or Auth Tab result on Android, a popup message or same-origin route on web.
In code it is `AuthRedirectChannel`, whose three phases — **arm** (reserve the platform resource), **open** (send the
user to MAL), **await** (the redirect, a cancellation or a failure) — each exist because one platform cannot work
without them. Android runs two captures at once and arbitrates between them; see **Browser Plan**. _Avoid_: Callback
handling, deep link (a deep link is only the Android form of it)

**Paste-the-code**:
The fallback in which the user copies the redirect URL out of their browser's address bar and pastes it into the app.
The only mechanism that works on every platform, so it is a modelled path and not dead code. _Avoid_: Manual flow

**Redirect URI**:
The address MAL sends the user back to after they approve access, registered on the MAL app and matched byte-exactly.
One is registered per platform and per web origin. _Avoid_: Redirect URL, callback URL (MAL's own registration form says
"URL"; the wire parameter and this codebase say
`redirect_uri`)

**Redirect Inbox**:
Android only: the process-scoped hand-off that holds a redirect `Intent`'s URI until something takes it — an armed
Redirect Capture, or the launch path when the process was killed while the user was away. In code it is
`AuthRedirectInbox`. Deliberately not called a relay; see **Relay**, which is a different thing entirely.
`AuthTabResultInbox` is the same idea for an Auth Tab's result code.

**Browser Plan**:
Android only: which of three ways this device will be sent to MyAnimeList — an **Auth Tab** (Chrome 137+, which hands the
redirect back through a result and reports cancellation outright), a plain **Custom Tab**, or a bare `ACTION_VIEW`. Chosen
once from what is installed, in `browserPlan()`. Every one of them keeps the Redirect Inbox behind it, because an Auth Tab
degrades to a Custom Tab silently and the redirect then arrives as an `Intent` instead. _Avoid_: WebView — disallowed by
RFC 8252 §8.12, not merely discouraged

**Client ID**:
The public identifier of this app's registration on myanimelist.net, sent with every authorization and token request.
Not a secret — it travels in the authorization URL, visible in the user's own address bar — so it is remembered for
convenience rather than protected: the one last signed in with on this device, else the build's `mal.clientId` default,
else the user is asked. It identifies the *app*, so signing out does not forget it. _Avoid_: API key, app key, client
secret (a public client has no secret, and `MalAuthConfig.clientSecret` exists only for a `web`-type app)

**Signed Out Reason**:
Why a Session is absent — never signed in, signed out deliberately, a refresh MAL rejected, or an authorization that
failed. Carried so the UI can explain itself rather than showing a bare "signed out".

### Platform

**Relay**:
The `/mal` routes on `:server` that forward token and API calls to MAL on the web target's behalf, because MAL sends no
CORS headers. Must be same-origin with the app; native targets bypass it and call MAL directly. _Avoid_: Proxy, BFF (it
holds no tokens of its own — a BFF is the thing it could become)

**Target**:
One of the four compilation targets — `android`, `jvm`, `js`, `wasmJs`. Distinct from an app module (`:app:webApp` is
one module producing two targets).
