package com.leeann323.fallalert;

import org.json.JSONObject;

import java.util.Locale;

/** 一条告警 */
public class Alert {

    public String kind = "";        // A / B / C / S
    public String kindName = "";    // 中文名
    public String area = "";
    public String eid = "";
    public String time = "";
    public String raw = "";
    public double hold = 0;
    public double score = 0;
    public long ts = 0;

    /** 与电脑端 KIND_NAME 保持一致的映射 */
    public static String label(String kind) {
        if ("A".equals(kind)) return "跌倒（冲击型）";
        if ("B".equals(kind)) return "晕倒（缓慢滑落）";
        if ("C".equals(kind)) return "晕厥（坐位垂头）";
        if ("S".equals(kind)) return "久蹲提醒（请确认）";
        return "异常告警";
    }

    public String title() {
        String n = kindName == null || kindName.isEmpty() ? label(kind) : kindName;
        return area == null || area.isEmpty() ? n : n + " · " + area;
    }

    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        if (time != null && !time.isEmpty()) sb.append(time);
        if (eid != null && !eid.isEmpty()) {
            if (sb.length() > 0) sb.append("   ");
            sb.append("编号 ").append(eid);
        }
        if (score > 0) {
            if (sb.length() > 0) sb.append("   ");
            sb.append("置信度 ").append(String.format(Locale.US, "%.2f", score));
        }
        if (sb.length() == 0 && raw != null && !raw.isEmpty()) {
            sb.append(raw);
        }
        return sb.toString();
    }

    /** 全屏告警页 / 通知里展示的详情 */
    public String detail() {
        StringBuilder sb = new StringBuilder();
        sb.append("类型：").append(kindName == null || kindName.isEmpty() ? label(kind) : kindName).append('\n');
        if (area != null && !area.isEmpty()) sb.append("区域：").append(area).append('\n');
        if (time != null && !time.isEmpty()) sb.append("时间：").append(time).append('\n');
        if (hold > 0) sb.append("持续：").append(String.format(Locale.US, "%.1f", hold)).append(" s\n");
        if (score > 0) sb.append("置信度：").append(String.format(Locale.US, "%.2f", score)).append('\n');
        if (eid != null && !eid.isEmpty()) sb.append("编号：").append(eid).append('\n');
        if (raw != null && !raw.isEmpty() && (kindName == null || kindName.isEmpty())) {
            sb.append("内容：").append(raw);
        }
        return sb.toString().trim();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("kind", kind);
            o.put("kind_name", kindName);
            o.put("area", area);
            o.put("eid", eid);
            o.put("time", time);
            o.put("raw", raw);
            o.put("hold", hold);
            o.put("score", score);
            o.put("ts", ts);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Alert fromJson(JSONObject o) {
        Alert a = new Alert();
        a.kind = o.optString("kind", "");
        a.kindName = o.optString("kind_name", "");
        if (a.kindName.isEmpty()) a.kindName = label(a.kind);
        a.area = o.optString("area", "");
        a.eid = o.optString("eid", "");
        a.time = o.optString("time", "");
        a.raw = o.optString("raw", "");
        a.hold = o.optDouble("hold", 0);
        a.score = o.optDouble("score", 0);
        a.ts = o.optLong("ts", 0);
        if (a.ts <= 0) a.ts = System.currentTimeMillis();
        return a;
    }
}
