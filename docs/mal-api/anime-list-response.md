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
| 2a | Those directions are **Descending** for `list_updated_at`, `list_score` and `anime_start_date`, and **Ascending** for `anime_title` only | The labels are the only place a direction is written down, so a wrong one is a label that lies — "Start date (oldest first)" opening on this season reads as broken ordering. `AnimeListSortOrdersTest` pins all four strings |
| 3 | `paging.next` is an **absolute URL** to `api.myanimelist.net` | Web must never follow it — it has to stay on the Relay's origin — so `offset` is driven from our side and only the link's *presence* is read |
| 4 | An anime whose episode count is unannounced reports `num_episodes: 0`, not `null` | The UI shows `?` rather than `3 / 0` |

Claim 3 is the one already acted on unconditionally: `AnimeListPage.hasMore` is `paging.next != null` and the URL is
discarded at the parse boundary, so the app is correct whether the link is absolute or relative.

## Tolerance, which is not a claim about MAL but a hedge against it

Every field of the wire DTOs has a default, and both status enums fall back to `Unknown` rather than throwing. MAL adds
things; a list the user is looking at must not become a crash because it shipped a sixth Watch Status. `media_type` is
deliberately **not** an enum for the same reason — an unrecognised one stays displayable.

## Status of the live check

**Checked by hand on 2026-09-24, in the running app, against a real account with more than 50 entries.** Nothing
contradicted the table above.

| # | Result |
|---|---|
| 1 | **Not checked.** The app cannot send two `status` values, so this cannot be observed from it; it needs `curl` 2 below. It only matters if multi-select filtering is ever proposed. |
| 2 | **Confirmed**, from the running app: all four Sort Orders reorder the list. |
| 2a | **Confirmed for `anime_start_date`:** newest first, matching the "Start date (newest first)" label. |
| 3 | **Not checked, and not needed.** Paging past 50 entries works through to the end of the list, which confirms the app's offset-driven paging. Whether the link is absolute is not visible from the app, and the app does not depend on it. |
| 4 | **Not checked.** |

The claims still open can be answered with a token from a desktop sign-in:

```console
# The request the app actually sends. Answers claim 3 (`paging.next`) and claim 4 (`num_episodes: 0`).
$ curl -s -H "Authorization: Bearer $MAL_ACCESS_TOKEN" \
    'https://api.myanimelist.net/v2/users/@me/animelist?limit=50&offset=0&nsfw=true&sort=list_updated_at&fields=id,title,main_picture,num_episodes,media_type,status,list_status%7Bstatus,score,num_episodes_watched,updated_at%7D' \
    | head -c 2000

# Claim 1: does a second `status` win, merge, or 400?
$ curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $MAL_ACCESS_TOKEN" \
    'https://api.myanimelist.net/v2/users/@me/animelist?limit=1&status=watching&status=completed'
```

A contradiction in claim 1 would change the spec's filter decision, not just this file.
