package com.riskledger.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            // Exchange links open in the exchange's app (if installed) or the browser, never inside Risk Ledger.
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                    return true;
                }
                return false;
            }
        });
        web.addJavascriptInterface(new Store(), "AndroidStore");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.appBack && window.appBack()", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @SuppressWarnings("deprecation")
    private void applyBars(boolean dark) {
        Window w = getWindow();
        int bg = dark ? 0xFF0E1015 : 0xFFF5F6F8;
        int nav = dark ? 0xFF171A21 : 0xFFFFFFFF;
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(nav);
        web.setBackgroundColor(bg);
        View d = w.getDecorView();
        int f = d.getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        d.setSystemUiVisibility(dark ? (f & ~light) : (f | light));
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    /** Exposed to the page as window.AndroidStore. */
    class Store {
        private File file() { return new File(getFilesDir(), "ledger.json"); }

        @JavascriptInterface
        public String load() {
            File f = file();
            if (!f.exists()) return null;
            try (FileInputStream in = new FileInputStream(f)) {
                return readAll(in);
            } catch (Exception e) { return null; }
        }

        @JavascriptInterface
        public synchronized void save(String json) {
            File tmp = new File(getFilesDir(), "ledger.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (Exception e) { return; }
            tmp.renameTo(file());
        }

        @JavascriptInterface
        public void share(String title, String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, "Risk Ledger - " + title);
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, title));
            });
        }

        /** Read-only HTTPS GET for public prices and exchange rates; answers via window.__httpCb. */
        @JavascriptInterface
        public void httpGet(final String id, final String url) {
            new Thread(() -> {
                boolean ok = false;
                String body;
                HttpURLConnection c = null;
                try {
                    if (!url.startsWith("https://")) throw new Exception("Only https is allowed");
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("User-Agent", "RiskLedger/1.1 (Android)");
                    c.setRequestProperty("Accept", "application/json");
                    int code = c.getResponseCode();
                    if (code >= 400) {
                        body = "HTTP " + code;
                    } else {
                        try (InputStream in = c.getInputStream()) { body = readAll(in); }
                        ok = true;
                    }
                } catch (Exception e) {
                    body = String.valueOf(e.getMessage());
                } finally {
                    if (c != null) c.disconnect();
                }
                final String js = "window.__httpCb(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(body) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        @JavascriptInterface
        public void setDark(final boolean dark) {
            runOnUiThread(() -> applyBars(dark));
        }
    }
}
