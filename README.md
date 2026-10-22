# Channel Atlas

A TV app for browsing free live TV channels from around the world, sorted by region, country, language and genre.
Made for Fire TV and Android TV, and driven entirely by the remote.

Channel data comes from the community-run [iptv-org](https://github.com/iptv-org/iptv) project
([API](https://github.com/iptv-org/api)), the same source sites like famelack.com mostly use. The app hosts nothing:
it links to streams that broadcasters make publicly available. Adult, closed and blocklisted channels are left out.

## Using it

- **Filters (top row):** Region, Country, Language and Genre each open a list with channel counts. The counts follow
  your other filters, so you only see choices that have channels.
- **Sort:** name A–Z or Z–A, country, best quality first, or most backup streams.
- **Search:** opens the on-screen keyboard and searches channel names and alternate names.
- **★ Favorites:** the Menu button (☰) on a channel adds it to your favorites or removes it. The Favorites chip shows only those.
- **Random:** plays a random channel from the current list. **Clear** resets every filter.
- **Watching:** OK plays a channel. In the player, ▲/▼ (or Channel +/−) switches channels along your list, OK shows
  what's on, and Back returns to the list. When a stream fails or stalls, the next backup stream is tried.
- ⏪/⏩ jump a page in the grid or in a list.

The list updates itself once a day. Some channels are geo-blocked or not on 24/7, and the cards say so.

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
- `PlayerActivity.java` plays streams with Android's own player, sending the User-Agent or Referer some channels need.
- `tools/MakeArt.java` draws the icon and the Fire TV banner.
