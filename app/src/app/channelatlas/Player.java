package app.channelatlas;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The video panel that sits over the browsing screen. Three modes:
 * NONE (hidden), FULL (whole screen, takes the remote) and MINI (a small window in the browsing screen's hero area
 * that keeps playing while you browse).
 *
 * Plays with Media3 ExoPlayer on a TextureView (which resizes and clips smoothly). ExoPlayer reads HEVC in HLS, skips
 * audio-only variants and tells which tracks the TV can decode, which Android's own MediaPlayer on Fire OS 7 doesn't.
 * Captions stay off: they're rarely useful here and some streams' captions are broken.
 */
final class Player {
    static final int NONE = 0, FULL = 1, MINI = 2;

    interface Listener {
        /** Called when the mode or the channel changes. `channel` is null when nothing plays. */
        void onPlayer(int mode, JSONObject channel);
    }

    private static final int TIMEOUT_MS = 20_000, PICTURE_MS = 12_000;

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

    private ExoPlayer exo;
    private JSONArray list = new JSONArray();
    private int index;
    private JSONObject channel;
    private int stream, generation, videoW, videoH;
    private boolean ready, firstFrame, audioChecked;
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

    // ---------- ExoPlayer

    private ExoPlayer exo() {
        if (exo != null) return exo;
        exo = new ExoPlayer.Builder(a).build();
        exo.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        exo.setWakeMode(C.WAKE_MODE_NETWORK);
        exo.setTrackSelectionParameters(exo.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setMaxVideoSize(1920, 1080) // the TV's own resolution; 4K variants only cost bandwidth
                .build());
        exo.setVideoTextureView(texture);
        exo.addListener(new androidx.media3.common.Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == androidx.media3.common.Player.STATE_BUFFERING) {
                    spinner.setVisibility(View.VISIBLE);
                } else if (state == androidx.media3.common.Player.STATE_READY) {
                    h.removeCallbacks(stalled);
                    spinner.setVisibility(View.GONE);
                    if (!ready) {
                        ready = true;
                        if (!firstFrame && hasVideo()) h.postDelayed(noPicture, PICTURE_MS);
                        else if (!hasVideo()) curtain.setVisibility(View.GONE); // radio-style, sound only
                    }
                } else if (state == androidx.media3.common.Player.STATE_ENDED) {
                    next("ended");
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                firstFrame = true;
                h.removeCallbacks(noPicture);
                curtain.setVisibility(View.GONE);
                spinner.setVisibility(View.GONE);
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                if (size.width == 0 || size.height == 0) return;
                videoW = Math.round(size.width * size.pixelWidthHeightRatio);
                videoH = size.height;
                fit();
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                checkAudio(tracks);
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // A live stream we fell behind on: jump back to the live edge.
                    exo.seekToDefaultPosition();
                    exo.prepare();
                    return;
                }
                next("error " + e.getErrorCodeName() + ": " + e.getMessage());
            }
        });
        return exo;
    }

    private boolean hasVideo() {
        return exo != null && exo.getCurrentTracks().containsType(C.TRACK_TYPE_VIDEO);
    }

    /** Audio the TV can't decode (some broadcast streams' MPEG-1 Layer II on Fire TV sticks): try the next stream. */
    private void checkAudio(Tracks tracks) {
        if (audioChecked || !tracks.containsType(C.TRACK_TYPE_AUDIO)) return;
        boolean playable = false;
        String format = null;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < g.length; i++) {
                if (g.isTrackSupported(i)) playable = true;
                else if (format == null) format = g.getTrackFormat(i).sampleMimeType;
            }
        }
        audioChecked = true;
        if (playable) return;
        badAudio = format == null ? "unknown" : format.replace("audio/", "");
        JSONArray streams = channel.optJSONArray("s");
        if (streams != null && stream + 1 < streams.length()) { next("audio " + badAudio + " can't be decoded"); return; }
        int gen = generation;
        setStatus("audio");
        h.postDelayed(() -> { if (gen == generation) setStatus(null); }, 10_000);
    }

    // ---------- Channels and streams

    /** Moves `delta` channels along the list and starts the new one from its first stream. */
    private void tune(int delta) {
        int n = list.length();
        if (n == 0) { setMode(NONE); return; }
        index = ((index + delta) % n + n) % n;
        channel = list.optJSONObject(index);
        stream = 0;
        if (mode == FULL) showInfo();
        listener.onPlayer(mode, channel);
        play();
    }

    private void play() {
        generation++;
        h.removeCallbacks(stalled);
        h.removeCallbacks(noPicture);
        ready = firstFrame = audioChecked = false;
        curtain.setVisibility(View.VISIBLE);
        setStatus(null);
        JSONArray streams = channel.optJSONArray("s");
        if (streams == null || stream >= streams.length()) {
            if (exo != null) exo.stop();
            spinner.setVisibility(View.GONE);
            setStatus("na");
            return;
        }
        spinner.setVisibility(View.VISIBLE);
        JSONObject s = streams.optJSONObject(stream);
        String url = s.optString("u");
        Log.i(MainActivity.TAG, "play " + channel.optString("id") + " #" + stream + " " + url);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
                .setUserAgent(s.has("ua") ? s.optString("ua") : "ChannelAtlas/1.0 (Android TV)");
        if (s.has("r")) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", s.optString("r"));
            http.setDefaultRequestProperties(headers);
        }
        MediaItem.Builder item = new MediaItem.Builder().setUri(url);
        if (url.toLowerCase(Locale.ROOT).contains(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);

        ExoPlayer p = exo();
        p.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(item.build()));
        p.prepare();
        p.setPlayWhenReady(true);
        h.postDelayed(stalled, TIMEOUT_MS);
    }

    private void next(String why) {
        Log.i(MainActivity.TAG, "stream " + stream + " of " + channel.optString("id") + ": " + why);
        stream++;
        play();
    }

    /** Stops playback for good (the panel is hidden or the app leaves the screen). */
    void stop() {
        generation++;
        h.removeCallbacks(stalled);
        h.removeCallbacks(noPicture);
        if (exo != null) {
            exo.release();
            exo = null;
        }
        channel = null;
        statusKind = null;
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
                if (down && exo != null) exo.setPlayWhenReady(!exo.getPlayWhenReady());
                return true;
        }
        return false;
    }
}
