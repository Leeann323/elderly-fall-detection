package com.leeann323.fallalert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 后台常驻服务：轮询 ntfy 中转服务，收到新告警立刻发通知（+全屏唤醒）。
 * 手机与电脑不在同一网络也能收，因为中转服务在公网。
 */
public class AlertService extends Service {

    private volatile boolean running = false;
    private Thread worker;
    private NotificationManager nm;
    private volatile String status = "启动中…";

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannels();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? null : intent.getAction();

        if (C.ACTION_STOP.equals(act)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (C.ACTION_ACK.equals(act)) {
            if (nm != null) nm.cancel(C.NOTI_ALERT);
            return START_STICKY;
        }

        // 默认：启动监听
        startForegroundCompat();
        if (!running) {
            P.setRunning(this, true);
            running = true;
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "ntfy-poll");
            worker.setDaemon(true);
            worker.start();
        }
        return START_STICKY;
    }

    private void shutdown() {
        running = false;
        P.setRunning(this, false);
        if (nm != null) nm.cancel(C.NOTI_STATUS);
        stopForeground(true);
        stopSelf();
    }

    // ------------------------- 通知渠道 -------------------------

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26 || nm == null) return;

        NotificationChannel alert = new NotificationChannel(
                C.CH_ALERT, "跌倒告警", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("老人跌倒 / 晕倒 / 坐位晕厥的紧急告警");
        alert.enableVibration(true);
        alert.setVibrationPattern(new long[]{0, 400, 200, 400, 200, 400});
        alert.enableLights(true);
        alert.setLightColor(Color.RED);
        alert.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        alert.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        nm.createNotificationChannel(alert);

        NotificationChannel statusCh = new NotificationChannel(
                C.CH_STATUS, "监听状态", NotificationManager.IMPORTANCE_MIN);
        statusCh.setDescription("后台监听服务的常驻通知（静默）");
        statusCh.setShowBadge(false);
        nm.createNotificationChannel(statusCh);
    }

    private Notification.Builder nb(String channel) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, channel);
        }
        return new Notification.Builder(this);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void startForegroundCompat() {
        Notification n = nb(C.CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("跌倒监测 · 监听中")
                .setContentText(status)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .setContentIntent(openApp())
                .build();
        startForeground(C.NOTI_STATUS, n);
    }

    private void updateStatusNotif(String text) {
        if (nm == null) return;
        try {
            Notification n = nb(C.CH_STATUS)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("跌倒监测 · 监听中")
                    .setContentText(text)
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_MIN)
                    .setContentIntent(openApp())
                    .build();
            nm.notify(C.NOTI_STATUS, n);
        } catch (Exception ignored) {
        }
    }

    // ------------------------- 轮询主循环 -------------------------

    private void loop() {
        long interval = C.POLL_MS;
        while (running) {
            try {
                String since = P.lastId(this);
                if (since == null || since.isEmpty()) since = "60m";   // 首次只看最近 1 小时

                String url = P.server(this);
                while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                url = url + "/" + Net.enc(P.topic(this)) + "/json?poll=1&since=" + Net.enc(since);

                String body = Net.get(url);
                int count = 0;
                String lastId = null;

                if (body != null) {
                    String[] lines = body.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i].trim();
                        if (line.isEmpty()) continue;
                        JSONObject o;
                        try {
                            o = new JSONObject(line);
                        } catch (Exception e) {
                            continue;
                        }
                        if (!"message".equals(o.optString("event", "message"))) continue;
                        String id = o.optString("id", "");
                        if (!id.isEmpty()) lastId = id;
                        handle(o);
                        count++;
                    }
                }
                if (lastId != null) P.setLastId(this, lastId);

                interval = C.POLL_MS;
                setStatus(P.topic(this) + " · " + now() + " · 本轮 " + count + " 条");
                sleepQuiet(interval);

            } catch (Exception ex) {
                setStatus("网络异常（" + Net.shorten(ex.getMessage()) + "）· " + now() + " 重试中");
                sleepQuiet(interval);
                interval = Math.min(60000L, interval * 2);
            }
        }
    }

    private void handle(JSONObject msg) {
        Alert a = null;
        String text = msg.optString("message", "");
        String trimmed = text.trim();

        if (trimmed.startsWith("{")) {
            try {
                a = Alert.fromJson(new JSONObject(trimmed));
            } catch (Exception ignored) {
                a = null;
            }
        }
        if (a == null) {
            a = new Alert();
            a.raw = text;
            a.kindName = msg.optString("title", "告警");
            a.time = now();
        }
        long t = msg.optLong("time", 0);
        if (t > 0) {
            a.ts = t * 1000L;
            if (a.time == null || a.time.isEmpty()) a.time = fmt(a.ts);
        }

        P.addHistory(this, a);
        notifyAlert(a);

        Intent b = new Intent(C.ACTION_NEW_ALERT);
        b.setPackage(getPackageName());
        b.putExtra("area", a.area);
        b.putExtra("kind_name", a.kindName);
        sendBroadcast(b);
    }

    private void notifyAlert(Alert a) {
        Intent full = new Intent(this, AlertActivity.class);
        full.putExtra("title", a.title());
        full.putExtra("body", a.detail());
        full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piFull = PendingIntent.getActivity(this, 2, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent ack = new Intent(this, AlertService.class).setAction(C.ACTION_ACK);
        PendingIntent piAck = PendingIntent.getService(this, 3, ack,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = nb(C.CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(a.title())
                .setContentText(a.detail())
                .setStyle(new Notification.BigTextStyle().bigText(a.detail()))
                .setPriority(Notification.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setWhen(a.ts > 0 ? a.ts : System.currentTimeMillis())
                .setContentIntent(piFull)
                .setFullScreenIntent(piFull, true)
                .setVibrate(new long[]{0, 500, 300, 500, 300, 500});
        if (Build.VERSION.SDK_INT < 26) {
            b.setDefaults(Notification.DEFAULT_ALL);
        }
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "已处理", piAck);

        try {
            nm.notify(C.NOTI_ALERT, b.build());
        } catch (Exception ignored) {
        }
    }

    // ------------------------- 小工具 -------------------------

    private void setStatus(String s) {
        status = s;
        updateStatusNotif(s);
    }

    private void sleepQuiet(long ms) {
        long left = ms;
        while (left > 0 && running) {
            try {
                Thread.sleep(Math.min(200L, left));
            } catch (InterruptedException e) {
                return;
            }
            left -= 200L;
        }
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private static String fmt(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ts));
    }
}
