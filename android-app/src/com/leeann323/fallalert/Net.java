package com.leeann323.fallalert;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 极简 HTTP 工具，只用 JDK 自带 API，避免引入任何第三方库 */
public final class Net {

    private Net() { }

    public static String get(String urlStr) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "FallAlert-Android/1.0");
            c.setRequestProperty("Accept", "application/x-ndjson");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = read(in);
            if (code >= 400) {
                throw new IOException("HTTP " + code + " " + shorten(body));
            }
            return body;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static String post(String urlStr, String body, String contentType) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("User-Agent", "FallAlert-Android/1.0");
            c.setRequestProperty("Content-Type", contentType == null ? "text/plain; charset=utf-8" : contentType);
            byte[] data = body.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(data.length);
            OutputStream out = c.getOutputStream();
            out.write(data);
            out.flush();
            out.close();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String resp = read(in);
            if (code >= 400) {
                throw new IOException("HTTP " + code + " " + shorten(resp));
            }
            return resp;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        r.close();
        return sb.toString();
    }

    public static String shorten(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    public static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
