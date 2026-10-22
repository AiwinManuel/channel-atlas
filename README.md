# Channel Atlas

A TV app for browsing free live TV channels from around the world, sorted by region, country, language and genre.
Made for Fire TV and Android TV, and driven entirely by the remote.

Channel data comes from the community-run [iptv-org](https://github.com/iptv-org/iptv) project
([API](https://github.com/iptv-org/api)), the same source sites like famelack.com mostly use. The app hosts nothing:
it links to streams that broadcasters make publicly available. Adult, closed and blocklisted channels are left out.

## Using it

The home screen works like a streaming app: a big banner for the channel you're on, and rows of channels below it.

- **Rows:** Continue watching, My List, Top picks in HD, one row per genre (News, Movies, Sports…) and rows for the
  countries with the most channels. ◀ ▶ move along a row, ▲ ▼ between rows, ⏪ ⏩ jump six channels.
- **Side menu** (◀ from the start of a row): Search, Home, My List, Genres (jump to a genre's row), Language,
  Region (a region like Europe or a single country), Sort (recommended, A–Z, best quality) and Random.
  Language and Region shape every row, and their lists show channel counts.
- **My List:** the Menu button (☰) on a channel adds it or removes it.
- **Watching:** OK plays a channel full screen. ▲ ▼ (or Channel + −) switch along the row you came from, and OK shows
  what's on. When a stream fails, stalls, shows no picture or has audio the TV can't play, the next backup stream is
  tried.
- **Mini player:** Back while watching shrinks the video into the banner, where it keeps playing while you browse.
  ▲ from the top row, then OK, brings it back full screen. Picking another channel replaces it. Back on the home
  screen stops it.

The list updates itself once a day. Some channels are geo-blocked or not on 24/7, and the banner says so.

## Building

No Gradle needed. You need a JDK 17+ and the Android SDK (build-tools 34, platform android-34).

```bash
./build.sh
adb connect <TV IP>:5555
adb install -r ChannelAtlas.apk
```

`build.sh` makes `channelatlas.keystore` on its first run. Keep it: updates must be signed with the same key.

## How it's put together

- `Catalog.java` streams iptv-org's JSON files (about 26 MB) and writes one compact `catalog.json` (a few MB) on the TV.
- `MainActivity.java` hosts the browsing screen (`app/assets/`: HTML, CSS and JS) in a WebView and connects it to Java.
- `Player.java` is the video panel over the browsing screen (hidden, full screen or mini). It plays streams with
  Android's own MediaPlayer, picks an H.264 video variant from HLS playlists itself, and sends the User-Agent or
  Referer some channels need. Fire OS 7's player can't read HEVC inside HLS, so HEVC-only channels don't play.
- `tools/MakeArt.java` draws the icon and the Fire TV banner.
