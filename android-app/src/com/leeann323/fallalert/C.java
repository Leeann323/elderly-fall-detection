package com.leeann323.fallalert;

/** 全局常量 */
public final class C {
    public static final String DEFAULT_SERVER = "https://ntfy.sh";
    public static final long POLL_MS = 8000L;

    public static final String PREFS = "fall_alert_prefs";
    public static final String K_SERVER = "server";
    public static final String K_TOPIC = "topic";
    public static final String K_LAST_ID = "last_id";
    public static final String K_HISTORY = "history";
    public static final String K_RUNNING = "running";

    public static final String ACTION_START = "com.leeann323.fallalert.START";
    public static final String ACTION_STOP = "com.leeann323.fallalert.STOP";
    public static final String ACTION_ACK = "com.leeann323.fallalert.ACK";
    public static final String ACTION_NEW_ALERT = "com.leeann323.fallalert.NEW_ALERT";

    public static final String CH_ALERT = "fall_alert";
    public static final String CH_STATUS = "fall_status";
    public static final int NOTI_ALERT = 1001;
    public static final int NOTI_STATUS = 1002;

    private C() { }

    /** 生成一个不易被猜到的频道名 */
    public static String newTopic() {
        String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        java.util.Random r = new java.util.Random();
        StringBuilder sb = new StringBuilder("ltc-fall-");
        for (int i = 0; i < 8; i++) {
            sb.append(abc.charAt(r.nextInt(abc.length())));
        }
        return sb.toString();
    }
}
