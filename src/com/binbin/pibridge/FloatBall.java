package com.binbin.pibridge;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Collections;

/** MT 式悬浮球：吸附态（边缘竖条，触摸区加宽+手势排除）/ 自由态（圆球），轻点回小丘，可拖拽互换。 */
public class FloatBall {
    private static WindowManager wm;
    private static View ball;
    private static boolean docked = true;
    private static int dockSide = 0; // 0 左 1 右
    private static int dockY = 420;
    private static int floatX, floatY;

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** 唤醒命中动画：悬浮球脉冲放大+发光（供 :kws 进程广播触发） */
    public static void pulse(Context c) {
        MAIN.post(() -> { try {
            if (ball == null || ball.getParent() == null) return;
            ball.animate().scaleX(1.6f).scaleY(1.6f).setDuration(180).start();
            MAIN.postDelayed(() -> { try { ball.animate().scaleX(1f).scaleY(1f).setDuration(250).start(); } catch (Exception ignore) {} }, 200);
            MAIN.postDelayed(() -> { try { ball.animate().scaleX(1.3f).scaleY(1.3f).setDuration(150).start(); } catch (Exception ignore) {} }, 480);
            MAIN.postDelayed(() -> { try { ball.animate().scaleX(1f).scaleY(1f).setDuration(220).start(); } catch (Exception ignore) {} }, 650);
        } catch (Exception ignore) {} });
    }
    private static android.widget.LinearLayout stateBox;   // 球旁提示气泡
    private static WindowManager.LayoutParams bubbleLp;
    private static long lastTap = 0;
    private static int openToken = 0;

    /** 球旁动态提示气泡：直说当前状态与该做什么 */
    private static void showBubble(Context c, int ballX, int ballW, int ballY, String main, String sub) {
        try {
            if (stateBox == null) {
                android.widget.LinearLayout box = new android.widget.LinearLayout(c);
                box.setOrientation(android.widget.LinearLayout.VERTICAL);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(dp(c, 14)); bg.setColor(0xF0141414);
                box.setBackground(bg);
                box.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
                android.widget.TextView t1 = new android.widget.TextView(c);
                t1.setTextColor(0xFFFFFFFF); t1.setTextSize(14); t1.setTypeface(null, android.graphics.Typeface.BOLD);
                t1.setId(101);
                android.widget.TextView t2 = new android.widget.TextView(c);
                t2.setTextColor(0xFFD9D9D9); t2.setTextSize(12);
                t2.setId(102);
                box.addView(t1); box.addView(t2);
                stateBox = box;
                bubbleLp = new WindowManager.LayoutParams(
                        dp(c, 215), WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        android.graphics.PixelFormat.TRANSLUCENT);
            }
            android.widget.TextView t1 = stateBox.findViewById(101);
            android.widget.TextView t2 = stateBox.findViewById(102);
            t1.setText(main);
            boolean hasSub = sub != null && !sub.isEmpty();
            t2.setText(sub == null ? "" : sub);
            t2.setVisibility(hasSub ? View.VISIBLE : View.GONE);
            int bw = dp(c, 215);
            int bx;
            if (dockSide == 1) bx = Math.max(dp(c, 4), ballX - bw - dp(c, 6));
            else bx = Math.min(screenW(c) - bw - dp(c, 4), ballX + ballW + dp(c, 6));
            bubbleLp.x = bx;
            bubbleLp.y = Math.max(dp(c, 60), Math.min(ballY - dp(c, 8), screenH(c) - dp(c, 230)));
            if (stateBox.getParent() == null) wm.addView(stateBox, bubbleLp);
            else wm.updateViewLayout(stateBox, bubbleLp);
            stateBox.setVisibility(View.VISIBLE);
        } catch (Exception e) { Log.w("PiBridge", "bubble: " + e); }
    }
    private static void hideBubbleSoon(long ms) {
        MAIN.postDelayed(() -> { try { if (stateBox != null && stateBox.getParent() != null) wm.removeView(stateBox); } catch (Exception ignore) {} }, ms);
    }

    /** VoiceCore 状态 → 球 + 气泡联动 */
    private static void onVoiceState(Context c, TextView v, int ballX, int ballW, int ballY, String st, String info) {
        int color = 0xE63E7C59; String txt = "丘";
        String bMain = "", bSub = "";
        if ("listen".equals(st)) {
            color = 0xE6E8853D; txt = "🎙";
            bMain = "🎧 请说话"; bSub = "说「小丘」+ 要求，例：小丘，今天几号？";
        } else if ("think".equals(st)) {
            color = 0xE6D9A441; txt = "💭";
            bMain = "💭 想好啦/识别到"; bSub = info;
        } else if ("speak".equals(st)) {
            color = 0xE6447BA6; txt = "🔊";
            bMain = "🔊 播报中"; bSub = info;
        } else if ("exit".equals(st)) {
            vibrate(c, 30);
            bMain = "✅ " + (info == null || info.isEmpty() ? "对话结束" : info);
            bSub = "";
        }
        v.setText(txt);
        setBallColor(v, color);
        if ("exit".equals(st)) { showBubble(c, ballX, ballW, ballY, bMain, bSub); hideBubbleSoon(1800); }
        else if (!bMain.isEmpty()) showBubble(c, ballX, ballW, ballY, bMain, bSub);
    }
    private static final int TOUCH_W = 36;   // 吸附态触摸区宽（视觉条 10px 贴边，其余向内延伸便于抓取）
    private static final int BAR_VISUAL = 10;
    private static final int BAR_H = 64;
    private static final int CIRCLE = 52;
    private static final int SLOP = 18;
    private static final int DOCK_MAGNET = 60;

    private static int dp(Context c, int v) { return (int) (c.getResources().getDisplayMetrics().density * v + 0.5f); }
    private static int screenW(Context c) { return c.getResources().getDisplayMetrics().widthPixels; }
    private static int screenH(Context c) { return c.getResources().getDisplayMetrics().heightPixels; }

    private static File prefFile(Context c) { return new File(c.getFilesDir(), "floatball"); }
    private static void save(Context c, boolean on) {
        try { FileOutputStream fo = new FileOutputStream(prefFile(c)); fo.write((on ? "1" : "0").getBytes()); fo.close(); } catch (Exception ignore) {}
    }
    public static boolean savedOn(Context c) {
        try { FileInputStream fi = new FileInputStream(prefFile(c)); byte[] b = new byte[1]; int n = fi.read(b); fi.close(); return n == 1 && b[0] == '1'; } catch (Exception e) { return false; }
    }
    public static boolean isOn() { return ball != null; }

    public static boolean toggle(Context c) {
        if (isOn()) { runMain(c, "hide"); return false; }
        runMain(c, "show");
        return true;
    }
    private static void runMain(Context c, final String what) {
        MAIN.post(() -> { if (what.equals("show")) show(c); else hide(c); });
    }
    public static void showAsync(Context c) { runMain(c, "show"); }
    public static void hideAsync(Context c) { runMain(c, "hide"); }

    public static boolean show(Context c) {
        if (ball != null) return true;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) return false;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        final TextView v = new TextView(c);
        v.setTextColor(Color.WHITE);
        v.setTextSize(16);
        v.setGravity(Gravity.CENTER);

        final WindowManager.LayoutParams fp = new WindowManager.LayoutParams(
                dp(c, TOUCH_W), dp(c, BAR_H),
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        fp.gravity = Gravity.TOP | Gravity.START;

        final Context fc = c;
        final long[] downAt = {0};
            final float[] down = new float[2];
        final int[] startPos = new int[2];
        final boolean[] moved = {false};
        final boolean[] dragging = {false};

        v.setOnTouchListener((vw, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = ev.getRawX(); down[1] = ev.getRawY();
                    startPos[0] = fp.x; startPos[1] = fp.y;
                    moved[0] = false; dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - down[0], dy = ev.getRawY() - down[1];
                    if (!dragging[0] && Math.hypot(dx, dy) > dp(fc, SLOP)) {
                        dragging[0] = true;
                        if (docked) {
                            // 吸附条沿边上下滑动（不展开，避免与系统手势打架）
                            fp.y = Math.max(0, Math.min(startPos[1] + (int) dy, screenH(fc) - fp.height));
                            dockY = fp.y;
                            try { wm.updateViewLayout(v, fp); } catch (Exception ignore) {}
                            return true;
                        }
                    }
                    if (dragging[0]) {
                        if (docked) {
                            fp.y = Math.max(0, Math.min(startPos[1] + (int) dy, screenH(fc) - fp.height));
                            dockY = fp.y;
                        } else {
                            fp.x = startPos[0] + (int) dx;
                            fp.y = startPos[1] + (int) dy;
                            fp.x = Math.max(0, Math.min(fp.x, screenW(fc) - fp.width));
                            fp.y = Math.max(0, Math.min(fp.y, screenH(fc) - fp.height));
                        }
                        try { wm.updateViewLayout(v, fp); } catch (Exception ignore) {}
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    if (!dragging[0]) {
                        // 轻点：吸附条 → 展开成圆球；圆球 → 打开小丘
                        if (docked) {
                            floatX = dockSide == 0 ? dp(fc, 24) : screenW(fc) - dp(fc, CIRCLE + 24);
                            floatY = fp.y;
                            docked = false;
                            fp.width = dp(fc, CIRCLE);
                            fp.height = dp(fc, CIRCLE);
                            fp.x = floatX; fp.y = floatY;
                            GradientDrawable g = new GradientDrawable();
                            g.setShape(GradientDrawable.OVAL);
                            g.setColor(0xE63E7C59);
                            v.setBackground(g);
                            v.setText("丘");
                            try { wm.updateViewLayout(v, fp); } catch (Exception ignore) {}
                        } else {
                            // 对话中：单击=结束对话
                            if (VoiceCore.running) {
                                VoiceCore.stop();
                                v.setText("丘"); setBallColor(v, 0xE63E7C59);
                                hideBubbleSoon(300);
                                return true;
                            }
                            long now = System.currentTimeMillis();
                            if (now - lastTap < 350) {
                                // ⭐ 双击：开启待命语音对话（防误触：需两连击）
                                lastTap = 0;
                                openToken++;
                                vibrate(fc, 40);
                                VoiceCore.nextStandby = true;
                                VoiceCore.start(new VoiceCore.Listener() {
                                    public void onState(String st, String info) {
                                        final int bx2 = fp.x, bw2 = fp.width, by2 = fp.y;
                                        MAIN.post(() -> onVoiceState(fc, v, bx2, bw2, by2, st, info));
                                    }
                                    public void onTask(String q) {
                                        MainActivity.PENDING_TASK = q;
                                        hideBubbleSoon(800);
                                        Intent i = new Intent(fc, MainActivity.class);
                                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                        fc.startActivity(i);
                                    }
                                    public Context ctx() { return fc; }
                                });
                                return true;
                            }
                            // 单击：延迟打开 App（若紧跟着第二击则让位给语音）
                            lastTap = now;
                            final int token = ++openToken;
                            MAIN.postDelayed(() -> {
                                if (token == openToken && !VoiceCore.running) {
                                    Intent i = new Intent(fc, MainActivity.class);
                                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    fc.startActivity(i);
                                }
                            }, 360);

                        }
                        return true;
                    }
                    // 拖拽结束：自由球靠近边缘 → 吸附
                    if (!docked) {
                        int leftDist = fp.x;
                        int rightDist = screenW(fc) - (fp.x + fp.width);
                        if (Math.min(leftDist, rightDist) < dp(fc, DOCK_MAGNET)) {
                            dockSide = leftDist <= rightDist ? 0 : 1;
                            dockY = Math.max(dp(fc, 8), Math.min(fp.y, screenH(fc) - dp(fc, BAR_H) - dp(fc, 8)));
                            applyDocked(v, fp, fc);
                        }
                    }
                    return true;
                }
            }
            return false;
        });

        wm.addView(v, fp);
        ball = v;
        docked = true;
        dockSide = 0;
        applyDocked(v, fp, fc);
        gestureExclude(v);
        save(c, true);
        return true;
    }

    /** 吸附态外观与位置（竖条贴边，触摸区加宽） */
    static void setBallColor(android.view.View v, int color) {
        try { android.graphics.drawable.GradientDrawable g = (android.graphics.drawable.GradientDrawable) v.getBackground(); if (g != null) g.setColor(color); } catch (Exception ignore) {}
    }
    static void vibrate(Context c, int ms) {
        try { android.os.Vibrator vib = (android.os.Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE); if (vib != null) vib.vibrate(ms); } catch (Exception ignore) {}
    }
    private static void applyDocked(TextView v, WindowManager.LayoutParams fp, Context c) {
        docked = true;
        fp.width = dp(c, TOUCH_W);
        fp.height = dp(c, BAR_H);
        fp.x = dockSide == 0 ? 0 : screenW(c) - fp.width;
        fp.y = dockY;
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xD93E7C59);
        float r = dp(c, 10);
        if (dockSide == 0) g.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        else g.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        // 视觉条贴边 10px，其余为透明触摸延伸区：用 LayerDrawable 穿透观感
        GradientDrawable clear = new GradientDrawable();
        clear.setColor(Color.TRANSPARENT);
        android.graphics.drawable.Drawable[] layers = dockSide == 0
                ? new android.graphics.drawable.Drawable[]{clear, g}
                : new android.graphics.drawable.Drawable[]{clear, g};
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(layers);
        if (dockSide == 0) ld.setLayerInset(1, 0, dp(c, 4), dp(c, TOUCH_W - BAR_VISUAL), dp(c, 4));
        else ld.setLayerInset(1, dp(c, TOUCH_W - BAR_VISUAL), dp(c, 4), 0, dp(c, 4));
        v.setBackground(ld);
        v.setText("");
        try { wm.updateViewLayout(v, fp); } catch (Exception ignore) {}
        gestureExclude(v);
    }

    /** 自由态外观（圆球） */
    private static void becomeCircle(TextView v, WindowManager.LayoutParams fp, Context c) {
        fp.width = dp(c, CIRCLE);
        fp.height = dp(c, CIRCLE);
        fp.x = Math.max(0, Math.min(floatX, screenW(c) - fp.width));
        fp.y = Math.max(0, Math.min(floatY, screenH(c) - fp.height));
        v.setBackground(circleDrawable(c));
        v.setText("丘");
        try { wm.updateViewLayout(v, fp); } catch (Exception ignore) {}
    }

    private static GradientDrawable circleDrawable(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xE63E7C59);
        return g;
    }

    /** 占住边缘手势区（API 29+），防止系统返回手势抢走触摸 */
    private static void gestureExclude(View v) {
        if (Build.VERSION.SDK_INT >= 29) {
            v.post(() -> {
                try {
                    Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
                    v.setSystemGestureExclusionRects(Collections.singletonList(r));
                } catch (Exception ignore) {}
            });
        }
    }

    public static void hide(Context c) {
        save(c, false);
        try { if (ball != null && wm != null) wm.removeView(ball); } catch (Exception ignore) {}
        ball = null;
    }
}
