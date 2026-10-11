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
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView web;
    private static String userAgent = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        try { userAgent = WebSettings.getDefaultUserAgent(this); } catch (Exception ignored) { }
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

    /** Result of an HTTP call: ok flag plus body (or error text). */
    static final class Res { boolean ok; String body; }

    private static Res request(String method, String url, Map<String, String> headers, String body) {
        Res r = new Res();
        HttpURLConnection c = null;
        try {
            if (!url.startsWith("https://")) throw new Exception("Only https is allowed");
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            // Some exchanges turn away requests that don't look like a phone browser.
            c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Accept", "application/json");
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            r.ok = code < 400;
            r.body = r.ok ? text : ("HTTP " + code + " " + text);
        } catch (Exception e) {
            r.ok = false;
            r.body = String.valueOf(e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    private void reply(String id, Res r) {
        final String js = "window.__httpCb(" + JSONObject.quote(id) + "," + r.ok + "," + JSONObject.quote(r.body) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private static String hmacHex(String secret, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] h = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // API keys are encrypted with a key that never leaves the phone's Android Keystore,
    // stored outside Android backups, and never handed back to the page.
    private SecretKey vaultKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias("rl_api")) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder("rl_api", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry("rl_api", null)).getSecretKey();
    }

    private File keyFile(String ex) { return new File(getNoBackupFilesDir(), "api_" + ex.replaceAll("[^a-z]", "") + ".bin"); }

    private void writeKeys(String ex, String key, String secret) throws Exception {
        JSONObject o = new JSONObject();
        o.put("k", key);
        o.put("s", secret);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, vaultKey());
        byte[] ct = c.doFinal(o.toString().getBytes(StandardCharsets.UTF_8));
        String out = Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        try (FileOutputStream f = new FileOutputStream(keyFile(ex))) { f.write(out.getBytes(StandardCharsets.UTF_8)); }
    }

    private String[] readKeys(String ex) {
        File f = keyFile(ex);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            String[] parts = readAll(in).split(":");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, vaultKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            JSONObject o = new JSONObject(new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
            return new String[]{o.getString("k"), o.getString("s")};
        } catch (Exception e) { return null; }
    }

    private static long serverTime(String url, String field) {
        Res r = request("GET", url, null, null);
        try {
            if (r.ok) {
                JSONObject o = new JSONObject(r.body);
                return field == null ? o.getLong("data") : o.getLong(field);
            }
        } catch (Exception ignored) { }
        return System.currentTimeMillis();
    }

    /** Only these fixed read-only endpoints can be signed; the page cannot ask for anything else. */
    private Res signedGet(String ex, String kind) {
        Res r = new Res();
        String[] k = readKeys(ex);
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
                return request("GET", base + path + "?" + q + "&signature=" + hmacHex(k[1], q), h, null);
            } else if (ex.equals("mexc")) {
                String path, params = "";
                if (kind.equals("futAssets")) path = "/api/v1/private/account/assets";
                else if (kind.equals("positions")) path = "/api/v1/private/position/open_positions";
                else if (kind.equals("stopOrders")) { path = "/api/v1/private/stoporder/list/orders"; params = "is_finished=0&page_num=1&page_size=100"; }
                else { r.body = "Unknown request"; return r; }
                String ts = String.valueOf(serverTime("https://contract.mexc.com/api/v1/contract/ping", null));
                h.put("ApiKey", k[0]);
                h.put("Request-Time", ts);
                h.put("Signature", hmacHex(k[1], k[0] + ts + params));
                h.put("Content-Type", "application/json");
                String q = params.isEmpty() ? "" : "?" + params;
                Res first = request("GET", "https://contract.mexc.com" + path + q, h, null);
                // If the main futures address is blocked on this network, MEXC's website address serves the same API.
                if (!first.ok && (first.body == null || !first.body.contains("{"))) return request("GET", "https://futures.mexc.com" + path + q, h, null);
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
            new Thread(() -> reply(id, request("GET", url, null, null))).start();
        }

        /** HTTPS POST with a JSON body, used for the public Binance P2P price search. */
        @JavascriptInterface
        public void httpPost(final String id, final String url, final String json) {
            new Thread(() -> {
                if (!url.startsWith("https://p2p.binance.com/")) { Res r = new Res(); r.body = "blocked"; reply(id, r); return; }
                reply(id, request("POST", url, null, json));
            }).start();
        }

        @JavascriptInterface
        public String saveKeys(String ex, String key, String secret) {
            try {
                if (key == null || secret == null || key.trim().isEmpty() || secret.trim().isEmpty()) return "Enter both the API key and the secret.";
                writeKeys(ex, key.trim(), secret.trim());
                return "";
            } catch (Exception e) { return "Couldn't save the key: " + e.getMessage(); }
        }

        /** Masked key for display, or "" when not connected. */
        @JavascriptInterface
        public String keyInfo(String ex) {
            String[] k = readKeys(ex);
            if (k == null) return "";
            String a = k[0];
            return a.length() <= 8 ? "••••" : a.substring(0, 4) + "…" + a.substring(a.length() - 4);
        }

        @JavascriptInterface
        public void removeKeys(String ex) { keyFile(ex).delete(); }

        @JavascriptInterface
        public void privateGet(final String id, final String ex, final String kind) {
            new Thread(() -> reply(id, signedGet(ex, kind))).start();
        }

        @JavascriptInterface
        public void setDark(final boolean dark) {
            runOnUiThread(() -> applyBars(dark));
        }
    }
}
