package com.riskledger.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The exit plan, carried out without second thoughts:
 *  - a new position gets a stop loss and TP1–TP4 (each closing its set % of the position)
 *  - after the first take-profit fills, the stop moves to breakeven
 *  - a missing stop is put back; leftovers are cancelled once the position is closed
 * Runs every few seconds from the background service and while the app is open.
 */
final class Autopilot {
    private static final String PREF = "autopilot";
    static final String CH_STATUS = "autopilot_status", CH_EVENTS = "autopilot_events";

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }

    static JSONObject plan(Context c) { try { return new JSONObject(sp(c).getString("plan", "{}")); } catch (Exception e) { return new JSONObject(); } }
    static JSONObject state(Context c) { try { return new JSONObject(sp(c).getString("state", "{}")); } catch (Exception e) { return new JSONObject(); } }
    private static void saveState(Context c, JSONObject s) { sp(c).edit().putString("state", s.toString()).apply(); }

    /** The page's plan settings; per-trade overrides are kept separately so a settings save can't wipe them. */
    static synchronized void setPlan(Context c, String json) { sp(c).edit().putString("plan", json).apply(); }

    static JSONObject overrides(Context c) { try { return new JSONObject(sp(c).getString("overrides", "{}")); } catch (Exception e) { return new JSONObject(); } }
    static synchronized void putOverride(Context c, String key, JSONObject ov) {
        try { JSONObject o = overrides(c); o.put(key, ov); sp(c).edit().putString("overrides", o.toString()).apply(); } catch (Exception ignored) { }
    }
    static synchronized void removeOverride(Context c, String key) {
        JSONObject o = overrides(c); o.remove(key); sp(c).edit().putString("overrides", o.toString()).apply();
    }

    /** Events for the app screen (TP hit, stop moved, closed…); also shown as notifications. */
    static synchronized void event(Context c, String type, String key, String title, String text, String outcome) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("events", "[]"));
            JSONObject e = new JSONObject();
            e.put("type", type); e.put("key", key); e.put("title", title); e.put("text", text); e.put("at", System.currentTimeMillis());
            if (outcome != null) e.put("outcome", outcome);
            a.put(e);
            while (a.length() > 60) a.remove(0);
            sp(c).edit().putString("events", a.toString()).apply();
        } catch (Exception ignored) { }
        notify(c, title, text);
    }

    static synchronized String takeEvents(Context c) {
        String s = sp(c).getString("events", "[]");
        sp(c).edit().putString("events", "[]").apply();
        return s;
    }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_STATUS, "Autopilot running", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_EVENTS, "Trade updates", NotificationManager.IMPORTANCE_HIGH));
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static int nid = 100;
    static void notify(Context c, String title, String text) {
        try {
            channels(c);
            Notification n = new Notification.Builder(c, CH_EVENTS)
                    .setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(openApp(c)).setAutoCancel(true).build();
            c.getSystemService(NotificationManager.class).notify(nid++, n);
        } catch (Exception ignored) { }
    }

    static String fmt(double v) {
        String s = String.valueOf(v);
        if (Math.abs(v) >= 1000) return String.format(java.util.Locale.US, "%,.2f", v);
        return s.length() > 10 ? String.format(java.util.Locale.US, "%.6f", v) : s;
    }

    // ---------- engine ----------

    private static String tag(String kind, String sym) { return "rl_" + kind + "_" + sym + "_" + (System.currentTimeMillis() % 100000000L); }

    /** Cancel every order this app placed for a position (by stored ids). */
    static synchronized void cancelOurs(Context c, boolean test, String key) {
        JSONObject st = state(c);
        JSONObject s = st.optJSONObject(key);
        if (s == null) return;
        JSONObject sl = s.optJSONObject("sl");
        if (sl != null) Futures.cancelAlgo(c, test, sl.optString("id"));
        JSONArray tps = s.optJSONArray("tps");
        if (tps != null) for (int i = 0; i < tps.length(); i++) { JSONObject t = tps.optJSONObject(i); if (t != null && !t.optBoolean("done")) Futures.cancelAlgo(c, test, t.optString("id")); }
        st.remove(key);
        saveState(c, st);
    }

    /** Exit prices for a position: from the trade's own plan, or from the default plan for positions opened elsewhere. */
    private static double[][] exits(JSONObject plan, JSONObject ov, String side, double entry) throws Exception {
        boolean L = side.equals("L");
        double sl;
        double[] tps = new double[4], closes = new double[4];
        if (ov != null) {
            sl = ov.getDouble("sl");
            JSONArray t = ov.getJSONArray("tps"), cl = ov.getJSONArray("closes");
            for (int i = 0; i < 4; i++) { tps[i] = t.optDouble(i, 0); closes[i] = cl.optDouble(i, 0); }
        } else {
            double dist = entry * plan.optDouble("slPct", 2) / 100;
            sl = L ? entry - dist : entry + dist;
            boolean pct = "pct".equals(plan.optString("targets", "R"));
            JSONArray t = plan.optJSONArray("tps");
            for (int i = 0; i < 4; i++) {
                JSONObject x = t == null ? null : t.optJSONObject(i);
                double at = x == null ? i + 1 : x.optDouble("at", i + 1);
                closes[i] = x == null ? 25 : x.optDouble("close", 25);
                double move = pct ? entry * at / 100 : dist * at;
                tps[i] = L ? entry + move : entry - move;
            }
        }
        return new double[][]{{sl}, tps, closes};
    }

    /** Place stop + take-profits for a fresh position. Returns its state, or null if nothing was placed. */
    private static JSONObject attach(Context c, boolean test, boolean dual, JSONObject plan, JSONObject ov, String sym, String side, double q, double entry) throws Exception {
        String key = "binance|" + sym + "|" + side;
        double[][] ex = exits(plan, ov, side, entry);
        double sl = ex[0][0];
        double[] tps = ex[1], closes = ex[2];
        double[] r = Futures.rule(c, test, sym);

        JSONObject s = new JSONObject();
        s.put("sym", sym); s.put("side", side); s.put("entry", entry); s.put("qty0", q); s.put("managed", true);
        s.put("startedAt", System.currentTimeMillis()); s.put("fromApp", ov != null);

        Net.Res sr = Futures.placeStop(c, test, dual, sym, side, sl, tag("sl", sym));
        if (!sr.ok) {
            if (Futures.code(sr) == -2021 && ov != null) { // price is already past the stop: follow the plan and exit
                Futures.closeMarket(c, test, dual, sym, side, q);
                event(c, "closed", key, sym + " closed at market", "Price was already past your stop (" + fmt(sl) + ") when the order filled, so the position was closed.", "SL");
                removeOverride(c, sym + "|" + side);
                return null;
            }
            event(c, "error", key, "Couldn't place the stop on " + sym, Futures.msg(sr), null);
            return null;
        }
        JSONObject slj = new JSONObject(); slj.put("id", Futures.algoId(sr)); slj.put("price", sl);
        s.put("sl", slj);

        int last = -1;
        for (int i = 0; i < 4; i++) if (closes[i] > 0 && tps[i] > 0) last = i;
        JSONArray tpa = new JSONArray();
        StringBuilder placed = new StringBuilder();
        for (int i = 0; i <= last; i++) {
            if (!(closes[i] > 0 && tps[i] > 0)) continue;
            String qty = null;
            if (i != last) {
                qty = Futures.floorTo(q * closes[i] / 100, r[1]);
                if (Double.parseDouble(qty) < r[2]) continue; // too small for this pair: the remainder rides to the next TP
            }
            Net.Res tr = Futures.placeTp(c, test, dual, sym, side, tps[i], qty, tag("tp" + (i + 1), sym));
            JSONObject t = new JSONObject();
            t.put("n", i + 1); t.put("price", tps[i]); t.put("qty", qty == null ? "rest" : qty);
            if (tr.ok) { t.put("id", Futures.algoId(tr)); placed.append(placed.length() > 0 ? ", " : "").append("TP").append(i + 1).append(" ").append(fmt(tps[i])); }
            else { t.put("done", true); t.put("failed", Futures.msg(tr)); }
            tpa.put(t);
        }
        s.put("tps", tpa);
        event(c, "attached", key, sym + (side.equals("L") ? " long" : " short") + ": exit plan set",
                "Stop " + fmt(sl) + (placed.length() > 0 ? " · " + placed : ""), null);
        return s;
    }

    private static JSONArray algoList(String body) {
        try {
            String b = body.trim();
            if (b.startsWith("[")) return new JSONArray(b);
            JSONObject o = new JSONObject(b);
            JSONArray a = o.optJSONArray("orders");
            if (a == null) a = o.optJSONArray("data");
            return a == null ? new JSONArray() : a;
        } catch (Exception e) { return new JSONArray(); }
    }

    /** One pass of the watcher. Returns a short status line. */
    static synchronized String runOnce(Context c) {
        JSONObject plan = plan(c);
        boolean test = plan.optBoolean("testnet", false);
        if (Vault.read(c, Futures.keySlot(test)) == null) return "No " + (test ? "testnet " : "") + "Binance key";
        try {
            Net.Res pr = Futures.call(c, test, "GET", "/fapi/v2/positionRisk", "");
            if (!pr.ok) { status(c, false, Futures.msg(pr)); return "Can't reach Binance: " + Futures.msg(pr); }
            Net.Res ar = Futures.call(c, test, "GET", "/fapi/v1/openAlgoOrders", "");
            if (!ar.ok) { status(c, false, Futures.msg(ar)); return "Can't read orders: " + Futures.msg(ar); }
            JSONArray pos = new JSONArray(pr.body), algos = algoList(ar.body);

            Set<String> openIds = new HashSet<>();
            Map<String, Integer> foreign = new HashMap<>();
            for (int i = 0; i < algos.length(); i++) {
                JSONObject o = algos.getJSONObject(i);
                openIds.add(String.valueOf(o.opt("algoId")));
                if (!o.optString("clientAlgoId").startsWith("rl_")) {
                    String side = o.optString("positionSide", "BOTH").equals("SHORT") || (o.optString("positionSide", "BOTH").equals("BOTH") && o.optString("side").equals("BUY")) ? "S" : "L";
                    String k = "binance|" + o.optString("symbol") + "|" + side;
                    foreign.put(k, foreign.containsKey(k) ? foreign.get(k) + 1 : 1);
                }
            }

            boolean dual = Futures.dual(c, test);
            JSONObject st = state(c), ovs = overrides(c);
            Set<String> seen = new HashSet<>();
            int managed = 0;

            for (int i = 0; i < pos.length(); i++) {
                JSONObject p = pos.getJSONObject(i);
                double amt = p.optDouble("positionAmt", 0);
                if (amt == 0) continue;
                String sym = p.getString("symbol"), side = amt > 0 ? "L" : "S", key = "binance|" + sym + "|" + side;
                double q = Math.abs(amt), entry = p.optDouble("entryPrice", 0);
                seen.add(key);
                JSONObject s = st.optJSONObject(key);
                JSONObject ov = ovs.optJSONObject(sym + "|" + side);

                if (s == null) {
                    if (ov == null && !plan.optBoolean("manageManual", false)) continue;
                    if (ov == null && foreign.containsKey(key)) { // you set your own exits on Binance: leave them alone
                        JSONObject skip = new JSONObject(); skip.put("managed", false); skip.put("sym", sym); skip.put("side", side);
                        st.put(key, skip);
                        event(c, "skipped", key, sym + ": left as is", "It already has stop or take-profit orders you placed on Binance, so the autopilot isn't managing it.", null);
                        continue;
                    }
                    JSONObject ns = attach(c, test, dual, plan, ov, sym, side, q, entry);
                    if (ns != null) { st.put(key, ns); saveState(c, st); managed++; }
                    continue;
                }
                if (!s.optBoolean("managed")) continue;
                managed++;

                JSONArray tps = s.optJSONArray("tps");
                int done = 0;
                if (tps != null) for (int t = 0; t < tps.length(); t++) {
                    JSONObject tp = tps.getJSONObject(t);
                    if (tp.optBoolean("hit")) { done++; continue; }
                    if (tp.optBoolean("done")) continue;
                    if (!openIds.contains(tp.optString("id"))) {
                        tp.put("done", true);
                        if (Futures.triggered(c, test, tp.optString("id"))) {
                            tp.put("hit", true); done++;
                            event(c, "tp", key, sym + ": TP" + tp.optInt("n") + " hit", "Take-profit at " + fmt(tp.optDouble("price")) + " filled.", null);
                        } else {
                            event(c, "tp", key, sym + ": TP" + tp.optInt("n") + " was cancelled", "The take-profit at " + fmt(tp.optDouble("price")) + " was cancelled outside the app, so it won't be placed again.", null);
                        }
                    }
                }

                // Position grew before any take-profit (limit entry filling in parts, or you added): redo the exits for the full size.
                if (done == 0 && q > s.optDouble("qty0") * 1.002) {
                    cancelOurs(c, test, key);
                    st = state(c);
                    JSONObject ns = attach(c, test, dual, plan, ov, sym, side, q, entry);
                    if (ns != null) st.put(key, ns);
                    saveState(c, st);
                    continue;
                }

                JSONObject sl = s.optJSONObject("sl");
                boolean wantBe = plan.optBoolean("beAfterTP1", true) && done > 0 && !s.optBoolean("be");
                if (wantBe) {
                    double off = plan.optDouble("beOffsetPct", 0.1) / 100, e0 = s.optDouble("entry", entry);
                    double be = side.equals("L") ? e0 * (1 + off) : e0 * (1 - off);
                    if (sl != null && openIds.contains(sl.optString("id"))) Futures.cancelAlgo(c, test, sl.optString("id"));
                    Net.Res br = Futures.placeStop(c, test, dual, sym, side, be, tag("be", sym));
                    if (br.ok) {
                        JSONObject nsl = new JSONObject(); nsl.put("id", Futures.algoId(br)); nsl.put("price", be);
                        s.put("sl", nsl); s.put("be", true);
                        event(c, "be", key, sym + ": stop moved to breakeven", "Stop is now " + fmt(be) + ". The rest of this trade can't turn into a loss.", null);
                    } else if (Futures.code(br) == -2021) { // price already back through breakeven: exit as planned
                        Futures.closeMarket(c, test, dual, sym, side, q);
                        s.put("be", true);
                        event(c, "be", key, sym + ": closed at breakeven", "Price came back to entry right after TP" + done + ", so the rest was closed.", null);
                    } else {
                        event(c, "error", key, sym + ": couldn't move the stop", Futures.msg(br), null);
                    }
                } else if (sl != null && !openIds.contains(sl.optString("id"))) {
                    // Stop is gone but the position is open (cancelled by hand or rejected): put it back.
                    Net.Res rr = Futures.placeStop(c, test, dual, sym, side, sl.optDouble("price"), tag("sl", sym));
                    if (rr.ok) { sl.put("id", Futures.algoId(rr)); event(c, "stop", key, sym + ": stop loss put back", "Your stop at " + fmt(sl.optDouble("price")) + " was missing, so it was placed again.", null); }
                    else if (Futures.code(rr) == -2021) { Futures.closeMarket(c, test, dual, sym, side, q); event(c, "stop", key, sym + ": closed at your stop", "Price was past your stop and the stop order was missing, so the position was closed.", null); }
                }
                s.put("qtyNow", q);
                st.put(key, s);
            }

            // Positions that have closed: clean up leftovers and report the result.
            Iterator<String> it = st.keys();
            Set<String> gone = new HashSet<>();
            while (it.hasNext()) { String k = it.next(); if (!seen.contains(k)) gone.add(k); }
            for (String k : gone) {
                JSONObject s = st.optJSONObject(k);
                if (s != null && s.optBoolean("managed")) {
                    JSONArray tps = s.optJSONArray("tps");
                    int top = 0, placedCount = 0, hitCount = 0;
                    if (tps != null) for (int t = 0; t < tps.length(); t++) {
                        JSONObject tp = tps.getJSONObject(t);
                        if (tp.has("failed")) continue;
                        placedCount++;
                        if (!tp.optBoolean("done")) {
                            if (openIds.contains(tp.optString("id"))) Futures.cancelAlgo(c, test, tp.optString("id")); // leftover
                            else if (Futures.triggered(c, test, tp.optString("id"))) tp.put("hit", true);
                        }
                        if (tp.optBoolean("hit")) { hitCount++; top = Math.max(top, tp.optInt("n")); }
                    }
                    JSONObject sl = s.optJSONObject("sl");
                    boolean stopFired = false;
                    if (sl != null) {
                        if (openIds.contains(sl.optString("id"))) Futures.cancelAlgo(c, test, sl.optString("id"));
                        else stopFired = Futures.triggered(c, test, sl.optString("id"));
                    }
                    boolean all = placedCount > 0 && hitCount == placedCount;
                    String outcome = top == 0 ? (s.optBoolean("be") ? "BE" : "SL") : (all ? "TP4" : "TP" + top);
                    String sym = s.optString("sym");
                    String how = all ? "All take-profits hit."
                            : top > 0 ? "TP" + top + " hit, the rest closed " + (stopFired ? (s.optBoolean("be") ? "at breakeven." : "at the stop.") : "by hand.")
                            : stopFired ? "Stopped out before TP1." : "Closed by hand before TP1.";
                    event(c, "closed", k, sym + " closed · " + outcome, how, stopFired || all || top > 0 ? outcome : "manual");
                    removeOverride(c, sym + "|" + s.optString("side"));
                }
                st.remove(k);
            }

            // Entry plans whose limit order never filled are dropped after 7 days.
            JSONObject ovNow = overrides(c);
            Iterator<String> oi = ovNow.keys();
            Set<String> stale = new HashSet<>();
            while (oi.hasNext()) { String k = oi.next(); JSONObject o = ovNow.optJSONObject(k); if (o != null && System.currentTimeMillis() - o.optLong("createdAt") > 7 * 86400_000L && !seen.contains("binance|" + k)) stale.add(k); }
            for (String k : stale) removeOverride(c, k);

            saveState(c, st);
            String line = managed == 0 ? "Watching for new positions" : "Managing " + managed + " position" + (managed == 1 ? "" : "s");
            status(c, true, line);
            return line;
        } catch (Exception e) {
            status(c, false, String.valueOf(e.getMessage()));
            return "Error: " + e.getMessage();
        }
    }

    private static void status(Context c, boolean ok, String line) {
        sp(c).edit().putBoolean("lastOk", ok).putString("lastLine", line).putLong("lastRun", System.currentTimeMillis()).apply();
    }

    /** Snapshot for the app screen. */
    static String statusJson(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("running", AutopilotService.running);
            o.put("lastRun", sp(c).getLong("lastRun", 0));
            o.put("lastOk", sp(c).getBoolean("lastOk", true));
            o.put("lastLine", sp(c).getString("lastLine", ""));
            o.put("state", state(c));
            o.put("overrides", overrides(c));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }
}
