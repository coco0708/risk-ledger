package com.riskledger.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        try { Net.userAgent = WebSettings.getDefaultUserAgent(this); } catch (Exception ignored) { }
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

    private void reply(String id, Net.Res r) {
        final String js = "window.__httpCb(" + JSONObject.quote(id) + "," + r.ok + "," + JSONObject.quote(r.body == null ? "" : r.body) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private void replyJson(String id, boolean ok, String body) {
        Net.Res r = new Net.Res(); r.ok = ok; r.body = body; reply(id, r);
    }

    private static long serverTime(String url, String field) {
        Net.Res r = Net.request("GET", url, null, null);
        try {
            if (r.ok) {
                JSONObject o = new JSONObject(r.body);
                return field == null ? o.getLong("data") : o.getLong(field);
            }
        } catch (Exception ignored) { }
        return System.currentTimeMillis();
    }

    /** Only these fixed read-only endpoints can be signed; the page cannot ask for anything else. */
    private Net.Res signedGet(String ex, String kind) {
        Net.Res r = new Net.Res();
        String[] k = Vault.read(this, ex);
        if (k == null) { r.body = "No API key saved for this exchange."; return r; }
        try {
            Map<String, String> h = new HashMap<>();
            if (ex.equals("binance") || (ex.equals("mexc") && kind.equals("spot"))) {
                if (ex.equals("mexc") && !kind.equals("spot")) { r.body = "Unknown request"; return r; }
                boolean fut = kind.equals("futAccount") || kind.equals("positions") || kind.equals("openOrders") || kind.equals("algoOrders");
                String base = ex.equals("mexc") ? "https://api.mexc.com" : (fut ? "https://fapi.binance.com" : "https://api.binance.com");
                String path;
                if (kind.equals("spot")) path = "/api/v3/account";
                else if (kind.equals("wallets") && ex.equals("binance")) path = "/sapi/v1/asset/wallet/balance";
                else if (kind.equals("futAccount")) path = "/fapi/v2/account";
                else if (kind.equals("positions")) path = "/fapi/v2/positionRisk";
                else if (kind.equals("openOrders")) path = "/fapi/v1/openOrders";
                else if (kind.equals("algoOrders")) path = "/fapi/v1/openAlgoOrders";
                else { r.body = "Unknown request"; return r; }
                long ts = serverTime(base + (fut ? "/fapi/v1/time" : "/api/v3/time"), "serverTime");
                String q = "recvWindow=10000&timestamp=" + ts;
                h.put(ex.equals("mexc") ? "X-MEXC-APIKEY" : "X-MBX-APIKEY", k[0]);
                return Net.request("GET", base + path + "?" + q + "&signature=" + Net.hmacHex(k[1], q), h, null);
            } else if (ex.equals("mexc")) {
                String path, params = "";
                if (kind.equals("futAssets")) path = "/api/v1/private/account/assets";
                else if (kind.equals("positions")) path = "/api/v1/private/position/open_positions";
                else if (kind.equals("stopOrders")) { path = "/api/v1/private/stoporder/list/orders"; params = "is_finished=0&page_num=1&page_size=100"; }
                else { r.body = "Unknown request"; return r; }
                String ts = String.valueOf(serverTime("https://contract.mexc.com/api/v1/contract/ping", null));
                h.put("ApiKey", k[0]);
                h.put("Request-Time", ts);
                h.put("Signature", Net.hmacHex(k[1], k[0] + ts + params));
                h.put("Content-Type", "application/json");
                String q = params.isEmpty() ? "" : "?" + params;
                Net.Res first = Net.request("GET", "https://contract.mexc.com" + path + q, h, null);
                // If the main futures address is blocked on this network, MEXC's website address serves the same API.
                if (!first.ok && (first.body == null || !first.body.contains("{"))) return Net.request("GET", "https://futures.mexc.com" + path + q, h, null);
                return first;
            }
            r.body = "This exchange can't be connected yet.";
        } catch (Exception e) { r.body = String.valueOf(e.getMessage()); }
        return r;
    }

    /** Exposed to the page as window.AndroidStore. */
    class Store {
        private File file() { return new File(getFilesDir(), "ledger.json"); }

        @JavascriptInterface
        public String load() {
            File f = file();
            if (!f.exists()) return null;
            try (FileInputStream in = new FileInputStream(f)) {
                return Net.readAll(in);
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
            new Thread(() -> reply(id, Net.request("GET", url, null, null))).start();
        }

        /** HTTPS POST with a JSON body, used for the public Binance P2P price search. */
        @JavascriptInterface
        public void httpPost(final String id, final String url, final String json) {
            new Thread(() -> {
                if (!url.startsWith("https://p2p.binance.com/")) { Net.Res r = new Net.Res(); r.body = "blocked"; reply(id, r); return; }
                reply(id, Net.request("POST", url, null, json));
            }).start();
        }

        @JavascriptInterface
        public String saveKeys(String ex, String key, String secret) {
            try {
                if (key == null || secret == null || key.trim().isEmpty() || secret.trim().isEmpty()) return "Enter both the API key and the secret.";
                Vault.write(MainActivity.this, ex, key.trim(), secret.trim());
                return "";
            } catch (Exception e) { return "Couldn't save the key: " + e.getMessage(); }
        }

        /** Masked key for display, or "" when not connected. */
        @JavascriptInterface
        public String keyInfo(String ex) {
            String[] k = Vault.read(MainActivity.this, ex);
            if (k == null) return "";
            String a = k[0];
            return a.length() <= 8 ? "••••" : a.substring(0, 4) + "…" + a.substring(a.length() - 4);
        }

        @JavascriptInterface
        public void removeKeys(String ex) { Vault.remove(MainActivity.this, ex); }

        @JavascriptInterface
        public void privateGet(final String id, final String ex, final String kind) {
            new Thread(() -> reply(id, signedGet(ex, kind))).start();
        }

        // ---------- autopilot ----------

        @JavascriptInterface
        public void apSetPlan(String json) { Autopilot.setPlan(MainActivity.this, json); }

        @JavascriptInterface
        public void apStart() { AutopilotService.start(MainActivity.this); }

        @JavascriptInterface
        public void apStop() { AutopilotService.stop(MainActivity.this); }

        @JavascriptInterface
        public String apStatus() { return Autopilot.statusJson(MainActivity.this); }

        @JavascriptInterface
        public String apEvents() { return Autopilot.takeEvents(MainActivity.this); }

        /** One watcher pass right now (used every few seconds while the app is open). */
        @JavascriptInterface
        public void apRunNow(final String id) {
            new Thread(() -> replyJson(id, true, Autopilot.runOnce(MainActivity.this))).start();
        }

        /** Trading actions on Binance futures: check | open | close | cancelEntry. */
        @JavascriptInterface
        public void futAction(final String id, final String action, final boolean test, final String json) {
            new Thread(() -> {
                try {
                    JSONObject j = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
                    JSONObject out;
                    if (action.equals("check")) out = Futures.check(MainActivity.this, test);
                    else if (action.equals("open")) out = Futures.open(MainActivity.this, test, j);
                    else if (action.equals("close")) out = Futures.close(MainActivity.this, test, j);
                    else if (action.equals("cancelEntry")) out = Futures.cancelEntry(MainActivity.this, test, j);
                    else { replyJson(id, false, "Unknown action"); return; }
                    replyJson(id, true, out.toString());
                } catch (Exception e) { replyJson(id, false, String.valueOf(e.getMessage())); }
            }).start();
        }

        @JavascriptInterface
        public void askNotifications() {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
                runOnUiThread(() -> requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7));
        }

        @JavascriptInterface
        public boolean batteryFree() {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        /** Ask Android to let the autopilot keep running with the screen off. */
        @JavascriptInterface
        public void askBattery() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface
        public void setDark(final boolean dark) {
            runOnUiThread(() -> applyBars(dark));
        }
    }
}
