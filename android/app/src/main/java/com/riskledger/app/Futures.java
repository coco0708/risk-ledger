package com.riskledger.app;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Binance USDT-M futures: signed requests, symbol rules (tick and lot size) and the order calls
 * the app and the autopilot use. Live and testnet use separate keys ("binance" / "binancetest").
 */
final class Futures {
    static final String LIVE = "https://fapi.binance.com";
    static final String TEST = "https://testnet.binancefuture.com";

    static String base(boolean test) { return test ? TEST : LIVE; }
    static String keySlot(boolean test) { return test ? "binancetest" : "binance"; }

    private static long offset = 0, offsetAt = 0;
    private static final Map<String, Boolean> dualCache = new HashMap<>();
    private static final Map<String, Long> dualAt = new HashMap<>();
    private static final Map<String, Map<String, double[]>> rules = new HashMap<>();
    private static final Map<String, Long> rulesAt = new HashMap<>();

    /** Error code from a Binance error body like `HTTP 400 {"code":-2021,"msg":"..."}`, or 0. */
    static int code(Net.Res r) {
        try { int i = r.body.indexOf('{'); if (i >= 0) return new JSONObject(r.body.substring(i)).optInt("code", 0); } catch (Exception ignored) { }
        return 0;
    }

    static String msg(Net.Res r) {
        try { int i = r.body.indexOf('{'); if (i >= 0) { String m = new JSONObject(r.body.substring(i)).optString("msg", ""); if (!m.isEmpty()) return m; } } catch (Exception ignored) { }
        return r.body == null ? "No answer from Binance" : r.body;
    }

    private static synchronized long now(boolean test) {
        if (System.currentTimeMillis() - offsetAt > 600_000) {
            Net.Res r = Net.request("GET", base(test) + "/fapi/v1/time", null, null);
            try { if (r.ok) { offset = new JSONObject(r.body).getLong("serverTime") - System.currentTimeMillis(); offsetAt = System.currentTimeMillis(); } } catch (Exception ignored) { }
        }
        return System.currentTimeMillis() + offset;
    }

    /** Signed call. `params` is a query string without timestamp/signature (may be empty). */
    static Net.Res call(Context ctx, boolean test, String method, String path, String params) {
        String[] k = Vault.read(ctx, keySlot(test));
        if (k == null) { Net.Res r = new Net.Res(); r.body = test ? "No Binance testnet key saved." : "No Binance key saved."; return r; }
        try {
            String q = (params == null || params.isEmpty() ? "" : params + "&") + "recvWindow=10000&timestamp=" + now(test);
            Map<String, String> h = new HashMap<>();
            h.put("X-MBX-APIKEY", k[0]);
            h.put("Content-Type", "application/x-www-form-urlencoded");
            return Net.request(method, base(test) + path + "?" + q + "&signature=" + Net.hmacHex(k[1], q), h, null);
        } catch (Exception e) { Net.Res r = new Net.Res(); r.body = String.valueOf(e.getMessage()); return r; }
    }

    /** Hedge mode (separate long and short positions) or one-way mode. Cached for 10 minutes. */
    static boolean dual(Context ctx, boolean test) {
        String b = base(test);
        Long at = dualAt.get(b);
        if (at != null && System.currentTimeMillis() - at < 600_000) return Boolean.TRUE.equals(dualCache.get(b));
        Net.Res r = call(ctx, test, "GET", "/fapi/v1/positionSide/dual", "");
        boolean d = false;
        try { if (r.ok) d = new JSONObject(r.body).optBoolean("dualSidePosition", false); } catch (Exception ignored) { }
        if (r.ok) { dualCache.put(b, d); dualAt.put(b, System.currentTimeMillis()); }
        return d;
    }

    /** {tickSize, stepSize, minQty, minNotional, marketStep} for a symbol, from exchangeInfo (cached 6 h). */
    static double[] rule(Context ctx, boolean test, String symbol) {
        String b = base(test);
        Map<String, double[]> m = rules.get(b);
        Long at = rulesAt.get(b);
        if (m == null || at == null || System.currentTimeMillis() - at > 6 * 3600_000L) {
            String body = null;
            File cache = new File(ctx.getCacheDir(), (test ? "xinfo_test" : "xinfo") + ".json");
            if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 6 * 3600_000L) {
                try (FileInputStream in = new FileInputStream(cache)) { body = Net.readAll(in); } catch (Exception ignored) { }
            }
            if (body == null) {
                Net.Res r = Net.request("GET", b + "/fapi/v1/exchangeInfo", null, null);
                if (r.ok) { body = r.body; try (FileOutputStream out = new FileOutputStream(cache)) { out.write(body.getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) { } }
            }
            if (body != null) {
                try {
                    Map<String, double[]> nm = new HashMap<>();
                    JSONArray syms = new JSONObject(body).getJSONArray("symbols");
                    for (int i = 0; i < syms.length(); i++) {
                        JSONObject s = syms.getJSONObject(i);
                        double[] v = {0.01, 0.001, 0.001, 5, 0.001};
                        JSONArray f = s.optJSONArray("filters");
                        if (f != null) for (int j = 0; j < f.length(); j++) {
                            JSONObject x = f.getJSONObject(j);
                            String t = x.optString("filterType");
                            if (t.equals("PRICE_FILTER")) v[0] = x.optDouble("tickSize", v[0]);
                            else if (t.equals("LOT_SIZE")) { v[1] = x.optDouble("stepSize", v[1]); v[2] = x.optDouble("minQty", v[2]); }
                            else if (t.equals("MIN_NOTIONAL")) v[3] = x.optDouble("notional", v[3]);
                            else if (t.equals("MARKET_LOT_SIZE")) v[4] = x.optDouble("stepSize", v[4]);
                        }
                        nm.put(s.getString("symbol"), v);
                    }
                    rules.put(b, nm); rulesAt.put(b, System.currentTimeMillis()); m = nm;
                } catch (Exception ignored) { }
            }
        }
        double[] v = m == null ? null : m.get(symbol);
        return v != null ? v : new double[]{0.01, 0.001, 0.001, 5, 0.001};
    }

    static String floorTo(double value, double step) {
        BigDecimal s = new BigDecimal(String.valueOf(step));
        return new BigDecimal(String.valueOf(value)).divide(s, 0, RoundingMode.FLOOR).multiply(s).stripTrailingZeros().toPlainString();
    }

    static String roundTo(double value, double step) {
        BigDecimal s = new BigDecimal(String.valueOf(step));
        return new BigDecimal(String.valueOf(value)).divide(s, 0, RoundingMode.HALF_UP).multiply(s).stripTrailingZeros().toPlainString();
    }

    static String closeSide(String side) { return side.equals("L") ? "SELL" : "BUY"; }
    static String posSide(String side) { return side.equals("L") ? "LONG" : "SHORT"; }

    /** Stop-market that closes the whole position when the mark price reaches `price`. */
    static Net.Res placeStop(Context ctx, boolean test, boolean dual, String sym, String side, double price, String tag) {
        double[] r = rule(ctx, test, sym);
        String p = "algoType=CONDITIONAL&symbol=" + sym + "&side=" + closeSide(side) + "&type=STOP_MARKET&triggerPrice=" + roundTo(price, r[0])
                + "&closePosition=true&workingType=MARK_PRICE&priceProtect=true&clientAlgoId=" + tag
                + (dual ? "&positionSide=" + posSide(side) : "");
        return call(ctx, test, "POST", "/fapi/v1/algoOrder", p);
    }

    /** Take-profit-market for `qty` (or the whole remaining position when qty is null). */
    static Net.Res placeTp(Context ctx, boolean test, boolean dual, String sym, String side, double price, String qty, String tag) {
        double[] r = rule(ctx, test, sym);
        String p = "algoType=CONDITIONAL&symbol=" + sym + "&side=" + closeSide(side) + "&type=TAKE_PROFIT_MARKET&triggerPrice=" + roundTo(price, r[0])
                + "&workingType=CONTRACT_PRICE&clientAlgoId=" + tag
                + (qty == null ? "&closePosition=true" : "&quantity=" + qty + (dual ? "" : "&reduceOnly=true"))
                + (dual ? "&positionSide=" + posSide(side) : "");
        return call(ctx, test, "POST", "/fapi/v1/algoOrder", p);
    }

    static Net.Res cancelAlgo(Context ctx, boolean test, String algoId) {
        return call(ctx, test, "DELETE", "/fapi/v1/algoOrder", "algoId=" + algoId);
    }

    /** Market order that only reduces: closes `qty` of the position. */
    static Net.Res closeMarket(Context ctx, boolean test, boolean dual, String sym, String side, double qty) {
        double[] r = rule(ctx, test, sym);
        String p = "symbol=" + sym + "&side=" + closeSide(side) + "&type=MARKET&quantity=" + floorTo(qty, r[4] > 0 ? r[4] : r[1])
                + (dual ? "&positionSide=" + posSide(side) : "&reduceOnly=true");
        return call(ctx, test, "POST", "/fapi/v1/order", p);
    }

    /** True when Binance reports the conditional order as triggered (it fired). */
    static boolean triggered(Context ctx, boolean test, String algoId) {
        Net.Res r = call(ctx, test, "GET", "/fapi/v1/algoOrder", "algoId=" + algoId);
        try {
            String st = new JSONObject(r.body).optString("algoStatus", "");
            return st.equals("TRIGGERED") || st.equals("FINISHED") || st.equals("TRIGGERING");
        } catch (Exception e) { return false; }
    }

    static String algoId(Net.Res r) {
        try { return String.valueOf(new JSONObject(r.body).get("algoId")); } catch (Exception e) { return null; }
    }

    // ---------- actions the app screen asks for ----------

    /** Key check: can it read the futures account and trade? */
    static JSONObject check(Context ctx, boolean test) throws Exception {
        JSONObject out = new JSONObject();
        Net.Res a = call(ctx, test, "GET", "/fapi/v2/account", "");
        out.put("ok", a.ok);
        if (!a.ok) { out.put("error", msg(a)); out.put("code", code(a)); return out; }
        JSONObject acc = new JSONObject(a.body);
        out.put("canTrade", acc.optBoolean("canTrade", false));
        out.put("wallet", acc.optDouble("totalWalletBalance", 0));
        out.put("available", acc.optDouble("availableBalance", 0));
        out.put("dual", dual(ctx, test));
        return out;
    }

    /**
     * Open a trade: set leverage, remember the exit plan for the autopilot, place the entry.
     * json: {symbol, side L|S, type MARKET|LIMIT, qty, price, leverage, sl, tps:[price], closes:[%]}
     */
    static JSONObject open(Context ctx, boolean test, JSONObject j) throws Exception {
        JSONObject out = new JSONObject();
        String sym = j.getString("symbol"), side = j.getString("side"), type = j.optString("type", "MARKET");
        double[] r = rule(ctx, test, sym);
        String qty = floorTo(j.getDouble("qty"), type.equals("MARKET") && r[4] > 0 ? Math.max(r[4], r[1]) : r[1]);
        double q = Double.parseDouble(qty), px = j.optDouble("price", 0);
        if (q < r[2]) { out.put("ok", false); out.put("error", "Position size " + qty + " is below Binance's minimum " + r[2] + " for " + sym + ". Raise the risk or widen the stop."); return out; }
        if (px > 0 && q * px < r[3]) { out.put("ok", false); out.put("error", "Order value is below Binance's minimum of " + r[3] + " USDT for " + sym + "."); return out; }
        boolean dual = dual(ctx, test);
        int lev = Math.max(1, j.optInt("leverage", 10));
        Net.Res lr = call(ctx, test, "POST", "/fapi/v1/leverage", "symbol=" + sym + "&leverage=" + lev);
        if (!lr.ok) { out.put("ok", false); out.put("error", "Couldn't set " + lev + "x leverage: " + msg(lr)); return out; }

        // Exit plan the autopilot attaches as soon as the position exists.
        JSONObject ov = new JSONObject();
        ov.put("sl", j.getDouble("sl"));
        ov.put("tps", j.getJSONArray("tps"));
        ov.put("closes", j.getJSONArray("closes"));
        ov.put("createdAt", System.currentTimeMillis());
        Autopilot.putOverride(ctx, sym + "|" + side, ov);

        String p = "symbol=" + sym + "&side=" + (side.equals("L") ? "BUY" : "SELL") + "&type=" + type + "&quantity=" + qty
                + (type.equals("LIMIT") ? "&price=" + roundTo(px, r[0]) + "&timeInForce=GTC" : "")
                + (dual ? "&positionSide=" + posSide(side) : "")
                + "&newClientOrderId=rl_e_" + System.currentTimeMillis();
        Net.Res o = call(ctx, test, "POST", "/fapi/v1/order", p);
        if (!o.ok) { Autopilot.removeOverride(ctx, sym + "|" + side); out.put("ok", false); out.put("error", msg(o)); return out; }
        JSONObject ord = new JSONObject(o.body);
        out.put("ok", true);
        out.put("orderId", String.valueOf(ord.opt("orderId")));
        out.put("qty", qty);
        out.put("status", ord.optString("status"));
        if (type.equals("MARKET")) Autopilot.runOnce(ctx); // attach stop and take-profits right away
        return out;
    }

    /** Close a position now at market and cancel the app's stop and take-profit orders for it. */
    static JSONObject close(Context ctx, boolean test, JSONObject j) throws Exception {
        JSONObject out = new JSONObject();
        String sym = j.getString("symbol"), side = j.getString("side");
        boolean dual = dual(ctx, test);
        Net.Res pr = call(ctx, test, "GET", "/fapi/v2/positionRisk", "symbol=" + sym);
        if (!pr.ok) { out.put("ok", false); out.put("error", msg(pr)); return out; }
        JSONArray arr = new JSONArray(pr.body);
        double q = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.getJSONObject(i);
            double amt = x.optDouble("positionAmt", 0);
            if (amt != 0 && (amt > 0) == side.equals("L")) q = Math.abs(amt);
        }
        Autopilot.cancelOurs(ctx, test, "binance|" + sym + "|" + side);
        if (q == 0) { out.put("ok", true); out.put("note", "No open position."); return out; }
        Net.Res c = closeMarket(ctx, test, dual, sym, side, q);
        out.put("ok", c.ok);
        if (!c.ok) out.put("error", msg(c));
        Autopilot.runOnce(ctx);
        return out;
    }

    /** Cancel an entry limit order that hasn't filled yet. */
    static JSONObject cancelEntry(Context ctx, boolean test, JSONObject j) throws Exception {
        JSONObject out = new JSONObject();
        String sym = j.getString("symbol"), side = j.getString("side");
        Net.Res c = call(ctx, test, "DELETE", "/fapi/v1/order", "symbol=" + sym + "&orderId=" + j.getString("orderId"));
        out.put("ok", c.ok || code(c) == -2011); // -2011: already filled or cancelled
        if (!c.ok && code(c) != -2011) out.put("error", msg(c));
        if (out.optBoolean("ok")) Autopilot.removeOverride(ctx, sym + "|" + side);
        return out;
    }
}
