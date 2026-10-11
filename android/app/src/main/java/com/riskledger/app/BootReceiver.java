package com.riskledger.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the autopilot after the phone reboots, if it was on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) && Autopilot.plan(c).optBoolean("enabled", false)) AutopilotService.start(c);
    }
}
