package com.leeann323.fallalert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机自启：上次处于监听状态的话，重启后继续监听 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (!P.running(context)) return;
            Intent s = new Intent(context, AlertService.class).setAction(C.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(s);
            } else {
                context.startService(s);
            }
        } catch (Exception ignored) {
        }
    }
}
