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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.TimeZone;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.net.InetAddress;
import java.net.UnknownHostException;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.dnsoverhttps.DnsOverHttps;
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

    private static final OkHttpClient plain = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build();
    private static OkHttpClient secureDns;

    /**
     * Some networks block exchanges by answering their address lookups with a block page
     * (seen as certificate errors like "Trust anchor for certification path not found").
     * This client looks addresses up over encrypted DNS (Cloudflare, then Google) instead.
     * The connection itself is still normal HTTPS with full certificate checks.
     */
    private static synchronized OkHttpClient secureDnsClient() throws Exception {
        if (secureDns != null) return secureDns;
        OkHttpClient boot = plain.newBuilder().build();
        final DnsOverHttps cf = new DnsOverHttps.Builder().client(boot)
                .url(HttpUrl.get("https://cloudflare-dns.com/dns-query"))
                .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1")).build();
        final DnsOverHttps gg = new DnsOverHttps.Builder().client(boot)
                .url(HttpUrl.get("https://dns.google/dns-query"))
                .bootstrapDnsHosts(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4")).build();
        Dns both = host -> {
            try { return cf.lookup(host); } catch (UnknownHostException e) { return gg.lookup(host); }
        };
        secureDns = plain.newBuilder().dns(both).build();
        return secureDns;
    }

    private static Res call(OkHttpClient client, String method, String url, Map<String, String> headers, String body) throws Exception {
        Request.Builder rb = new Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json");
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) rb.header(h.getKey(), h.getValue());
        if (body != null) rb.method(method, RequestBody.create(body, MediaType.get("application/json; charset=utf-8")));
        else rb.method(method, null);
        try (Response resp = client.newCall(rb.build()).execute()) {
            Res r = new Res();
            String text = resp.body() == null ? "" : resp.body().string();
            r.ok = resp.isSuccessful();
            r.body = r.ok ? text : ("HTTP " + resp.code() + " " + text);
            return r;
        }
    }

    private static Res request(String method, String url, Map<String, String> headers, String body) {
        if (!url.startsWith("https://")) { Res r = new Res(); r.body = "Only https is allowed"; return r; }
        try {
            return call(plain, method, url, headers, body);
        } catch (Exception first) {
            // Couldn't connect, or the network answered with a fake certificate: try again over encrypted DNS.
            try { return call(secureDnsClient(), method, url, headers, body); }
            catch (Exception second) { Res r = new Res(); r.body = String.valueOf(second.getMessage() != null ? second.getMessage() : first.getMessage()); return r; }
        }
    }

    private void reply(String id, Res r) {
        final String js = "window.__httpCb(" + JSONObject.quote(id) + "," + r.ok + "," + JSONObject.quote(r.body) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private static byte[] hmac(String secret, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(String secret, String msg) throws Exception {
        byte[] h = hmac(secret, msg);
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

    private void writeKeys(String ex, String key, String secret, String pass) throws Exception {
        JSONObject o = new JSONObject();
        o.put("k", key);
        o.put("s", secret);
        if (pass != null && !pass.isEmpty()) o.put("p", pass);
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
            return new String[]{o.getString("k"), o.getString("s"), o.optString("p", "")};
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

    private static long okxTime() {
        Res r = request("GET", "https://www.okx.com/api/v5/public/time", null, null);
        if (!r.ok) r = request("GET", "https://aws.okx.com/api/v5/public/time", null, null);
        try { if (r.ok) return Long.parseLong(new JSONObject(r.body).getJSONArray("data").getJSONObject(0).getString("ts")); } catch (Exception ignored) { }
        return System.currentTimeMillis();
    }

    private static final String SYM = "[A-Z0-9]{1,20}_[A-Z0-9]{2,10}";

    /** Only these fixed read-only endpoints can be signed; the page cannot ask for anything else. */
    private Res signedGet(String ex, String kind) {
        Res r = new Res();
        String[] k = readKeys(ex);
        if (k == null) { r.body = "No API key saved for this exchange."; return r; }
        try {
            Map<String, String> h = new HashMap<>();
            if (ex.equals("mexc") && kind.startsWith("p2pRef:")) {
                // MEXC's own USDT price in a fiat currency (needs the key's P2P Read permission).
                String fiat = kind.substring(7);
                if (!fiat.matches("[A-Z]{3,5}")) { r.body = "Unknown currency"; return r; }
                long ts = serverTime("https://api.mexc.com/api/v3/time", "serverTime");
                String q = "coinId=USDT&fiatUnit=" + fiat + "&recvWindow=10000&timestamp=" + ts;
                h.put("X-MEXC-APIKEY", k[0]);
                return request("GET", "https://api.mexc.com/api/v3/fiat/market/reference_price?" + q + "&signature=" + hmacHex(k[1], q), h, null);
            }
            if (ex.equals("binance")) {
                // USDT-M futures
                String path, params = "";
                String[] p = kind.split(":");
                if (kind.equals("futAccount")) path = "/fapi/v2/account";
                else if (kind.equals("positions")) path = "/fapi/v2/positionRisk";
                else if (kind.equals("openOrders")) path = "/fapi/v1/openOrders";
                else if (kind.equals("algoOrders")) path = "/fapi/v1/openAlgoOrders";
                else if (p.length == 3 && (p[0].equals("income") || p[0].equals("trades")) && p[1].matches("[A-Z0-9]{2,30}") && p[2].matches("\\d{10,14}")) {
                    path = p[0].equals("income") ? "/fapi/v1/income" : "/fapi/v1/userTrades";
                    params = "symbol=" + p[1] + "&startTime=" + p[2] + "&limit=1000&";
                }
                else { r.body = "Unknown request"; return r; }
                long ts = serverTime("https://fapi.binance.com/fapi/v1/time", "serverTime");
                String q = params + "recvWindow=10000&timestamp=" + ts;
                h.put("X-MBX-APIKEY", k[0]);
                return request("GET", "https://fapi.binance.com" + path + "?" + q + "&signature=" + hmacHex(k[1], q), h, null);
            }
            if (ex.equals("okx")) {
                // Perpetual swaps on the OKX trading account. OKX also needs the key's passphrase.
                if (k[2].isEmpty()) { r.body = "OKX needs the API passphrase. Disconnect OKX and connect it again."; return r; }
                String path;
                if (kind.equals("balance")) path = "/api/v5/account/balance?ccy=USDT";
                else if (kind.equals("positions")) path = "/api/v5/account/positions?instType=SWAP";
                else if (kind.equals("algoCond")) path = "/api/v5/trade/orders-algo-pending?ordType=conditional";
                else if (kind.equals("algoOco")) path = "/api/v5/trade/orders-algo-pending?ordType=oco";
                else if (kind.equals("history")) path = "/api/v5/account/positions-history?instType=SWAP&limit=100";
                else if (kind.startsWith("leverage:") && kind.substring(9).matches(SYM)) path = "/api/v5/account/leverage-info?instId=" + kind.substring(9).replace("_", "-") + "-SWAP&mgnMode=cross";
                else { r.body = "Unknown request"; return r; }
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                String ts = f.format(new Date(okxTime()));
                h.put("OK-ACCESS-KEY", k[0]);
                h.put("OK-ACCESS-SIGN", Base64.encodeToString(hmac(k[1], ts + "GET" + path), Base64.NO_WRAP));
                h.put("OK-ACCESS-TIMESTAMP", ts);
                h.put("OK-ACCESS-PASSPHRASE", k[2]);
                h.put("Content-Type", "application/json");
                Res first = request("GET", "https://www.okx.com" + path, h, null);
                // OKX's second address for the same global API, used when www.okx.com can't be reached.
                if (!first.ok && (first.body == null || !first.body.contains("{"))) return request("GET", "https://aws.okx.com" + path, h, null);
                return first;
            }
            if (ex.equals("bybit")) {
                // USDT perpetuals (linear) on the Unified Trading Account.
                String path, q;
                if (kind.equals("balance")) { path = "/v5/account/wallet-balance"; q = "accountType=UNIFIED"; }
                else if (kind.equals("balanceContract")) { path = "/v5/account/wallet-balance"; q = "accountType=CONTRACT"; }
                else if (kind.equals("positions")) { path = "/v5/position/list"; q = "category=linear&limit=200&settleCoin=USDT"; }
                else if (kind.equals("stopOrders")) { path = "/v5/order/realtime"; q = "category=linear&limit=50&orderFilter=StopOrder&settleCoin=USDT"; }
                else if (kind.equals("tpslOrders")) { path = "/v5/order/realtime"; q = "category=linear&limit=50&orderFilter=tpslOrder&settleCoin=USDT"; }
                else if (kind.equals("history")) { path = "/v5/position/closed-pnl"; q = "category=linear&limit=100"; }
                else if (kind.startsWith("leverage:") && kind.substring(9).matches(SYM)) { path = "/v5/position/list"; q = "category=linear&symbol=" + kind.substring(9).replace("_", ""); }
                else { r.body = "Unknown request"; return r; }
                String ts = String.valueOf(serverTime("https://api.bybit.com/v5/market/time", "time"));
                String rw = "10000";
                h.put("X-BAPI-API-KEY", k[0]);
                h.put("X-BAPI-TIMESTAMP", ts);
                h.put("X-BAPI-RECV-WINDOW", rw);
                h.put("X-BAPI-SIGN", hmacHex(k[1], ts + k[0] + rw + q));
                Res first = request("GET", "https://api.bybit.com" + path + "?" + q, h, null);
                // Bybit's second address serves the same API when the main one is blocked on this network.
                if (!first.ok && (first.body == null || !first.body.contains("{"))) return request("GET", "https://api.bytick.com" + path + "?" + q, h, null);
                return first;
            }
            if (ex.equals("mexc")) {
                String path, params = "";
                if (kind.equals("futAssets")) path = "/api/v1/private/account/assets";
                else if (kind.equals("positions")) path = "/api/v1/private/position/open_positions";
                else if (kind.equals("stopOrders")) { path = "/api/v1/private/stoporder/list/orders"; params = "is_finished=0&page_num=1&page_size=100"; }
                else if (kind.equals("history")) { path = "/api/v1/private/position/list/history_positions"; params = "page_num=1&page_size=50"; }
                else if (kind.startsWith("leverage:") && kind.substring(9).matches(SYM)) { path = "/api/v1/private/position/leverage"; params = "symbol=" + kind.substring(9); }
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

        /** HTTPS POST with a JSON body, only for the public Binance and Bybit P2P price searches. */
        @JavascriptInterface
        public void httpPost(final String id, final String url, final String json) {
            new Thread(() -> {
                if (!url.startsWith("https://p2p.binance.com/") && !url.startsWith("https://api2.bybit.com/fiat/otc/")) { Res r = new Res(); r.body = "blocked"; reply(id, r); return; }
                reply(id, request("POST", url, null, json));
            }).start();
        }

        @JavascriptInterface
        public String saveKeys(String ex, String key, String secret) {
            try {
                if (key == null || secret == null || key.trim().isEmpty() || secret.trim().isEmpty()) return "Enter both the API key and the secret.";
                writeKeys(ex, key.trim(), secret.trim(), null);
                return "";
            } catch (Exception e) { return "Couldn't save the key: " + e.getMessage(); }
        }

        /** Same as saveKeys, plus the passphrase OKX keys need. */
        @JavascriptInterface
        public String saveKeysPass(String ex, String key, String secret, String pass) {
            try {
                if (key == null || secret == null || key.trim().isEmpty() || secret.trim().isEmpty()) return "Enter both the API key and the secret.";
                if (pass == null || pass.isEmpty()) return "Enter the passphrase you set when you created the key.";
                writeKeys(ex, key.trim(), secret.trim(), pass);
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
