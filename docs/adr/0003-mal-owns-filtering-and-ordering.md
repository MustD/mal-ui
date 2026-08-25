# MAL owns filtering and ordering, so there is no reverse sort

Status: accepted

The Anime List is fetched a page at a time, so it is never wholly in memory, so **filtering and ordering are query
parameters on `GET /users/@me/animelist` rather than operations this app performs**. MAL's menu is short and rigid:
`status` takes one Watch Status or none, and `sort` takes one of `list_updated_at`, `list_score`, `anime_title`,
`anime_start_date` — **each with a fixed direction and no direction parameter**.

The visible consequence is that the Sort Order menu offers exactly four orderings, each labelled with the direction it
actually sorts in ("Score (high to low)", "Title (A–Z)"), and **there is no reverse or descending toggle anywhere in the
UI**. That is a decision, not an omission: a future reader will otherwise assume one was forgotten and add it.

`anime_id` is a fifth `sort` value MAL's documentation marks "under development". It is absent from
`AnimeListSortOrder`, which is what stops it being offered by accident.

## Considered options

- **Fetch the whole Anime List up front, then filter and order client-side.** The only option that could offer a
  reverse toggle honestly, and it buys real things: instant filter and Sort Order changes with no request, and
  arbitrary orderings MAL does not have. Rejected on what it costs to get there — a list of a thousand entries is
  twenty sequential requests behind a spinner before the user sees anything, on every launch, because nothing about the
  Anime List is cached (`sessionStorage` on web already carries a refresh token, and hundreds of entries do not belong
  beside it — see [ADR-0001](0001-refresh-token-in-web-session-storage.md)). The first screen is the whole product here;
  paging puts fifty entries on it in one request and keeps the app's memory footprint flat on four Targets including
  two browsers.
- **Page, and reverse the pages already loaded.** Rejected outright, and it is the option worth naming: it is a bug
  that looks like a feature. "Score, ascending" over two loaded pages of a five-page list shows the lowest score
  *among the hundred entries fetched so far*, which is not the lowest score, and the list changes under the toggle as
  scrolling loads more. Nothing on screen says so.
- **Page, and disable the reverse toggle until the list is exhausted.** A control that is dead for the first nineteen
  of twenty pages, on the lists long enough to want it. Worse than absent: it advertises a capability and withholds it.
- **Multi-select filtering.** Would mean N parallel paged queries merged client-side, and the ordering does not survive
  the merge — MAL orders within a response, and there is no key to re-order by (`list_updated_at` is exposed, but
  `list_score` ties and `anime_title` would need MAL's own collation). Out of scope for the same reason as the reverse
  toggle: it can only be done wrongly.

## Consequences

- **Every filter or Sort Order change is a refetch from `offset=0`**, discarding every loaded page. One path does it —
  `AnimeListPager.reset` — so the two controls cannot drift into subtly different behaviour. The previously loaded
  entries stay on screen until the replacement lands, and the list scrolls back to the top when it does.
- **Directions live in the labels**, because there is nowhere else to put them. A bare "Score" reads as ascending to
  about half of everyone, and a list opening on a 10 then looks broken rather than differently ordered.
  `AnimeListSortOrdersTest` pins the four strings for that reason.
- The Sort Order is in-memory and resets to "Last updated (newest first)" each launch, like the filter and unlike the
  Layout. A Sort Order that outlived the launch would be a preference nobody set.
- **`offset` is driven from this side**, not from `paging.next` — that link is an absolute `api.myanimelist.net` URL the
  web target must never follow, since it has to stay on the Relay's origin. Only its presence is read, as "there is
  more". So a `reset` that forgot to put `offset` back would page the new query from wherever the old one had got to,
  silently skipping its first entries; the pager tests assert the offsets, not just the entries.
- If MAL ever adds a direction parameter, this reverses cleanly: the menu grows from four entries to four plus a
  toggle, and nothing about the paging changes. The decision is about *reversing a paged list ourselves*, not about
  descending order being undesirable.
