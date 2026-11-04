package com.leeann323.fallalert;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 配置与本地记录（用 SharedPreferences 保存，避免引入数据库依赖） */
public final class P {

    private P() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(C.PREFS, Context.MODE_PRIVATE);
    }

    public static String server(Context c) {
        String s = sp(c).getString(C.K_SERVER, C.DEFAULT_SERVER);
        if (s == null || s.trim().isEmpty()) {
            return C.DEFAULT_SERVER;
        }
        return s.trim();
    }

    public static void setServer(Context c, String v) {
        sp(c).edit().putString(C.K_SERVER, v == null ? "" : v.trim()).apply();
    }

    public static String topic(Context c) {
        SharedPreferences s = sp(c);
        String t = s.getString(C.K_TOPIC, "");
        if (t == null || t.trim().isEmpty()) {
            t = C.newTopic();
            s.edit().putString(C.K_TOPIC, t).apply();
        }
        return t.trim();
    }

    public static void setTopic(Context c, String v) {
        sp(c).edit().putString(C.K_TOPIC, v == null ? "" : v.trim()).apply();
    }

    public static String lastId(Context c) {
        return sp(c).getString(C.K_LAST_ID, "");
    }

    public static void setLastId(Context c, String v) {
        sp(c).edit().putString(C.K_LAST_ID, v == null ? "" : v).apply();
    }

    public static boolean running(Context c) {
        return sp(c).getBoolean(C.K_RUNNING, false);
    }

    public static void setRunning(Context c, boolean v) {
        sp(c).edit().putBoolean(C.K_RUNNING, v).apply();
    }

    public static List<Alert> history(Context c) {
        List<Alert> out = new ArrayList<Alert>();
        try {
            JSONArray arr = new JSONArray(sp(c).getString(C.K_HISTORY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                out.add(Alert.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void addHistory(Context c, Alert a) {
        List<Alert> list = history(c);
        list.add(0, a);
        while (list.size() > 100) {
            list.remove(list.size() - 1);
        }
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            arr.put(list.get(i).toJson());
        }
        sp(c).edit().putString(C.K_HISTORY, arr.toString()).apply();
    }

    public static void clearHistory(Context c) {
        sp(c).edit().putString(C.K_HISTORY, "[]").apply();
    }

    /** 完整推送地址，方便复制到电脑端配置里 */
    public static String fullUrl(Context c) {
        String s = server(c);
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "/" + topic(c);
    }
}
