# Channel Atlas

> **⚠️ This is an experiment.** Channel Atlas is a personal, vibe-coded prototype built in one session and tested on a
> single device (a Fire TV Stick 4K, Fire OS 7.7 / Android 9). It is not a finished or supported product, it has no
> automated tests, and it may break whenever stream sources, Fire OS or the libraries it uses change. Use it at your
> own risk.

A TV app for browsing free live TV channels from around the world, sorted by region, country, language and genre,
in a streaming-app style home screen with a mini player. Made for Fire TV and Android TV, and driven entirely by the
remote. Inspired by [famelack.com](https://famelack.com/); not affiliated with it.

Channel data comes from the community-run [iptv-org](https://github.com/iptv-org/iptv) project
([API](https://github.com/iptv-org/api)). The app hosts and restreams nothing: it plays streams that broadcasters
make publicly available, straight from their servers. Adult, closed and blocklisted channels are left out. Whether a
channel plays depends on its broadcaster: some are geo-blocked, some aren't on around the clock, and some go offline.

## Using it

The home screen works like a streaming app: a big banner for the channel you're on, and rows of channels below it.

- **Rows:** Continue watching, My List, Top picks in HD, one row per genre (News, Movies, Sports…) and rows for the
  countries with the most channels. ◀ ▶ move along a row, ▲ ▼ between rows, ⏪ ⏩ jump six channels.
- **Side menu** (◀ from the start of a row): Search, Home, My List, Genres (jump to a genre's row), Language,
  Region (a region like Europe or a single country), Sort (recommended, A–Z, best quality) and Random.
  Language and Region shape every row, and their lists show channel counts.
- **Search:** opens the TV's keyboard; press Next or Back on the keyboard to search. Names that start with what you
  typed come first. Home in the side menu clears the search.
- **My List:** the Menu button (☰) on a channel adds it or removes it.
- **Watching:** OK plays a channel full screen. ▲ ▼ (or Channel + −) switch along the row you came from, and OK shows
  what's on. When a stream fails, stalls, shows no picture or has audio the TV can't play, the next backup stream is
  tried.
- **Mini player:** Back while watching shrinks the video into the banner, where it keeps playing while you browse.
  ▲ from the top row, then OK, brings it back full screen. Picking another channel replaces it. Back on the home
  screen stops it.

The channel list (about 10,000 channels) downloads the first time the app opens, which takes about a minute, and
then updates itself once a day.

## Installing it on a Fire TV

You need a Mac or Linux computer on the same network as the TV.

1. **On the TV, turn on debugging:** Settings → My Fire TV → Developer Options → **ADB Debugging** on. If Developer
   Options isn't there, open Settings → My Fire TV → About and press OK on the device name 7 times.
2. **Find the TV's IP address:** Settings → My Fire TV → About → Network.
3. **On the computer, install the tools** (see [Building](#building)): a JDK 17+, the Android SDK command-line tools
   with build-tools 35 or newer and platform android-34, and `adb` (Android platform-tools).
4. **Build:**

   ```bash
   ./build.sh
   ```

   It ends with `Built ChannelAtlas.apk`.
5. **Install:**

   ```bash
   adb connect <TV IP>:5555
   adb install -r ChannelAtlas.apk
   ```

   The first time, the TV asks *Allow USB debugging?*: choose **Allow**.
6. **Open it** from Your Apps on the TV (hold Home → Apps; new apps are at the end of the list).

When you're done installing, you can turn ADB Debugging off again. To remove the app, uninstall it from the TV's
app settings or run `adb uninstall app.channelatlas`.

Android TV and Google TV devices work the same way (Settings → Device Preferences → About → press Build 7 times,
then Developer options → USB debugging / Network debugging).

## Building

No Gradle needed: `build.sh` runs aapt2, javac, d8, zipalign and apksigner directly.

- **JDK 17 or newer.** `build.sh` uses `JAVA_HOME`.
- **Android SDK** with build-tools **35 or newer** (the d8 in build-tools 34 crashes on one of the player's libraries)
  and platform **android-34**. `build.sh` uses `ANDROID_HOME`, and takes the newest build-tools it finds.

  ```bash
  sdkmanager "build-tools;36.0.0" "platforms;android-34"
  ```

- **Libraries:** on its first run `build.sh` calls `tools/fetch_libs.sh`, which downloads Media3 ExoPlayer 1.11.1 and
  its dependencies (about 7 MB) into `libs/` from Google's Maven repository and Maven Central, and checks each file
  against the SHA-1 the repository publishes. `libs/` isn't in git.
- **Signing key:** `build.sh` makes `channelatlas.keystore` on its first run. It isn't in git. Keep it: an update
  only installs over the app on the TV when it's signed with the same key (otherwise uninstall the old one first).

The result is `ChannelAtlas.apk` (about 3 MB), for Android 6.0 (API 23) and newer.

## How it's put together

- `app/src/app/channelatlas/Catalog.java` streams iptv-org's JSON files (about 26 MB) and writes one compact
  `catalog.json` (a few MB) on the TV.
- `MainActivity.java` hosts the browsing screen (`app/assets/`: HTML, CSS and JS) in a WebView and connects it to Java.
- `Player.java` is the video panel over the browsing screen (hidden, full screen or mini). It plays streams with
  Media3 ExoPlayer, which reads HEVC in HLS (Fire OS 7's own player can't) and reports which tracks the TV can decode,
  and sends the User-Agent or Referer some channels need. Captions stay off.
- `app/assets/app.js` builds the rows, the side menu, search and the pickers, and tells Java where the mini window
  goes. It's laid out for a 960×540 page (a 1080p screen at 2× density).
- `tools/MakeArt.java` draws the icon and the Fire TV banner; `tools/fetch_libs.sh` downloads the libraries.

## Known limitations

- Tested only on a Fire TV Stick 4K (Fire OS 7.7). Other devices may behave differently.
- Some channels' audio (MPEG-1 Layer II, used by some broadcast streams) can't be decoded on Fire TV sticks; the app
  tries a backup stream or says "No sound".
- Free streams come and go. A channel in the list may be offline, geo-blocked or slow to start.
- There are no automated tests; everything was checked by hand on the TV.
