package com.binbin.pibridge;

import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 通知监听（"听"的核心）：捕获全部通知（微信/短信/淘宝物流...）写入环形缓冲文件。
 * 权限：BIND_NOTIFICATION_LISTENER_SERVICE（L2 settings put 授权）。
 * 读：notify_read 工具；清：notify_clear。
 */
public class NotifyListener extends NotificationListenerService {
    private static final int MAX = 60;
    // 近期通知原引用（回复用：RemoteInput 在 sbn 对象里）
    private static final java.util.LinkedHashMap<String, StatusBarNotification> recentSbn = new java.util.LinkedHashMap<>();
    public static StatusBarNotification getSbn(String key) {
        synchronized (NotifyListener.class) { return recentSbn.get(key); }
    }
    public static String findKey(String pkg, String match) {
        synchronized (NotifyListener.class) {
            java.util.Iterator<String> it = recentSbn.keySet().iterator();
            String last = null;
            while (it.hasNext()) {
                String k = it.next();
                StatusBarNotification s = recentSbn.get(k);
                if (s == null) continue;
                if (pkg != null && !pkg.isEmpty() && !s.getPackageName().contains(pkg)) continue;
                if (match != null && !match.isEmpty()) {
                    String t = String.valueOf(s.getNotification().extras.getCharSequence(android.app.Notification.EXTRA_TITLE, ""));
                    if (!t.contains(match)) continue;
                }
                last = k;
            }
            return last;
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "notify-log.json");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null || sbn.getNotification() == null) return;
            android.os.Bundle ex = sbn.getNotification().extras;
            String title = cs(ex.getCharSequence(android.app.Notification.EXTRA_TITLE));
            String text = cs(ex.getCharSequence(android.app.Notification.EXTRA_TEXT));
            String big = cs(ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT));
            if ((title.isEmpty() && text.isEmpty() && big.isEmpty())) return;
            synchronized (NotifyListener.class) {
                JSONArray arr = readArr(this);
                synchronized (NotifyListener.class) {
                    recentSbn.put(sbn.getKey(), sbn);
                    while (recentSbn.size() > 30) {
                        String eldest = recentSbn.keySet().iterator().next();
                        recentSbn.remove(eldest);
                    }
                }
                JSONObject o = new JSONObject();
                o.put("pkg", sbn.getPackageName());
                o.put("key", sbn.getKey());
                if (!title.isEmpty()) o.put("title", title);
                if (!text.isEmpty()) o.put("text", text);
                if (!big.isEmpty() && !big.equals(text)) o.put("big", big);
                o.put("time", System.currentTimeMillis());
                arr.put(o);
                while (arr.length() > MAX) arr.remove(0);
                Tools.write(file(this), arr.toString());
            }
        } catch (Exception ignore) {}
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) { /* 不处理 */ }

    /** 读取：limit 条（最新在前），可按包名过滤 */
    public static JSONArray read(Context c, int limit, String pkgFilter) {
        JSONArray arr = readArr(c);
        JSONArray out = new JSONArray();
        for (int i = arr.length() - 1; i >= 0 && out.length() < limit; i--) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (pkgFilter != null && !pkgFilter.isEmpty() && !o.optString("pkg","").contains(pkgFilter)) continue;
            out.put(o);
        }
        return out;
    }

    public static void clear(Context c) {
        try { Tools.write(file(c), "[]"); } catch (Exception ignore) {}
    }

    private static JSONArray readArr(Context c) {
        try {
            return new JSONArray(new String(java.nio.file.Files.readAllBytes(file(c).toPath()), "UTF-8"));
        } catch (Exception e) { return new JSONArray(); }
    }

    private static String cs(CharSequence c) { return c == null ? "" : c.toString().trim(); }
}
