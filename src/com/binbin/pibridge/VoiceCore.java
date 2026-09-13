package com.binbin.pibridge;

import android.content.Context;
import java.io.File;
import org.json.JSONObject;

/** 统一连续对话引擎：听→想→说→自动续轮。悬浮球与 App 主界面共用 */
public class VoiceCore {
    public interface Listener {
        void onState(String state, String info); // listen / think / speak / idle / exit
        void onTask(String q);                   // 任务型请求（交给有执行环境的一侧）
        Context ctx();
    }

    public static volatile boolean running = false;
    public static volatile boolean standby = false; // 待命模式：需"小丘"唤醒词才响应
    private static Listener cb;
    private static int gen = 0;
    private static int miss = 0;

    /** 唤醒词（多样性：单喊/叠喊/问候式）；返回剥离唤醒词后的指令，null=未唤醒 */
    static String stripWake(String h) {
        String norm = h.replaceAll("[，。！？,.!?、\\s]+", "");
        // 同音字归一化（qiū：秋/邱→丘），只影响唤醒匹配不影响指令内容
        norm = norm.replace("秋", "丘").replace("邱", "丘");
        String[] wakes = {"小丘小丘", "你好小丘", "嘿小丘", "嗨小丘", "小丘", "丘丘"};
        for (String w : wakes) if (norm.startsWith(w)) return norm.substring(w.length());
        return null;
    }

    public static volatile boolean nextStandby = false; // 下一次 start 的模式（供调用方静态设置）
    public static void start(Listener l) { start(l, nextStandby); }

    public static void start(Listener l, boolean standbyMode) {
        if (running) return;
        cb = l; running = true; gen++; miss = 0; standby = standbyMode;
        emit("listen", "");
        final int g = gen;
        new Thread(() -> {
            try { Thread.sleep(400); } catch (Exception ignore) {}
            round(g);
        }, "voicecore").start();
    }

    public static void stop() {
        running = false;
        Tools.onSpeakDone = null;
        emit("idle", "");
    }

    static void emit(String s, String info) {
        Listener l = cb;
        if (l != null) try { l.onState(s, info); } catch (Exception ignore) {}
    }

    private static void round(final int g) {
        if (!running || g != gen) return;
        emit("listen", "");
        final File wav;
        try { wav = WavUtil.recordAutoStop(cb.ctx(), 20); }
        catch (Exception e) { exit(g, "录音出错: " + e.getMessage()); return; }
        if (!running || g != gen) return;
        if (wav == null) {
            if (++miss >= (standby ? 4 : 2)) { exit(g, "没听到说话，对话结束"); return; }
            round(g); return;
        }
        miss = 0;
        String heard = "";
        try {
            JSONObject env = Tools.call("stt_transcribe", new JSONObject().put("file", wav.getAbsolutePath()));
            if (env != null && env.optBoolean("ok")) heard = env.optString("data", "").trim();
        } catch (Exception ignore) {}
        if (!running || g != gen) return;
        String h = heard;
        if (h.isEmpty()) { round(g); return; }
        if (standby) {
            String rest = stripWake(h);
            if (rest == null) { emit("listen", "未唤醒"); round(g); return; } // 无唤醒词→忽略继续听
            if (rest.isEmpty()) { // 只喊了名字 → 应答
                emit("speak", "在呢");
                new Thread(() -> { try { Tools.call("tts_speak", new JSONObject().put("text", "在呢，请讲。")); } catch (Exception ignore) {} }).start();
                return;
            }
            h = rest;
        }
        final String cmd = h;
        if (cmd.matches(".*(结束对话|退出对话|停止对话).*")) { exit(g, "好的，对话结束"); return; }
        emit("think", cmd);
        new Thread(() -> think(g, cmd), "vc-think").start();
    }

    private static void think(final int g, final String heard) {
        final String[] box = {null};
        try {
            JSONObject env = Tools.call("chat_fast", new JSONObject().put("q", heard));
            if (env != null && env.optBoolean("ok")) {
                JSONObject d = env.optJSONObject("data");
                if (d != null && "chat".equals(d.optString("type"))) box[0] = d.optString("answer", "");
            }
        } catch (Exception ignore) {}
        if (!running || g != gen) return;
        final String answer = box[0];
        if (answer == null || answer.isEmpty()) {
            // 任务型 → 交给有执行环境的一侧（App 内 pi）
            emit("speak", "任务交给小丘处理");
            final Listener l = cb;
            new Thread(() -> {
                try { Tools.call("tts_speak", new JSONObject().put("text", "这个任务交给小丘处理，请打开小丘查看。")); } catch (Exception ignore) {}
                stop();
                if (l != null) try { l.onTask(heard); } catch (Exception ignore) {}
            }, "vc-task").start();
            return;
        }
        emit("speak", answer);
        armDone(g);
        try { Tools.call("tts_speak", new JSONObject().put("text", answer)); } catch (Exception e) { exit(g, "朗读失败"); }
    }

    /** 朗读完成 → 下一轮（400ms 缓冲） */
    static void armDone(final int g) {
        Tools.onSpeakDone = () -> new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (running && g == gen) round(g);
        }, 400);
    }

    private static void exit(final int g, String msg) {
        if (g != gen) return;
        stop();
        Listener l = cb;
        if (l != null) try { l.onState("exit", msg); } catch (Exception ignore) {}
    }
}
