package com.riskledger.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Watches price alerts while the app is closed. Runs only while there are alerts that haven't been hit,
 * checks the exchange's public price every 15 seconds and posts a notification when an alert is hit.
 * Alerts live in alerts.json (written by the app); hits are recorded in alerts_hit.json (read by the app).
 */
public class AlertService extends Service {
    static final String CH_WATCH = "rl_watch", CH_HIT = "rl_alerts";
    private static final long EVERY_MS = 15000;
    private HandlerThread thread;
    private Handler handler;

    static File alertsFile(Context c) { return new File(c.getFilesDir(), "alerts.json"); }
    static File hitsFile(Context c) { return new File(c.getFilesDir(), "alerts_hit.json"); }

    static String read(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        } catch (Exception e) { return null; }
    }

    static synchronized void write(File f, String s) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { return; }
        tmp.renameTo(f);
    }

    static JSONObject hits(Context c) {
        try { String s = read(hitsFile(c)); return s == null ? new JSONObject() : new JSONObject(s); }
        catch (Exception e) { return new JSONObject(); }
    }

    /** Alerts that haven't been hit yet. */
    static JSONArray active(Context c) {
        JSONArray out = new JSONArray();
        try {
            String s = read(alertsFile(c));
            if (s == null) return out;
            JSONArray all = new JSONArray(s);
            JSONObject hit = hits(c);
            for (int i = 0; i < all.length(); i++) {
                JSONObject a = all.getJSONObject(i);
                if (!hit.has(a.optString("id"))) out.put(a);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel w = new NotificationChannel(CH_WATCH, "Watching price alerts", NotificationManager.IMPORTANCE_MIN);
        w.setShowBadge(false);
        nm.createNotificationChannel(w);
        NotificationChannel a = new NotificationChannel(CH_HIT, "Price alerts", NotificationManager.IMPORTANCE_HIGH);
        a.enableVibration(true);
        nm.createNotificationChannel(a);
    }

    /** Start the watcher when there are alerts to watch, stop it when there are none. */
    static void sync(Context c) {
        Intent i = new Intent(c, AlertService.class);
        try {
            if (active(c).length() > 0) c.startForegroundService(i);
            else c.stopService(i);
        } catch (Exception ignored) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        channels(this);
        thread = new HandlerThread("price-alerts");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = watching(active(this).length());
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);
        handler.removeCallbacksAndMessages(null);
        handler.post(loop);
        return START_STICKY;
    }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            int left = check();
            if (left == 0) { stopForeground(true); stopSelf(); return; }
            getSystemService(NotificationManager.class).notify(1, watching(left));
            handler.postDelayed(this, EVERY_MS);
        }
    };

    /** Check every waiting alert once; returns how many are still waiting. */
    private int check() {
        JSONArray act = active(this);
        if (act.length() == 0) return 0;
        Map<String, Double> prices = new HashMap<>();
        JSONObject hit = hits(this);
        boolean changed = false;
        int left = 0;
        for (int i = 0; i < act.length(); i++) {
            try {
                JSONObject a = act.getJSONObject(i);
                String key = a.optString("ex") + "|" + a.optString("b") + "|" + a.optString("q");
                Double p = prices.get(key);
                if (p == null) { p = price(a.optString("ex"), a.optString("b"), a.optString("q")); prices.put(key, p); }
                if (p == null || p <= 0) { left++; continue; }
                double target = a.optDouble("price");
                boolean above = "above".equals(a.optString("cond"));
                if (above ? p >= target : p <= target) {
                    JSONObject h = new JSONObject();
                    h.put("at", System.currentTimeMillis());
                    h.put("price", p);
                    hit.put(a.optString("id"), h);
                    changed = true;
                    notifyHit(a, p, above, target);
                } else left++;
            } catch (Exception ignored) { left++; }
        }
        if (changed) write(hitsFile(this), hit.toString());
        return left;
    }

    /** Last price of a USDT perpetual from the exchange's public API (backup address when the main one fails). */
    static Double price(String ex, String b, String q) {
        if (!b.matches("[A-Z0-9]{1,20}") || !q.matches("[A-Z0-9]{2,10}")) return null;
        String[] urls;
        switch (ex) {
            case "binance": urls = new String[]{"https://fapi.binance.com/fapi/v1/ticker/price?symbol=" + b + q}; break;
            case "bybit": urls = new String[]{"https://api.bybit.com/v5/market/tickers?category=linear&symbol=" + b + q, "https://api.bytick.com/v5/market/tickers?category=linear&symbol=" + b + q}; break;
            case "okx": urls = new String[]{"https://www.okx.com/api/v5/market/ticker?instId=" + b + "-" + q + "-SWAP", "https://aws.okx.com/api/v5/market/ticker?instId=" + b + "-" + q + "-SWAP"}; break;
            default: urls = new String[]{"https://contract.mexc.com/api/v1/contract/ticker?symbol=" + b + "_" + q, "https://futures.mexc.com/api/v1/contract/ticker?symbol=" + b + "_" + q};
        }
        for (String u : urls) {
            try {
                MainActivity.Res r = MainActivity.request("GET", u, null, null);
                if (!r.ok) continue;
                JSONObject j = new JSONObject(r.body);
                double p;
                switch (ex) {
                    case "binance": p = j.getDouble("price"); break;
                    case "bybit": p = j.getJSONObject("result").getJSONArray("list").getJSONObject(0).getDouble("lastPrice"); break;
                    case "okx": p = j.getJSONArray("data").getJSONObject(0).getDouble("last"); break;
                    default: p = j.getJSONObject("data").getDouble("lastPrice");
                }
                if (p > 0) return p;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static String fmt(double v) { return new BigDecimal(String.valueOf(v)).stripTrailingZeros().toPlainString(); }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification watching(int n) {
        return new Notification.Builder(this, CH_WATCH)
                .setSmallIcon(R.drawable.ic_fg)
                .setContentTitle("Watching " + n + " price alert" + (n == 1 ? "" : "s"))
                .setContentText("Checks prices every 15 seconds")
                .setOngoing(true)
                .setContentIntent(openApp())
                .build();
    }

    private void notifyHit(JSONObject a, double p, boolean above, double target) {
        String note = a.optString("note", "");
        Notification n = new Notification.Builder(this, CH_HIT)
                .setSmallIcon(R.drawable.ic_fg)
                .setContentTitle(a.optString("pair") + (above ? " above " : " below ") + fmt(target))
                .setContentText("Now " + fmt(p) + (note.isEmpty() ? "" : " · " + note))
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build();
        getSystemService(NotificationManager.class).notify(a.optString("id").hashCode(), n);
    }

    @Override
    public void onDestroy() {
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
