package com.binbin.pibridge;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/** 辅助服务：全局键 + 手势注入 + 截图 + 读屏 + 文本直设。开启方式（adb）：settings put secure enabled_accessibility_services */
public class AdbService extends AccessibilityService {
    public static AdbService inst;

    public static final int GLOBAL_BACK = GLOBAL_ACTION_BACK;
    public static final int GLOBAL_HOME = GLOBAL_ACTION_HOME;
    public static final int GLOBAL_RECENTS = GLOBAL_ACTION_RECENTS;

    @Override protected void onServiceConnected() {
        inst = this;
        try { // 副屏读树 + 全量节点（含"不重要"视图）
            AccessibilityServiceInfo info = getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        } catch (Exception ignore) {}
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
    @Override public boolean onUnbind(Intent i) { inst = null; return false; }

    public static boolean global(int action) {
        return inst != null && inst.performGlobalAction(action);
    }

    public static boolean tap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        return gesture(p, 40);
    }

    public static boolean swipe(int x1, int y1, int x2, int y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return gesture(p, Math.max(50, ms));
    }

    private static boolean gesture(Path p, long ms) {
        if (inst == null || Build.VERSION.SDK_INT < 24) return false;
        GestureBuilder gb = new GestureBuilder();
        gb.addStroke(p, ms);
        return inst.dispatchGesture(gb.build(), null, null);
    }

    /** 隔离 API 24 的 GestureDescription，避免类加载问题 */
    private static class GestureBuilder {
        android.accessibilityservice.GestureDescription.Builder b = new android.accessibilityservice.GestureDescription.Builder();
        void addStroke(Path p, long ms) {
            b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, ms));
        }
        android.accessibilityservice.GestureDescription build() { return b.build(); }
    }

    // ========== 截图（API 30+，无障碍通道，无需投影授权） ==========

    public interface ShotCb { void onShot(boolean ok, String msg); }

    public static void screenshot(final ShotCb cb) {
        if (inst == null) { cb.onShot(false, "辅助服务未开启"); return; }
        if (Build.VERSION.SDK_INT < 30) { cb.onShot(false, "需要 Android 11+"); return; }
        inst.takeScreenshot(Display.DEFAULT_DISPLAY, Runnable::run,
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override public void onSuccess(AccessibilityService.ScreenshotResult r) {
                        try {
                            Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                            Bitmap soft = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                            r.getHardwareBuffer().close();
                            if (soft == null) { cb.onShot(false, "位图转换失败"); return; }
                            File dir = new File(Environment_getExternal(), "pibridge/shots");
                            if (!dir.isDirectory()) dir.mkdirs();
                            File f = new File(dir, "shot_" + System.currentTimeMillis() + ".png");
                            FileOutputStream fo = new FileOutputStream(f);
                            soft.compress(Bitmap.CompressFormat.PNG, 100, fo);
                            fo.close();
                            cb.onShot(true, f.getAbsolutePath() + " " + f.length() + "B");
                        } catch (Exception e) {
                            cb.onShot(false, e.toString());
                        }
                    }
                    @Override public void onFailure(int code) { cb.onShot(false, "截图失败 code=" + code); }
                });
    }

    private static File Environment_getExternal() {
        return android.os.Environment.getExternalStorageDirectory();
    }

    // ========== 读屏（控件树结构化输出，AI 直接理解界面） ==========

    public static String diag = ""; // 副屏读树诊断
    public static JSONArray readTree() { return readTree(0); }

    private static final android.util.SparseArray<JSONArray> lastTree = new android.util.SparseArray<>();
    private static final android.util.SparseArray<String> lastPkg = new android.util.SparseArray<>();

    /** 取某屏上次读取树中编号为 idx 的节点信息（含 xy） */
    public static String pkgOf(int displayId) { return lastPkg.get(displayId, "?"); }
    public static int[] lastBounds(int displayId) {
        JSONArray arr = lastTree.get(displayId, null);
        if (arr == null || arr.length() == 0) return new int[]{0, 0};
        JSONObject first = arr.optJSONObject(0);
        if (first == null) return new int[]{0, 0};
        try {
            String[] p = first.optString("xy").split(" ")[0].split(",");
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        } catch (Exception e) { return new int[]{0, 0}; }
    }
    public static JSONObject nodeByIndex(int displayId, int idx) {
        JSONArray arr = lastTree.get(displayId, null);
        if (arr == null) return null;
        for (int k = 0; k < arr.length(); k++) {
            JSONObject o = arr.optJSONObject(k);
            if (o != null && o.optInt("i", -1) == idx) return o;
        }
        return null;
    }

    /** 在指定屏树里找首个含 target 文本的节点 */
    public static JSONObject findNodeByText(int displayId, String target) {
        JSONArray arr = lastTree.get(displayId, null);
        if (arr == null) return null;
        for (int k = 0; k < arr.length(); k++) {
            JSONObject o = arr.optJSONObject(k);
            if (o != null && (o.optString("text","").contains(target) || o.optString("desc","").contains(target))) return o;
        }
        return null;
    }

    public static int[] displaySize(int displayId) {
        try {
            DisplayManager dm = (DisplayManager) inst.getSystemService(Context.DISPLAY_SERVICE);
            android.view.Display d = displayId > 0 ? dm.getDisplay(displayId) : dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (d != null) { android.view.Display.Mode m = d.getMode(); return new int[]{m.getPhysicalWidth(), m.getPhysicalHeight()}; }
        } catch (Exception ignore) {}
        return new int[]{1280, 2772};
    }

    /** 读结构树；displayId>0 时读指定副屏（隐形虚拟屏同样可读，API33+） */
    public static JSONArray readTree(int displayId) { return readTree(displayId, null); }
    public static JSONArray readTree(int displayId, String filterPkg) {
        seq = 0;
        if (inst == null) return null;
        JSONArray out = new JSONArray();
        try {
            if (displayId > 0 && Build.VERSION.SDK_INT >= 33) {
                diag = "";
                // 反射调用 API33+ getWindowsOnAllDisplays()（返回 SparseArray<displayId, 窗口列表>）
                Object arr = null;
                try {
                    arr = AccessibilityService.class.getMethod("getWindowsOnAllDisplays").invoke(inst);
                } catch (Throwable e) { diag = "反射异常: " + e; }
                java.util.List<AccessibilityWindowInfo> wins = null;
                if (arr instanceof android.util.SparseArray) {
                    Object l = ((android.util.SparseArray<?>) arr).get(displayId);
                    if (l instanceof java.util.List) {
                        wins = (java.util.List<AccessibilityWindowInfo>) l;
                    } else diag = "该屏无窗口表";
                }
                if (wins != null) {
                    diag += " 窗口数:" + wins.size();
                    // 副屏窗口就绪有延迟：重试等待（最多4次×900ms）
                    for (int tryN = 0; tryN < 4 && out.length() == 0; tryN++) {
                        if (tryN > 0) { try { Thread.sleep(900); } catch (Exception ignore) {} }
                        for (AccessibilityWindowInfo w : wins) {
                            AccessibilityNodeInfo r = null;
                            try { r = w.getRoot(); } catch (Throwable e) { diag += " root异常:" + e; }
                            if (r != null) {
                                String wp = String.valueOf(r.getPackageName());
                                if (filterPkg != null && !filterPkg.isEmpty() && !wp.equals(filterPkg)) { diag += "[skip:" + wp + "]"; continue; }
                                diag += "[w" + w.getType() + "/" + w.getLayer() + ":" + wp + " 子节点" + r.getChildCount() + "]";
                                try { lastPkg.put(displayId, wp); } catch (Exception ignore) {}
                                walk(r, out, 0);
                            } else diag += "无根]";
                        }
                    }
                    diag += " 节点数:" + out.length();
                    if (out.length() > 0) lastTree.put(displayId, out);
                    return out.length() > 0 ? out : null;
                }
                return null;
            }
            AccessibilityNodeInfo root = inst.getRootInActiveWindow();
            if (root == null) return null;
            try { lastPkg.put(0, String.valueOf(root.getPackageName())); } catch (Exception ignore) {}
            walk(root, out, 0);
            if (out.length() > 0) lastTree.put(0, out);
            return out;
        } catch (Exception e) { return null; }
    }

    private static int seq = 0; // 全局节点编号（截图/点击引用的稳定锚）

    private static void walk(AccessibilityNodeInfo n, JSONArray out, int depth) {
        if (out.length() >= 250 || depth > 15 || n == null) return;
        try {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            JSONObject o = new JSONObject();
            // 屏外虚拟节点剔除（长列表 App 动辄数千节点）
            int[] ds = displaySize(0);
            if (r.left >= ds[0] + 200 || r.top >= ds[1] + 200 || r.right <= -200 || r.bottom <= -200) return;
            o.put("i", seq++);
            CharSequence t = n.getText();
            if (t != null && t.length() > 0) o.put("text", t.length() > 80 ? t.toString().substring(0, 80) + "…" : t.toString());
            if (n.isClickable() && (t == null || t.length() == 0) && n.getContentDescription() == null) {
                String ht = harvest(n, 0); // 行容器：从子节点借文本
                if (ht != null) o.put("text", ht.length() > 80 ? ht.substring(0, 80) + "…" : ht);
            }
            CharSequence d = n.getContentDescription();
            if (d != null && d.length() > 0) o.put("desc", d.length() > 60 ? d.toString().substring(0, 60) + "…" : d.toString());
            String id = n.getViewIdResourceName();
            if (id != null) { int i = id.indexOf('/'); if (i >= 0) id = id.substring(i + 1); o.put("id", id); }
            o.put("xy", r.left + "," + r.top + " " + r.width() + "x" + r.height());
            CharSequence cls = n.getClassName();
            if (cls != null) {
                String c = cls.toString();
                o.put("cls", c.substring(c.lastIndexOf('.') + 1));
            }
            if (n.isClickable()) o.put("click", true);
            if (n.isEditable()) o.put("edit", true);
            if (n.isPassword()) o.put("pwd", true);
            if (n.isSelected()) o.put("sel", true);
            if (n.isScrollable()) o.put("scroll", true);
            if (o.length() > 1) out.put(o);
            for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, depth + 1);
        } catch (Exception ignore) {}
    }

    /** 深度收割后代的首个有效文本/描述（给可点击行容器用） */
    private static String harvest(AccessibilityNodeInfo n, int d) {
        if (n == null || d > 4) return null;
        try {
            CharSequence t = n.getText();
            if (t != null && t.toString().trim().length() > 0) return t.toString().trim();
            CharSequence ds = n.getContentDescription();
            if (ds != null && ds.toString().trim().length() > 0) return ds.toString().trim();
            for (int i = 0; i < n.getChildCount(); i++) {
                String r = harvest(n.getChild(i), d + 1);
                if (r != null) return r;
            }
        } catch (Exception ignore) {}
        return null;
    }

    // ========== 文本直设（对可编辑节点 ACTION_SET_TEXT，绕过粘贴菜单） ==========

    /** 副屏文字输入：在指定副屏找可编辑节点（优先焦点）ACTION_SET_TEXT */

    // ========== 原生节点操作（副屏/主屏通用，无L2依赖）==========

    /** 按文本找活节点并原生点击（含可点击父容器回溯） */
    public static String clickByText(int displayId, String target) {
        if (inst == null) return "辅助服务未开启";
        AccessibilityNodeInfo n = findLiveNode(displayId, target);
        if (n == null) return "未找到";
        if (n.isClickable()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK) ? "已点击" : "节点拒绝";
        AccessibilityNodeInfo p = n.getParent();
        int hops = 0;
        while (p != null && hops < 6) {
            if (p.isClickable()) return p.performAction(AccessibilityNodeInfo.ACTION_CLICK) ? "已点击(父容器)" : "父节点拒绝";
            p = p.getParent(); hops++;
        }
        return "节点不可点击";
    }

    /** 原生滚动：找可滚动节点执行滚动动作 */
    public static String scrollNode(int displayId, boolean forward) {
        if (inst == null) return "辅助服务未开启";
        try {
            java.util.List<AccessibilityWindowInfo> wins = null;
            if (displayId > 0 && Build.VERSION.SDK_INT >= 33) {
                Object arr = AccessibilityService.class.getMethod("getWindowsOnAllDisplays").invoke(inst);
                if (arr instanceof android.util.SparseArray) {
                    Object l = ((android.util.SparseArray<?>) arr).get(displayId);
                    if (l instanceof java.util.List) wins = (java.util.List<AccessibilityWindowInfo>) l;
                }
            } else {
                wins = inst.getWindows();
            }
            if (wins == null) return "无窗口";
            int action = forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            for (AccessibilityWindowInfo w : wins) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                String rs = scrollWalk(r, action, 0);
                if (rs != null) return rs;
            }
            return "未找到可滚动节点";
        } catch (Throwable e) { return "异常: " + e; }
    }
    private static String scrollWalk(AccessibilityNodeInfo n, int action, int d) {
        if (n == null || d > 25) return null;
        try {
            if (n.isScrollable()) return n.performAction(action) ? "已滚动" : null;
            for (int i = 0; i < n.getChildCount(); i++) {
                String r = scrollWalk(n.getChild(i), action, d + 1);
                if (r != null) return r;
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static AccessibilityNodeInfo findLiveNode(int displayId, String target) {
        liveMatch = null; liveMatchClick = null;
        try {
            if (displayId > 0 && Build.VERSION.SDK_INT >= 33) {
                Object arr = AccessibilityService.class.getMethod("getWindowsOnAllDisplays").invoke(inst);
                if (arr instanceof android.util.SparseArray) {
                    Object l = ((android.util.SparseArray<?>) arr).get(displayId);
                    if (l instanceof java.util.List) {
                        for (AccessibilityWindowInfo w : (java.util.List<AccessibilityWindowInfo>) l) {
                            AccessibilityNodeInfo r = w.getRoot();
                            if (r != null) liveWalk(r, target, 0);
                        }
                    }
                }
                return liveMatchClick != null ? liveMatchClick : liveMatch;
            }
            AccessibilityNodeInfo root = inst.getRootInActiveWindow();
            if (root != null) liveWalk(root, target, 0);
            return liveMatchClick != null ? liveMatchClick : liveMatch;
        } catch (Throwable e) { return null; }
    }
    private static AccessibilityNodeInfo liveMatch;
    private static AccessibilityNodeInfo liveMatchClick;
    private static AccessibilityNodeInfo liveWalk(AccessibilityNodeInfo n, String target, int d) {
        if (n == null || d > 25) return null;
        try {
            String t = n.getText() == null ? "" : n.getText().toString();
            String ds = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
            if (target.length() > 0 && t.length() <= 40 && (t.contains(target) || ds.contains(target))) {
                if (liveMatch == null) liveMatch = n;
                if (liveMatchClick == null && n.isClickable()) liveMatchClick = n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo r = liveWalk(n.getChild(i), target, d + 1);
                if (r != null) return r;
            }
        } catch (Exception ignore) {}
        return null;
    }

    public static String setTextOnDisplay(int displayId, String text) {
        if (inst == null) return "辅助服务未开启";
        try {
            Object arr = AccessibilityService.class.getMethod("getWindowsOnAllDisplays").invoke(inst);
            if (!(arr instanceof android.util.SparseArray)) return "设备不支持";
            Object l = ((android.util.SparseArray<?>) arr).get(displayId);
            if (!(l instanceof java.util.List)) return "该屏无窗口";
            AccessibilityNodeInfo anyEditable = null, focusedEditable = null;
            for (AccessibilityWindowInfo w : (java.util.List<AccessibilityWindowInfo>) l) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) {
                    if (focusedEditable == null) focusedEditable = findEditable(r, 0, true);
                    if (anyEditable == null) anyEditable = findEditable(r, 0, false);
                }
            }
            AccessibilityNodeInfo target = focusedEditable != null ? focusedEditable : anyEditable;
            // 页面加载时序：最多重试8次找可编辑节点
            for (int attempt = 0; attempt < 8 && target == null; attempt++) {
                try { Thread.sleep(1200); } catch (Exception ignore) {}
                for (AccessibilityWindowInfo w : (java.util.List<AccessibilityWindowInfo>) l) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) continue;
                    target = findEditable(r, 0, true);
                    if (target == null) target = findEditable(r, 0, false);
                    if (target != null) break;
                }
            }
            if (target == null) return "未找到可编辑节点";
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ? "已设置" : "节点拒绝设置";
        } catch (Throwable e) { return "异常: " + e; }
    }
    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n, int d, boolean needFocus) {
        if (n == null || d > 25) return null;
        try {
            if (n.isEditable() && (!needFocus || n.isFocused())) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo r = findEditable(n.getChild(i), d + 1, needFocus);
                if (r != null) return r;
            }
        } catch (Exception ignore) {}
        return null;
    }


    /** 副屏兜底：找可编辑节点中心(px) */
    public static int[] editableCenterOnDisplay(int displayId) {
        if (inst == null) return null;
        try {
            Object arr = AccessibilityService.class.getMethod("getWindowsOnAllDisplays").invoke(inst);
            if (!(arr instanceof android.util.SparseArray)) return null;
            Object l = ((android.util.SparseArray<?>) arr).get(displayId);
            if (!(l instanceof java.util.List)) return null;
            for (AccessibilityWindowInfo w : (java.util.List<AccessibilityWindowInfo>) l) {
                int[] c = findEditableCenter(w.getRoot(), 0);
                if (c != null) return c;
            }
        } catch (Throwable ignore) {}
        return null;
    }
    private static int[] findEditableCenter(AccessibilityNodeInfo n, int d) {
        if (n == null || d > 25) return null;
        try {
            if (n.isEditable()) {
                android.graphics.Rect rc = new android.graphics.Rect();
                n.getBoundsInScreen(rc);
                if (rc.width() > 0) return new int[]{rc.centerX(), rc.centerY()};
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                int[] r = findEditableCenter(n.getChild(i), d + 1);
                if (r != null) return r;
            }
        } catch (Exception ignore) {}
        return null;
    }
    /** 副屏兜底：读可编辑节点当前文本（验证注入结果） */
    public static String readEditableTextOnDisplay(int displayId) {
        if (inst == null) return null;
        try {
            Object arr = AccessibilityService.class.getMethod("getWindowsOnAllDisplays").invoke(inst);
            if (!(arr instanceof android.util.SparseArray)) return null;
            Object l = ((android.util.SparseArray<?>) arr).get(displayId);
            if (!(l instanceof java.util.List)) return null;
            for (AccessibilityWindowInfo w : (java.util.List<AccessibilityWindowInfo>) l) {
                String t = readEditableText(w.getRoot(), 0);
                if (t != null) return t;
            }
        } catch (Throwable ignore) {}
        return null;
    }
    private static String readEditableText(AccessibilityNodeInfo n, int d) {
        if (n == null || d > 25) return null;
        try {
            if (n.isEditable()) return n.getText() == null ? "" : n.getText().toString();
            for (int i = 0; i < n.getChildCount(); i++) {
                String r = readEditableText(n.getChild(i), d + 1);
                if (r != null) return r;
            }
        } catch (Exception ignore) {}
        return null;
    }
    public static String setText(String text) {

        if (inst == null) return "辅助服务未开启";
        AccessibilityNodeInfo root = inst.getRootInActiveWindow();
        if (root == null) return "无活动窗口";
        AccessibilityNodeInfo node = inst.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null || !node.isEditable()) node = findEditable(root, 0);
        if (node == null) return "未找到可编辑输入框";
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b) ? "已设置" : "设置被拒绝";
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 15) return null;
        if (n.isEditable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }
}
