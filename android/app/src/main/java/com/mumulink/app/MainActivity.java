package com.mumulink.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    static final String LAUNCHER = "file:///android_asset/launcher.html";

    WebView web;
    SharedPreferences prefs;
    final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("mumulink", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "App");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                String u = r.getUrl().toString();
                if (r.isForMainFrame() && !u.startsWith(LAUNCHER)) {
                    ui.post(() -> v.loadUrl(LAUNCHER + "#error"));
                }
            }
        });

        hideBars();
        if (state != null) web.restoreState(state);
        else web.loadUrl(LAUNCHER);
    }

    void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    public void onBackPressed() {
        String u = web.getUrl();
        if (u != null && !u.startsWith(LAUNCHER)) {
            // 화면 보기 중 -> 기기 목록 / 기기 목록 -> 앱 첫 화면 / 매크로 화면 -> 기기 목록(이전 페이지)
            web.evaluateJavascript("window.appBack ? (window.appBack() ? 'y' : 'n') : 'x'", r -> {
                if (r != null && r.contains("y")) return;
                if (r != null && r.contains("x") && web.canGoBack()) web.goBack();
                else web.loadUrl(LAUNCHER + "#home");
            });
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    /** launcher.html 과 뷰어 페이지에서 쓰는 기능 */
    class Bridge {
        @JavascriptInterface
        public String get(String key) {
            return prefs.getString(key, "");
        }

        @JavascriptInterface
        public void set(String key, String value) {
            prefs.edit().putString(key, value).apply();
        }

        @JavascriptInterface
        public void home() {
            ui.post(() -> web.loadUrl(LAUNCHER + "#home"));
        }

        @JavascriptInterface
        public void resolve(final String code) {
            new Thread(() -> {
                String url = null, err = null;
                try {
                    url = lookup(code);
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                final String js = "window.onResolved(" + JSONObject.quote(url == null ? "" : url)
                        + "," + JSONObject.quote(err == null ? "" : err) + ")";
                ui.post(() -> web.evaluateJavascript(js, null));
            }).start();
        }
    }

    /** PC 프로그램이 ntfy.sh 에 올려둔 최신 접속 주소를 가져온다 */
    static String lookup(String code) throws Exception {
        URL u = new URL("https://ntfy.sh/mumulink-" + code + "/json?poll=1&since=24h");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        String last = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                JSONObject o = new JSONObject(line);
                if ("message".equals(o.optString("event"))) {
                    String m = o.optString("message").trim();
                    if (m.matches("https://[a-z0-9-]+\\.trycloudflare\\.com")) last = m;
                }
            }
        } finally {
            c.disconnect();
        }
        return last;
    }
}
