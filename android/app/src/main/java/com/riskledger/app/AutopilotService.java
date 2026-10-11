package com.riskledger.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps the autopilot checking Binance every 5 seconds while the phone is on, with a visible notification. */
public class AutopilotService extends Service {
    static volatile boolean running = false;
    private Thread loop;
    private PowerManager.WakeLock wake;

    static void start(Context c) {
        Intent i = new Intent(c, AutopilotService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) {
        Intent i = new Intent(c, AutopilotService.class);
        i.setAction("stop");
        c.startService(i);
    }

    private Notification build(String text) {
        Intent stop = new Intent(this, AutopilotService.class).setAction("stop");
        PendingIntent ps = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean test = Autopilot.plan(this).optBoolean("testnet", false);
        return new Notification.Builder(this, Autopilot.CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Autopilot on" + (test ? " · testnet" : ""))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(Autopilot.openApp(this))
                .addAction(new Notification.Action.Builder(null, "Stop autopilot", ps).build())
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Autopilot.channels(this);
        if (intent != null && "stop".equals(intent.getAction())) {
            try { Autopilot.setPlan(this, Autopilot.plan(this).put("enabled", false).toString()); } catch (Exception ignored) { }
            running = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = build("Starting…");
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);
        if (!Autopilot.plan(this).optBoolean("enabled", false)) { stopForeground(true); stopSelf(); return START_NOT_STICKY; }
        if (loop == null || !loop.isAlive()) {
            running = true;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RiskLedger:autopilot");
            wake.acquire();
            loop = new Thread(() -> {
                android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
                while (running) {
                    String line = Autopilot.runOnce(this);
                    try { nm.notify(1, build(line)); } catch (Exception ignored) { }
                    try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                }
            });
            loop.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (loop != null) loop.interrupt();
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
