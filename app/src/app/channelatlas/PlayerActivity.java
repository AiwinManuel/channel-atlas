package app.channelatlas;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
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
 * Full-screen player. Up/Down (or Channel +/-) zaps through the list it was opened with; OK shows what's on.
 * Each channel can have several stream URLs: when one fails, stalls or has audio this TV can't play, the next is tried.
 *
 * A plain MediaPlayer on a SurfaceView rather than VideoView: VideoView turns on closed captions by itself, and on
 * Fire OS 7 (Android 9) the media server crashes on some streams' captions.
 */
public class PlayerActivity extends Activity implements SurfaceHolder.Callback {
    static JSONArray list = new JSONArray();
    static int index;
    /** The channel watched last, so the grid can put the focus back on it. */
    static String lastId;

    private static final int TIMEOUT_MS = 20_000;

    private FrameLayout root;
    private SurfaceView surface;
    private View curtain;
    private ProgressBar spinner;
    private TextView info, status;
    private final Handler h = new Handler(Looper.getMainLooper());

    private SurfaceHolder holder;
    private MediaPlayer player;
    private JSONObject channel;
    private int stream;
    /** Bumped for every stream tried, so late callbacks from the previous one are ignored. */
    private int generation;
    private int videoW, videoH;
    private int retries;

    private final Runnable hideInfo = () -> info.animate().alpha(0f).setDuration(400);
    private final Runnable stalled = () -> next("didn't start");

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> root.post(this::fit));

        // Black until the new channel's first frame, so the last channel's picture never lingers.
        curtain = new View(this);
        curtain.setBackgroundColor(Color.BLACK);
        root.addView(curtain, new FrameLayout.LayoutParams(-1, -1));

        spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));

        status = text(18);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.GONE);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        info = text(16);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD90B1220);
        bg.setCornerRadius(dp(14));
        info.setBackground(bg);
        info.setPadding(dp(22), dp(14), dp(22), dp(14));
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        ip.setMargins(dp(32), dp(28), dp(32), 0);
        root.addView(info, ip);
        setContentView(root);
        tune(0);
    }

    // ---------- Surface

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        if (player != null) player.setDisplay(h);
        else if (channel != null) play();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int ht) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        holder = null;
        if (player != null) player.setDisplay(null);
    }

    /** Letterboxes the picture to the stream's own shape. */
    private void fit() {
        int w = root.getWidth(), ht = root.getHeight();
        if (w == 0 || ht == 0) return;
        int vw = w, vh = ht;
        if (videoW > 0 && videoH > 0) {
            if ((long) videoW * ht > (long) w * videoH) vh = (int) ((long) w * videoH / videoW);
            else vw = (int) ((long) ht * videoW / videoH);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width != vw || lp.height != vh) {
            lp.width = vw;
            lp.height = vh;
            surface.setLayoutParams(lp);
        }
    }

    // ---------- Channels and streams

    /** Moves `delta` channels along the list and starts the new one from its first stream. */
    private void tune(int delta) {
        int n = list.length();
        if (n == 0) { finish(); return; }
        index = ((index + delta) % n + n) % n;
        channel = list.optJSONObject(index);
        lastId = channel.optString("id");
        stream = 0;
        showInfo();
        play();
    }

    private void play() {
        generation++;
        int gen = generation;
        h.removeCallbacks(stalled);
        stopPlayer();
        curtain.setVisibility(View.VISIBLE);
        status.setVisibility(View.GONE);
        JSONArray streams = channel.optJSONArray("s");
        if (streams == null || stream >= streams.length()) {
            spinner.setVisibility(View.GONE);
            status.setText("This channel isn't available right now.\n▲ ▼  try another channel");
            status.setVisibility(View.VISIBLE);
            return;
        }
        spinner.setVisibility(View.VISIBLE);
        if (holder == null) return; // surfaceCreated starts it

        JSONObject s = streams.optJSONObject(stream);
        String url = s.optString("u");
        Map<String, String> headers = new HashMap<>();
        if (s.has("ua")) headers.put("User-Agent", s.optString("ua"));
        if (s.has("r")) headers.put("Referer", s.optString("r"));
        Log.i(MainActivity.TAG, "play " + channel.optString("id") + " #" + stream + " " + url);

        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            mp.setDisplay(holder);
            mp.setScreenOnWhilePlaying(true);
            mp.setDataSource(this, Uri.parse(url), headers);
        } catch (IOException | RuntimeException e) {
            Log.i(MainActivity.TAG, "can't open stream: " + e);
            h.post(() -> { if (gen == generation) next("can't open"); });
            return;
        }
        mp.setOnPreparedListener(p -> {
            if (gen != generation) return;
            h.removeCallbacks(stalled);
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
            stopPlayer();
            if (retries++ < 3) h.postDelayed(() -> { if (gen == generation) play(); }, 400);
            else h.post(() -> { if (gen == generation) next("can't prepare"); });
            return;
        }
        retries = 0;
        h.postDelayed(stalled, TIMEOUT_MS);
        probeAudio(url, headers, gen);
    }

    private void next(String why) {
        Log.i(MainActivity.TAG, "stream " + stream + " of " + channel.optString("id") + ": " + why);
        stream++;
        play();
    }

    /** Releases the current player off the main thread: a stuck network stream can make release() slow. */
    private void stopPlayer() {
        MediaPlayer old = player;
        player = null;
        if (old == null) return;
        old.setOnErrorListener(null);
        old.setOnInfoListener(null);
        old.setOnPreparedListener(null);
        old.setOnCompletionListener(null);
        old.setOnVideoSizeChangedListener(null);
        try {
            old.setDisplay(null); // free the surface for the next player right away
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
        status.setText("No sound: this TV can't play this channel's audio (" + audio + ").");
        status.setVisibility(View.VISIBLE);
        h.postDelayed(() -> { if (gen == generation) status.setVisibility(View.GONE); }, 6000);
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

    // ---------- Overlay and keys

    private TextView text(int sp) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        t.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void showInfo() {
        String meta = channel.optString("c");
        if (!channel.optString("g").isEmpty()) meta += "  ·  " + channel.optString("g");
        info.setText(channel.optString("n") + "\n" + meta + "\n" + (index + 1) + " / " + list.length()
                + "     ▲ ▼  change channel     Back  list");
        info.animate().cancel();
        info.setAlpha(1f);
        h.removeCallbacks(hideInfo);
        h.postDelayed(hideInfo, 5000);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                tune(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                tune(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MENU:
                showInfo();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (player != null) {
                    try {
                        if (player.isPlaying()) player.pause(); else player.start();
                    } catch (IllegalStateException ignored) {}
                }
                return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onStop() {
        super.onStop();
        generation++;
        h.removeCallbacksAndMessages(null);
        stopPlayer();
        finish();
    }
}
