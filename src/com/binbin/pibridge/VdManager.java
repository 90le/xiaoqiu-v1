package com.binbin.pibridge;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * v1.2 完全体核心：App 级隐形虚拟屏
 * - createVirtualDisplay + ImageReader：完全不可见（无悬浮窗），画面帧直进内存
 * - launch()：ActivityOptions.setLaunchDisplayId 把任意 App 发射到副屏（用户主屏无感）
 * - shot()：最新帧存 PNG（pi 拿路径做视觉推理）
 * - 主屏 / 副屏完全解耦：用户该干嘛干嘛，副屏里小丘悄悄干活
 */
public class VdManager {
    private static final String TAG = "PiBridge";
    private static VirtualDisplay vd;
    private static ImageReader reader;
    private static volatile Bitmap last;
    private static int id = -1, w, h;
    private static android.os.HandlerThread vdThread; // 帧回调线程（ImageReader 需要 looper）

    private static final java.util.Set<String> launched = new java.util.HashSet<>();
    private static String lastPkg = "";
    private static long lastUse = System.currentTimeMillis();
    private static int dpi = 160;
    public static void touch() { lastUse = System.currentTimeMillis(); }
    static { // 空闲自动回收：3分钟无操作自动销毁副屏（防渲染/电池泄漏）
        Thread idleT = new Thread(() -> {
            while (true) {
                try { Thread.sleep(30000); } catch (Exception ignore) {}
                synchronized (VdManager.class) {
                    if (vd != null && System.currentTimeMillis() - lastUse > 180000) {
                        android.util.Log.i("PiBridge", "副屏空闲3分钟，自动回收");
                        destroy();
                    }
                }
            }
        }, "vd-idle-recycler");
        idleT.setDaemon(true);
        idleT.start();
    }
    public static void track(String pkg) { if (pkg != null && !pkg.isEmpty()) { launched.add(pkg); lastPkg = pkg; } }
    public static String lastLaunchedPkg() { return lastPkg; }
    public static java.util.Set<String> launchedPkgs() { return new java.util.HashSet<>(launched); }
    public static int displayId() { return id; }
    public static int dispW() { return w; }
    public static int dispH() { return h; }
    public static boolean alive() { return vd != null && id > 0; }

    /** 创建隐形副屏（默认 900x2000@160dpi，够看清单又省内存） */
    public static synchronized JSONObject create(Context c, int width, int height) {
        touch();
        if (alive()) { // 防泄漏：已有副屏先释放，绝不叠加僵尸display
            try { android.util.Log.i("PiBridge", "create前释放旧副屏#" + id); destroy(); } catch (Exception ignore) {}
        }
        // 默认对齐主屏真实分辨率+密度（App渲染与主屏一致）
        int dpi = 160;
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            android.hardware.display.DisplayManager dmgr = (android.hardware.display.DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            dmgr.getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(dm);
            if (width <= 0) width = dm.widthPixels;
            if (height <= 0) height = dm.heightPixels;
            dpi = dm.densityDpi;
        } catch (Exception ignore) {}
        w = width <= 0 ? 900 : width;
        h = height <= 0 ? 2000 : height;
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        vdThread = new android.os.HandlerThread("xiaoqiu-vd");
        vdThread.start();
        reader.setOnImageAvailableListener(r -> {
            Image img = null;
            try {
                img = r.acquireLatestImage();
                if (img == null) return;
                Image.Plane p = img.getPlanes()[0];
                ByteBuffer buf = p.getBuffer();
                Bitmap bm = Bitmap.createBitmap(p.getRowStride() / p.getPixelStride(), img.getHeight(), Bitmap.Config.ARGB_8888);
                bm.copyPixelsFromBuffer(buf);
                if (last != null && !last.isRecycled()) last.recycle();
                last = bm;
            } catch (Exception ignore) {
            } finally { if (img != null) try { img.close(); } catch (Exception ignore) {} }
        }, new android.os.Handler(vdThread.getLooper()));

        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        vd = ((DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE))
                .createVirtualDisplay("xiaoqiu-vd", w, h, dpi, reader.getSurface(), flags);
        if (vd == null) return err("CREATE_FAIL", "createVirtualDisplay 返回 null");
        id = vd.getDisplay().getDisplayId();
        JSONObject o = new JSONObject();
        try { o.put("displayId", id).put("w", w).put("h", h).put("invisible", true); } catch (Exception ignore) {}
        return o;
    }

    /** 把指定包名的 App 发射到副屏（优先 App 内启动，失败交 L2 shell 兜底） */
    public static JSONObject launch(Context c, String pkg) throws Exception {
        if (!alive()) return err("NO_VD", "先 create");
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return err("NOT_FOUND", "应用不存在: " + pkg);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        android.os.Bundle opt = buildLaunchOptions();
        try {
            c.startActivity(i, opt);
            return res("已发射到副屏" + id + "（App内通道）");
        } catch (Exception appErr) {
            Log.w(TAG, "App内发射失败，交L2: " + appErr);
            return null; // 交调用方走 L2 am start --display
        }
    }

    private static Bundle buildLaunchOptions() {
        ActivityOptions o = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= 26) o.setLaunchDisplayId(id);
        return o.toBundle();
    }

    /** 最新一帧存 PNG（供 pi 视觉），无新帧返回 false */
    /** 网格标注版截图（SoM思想）：6x10网格+A1..F10标签+坐标尺，pi 视觉定位精度大幅提升 */
    public static synchronized JSONObject shotGrid(Context c) {
        if (!alive() || last == null || last.isRecycled()) return err("NO_FRAME", "副屏无画面");
        try {
            Bitmap src = last.copy(last.getConfig(), true);
            android.graphics.Canvas cv = new android.graphics.Canvas(src);
            android.graphics.Paint lp = new android.graphics.Paint();
            lp.setColor(0x88FF3030); lp.setStrokeWidth(2);
            android.graphics.Paint tp = new android.graphics.Paint();
            tp.setColor(0xFFFF3030); tp.setTextSize(34); tp.setFakeBoldText(true);
            int cols = 6, rows = 10;
            for (int i = 1; i < cols; i++) {
                int x = src.getWidth() * i / cols;
                cv.drawLine(x, 0, x, src.getHeight(), lp);
            }
            for (int j = 1; j < rows; j++) {
                int y = src.getHeight() * j / rows;
                cv.drawLine(0, y, src.getWidth(), y, lp);
            }
            String col = "ABCDEF";
            for (int i = 0; i < cols; i++) for (int j = 0; j < rows; j++) {
                int x = src.getWidth() * i / cols + 8;
                int y = src.getHeight() * j / rows + 38;
                cv.drawText(col.charAt(i) + "" + (j + 1), x, y, tp);
            }
            File dir = new File(c.getExternalFilesDir(null), "vd");
            if (!dir.isDirectory()) dir.mkdirs();
            File f = new File(dir, "grid.png");
            FileOutputStream fo = new FileOutputStream(f);
            src.compress(Bitmap.CompressFormat.PNG, 80, fo);
            fo.close();
            src.recycle();
            File pub = new File("/storage/emulated/0/pibridge/shots/vd-grid.png");
            try { java.nio.file.Files.copy(f.toPath(), pub.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignore) {}
            JSONObject o = new JSONObject();
            try { o.put("file", pub.getAbsolutePath()).put("grid", cols + "x" + rows)
                    .put("hint", "网格标签A1-F10，格宽" + src.getWidth()/cols + "px 格高" + src.getHeight()/rows + "px"); } catch (Exception ignore) {}
            return o;
        } catch (Exception e) { return err("GRID_FAIL", e.toString()); }
    }

    public static synchronized JSONObject shot(Context c) {
        if (!alive() || last == null || last.isRecycled()) return err("NO_FRAME", "副屏无画面（App 未渲染?）");
        try {
            File dir = new File(c.getExternalFilesDir(null), "vd");
            if (!dir.isDirectory()) dir.mkdirs();
            java.io.ByteArrayOutputStream pbo = new java.io.ByteArrayOutputStream();
            last.compress(Bitmap.CompressFormat.PNG, 80, pbo);
            byte[] png = pbo.toByteArray();
            File f = new File(dir, "shot-" + System.currentTimeMillis() + ".png");
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(png); fo.close();
            // 同步一份到公共目录，pi 环境直接可读
            File pub = new File("/storage/emulated/0/pibridge/shots/vd-latest.png");
            try { java.nio.file.Files.copy(f.toPath(), pub.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignore) {}
            JSONObject o = new JSONObject();
            try {
                o.put("file", pub.getAbsolutePath()).put("size", pub.length());
                o.put("png_b64", android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)); // 前端预览用（绕开FUSE权限）
            } catch (Exception ignore) {}
            return o;
        } catch (Exception e) { return err("SHOT_FAIL", e.toString()); }
    }

    public static synchronized void destroy() {
        try { if (vd != null) vd.release(); } catch (Exception ignore) {}
        try { if (reader != null) reader.close(); } catch (Exception ignore) {}
        try { if (last != null) last.recycle(); } catch (Exception ignore) {}
        if (vdThread != null) vdThread.quitSafely();
        vd = null; reader = null; last = null; id = -1; vdThread = null; launched.clear(); lastPkg = "";
    }

    private static JSONObject res(String msg) { JSONObject o = new JSONObject(); try { o.put("msg", msg); } catch (Exception ignore) {} return o; }
    private static JSONObject err(String code, String msg) { JSONObject o = new JSONObject(); try { o.put("error", code).put("msg", msg); } catch (Exception ignore) {} return o; }
}
