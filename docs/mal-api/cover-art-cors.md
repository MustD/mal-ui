# Probe: cover-art CORS from the web Targets

Companion to ticket 01 of the Anime List feature (`.scratch/anime-list/issues/01-probe-cover-art-cors.md`), and the
measurement behind the cover-art paragraph of that feature's spec. Run on **2026-08-24**.

## What was asked

On the browser Targets, Compose renders into a canvas, so an image is fetched by the HTTP client and not by an `<img>`
tag the browser owns — which makes it subject to CORS from `cdn.myanimelist.net`, the host every `main_picture` URL
points at. MAL's token and API endpoints send no `Access-Control-Allow-Origin` at all and answer preflight `OPTIONS`
with 405, which is the whole reason `:server` has a Relay (`docs/errors.md`). The question is whether the CDN behaves
the same way. If it does, web cover art fails exactly the way the token endpoint did, and ticket 07 has to ship a
`GET /mal/img/{path...}` Relay route plus an ADR for widening the Relay's deliberately narrow scope.

## What came back

**`cdn.myanimelist.net` is permissive. Cover art loads on both web Targets with no Relay and no workaround.**

Headers, verbatim, for a real `main_picture` pair — Cowboy Bebop, `medium` = `.../4/19644.jpg`, `large` =
`.../4/19644l.jpg` — requested with `Origin: http://localhost:18020`:

```console
$ curl -i https://cdn.myanimelist.net/images/anime/4/19644.jpg -H "Origin: http://localhost:18020"
HTTP/1.1 200 OK
Content-Type: image/jpeg
Content-Length: 21257
Server: Apache
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET,PUT,POST,PATCH,DELETE,HEAD
Access-Control-Max-Age: 3000
Cache-Control: max-age=503052
Akamai-Cache-Status: Hit from child
```

`large` answers identically (`Content-Length: 35865`), so **both sizes are served the same way** — the one thing that
could plausibly have differed does not.

Four things beyond the bare header, each of which could have made this a false positive:

- **`Access-Control-Allow-Origin: *`, not an echoed `Origin`, and there is no `Vary: Origin`.** The header is
  unconditional: identical with no `Origin`, with `http://localhost:18020`, with `http://localhost:18030` and with
  `https://mal-ui.localhost`. Nothing here is origin-sensitive, so a response cached for one of the four dev origins is
  valid for the others, and the reverse-proxy hostnames need no separate treatment.
- **It survives a cache miss.** A cache-busted URL returns `Akamai-Cache-Status: Miss from child, Miss from parent` and
  still carries the header, so it comes from the origin server and is not an artefact of one warm edge object.
- **Preflight is answered, not rejected.** `OPTIONS` returns `200` with `Allow: HEAD,GET,POST,OPTIONS` and the same
  `Access-Control-*` headers — the opposite of the token endpoint's bare 405. Not that the app will send one: a plain
  image `GET` is a simple request.
- **Range requests are allowed too**, `206` with the CORS headers intact.

### From inside a running build

Headers are only half the question, so the fetch was also run **in the page of a live wasmJs build** at
`http://localhost:18020` (and repeated against the js build at `http://localhost:18030`), driven over CDP in headless
Chromium. Both Targets, all three URLs, gave the same result:

```json
{ "fetch": { "ok": true, "status": 200, "type": "cors" },
  "exposedHeaders": ["cache-control", "content-length", "content-type", "expires", "last-modified"],
  "bytes": 35865, "mime": "image/jpeg",
  "decoded": "300x446",
  "canvasReadback": "ok, first pixel rgba(248,238,247,255)" }
```

- `"type": "cors"` is the load-bearing part: the response is a real cross-origin response that passed the CORS check,
  not an opaque one. An opaque response reads as 0 bytes and cannot be decoded.
- `createImageBitmap` on the fetched blob **decodes**, so the bytes reach Skia as pixels rather than merely arriving.
- The canvas readback (`<img crossorigin="anonymous">` → `drawImage` → `getImageData`) succeeds, so the canvas is
  **not tainted**. This is the check that matters for Compose specifically, which composites everything into one
  canvas: a tainted canvas would poison the whole surface, not just the picture.
- **js and wasmJs do not differ.** Same headers, same decode, same readback — one finding covers both.

## What it means for the design

- **Ticket 07 carries no Relay work.** No `GET /mal/img/{path...}` route, no ADR widening the Relay's scope. The Relay
  stays exactly the two routes it has today, and the "cannot become an open forwarding proxy" property stays unargued
  because nothing re-opens it. Coil 3 points straight at `cdn.myanimelist.net` on all four Targets, so cover art needs
  no per-platform branch at all.
- **Nothing on the app side has to opt in.** `Access-Control-Allow-Origin: *` with no credentials involved means the
  default `fetch` mode already works; no `crossOrigin` attribute, no `mode`/`credentials` tuning, no proxy prefix.
- **A missing image is not a readable 404 on web.** `cdn.myanimelist.net` sends **no** `Access-Control-*` headers on a
  404 — only the 200 path is CORS-enabled — so a browser sees a network-level CORS failure with no status code, while
  jvm/android see a plain 404. Cover-art error handling must therefore be "no image, show the placeholder", never a
  branch on the status code. `main_picture` is also absent entirely on some entries, which reaches the same placeholder.
- **This is a CDN property, not a promise.** It is Akamai config on a host MAL can change without telling anyone. If web
  cover art ever turns into a grid of grey boxes with `TypeError: Failed to fetch` in the console, this is the first
  thing to re-check — and the fallback the spec pre-agreed (a web-only Relay image route, upstream host hardcoded like
  the existing ones) is still the right shape, just not needed now.

## Re-running it

The header half is one command and needs no MAL session, no token and no build:

```bash
curl -i https://cdn.myanimelist.net/images/anime/4/19644.jpg -H "Origin: http://localhost:18020" | head -20
```

The in-page half needs a dev server (`./gradlew :app:webApp:wasmJsBrowserDevelopmentRun --continuous`) and a browser
pointed at it; the probe script itself was throwaway and is not kept — `fetch(url).then(r => r.type)` in the page's
console is the whole test, with `"cors"` the pass and a `TypeError` the fail.
