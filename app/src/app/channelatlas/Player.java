package app.channelatlas;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The video panel that sits over the browsing screen. Three modes:
 * NONE (hidden), FULL (whole screen, takes the remote) and MINI (a small window in the browsing screen's hero area
 * that keeps playing while you browse).
 *
 * A plain MediaPlayer on a TextureView: VideoView turns on closed captions by itself, and on Fire OS 7 (Android 9) the
 * media server crashes on some streams' captions. TextureView (unlike SurfaceView) resizes and clips smoothly.
 */
final class Player implements TextureView.SurfaceTextureListener {
    static final int NONE = 0, FULL = 1, MINI = 2;

    interface Listener {
        /** Called when the mode or the channel changes. `channel` is null when nothing plays. */
        void onPlayer(int mode, JSONObject channel);
    }

    private static final int TIMEOUT_MS = 20_000;

    private final Activity a;
    private final Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final FrameLayout root, box;
    private final TextureView texture;
    private final View curtain;
    private final ProgressBar spinner;
    private final TextView info, status;
    private final GradientDrawable frame;

    private int mode = NONE;
    /** The mini window, in pixels of the screen (left, top, width, height). */
    private int[] miniRect = {1120, 56, 744, 418};
    private int[] current = {0, 0, 0, 0};
    private ValueAnimator anim;

    private Surface surface;
    private MediaPlayer player;
    private JSONArray list = new JSONArray();
    private int index;
    private JSONObject channel;
    private int stream, generation, retries, videoW, videoH;
    private String statusKind, badAudio;

    private final Runnable hideInfo;
    private final Runnable stalled = () -> next("didn't start");
    private final Runnable noPicture = () -> next("no picture");

    Player(Activity a, FrameLayout root, Listener listener) {
        this.a = a;
        this.root = root;
        this.listener = listener;

        box = new FrameLayout(a);
        box.setBackgroundColor(Color.BLACK);
        box.setVisibility(View.GONE);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), mode == MINI ? dp(10) : 0);
            }
        });
        box.setClipToOutline(true);
        frame = new GradientDrawable();
        frame.setColor(Color.TRANSPARENT);
        frame.setCornerRadius(dp(10));
        box.setForeground(frame);
        root.addView(box, new FrameLayout.LayoutParams(0, 0));

        texture = new TextureView(a);
        texture.setSurfaceTextureListener(this);
        box.addView(texture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        box.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) box.post(this::fit);
        });

        // Black until the new channel's first frame, so the last channel's picture never lingers.
        curtain = new View(a);
        curtain.setBackgroundColor(Color.BLACK);
        box.addView(curtain, new FrameLayout.LayoutParams(-1, -1));

        spinner = new ProgressBar(a);
        box.addView(spinner, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));

        status = text(18);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.GONE);
        status.setPadding(dp(16), 0, dp(16), 0);
        box.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        info = text(16);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD9101014);
        bg.setCornerRadius(dp(14));
        info.setBackground(bg);
        info.setPadding(dp(22), dp(14), dp(22), dp(14));
        info.setAlpha(0f);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        ip.setMargins(dp(32), dp(28), dp(32), 0);
        box.addView(info, ip);
        hideInfo = () -> info.animate().alpha(0f).setDuration(400);
    }

    int mode() { return mode; }

    /** Sets the mini window from the page's layout: CSS pixels, scaled by the page's width on screen. */
    void setMiniRect(double x, double y, double w, double ht, double cssWidth) {
        double s = root.getWidth() / cssWidth;
        miniRect = new int[]{(int) Math.round(x * s), (int) Math.round(y * s), (int) Math.round(w * s), (int) Math.round(ht * s)};
        if (mode == MINI) moveTo(miniRect, false);
    }

    // ---------- Modes

    /** Starts `list[index]` full screen. */
    void open(JSONArray list, int index) {
        this.list = list;
        this.index = index;
        setMode(FULL);
        tune(0);
    }

    void setMode(int m) {
        if (m == mode) return;
        int old = mode;
        mode = m;
        if (m == NONE) {
            stop();
            box.setVisibility(View.GONE);
        } else {
            boolean wasHidden = box.getVisibility() != View.VISIBLE;
            box.setVisibility(View.VISIBLE);
            int[] full = {0, 0, root.getWidth(), root.getHeight()};
            moveTo(m == FULL ? full : miniRect, !wasHidden && old != NONE);
            frame.setStroke(m == MINI ? dp(2) : 0, 0x66FFFFFF);
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, m == MINI ? 11 : 18);
            info.setVisibility(m == FULL ? View.VISIBLE : View.GONE);
            if (statusKind != null) setStatus(statusKind);
            if (m == FULL && channel != null) showInfo();
            box.invalidateOutline();
        }
        listener.onPlayer(mode, mode == NONE ? null : channel);
    }

    private void moveTo(int[] target, boolean animate) {
        if (anim != null) anim.cancel();
        if (!animate) { apply(target); return; }
        int[] from = current.clone();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(260);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(v -> {
            float f = (float) v.getAnimatedValue();
            int[] r = new int[4];
            for (int i = 0; i < 4; i++) r[i] = Math.round(from[i] + (target[i] - from[i]) * f);
            apply(r);
        });
        anim.start();
    }

    private void apply(int[] r) {
        current = r.clone();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) box.getLayoutParams();
        lp.leftMargin = r[0];
        lp.topMargin = r[1];
        lp.width = r[2];
        lp.height = r[3];
        box.setLayoutParams(lp);
    }

    /** Letterboxes the picture to the stream's own shape inside the panel. */
    private void fit() {
        int w = box.getWidth(), ht = box.getHeight();
        if (w == 0 || ht == 0) return;
        int vw = w, vh = ht;
        if (videoW > 0 && videoH > 0) {
            if ((long) videoW * ht > (long) w * videoH) vh = (int) ((long) w * videoH / videoW);
            else vw = (int) ((long) ht * videoW / videoH);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) texture.getLayoutParams();
        if (lp.width != vw || lp.height != vh) {
            lp.width = vw;
            lp.height = vh;
            texture.setLayoutParams(lp);
        }
        box.invalidateOutline();
    }

    // ---------- Surface

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int ht) {
        surface = new Surface(st);
        if (player != null) player.setSurface(surface);
        else if (channel != null && mode != NONE) play();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int ht) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (player != null) {
            try { player.setSurface(null); } catch (RuntimeException ignored) {}
        }
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    // ---------- Channels and streams

    /** Moves `delta` channels along the list and starts the new one from its first stream. */
    private void tune(int delta) {
        int n = list.length();
        if (n == 0) { setMode(NONE); return; }
        index = ((index + delta) % n + n) % n;
        channel = list.optJSONObject(index);
        stream = 0;
        retries = 0;
        if (mode == FULL) showInfo();
        listener.onPlayer(mode, channel);
        play();
    }

    private void play() {
        generation++;
        int gen = generation;
        h.removeCallbacks(stalled);
        h.removeCallbacks(noPicture);
        release();
        curtain.setVisibility(View.VISIBLE);
        setStatus(null);
        JSONArray streams = channel.optJSONArray("s");
        if (streams == null || stream >= streams.length()) {
            spinner.setVisibility(View.GONE);
            setStatus("na");
            return;
        }
        spinner.setVisibility(View.VISIBLE);
        if (surface == null) return; // onSurfaceTextureAvailable starts it

        JSONObject s = streams.optJSONObject(stream);
        String url = s.optString("u");
        Map<String, String> headers = new HashMap<>();
        if (s.has("ua")) headers.put("User-Agent", s.optString("ua"));
        if (s.has("r")) headers.put("Referer", s.optString("r"));
        Log.i(MainActivity.TAG, "play " + channel.optString("id") + " #" + stream + " " + url);
        h.postDelayed(stalled, TIMEOUT_MS);
        // Pick the stream's video variant ourselves (network, so off the main thread), then start the player.
        new Thread(() -> {
            String chosen = url;
            try {
                chosen = videoVariant(url, headers);
            } catch (Exception e) {
                Log.i(MainActivity.TAG, "variant: " + e);
            }
            String target = chosen;
            h.post(() -> { if (gen == generation) start(target, headers, gen); });
        }, "variant").start();
    }

    private void start(String url, Map<String, String> headers, int gen) {
        if (surface == null) return; // onSurfaceTextureAvailable plays it again
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            mp.setSurface(surface);
            mp.setScreenOnWhilePlaying(true);
            mp.setDataSource(a, Uri.parse(url), headers);
        } catch (IOException | RuntimeException e) {
            Log.i(MainActivity.TAG, "can't open stream: " + e);
            h.post(() -> { if (gen == generation) next("can't open"); });
            return;
        }
        mp.setOnPreparedListener(p -> {
            if (gen != generation) return;
            h.removeCallbacks(stalled);
            h.postDelayed(noPicture, 12_000);
            p.start();
        });
        mp.setOnVideoSizeChangedListener((p, w, ht) -> {
            if (gen != generation || w == 0 || ht == 0) return;
            videoW = w;
            videoH = ht;
            fit();
        });
        mp.setOnInfoListener((p, what, extra) -> {
            if (gen != generation) return false;
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                h.removeCallbacks(noPicture);
                curtain.setVisibility(View.GONE);
                spinner.setVisibility(View.GONE);
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) spinner.setVisibility(View.VISIBLE);
            else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) spinner.setVisibility(View.GONE);
            return false;
        });
        mp.setOnErrorListener((p, what, extra) -> {
            if (gen == generation) next("error " + what + "/" + extra);
            return true;
        });
        mp.setOnCompletionListener(p -> { if (gen == generation) next("ended"); });
        try {
            mp.prepareAsync();
        } catch (IllegalStateException e) {
            // The surface can still be busy with the channel before; give it a moment and try the same stream again.
            Log.i(MainActivity.TAG, "prepare failed, retrying: " + e);
            release();
            if (retries++ < 3) h.postDelayed(() -> { if (gen == generation) play(); }, 400);
            else h.post(() -> { if (gen == generation) next("can't prepare"); });
            return;
        }
        probeAudio(url, headers, gen);
    }

    /**
     * For an HLS master playlist, the URL of its best variant that has video (up to 1080p). Android's player picks by
     * bandwidth alone and can land on an audio-only variant (some free channels list one), which plays sound with no
     * picture. Returns `url` itself when it isn't a master playlist or says nothing about video.
     */
    private static String videoVariant(String url, Map<String, String> headers) throws IOException {
        if (!url.toLowerCase(Locale.ROOT).contains(".m3u8")) return url;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        String best = null, base;
        long bestScore = -1;
        boolean master = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            base = c.getURL().toString();
            String inf = null;
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (line.startsWith("#EXT-X-STREAM-INF:")) { inf = line; master = true; continue; }
                if (inf == null || line.isEmpty() || line.startsWith("#")) continue;
                String attrs = inf.toUpperCase(Locale.ROOT);
                inf = null;
                int height = 0;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("RESOLUTION=(\\d+)X(\\d+)").matcher(attrs);
                if (m.find()) height = Integer.parseInt(m.group(2));
                boolean video = height > 0 || attrs.contains("AVC1") || attrs.contains("HVC1") || attrs.contains("HEV1");
                if (!video) continue;
                java.util.regex.Matcher bw = java.util.regex.Pattern.compile("BANDWIDTH=(\\d+)").matcher(attrs);
                long bandwidth = bw.find() ? Long.parseLong(bw.group(1)) : 0;
                // H.264 over HEVC (Fire OS 7's player can't read HEVC in HLS transport streams); then up to 1080p, the
                // higher the better; then the bandwidth.
                boolean hevc = attrs.contains("HVC1") || attrs.contains("HEV1");
                long score = (hevc ? 0 : 1_000_000_000_000L) + (height > 1080 ? 0 : height) * 100_000_000L
                        + Math.min(bandwidth, 99_999_999L);
                if (score > bestScore) { bestScore = score; best = line; }
            }
        } finally {
            c.disconnect();
        }
        if (!master || best == null) return url;
        String chosen = new URL(new URL(base), best).toString();
        Log.i(MainActivity.TAG, "variant " + chosen);
        return chosen;
    }

    /** Shows a message on the video ("na": nothing plays, "audio": no sound), worded for the current size. */
    private void setStatus(String kind) {
        statusKind = kind;
        if (kind == null) { status.setVisibility(View.GONE); return; }
        boolean mini = mode == MINI;
        status.setText("na".equals(kind)
                ? (mini ? "Not available right now" : "This channel isn't available right now.\n▲ ▼  try another channel")
                : (mini ? "No sound on this TV" : "No sound: this TV can't play this channel's audio (" + badAudio + ")."));
        status.setVisibility(View.VISIBLE);
    }

    private void next(String why) {
        Log.i(MainActivity.TAG, "stream " + stream + " of " + channel.optString("id") + ": " + why);
        stream++;
        retries = 0;
        play();
    }

    /** Stops playback for good (the panel is hidden or the app leaves the screen). */
    void stop() {
        generation++;
        h.removeCallbacks(stalled);
        h.removeCallbacks(noPicture);
        release();
        channel = null;
    }

    /** Releases the current player off the main thread: a stuck network stream can make release() slow. */
    private void release() {
        MediaPlayer old = player;
        player = null;
        if (old == null) return;
        old.setOnErrorListener(null);
        old.setOnInfoListener(null);
        old.setOnPreparedListener(null);
        old.setOnCompletionListener(null);
        old.setOnVideoSizeChangedListener(null);
        try {
            old.setSurface(null); // free the surface for the next player right away
        } catch (RuntimeException ignored) {}
        new Thread(old::release, "player-release").start();
    }

    // ---------- Audio this TV can't decode

    /**
     * Fire TV sticks can't decode MPEG-1 Layer II audio, which some broadcast streams use: the picture plays with no
     * sound and no error, and HLS doesn't list its tracks to the app. So read the first segment's formats ourselves.
     */
    private void probeAudio(String url, Map<String, String> headers, int gen) {
        new Thread(() -> {
            String bad = null;
            try {
                bad = undecodableAudio(firstSegment(url, headers), headers);
            } catch (Exception e) {
                Log.i(MainActivity.TAG, "audio probe: " + e);
            }
            String audio = bad;
            if (audio != null) h.post(() -> onBadAudio(audio, gen));
        }, "audio-probe").start();
    }

    private void onBadAudio(String audio, int gen) {
        if (gen != generation) return;
        JSONArray streams = channel.optJSONArray("s");
        if (streams != null && stream + 1 < streams.length()) { next("audio " + audio + " can't be decoded"); return; }
        badAudio = audio;
        setStatus("audio");
        h.postDelayed(() -> { if (gen == generation) setStatus(null); }, 6000);
    }

    /** The first media segment of an HLS playlist (following a master playlist to its first variant), or null. */
    private static String firstSegment(String url, Map<String, String> headers) throws IOException {
        for (int depth = 0; depth < 3; depth++) {
            if (!url.toLowerCase(Locale.ROOT).contains(".m3u8")) return depth == 0 ? null : url;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            String next = null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                url = c.getURL().toString(); // after redirects, for relative links
                for (String line; (line = r.readLine()) != null; ) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) { next = line; break; }
                }
            } finally {
                c.disconnect();
            }
            if (next == null) return null;
            url = new URL(new URL(url), next).toString();
            if (!url.toLowerCase(Locale.ROOT).contains(".m3u8")) return url;
        }
        return null;
    }

    /** The audio format in `segment` that this TV has no decoder for, or null. */
    private static String undecodableAudio(String segment, Map<String, String> headers) throws IOException {
        if (segment == null) return null;
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(segment, headers);
            MediaCodecList codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            String bad = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) continue;
                int rate = f.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
                int ch = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
                if (codecs.findDecoderForFormat(MediaFormat.createAudioFormat(mime, rate, ch)) != null) return null;
                bad = mime.replace("audio/", "");
            }
            return bad;
        } finally {
            ex.release();
        }
    }

    // ---------- Overlay and keys (full screen only)

    private TextView text(int sp) {
        TextView t = new TextView(a);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        t.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private int dp(int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }

    private void showInfo() {
        if (channel == null) return;
        String meta = channel.optString("c");
        if (!channel.optString("g").isEmpty()) meta += "  ·  " + channel.optString("g");
        info.setText(channel.optString("n") + "\n" + meta + "\n" + (index + 1) + " / " + list.length()
                + "     ▲ ▼  change channel     Back  keep browsing");
        info.animate().cancel();
        info.setAlpha(1f);
        h.removeCallbacks(hideInfo);
        h.postDelayed(hideInfo, 5000);
    }

    /** Remote keys while full screen. Back shrinks the video into the mini window. */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                if (down && e.getRepeatCount() == 0) setMode(MINI);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down) tune(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down) tune(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MENU:
                if (down) showInfo();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (down && player != null) {
                    try {
                        if (player.isPlaying()) player.pause(); else player.start();
                    } catch (IllegalStateException ignored) {}
                }
                return true;
        }
        return false;
    }
}
