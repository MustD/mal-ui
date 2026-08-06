# Refresh token in web `sessionStorage`

Status: accepted

The web targets store the whole Session — access token, **refresh token** and cached user — in `sessionStorage`, via the
same
`KeyValueStore` facade the other targets use. `draft-ietf-oauth-browser-based-apps` ranks in-memory storage above this,
and we took the lower-ranked option deliberately, so that a Session survives a reload and so that a Pending
Authorization survives the full-page-redirect fallback (which destroys and rebuilds the document by design).

## Considered options

- **In-memory only** — what the draft recommends, and what the research doc's §6.3 proposed. Rejected because it makes
  every reload a fresh login and, worse, makes the full-page-redirect fallback impossible: the code verifier would be
  gone by the time the redirect lands.
- **Split — Pending Authorization in `sessionStorage`, tokens in memory.** Considered and rejected as not worth the
  extra concept; the refresh token is the only meaningful difference in exposure and web Sessions are wanted.
- **`localStorage`** — never. Shared across tabs, so two tabs can both refresh and one loses, and it outlives the
  browsing session entirely.
- **Promote the Relay to a token-mediating backend (BFF)** — the actual fix. `:server` is already same-origin and
  already proxies `/mal`; the upgrade is holding tokens in a server-side session keyed by an `HttpOnly` cookie. This is
  the path to take if this ever stops being a personal project.

## Consequences

- A refresh token — a one-month credential, since MAL's rotate but leave the old one valid — sits in web storage that
  the draft notes has "no guarantee of being encrypted at rest". Any script execution on the origin can read it. The
  draft is blunt that *none* of the client-side options survive that attacker, which is why the mitigation is the BFF
  and not a different storage API.
- Tab-scoping is a genuine benefit and not just a consolation: two tabs cannot race each other's refresh, which
  `localStorage` would allow.
- A `window.open`ed popup receives a **copy** of the opener's session storage, not a shared view. So the popup document
  must never read or clear the Pending Authorization — its clear would not reach the opener. The popup only
  `postMessage`s the raw redirect back.
- Closing the tab ends the Session. Acceptable while the signed-in surface is a shell; revisit if the web target grows
  state worth preserving.
