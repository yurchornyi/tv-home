# KIK: channels for the TV Home shelf

TV Home shows posters above the KIK tile. It reads them from KIK's Android TV home-screen channels
(`TvContract.Channels` / `TvContract.PreviewPrograms`), and only from channels whose
`internal_provider_id` starts with `tvhome:`. While KIK publishes none, TV Home shows generic TMDB posters.

KIK today publishes RezkaTV's five channels: Новинки, Смотрят, Горячие, Обновления сериалов, История. All of them
have `internal_provider_id = 9`. The first three are Rezka's generic catalogue in Russian, with no country filter,
so TV Home ignores them.

## What KIK should publish

Three channels, all for the **profile that last played a video** on this TV. Not the one chosen last: if Катя
opens her profile and only looks around, the shelf stays on Юра's lists until Катя starts something.

| `internal_provider_id` | Display name | Programs |
|---|---|---|
| `tvhome:continue` | Продовжити перегляд | Started but not finished (the profile's history with progress), newest first. Set `last_playback_position_millis` and `duration_millis`. |
| `tvhome:for_you` | Для вас | The profile's recommendations (the same ranking KIK uses on its home screen), with the profile's country filter applied. |
| `tvhome:new_episodes` | Нові серії | New episodes of series the profile follows ("Обновления сериалов"). |

Make the channels in this order: TV Home shows them in the order they were made, taking turns between them.

For each program:
- `title`: the Ukrainian title (the one KIK shows).
- `poster_art_uri`: an `https://` landscape backdrop, 16:9 and at least 1280 px wide. TV Home skips programs without
  an http(s) poster. Set `poster_art_aspect_ratio` to `ASPECT_RATIO_16_9`.
- `intent_uri`: an intent that opens this exact title in KIK. For Continue, it should resume playback.
  `Intent.toUri(Intent.URI_INTENT_SCHEME)`, e.g. the existing `hdrezka://film?u=...` VIEW intent.
- `weight`: higher means first within the channel.
- `searchable` = 1 (the default). Android lets a non-system launcher read only searchable rows.
- `type`: `TYPE_MOVIE` / `TYPE_TV_EPISODE` / `TYPE_TV_SERIES`.

## When to republish

- When playback starts in a profile other than the remembered one: remember it (SharedPreferences) and replace
  the programs of all three channels with that profile's lists. Choosing a profile alone changes nothing.
- When playback stops (Continue changes).
- Periodically in the background, e.g. a WorkManager job every few hours. It refreshes For you and New episodes
  for the remembered profile (the one that played last).

Replace a channel's programs by deleting its old ones and inserting the new ones. Keep the channel ids stable:
update channels, don't recreate them. TV Home rereads at most once a minute.

## RezkaTV's old channels

Delete the old channels Новинки, Смотрят, Горячие, История and Обновления сериалов (or stop publishing them).
Otherwise they stay on the stock Android TV home screen with Russian names.

## Checking it

On the TV, with TV Home 2.47 or later, focus the KIK tile and run:

    adb logcat -s TvHome.ChannelFeed

It should print `kik.app: N programs` with N > 0, and the shelf above KIK should show that profile's titles.
The Watch / Continue button opens the program's `intent_uri`.
