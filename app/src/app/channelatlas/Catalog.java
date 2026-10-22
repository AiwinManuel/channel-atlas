package app.channelatlas;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Downloads iptv-org's public channel database (https://github.com/iptv-org/api) and boils it down to one small
 * catalog.json: every channel with a working-looking stream, its country, languages, genres, logo and stream URLs.
 * The big files (about 26 MB) are read as a stream, so the TV never holds them in memory.
 */
final class Catalog {
    private Catalog() {}

    static final String API = "https://iptv-org.github.io/api/";
    static final long MAX_AGE = 24L * 60 * 60 * 1000;

    interface Progress { void step(String message); }

    static File file(Context c) { return new File(c.getFilesDir(), "catalog.json"); }

    static String read(Context c) {
        File f = file(c);
        if (!f.exists()) return "";
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(data, 0, off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static long age(Context c) {
        File f = file(c);
        return f.exists() ? System.currentTimeMillis() - f.lastModified() : Long.MAX_VALUE;
    }

    private static final class Stream {
        String url, quality, userAgent, referrer, feed;
        boolean geo, part;
        int q;
    }

    private static final class Channel {
        String id, name, country;
        List<String> genres;
        List<String> alt;
        boolean bad;
        final List<Stream> streams = new ArrayList<>();
        final Set<String> langs = new LinkedHashSet<>();
        String logo;
        boolean logoMain;
    }

    interface Each { void item(Map<String, Object> o) throws IOException; }

    static void sync(Context c, Progress p) throws IOException {
        Set<String> blocked = new HashSet<>();
        p.step("Checking the blocklist…");
        each("blocklist.json", o -> blocked.add(str(o, "channel")));

        Map<String, Channel> channels = new HashMap<>();
        p.step("Reading channels…");
        each("channels.json", o -> {
            Channel ch = new Channel();
            ch.id = str(o, "id");
            ch.name = str(o, "name");
            ch.country = str(o, "country");
            ch.genres = list(o, "categories");
            ch.alt = list(o, "alt_names");
            ch.bad = Boolean.TRUE.equals(o.get("is_nsfw")) || o.get("closed") != null || blocked.contains(ch.id);
            if (ch.id != null && ch.name != null && !ch.bad) channels.put(ch.id, ch);
        });

        p.step("Reading streams…");
        each("streams.json", o -> {
            Channel ch = channels.get(str(o, "channel"));
            String url = str(o, "url");
            if (ch == null || url == null) return;
            Stream s = new Stream();
            s.url = url;
            s.feed = str(o, "feed");
            s.quality = str(o, "quality");
            s.userAgent = str(o, "user_agent");
            s.referrer = str(o, "referrer");
            List<String> labels = list(o, "labels");
            s.geo = labels.contains("Geo-blocked");
            s.part = labels.contains("Not 24/7");
            s.q = quality(s.quality);
            ch.streams.add(s);
        });
        // Only channels that have something to play from here on.
        channels.values().removeIf(ch -> ch.streams.isEmpty());

        p.step("Reading languages…");
        Map<String, List<String>> mainLangs = new HashMap<>(), feedLangs = new HashMap<>();
        each("feeds.json", o -> {
            String id = str(o, "channel");
            if (!channels.containsKey(id)) return;
            List<String> langs = list(o, "languages");
            feedLangs.put(id + "@" + str(o, "id"), langs);
            if (Boolean.TRUE.equals(o.get("is_main")) || !mainLangs.containsKey(id)) mainLangs.put(id, langs);
        });
        for (Channel ch : channels.values()) {
            for (Stream s : ch.streams) {
                List<String> l = s.feed != null ? feedLangs.get(ch.id + "@" + s.feed) : null;
                if (l == null) l = mainLangs.get(ch.id);
                if (l != null) ch.langs.addAll(l);
            }
        }

        p.step("Reading logos…");
        each("logos.json", o -> {
            Channel ch = channels.get(str(o, "channel"));
            if (ch == null || Boolean.FALSE.equals(o.get("in_use"))) return;
            boolean main = o.get("feed") == null;
            if (ch.logo == null || (main && !ch.logoMain)) {
                ch.logo = str(o, "url");
                ch.logoMain = main;
            }
        });

        p.step("Reading countries and genres…");
        Set<String> usedCountries = new HashSet<>(), usedLangs = new HashSet<>(), usedGenres = new HashSet<>();
        for (Channel ch : channels.values()) {
            usedCountries.add(ch.country);
            usedLangs.addAll(ch.langs);
            usedGenres.addAll(ch.genres);
        }
        List<Map<String, Object>> countries = new ArrayList<>(), langs = new ArrayList<>(), genres = new ArrayList<>(),
                regions = new ArrayList<>();
        each("countries.json", o -> { if (usedCountries.contains(str(o, "code"))) countries.add(o); });
        each("languages.json", o -> { if (usedLangs.contains(str(o, "code"))) langs.add(o); });
        each("categories.json", o -> { if (usedGenres.contains(str(o, "id"))) genres.add(o); });
        each("regions.json", regions::add);

        p.step("Saving the channel list…");
        List<Channel> sorted = new ArrayList<>(channels.values());
        Collections.sort(sorted, (a, b) -> a.name.compareToIgnoreCase(b.name));
        File tmp = new File(c.getFilesDir(), "catalog.tmp");
        try (JsonWriter w = new JsonWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            w.beginObject();
            w.name("updated").value(System.currentTimeMillis());
            w.name("countries").beginArray();
            for (Map<String, Object> o : countries) {
                w.beginObject().name("code").value(str(o, "code")).name("name").value(str(o, "name"))
                        .name("flag").value(str(o, "flag")).endObject();
            }
            w.endArray();
            w.name("languages").beginArray();
            for (Map<String, Object> o : langs) {
                w.beginObject().name("code").value(str(o, "code")).name("name").value(str(o, "name")).endObject();
            }
            w.endArray();
            w.name("genres").beginArray();
            for (Map<String, Object> o : genres) {
                w.beginObject().name("id").value(str(o, "id")).name("name").value(str(o, "name")).endObject();
            }
            w.endArray();
            w.name("regions").beginArray();
            for (Map<String, Object> o : regions) {
                w.beginObject().name("code").value(str(o, "code")).name("name").value(str(o, "name"));
                strings(w, "countries", list(o, "countries"));
                w.endObject();
            }
            w.endArray();
            w.name("channels").beginArray();
            for (Channel ch : sorted) {
                // Streams that play everywhere and around the clock first, then the sharpest.
                Collections.sort(ch.streams, (a, b) -> a.geo != b.geo ? (a.geo ? 1 : -1)
                        : a.part != b.part ? (a.part ? 1 : -1) : b.q - a.q);
                w.beginObject();
                w.name("id").value(ch.id).name("n").value(ch.name).name("c").value(ch.country);
                if (!ch.alt.isEmpty()) strings(w, "a", ch.alt);
                strings(w, "l", new ArrayList<>(ch.langs));
                strings(w, "g", ch.genres);
                if (ch.logo != null) w.name("i").value(ch.logo);
                boolean allGeo = true, allPart = true;
                w.name("s").beginArray();
                for (Stream s : ch.streams) {
                    allGeo &= s.geo;
                    allPart &= s.part;
                    w.beginObject().name("u").value(s.url);
                    if (s.q > 0) w.name("q").value(s.q);
                    if (s.userAgent != null) w.name("ua").value(s.userAgent);
                    if (s.referrer != null) w.name("r").value(s.referrer);
                    w.endObject();
                }
                w.endArray();
                if (allGeo) w.name("geo").value(1);
                if (allPart) w.name("part").value(1);
                w.endObject();
            }
            w.endArray();
            w.endObject();
        }
        if (!tmp.renameTo(file(c))) throw new IOException("couldn't save the channel list");
    }

    private static int quality(String q) {
        if (q == null) return 0;
        int n = 0;
        for (int i = 0; i < q.length() && Character.isDigit(q.charAt(i)); i++) n = n * 10 + (q.charAt(i) - '0');
        return n;
    }

    private static void strings(JsonWriter w, String name, List<String> values) throws IOException {
        w.name(name).beginArray();
        for (String v : values) w.value(v);
        w.endArray();
    }

    private static String str(Map<String, Object> o, String key) {
        Object v = o.get(key);
        return v instanceof String ? (String) v : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Map<String, Object> o, String key) {
        Object v = o.get(key);
        return v instanceof List ? (List<String>) v : Collections.<String>emptyList();
    }

    /** Streams one of the API's files (a JSON array of flat objects) and hands each object to `each`. */
    private static void each(String name, Each each) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(API + name).openConnection();
        http.setConnectTimeout(20_000);
        http.setReadTimeout(60_000);
        http.setRequestProperty("User-Agent", "ChannelAtlas/1.0 (Android TV)");
        try {
            int code = http.getResponseCode();
            if (code != 200) throw new IOException(name + ": HTTP " + code);
            try (JsonReader r = new JsonReader(new InputStreamReader(new BufferedInputStream(http.getInputStream(), 65536),
                    StandardCharsets.UTF_8))) {
                r.beginArray();
                while (r.hasNext()) each.item(object(r));
                r.endArray();
            }
        } finally {
            http.disconnect();
        }
    }

    /** One object: strings, booleans, numbers (as strings), nulls and lists of strings. Nested objects are skipped. */
    private static Map<String, Object> object(JsonReader r) throws IOException {
        Map<String, Object> o = new HashMap<>();
        r.beginObject();
        while (r.hasNext()) {
            String key = r.nextName();
            JsonToken t = r.peek();
            if (t == JsonToken.NULL) { r.nextNull(); o.put(key, null); }
            else if (t == JsonToken.BOOLEAN) o.put(key, r.nextBoolean());
            else if (t == JsonToken.STRING || t == JsonToken.NUMBER) o.put(key, r.nextString());
            else if (t == JsonToken.BEGIN_ARRAY) {
                List<String> l = new ArrayList<>();
                r.beginArray();
                while (r.hasNext()) {
                    if (r.peek() == JsonToken.STRING) l.add(r.nextString()); else r.skipValue();
                }
                r.endArray();
                o.put(key, l);
            } else r.skipValue();
        }
        r.endObject();
        return o;
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
