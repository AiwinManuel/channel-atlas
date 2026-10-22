package app.channelatlas;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The one screen: the browsing UI (a WebView with the page in assets/) and, over it, the video panel (Player), which
 * is either hidden, full screen or a mini window that keeps playing while you browse.
 */
public class MainActivity extends Activity implements Player.Listener {
    static final String TAG = "ChannelAtlas";

    private WebView web;
    private Player player;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean syncing;
    private boolean loaded;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0A0F);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0A0A0F);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) { loaded = true; }
        });
        web.loadUrl("file:///android_asset/index.html");
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        player = new Player(this, root, this);
        setContentView(root);
        web.requestFocus();
    }

    @Override
    public void onPlayer(int mode, JSONObject channel) {
        if (mode != Player.FULL) web.requestFocus();
        if (!loaded) return;
        JSONObject o = new JSONObject();
        try {
            o.put("mode", mode == Player.FULL ? "full" : mode == Player.MINI ? "mini" : "none");
            if (channel != null) o.put("id", channel.optString("id")).put("name", channel.optString("n"));
        } catch (JSONException ignored) {}
        js("app.player(" + o + ")");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (player.mode() == Player.FULL && player.onKey(e)) return true;
        String key = null;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_MENU: key = "menu"; break;
            case KeyEvent.KEYCODE_MEDIA_REWIND: key = "rew"; break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: key = "ff"; break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: key = "play"; break;
            case KeyEvent.KEYCODE_BACK:
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    web.evaluateJavascript("window.app ? app.back() : false", v -> { if (!"true".equals(v)) finish(); });
                }
                return true;
        }
        if (key != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) js("app.key('" + key + "')");
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void js(String code) {
        ui.post(() -> web.evaluateJavascript(code, null));
    }

    @Override
    protected void onStop() {
        super.onStop();
        player.setMode(Player.NONE); // Home button or another app: stop the stream
    }

    @Override
    protected void onDestroy() {
        player.stop();
        web.destroy();
        super.onDestroy();
    }

    final class Bridge {
        @JavascriptInterface
        public String catalog() { return Catalog.read(MainActivity.this); }

        @JavascriptInterface
        public double age() { return (double) Catalog.age(MainActivity.this); }

        @JavascriptInterface
        public void sync() {
            if (syncing) return;
            syncing = true;
            Context c = getApplicationContext();
            new Thread(() -> {
                String error = null;
                try {
                    Catalog.sync(c, msg -> js("app.progress(" + JSONObject.quote(msg) + ")"));
                } catch (Exception e) {
                    Log.w(TAG, "sync failed", e);
                    error = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                syncing = false;
                js("app.synced(" + (error == null ? "null" : JSONObject.quote(error)) + ")");
            }, "catalog-sync").start();
        }

        /** Plays `list[index]` full screen; ▲/▼ in the player move along `list`. */
        @JavascriptInterface
        public void play(String list, int index) {
            JSONArray l;
            try {
                l = new JSONArray(list);
            } catch (JSONException e) {
                Log.w(TAG, "bad play list", e);
                return;
            }
            ui.post(() -> player.open(l, index));
        }

        /** The mini window back to full screen. */
        @JavascriptInterface
        public void expand() { ui.post(() -> { if (player.mode() == Player.MINI) player.setMode(Player.FULL); }); }

        /** Stops the mini window. */
        @JavascriptInterface
        public void stop() { ui.post(() -> player.setMode(Player.NONE)); }

        /** Where the mini window goes, in the page's CSS pixels. */
        @JavascriptInterface
        public void miniRect(double x, double y, double w, double h, double cssWidth) {
            ui.post(() -> player.setMiniRect(x, y, w, h, cssWidth));
        }

        @JavascriptInterface
        public void keyboard() {
            ui.post(() -> {
                InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (im != null) im.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
            });
        }

        @JavascriptInterface
        public void log(String message) { Log.i(TAG, message); }
    }
}
