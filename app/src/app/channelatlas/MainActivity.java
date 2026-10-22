package app.channelatlas;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The browsing screen: a WebView with the UI in assets/, talking to Java through `Android`. */
public class MainActivity extends Activity {
    static final String TAG = "ChannelAtlas";

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean syncing;
    private boolean loaded;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B1220);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) { loaded = true; }
        });
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        web.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String id = PlayerActivity.lastId;
        if (loaded && id != null) js("app.returned(" + JSONObject.quote(id) + ")");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
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
    protected void onDestroy() {
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

        @JavascriptInterface
        public void play(String list, int index) {
            try {
                PlayerActivity.list = new JSONArray(list);
                PlayerActivity.index = index;
            } catch (JSONException e) {
                Log.w(TAG, "bad play list", e);
                return;
            }
            ui.post(() -> startActivity(new Intent(MainActivity.this, PlayerActivity.class)));
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
