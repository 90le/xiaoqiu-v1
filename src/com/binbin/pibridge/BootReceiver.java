package com.binbin.pibridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启（还需 HyperOS 允许 pi 桥自启动） */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
            if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(new Intent(c, BridgeService.class));
            else c.startService(new Intent(c, BridgeService.class));
        }
    }
}
