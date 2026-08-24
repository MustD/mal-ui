# `GET /users/@me/animelist`: what the spec assumed, and what MAL returns

Companion to ticket 02 of the Anime List feature (`.scratch/anime-list/issues/02-first-page-on-screen.md`). The auth
work found MAL's documentation and MAL's behaviour differing more than once — `docs/mal-api/mal-redirect-uri-probes.http`
is the standing example — so the first ticket that fetches a real page records what actually came back.

## What the implementation sends

One request shape, on every page (`MalAnimeListClient` in `:core`):

```
GET {apiBaseUrl}/users/@me/animelist
    ?limit=50
    &offset=0
    &nsfw=true
    &sort=list_updated_at
    &fields=id,title,main_picture,num_episodes,media_type,status,list_status{status,score,num_episodes_watched,updated_at}
Authorization: Bearer …
```

`status` is **absent** for the unfiltered list, and carries exactly one of `watching` / `completed` / `on_hold` /
`dropped` / `plan_to_watch` otherwise. `apiBaseUrl` is `https://api.myanimelist.net/v2` on jvm and android, and the
same-origin Relay (`/mal/v2`) on the two browser Targets — the Relay forwards query parameters verbatim, so the two
paths send the identical request.

`AnimeListPagerTest` asserts every one of those parameters on the outgoing request. That is not redundant with asserting
the parsed result: a wrong `fields` string still returns **200**, and simply omits what was not asked for.

## The four claims from MAL's v2 documentation that this feature is built on

| # | Claim | Where it bites if wrong |
|---|---|---|
| 1 | `status` takes **one** value or none — there is no multi-select | Six mutually exclusive filter chips, rather than a multi-select that would need N merged paged queries |
| 2 | `sort` accepts `list_updated_at`, `list_score`, `anime_title`, `anime_start_date`, **each with a fixed direction and no direction parameter** | Four Sort Orders labelled with their real direction, and no reverse toggle (ADR-0003) |
| 3 | `paging.next` is an **absolute URL** to `api.myanimelist.net` | Web must never follow it — it has to stay on the Relay's origin — so `offset` is driven from our side and only the link's *presence* is read |
| 4 | An anime whose episode count is unannounced reports `num_episodes: 0`, not `null` | The UI shows `?` rather than `3 / 0` |

Claim 3 is the one already acted on unconditionally: `AnimeListPage.hasMore` is `paging.next != null` and the URL is
discarded at the parse boundary, so the app is correct whether the link is absolute or relative.

## Tolerance, which is not a claim about MAL but a hedge against it

Every field of the wire DTOs has a default, and both status enums fall back to `Unknown` rather than throwing. MAL adds
things; a list the user is looking at must not become a crash because it shipped a sixth Watch Status. `media_type` is
deliberately **not** an enum for the same reason — an unrecognised one stays displayable.

## Status of the live check

**[unverified] — this section is the one part of ticket 02 that needs a signed-in MAL account, which the implementing
agent does not have.** Everything above is from MAL's v2 documentation plus the shapes the code now depends on; nothing
here has been compared against a live response yet.

To fill it in, sign in on the desktop app, take the access token, and run:

```console
# 1. The request the app actually sends. Confirms the response shape, `paging.next`, and `num_episodes: 0`.
$ curl -s -H "Authorization: Bearer $MAL_ACCESS_TOKEN" \
    'https://api.myanimelist.net/v2/users/@me/animelist?limit=50&offset=0&nsfw=true&sort=list_updated_at&fields=id,title,main_picture,num_episodes,media_type,status,list_status%7Bstatus,score,num_episodes_watched,updated_at%7D' \
    | head -c 2000

# 2. Claim 1: does a second `status` win, merge, or 400?
$ curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $MAL_ACCESS_TOKEN" \
    'https://api.myanimelist.net/v2/users/@me/animelist?limit=1&status=watching&status=completed'

# 3. Claim 2: which `sort` values are accepted, and which 400.
$ for s in list_updated_at list_score anime_title anime_start_date anime_id list_score_desc; do \
    printf '%s ' "$s"; \
    curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $MAL_ACCESS_TOKEN" \
      "https://api.myanimelist.net/v2/users/@me/animelist?limit=1&sort=$s"; \
  done
```

Record the answers here, replacing this section, and note anything that contradicts the table above — a contradiction in
claim 1 or 2 changes the spec's filter and Sort Order decisions, not just this file.
