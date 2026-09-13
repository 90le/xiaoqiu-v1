package com.binbin.pibridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import org.json.JSONObject;
import com.k2fsa.sherpa.onnx.QnnConfig;
import java.io.File;

/** 全局唤醒词服务：息屏/任意界面喊「小丘」即可唤醒执行（sherpa KWS 离线） */
public class WakeService extends Service {
    private static final String CH = "wake";
    private AudioRecord ar; // 常驻字段：服务任何退出路径都能立即释放麦克风
    private static volatile boolean running = false;
    private static KeywordSpotter kws;
    private static String lastKeyword = "";

    public static boolean isRunning() { return running; }

    public static void start(Context c) {
        if (running) return;
        c.startService(new Intent(c, WakeService.class));
    }
    public static void stop(Context c) {
        running = false;
        c.stopService(new Intent(c, WakeService.class));
        MAIN.post(() -> { try { if (kws != null) { kws.release(); kws = null; } } catch (Exception ignore) {} });
    }
    private static final android.os.Handler MAIN = new android.os.Handler(Looper.getMainLooper());

    /** 唤醒状态落文件：:kws 进程写，主进程读（跨进程唯一可信来源） */
    static void writeState(Context c, boolean on) {
        try {
            Tools.write(new File(c.getFilesDir(), "wake-state.json"),
                    new org.json.JSONObject().put("running", on).put("ts", System.currentTimeMillis()).toString());
        } catch (Exception ignore) {}
    }
    static boolean readState(Context c) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(
                    new String(java.nio.file.Files.readAllBytes(new File(c.getFilesDir(), "wake-state.json").toPath()), "UTF-8"));
            if (!o.optBoolean("running")) return false;
            return System.currentTimeMillis() - o.optLong("ts") < 20000; // 心跳超20秒视为已死
        } catch (Exception e) { return false; }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    private volatile boolean sessionActive = false; // 会话循环占用中（主监听让位）
    private volatile Thread sessionThread = null;   // 看门狗用：会话线程死了自动解除占用
    @Override public void onCreate() {
        // 前台服务：息屏不被 MIUI 冻结/回收（常驻通知=唤醒待命中的存在感）
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            android.app.NotificationChannel ch = new android.app.NotificationChannel("wake", "语音唤醒", android.app.NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false); nm.createNotificationChannel(ch);
            android.app.Notification n = new android.app.Notification.Builder(this, "wake")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("小丘待命中")
                    .setContentText("任意界面/息屏喊「小丘」唤醒我")
                    .setOngoing(true)
                    .build();
            startForeground(1001, n);
        } catch (Exception e) { Log.w("PiBridge", "fgs: " + e); }
        super.onCreate();
        Log.i("PiBridge", "WakeService onCreate");
        Tools.init(this); // :kws 独立进程必须自行初始化 Tools（ctx/引擎/配置）
        // 冷启动预热：TTS 预绑定 + 一次微型云 STT 暖 TLS/DNS（首次唤醒 -1~2s）
        new Thread(() -> {
            try { Thread.sleep(3000); } catch (Exception ignore) {}
            try { Tools.speakLocal(""); } catch (Exception ignore) {} // 触发 miTts/tts 懒加载绑定
            try {
                java.io.File w = java.io.File.createTempFile("warmup", ".wav", getCacheDir());
                com.binbin.pibridge.WavUtil.writeWav(w, new byte[3200], 16000, 1, 16); // 0.1s 静音
                Tools.cloudStt(w);
                w.delete();
                Log.i("PiBridge", "预热完成（TLS/TTS 已暖）");
            } catch (Throwable ignore) {}
        }, "warmup").start();
        // 统一会话总线接收（页面引擎 → 本进程）
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c2, android.content.Intent i) { turnDone = true; }
        }, new android.content.IntentFilter("com.pihost.VOICE_DONE"));
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c2, android.content.Intent i) { turnAck = true; }
        }, new android.content.IntentFilter("com.pihost.VOICE_ACK"));
        // 执行进度语音汇报：每个新工具开始 → 口播中文状态（节流：≥6s 间隔、每任务≤6次）
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c2, android.content.Intent i) {
                String t = i.getStringExtra("text");
                if (t == null || t.isEmpty() || !running || sessionStop) return;
                long now = System.currentTimeMillis();
                if (now - lastProgSpeak < 6000 || progCount >= 6) return;
                lastProgSpeak = now; progCount++;
                String zh = progZh(t);
                if (!zh.isEmpty()) { Log.i("PiBridge", "🗣 进度: " + zh); speakMarked(zh); }
            }
        }, new android.content.IntentFilter("com.pihost.VOICE_PROG"));
        // 智能进度播报（页面引擎据实时数据生成）：短句代播，不打断轮次结构
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c2, android.content.Intent i) {
                final String t = i.getStringExtra("text");
                if (t == null || t.isEmpty() || sessionStop) return;
                new Thread(() -> speakPSay(t)).start();
            }
        }, new android.content.IntentFilter("com.pihost.VOICE_PSAY"));
        // 跨进程麦克风互斥：主进程录音（声纹录入等）时暂停唤醒
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c2, android.content.Intent i) {
                Tools.micBusy = i.getBooleanExtra("on", false);
                if (Tools.micBusy) Log.i("PiBridge", "🎙 主进程录音中，唤醒暂停");
            }
        }, new android.content.IntentFilter("com.pihost.MIC_BUSY"));
        // 全局停止钮：停播+立即收尾
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c2, android.content.Intent i) {
                Tools.stopTts();
                sessionStop = true;
                WavUtil.abortAll(); // 录音中也能立即停（不等 6-12s 录完）
            }
        }, new android.content.IntentFilter("com.pihost.VOICE_STOP"));
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c2, android.content.Intent i) {
                String cmd = i.getStringExtra("cmd");
                if ("start".equals(cmd)) {
                    if (!sessionActive) { // 🎙点火：从主监听切进会话循环
                        sessionActive = true;
                        new Thread(() -> { sessionThread = Thread.currentThread(); try { if (ar != null) { ar.stop(); ar.release(); ar = null; } } catch (Exception ignore) {} sessionLoop(i.getStringExtra("from") == null ? "mic" : i.getStringExtra("from"), ""); }, "mic-session").start();
                    }
                } else { sessionStop = true; }
            }
        }, new android.content.IntentFilter("com.pihost.SESSION_CMD"));
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c2, android.content.Intent i) {
                pendingSpeak = new String[]{ i.getStringExtra("text"), i.getStringExtra("token"), i.getStringExtra("humanize") };
            }
        }, new android.content.IntentFilter("com.pihost.VOICE_SPEAK"));
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "语音唤醒", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("小丘待命中")
                .setContentText("说「小丘」唤醒 · 双击悬浮球也可")
                .setOngoing(true).build();
        startForeground(2001, n);
        running = true;
        writeState(this, true);
        new Thread(this::loop, "wake-kws").start();
    }

    private void loop() {
        // 小爱同学式持续监听管线：常录音环形缓冲 + 语音端点检测 + 整句转写（语音零丢失）
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xiaoqiu:wake");
        wl.acquire();
        try {
            int sr = 16000;
            int ringN = sr * 15;
            short[] ring = new short[ringN];
            int wpos = 0;
            long total = 0;
            final int TH_HIGH = 1500, TH_LOW = 600;
            int state = 0; // 0=静音 1=说话中
            long speechStart = 0, silenceMs = 0;
            int speechStartIdx = 0;
            short[] chunk = new short[sr / 10];
            long lastBeat = 0;
            File dir = new File(getFilesDir(), "sherpa/kws/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01");
            boolean announced = false;
            // 流式 KWS（assets 模型 + 熔断保护）：命中即唤醒，零转写延迟
            boolean kwsOn = Tools.initKwsOnce(dir);
            com.k2fsa.sherpa.onnx.OnlineStream kwsSt = kwsOn ? Tools.kwsCreateStream() : null;
            float[] kwsBuf = new float[chunk.length];
            while (running) {
                try {
                    if (ar == null) {
                        int minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                        ar = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, 16000 * 2 * 4));
                        ar.startRecording();
                        if (!announced) { Log.i("PiBridge", "唤醒监听就绪（持续监听管线：环形缓冲+VAD+整句转写）"); announced = true; }
                    }
                    if (Tools.ttsSpeaking || VoiceCore.running || Tools.micBusy) { Thread.sleep(250); continue; }
                    if (sessionActive) {
                        Thread st = sessionThread;
                        if (st == null || !st.isAlive()) { // 看门狗：会话线程已死但占用未清→强制解除（唤醒不了的根治）
                            Log.w("PiBridge", "会话占用泄漏，看门狗解除");
                            sessionActive = false;
                            try { sendBroadcast(new android.content.Intent("com.pihost.WAKE_GLOW_OFF")); } catch (Exception ignore) {}
                        }
                        Thread.sleep(200); continue;
                    }
                    try { // 媒体互斥：手机在放音乐/视频时，唤醒让位（不抢麦克风不误识别）
                        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                        if (am != null && am.isMusicActive()) { Thread.sleep(500); continue; }
                    } catch (Exception ignore) {}
                    int n = ar.read(chunk, 0, chunk.length);
                    if (n <= 0) { Thread.sleep(50); continue; }
                    for (int i = 0; i < n; i++) { ring[wpos] = chunk[i]; wpos = (wpos + 1) % ringN; }
                    total += n;
                    if (kwsSt != null) { // 流式喂 KWS：命中=零延迟唤醒
                        if (Tools.ttsSpeaking || System.currentTimeMillis() - lastSpokenAt < 1800) {
                            // 回声保护窗内：重置流防自唤醒
                            try { Tools.kwsReset(kwsSt); } catch (Exception ignore) {}
                        } else {
                            for (int i = 0; i < n; i++) kwsBuf[i] = chunk[i] / 32768.0f;
                            String kwHit = Tools.kwsFeedStream(kwsSt, kwsBuf, n);
                            if (kwHit != null && !kwHit.isEmpty() && !sessionActive) {
                                if (!voiceGate(ring, wpos, total, sr, ringN, kwHit)) continue;
                                Log.i("PiBridge", "🔔 KWS 命中: " + kwHit + "（零转写延迟）");
                                if (ar != null) { try { ar.stop(); ar.release(); ar = null; } catch (Exception ignore) {} }
                                sendBroadcast(new android.content.Intent("com.pihost.WAKE_ANIM"));
                                sessionLoop("wake", "");
                                Thread.sleep(400);
                                continue;
                            }
                        }
                    }
                    if (total - lastBeat > sr * 2) { writeState(this, true); lastBeat = total; }
                    double sumSq = 0;
                    for (int i = 0; i < n; i++) { double sv = chunk[i]; sumSq += sv * sv; }
                    double rms = Math.sqrt(sumSq / n);
                    if (Tools.ttsSpeaking || VoiceCore.running || Tools.micBusy) { state = 0; continue; }
                    try {
                        android.media.AudioManager am2 = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                        if (am2 != null && am2.isMusicActive()) { state = 0; continue; }
                    } catch (Exception ignore) {}
                    if (state == 0) {
                        if (rms >= TH_HIGH) {
                            state = 1; silenceMs = 0;
                            speechStart = Math.max(0, total - n - sr * 300 / 1000); // 300ms预滚
                            speechStartIdx = (int)((speechStart % ringN) + ringN) % ringN;
                            Log.d("PiBridge", "语音段开始");
                        }
                    } else {
                        if (rms >= TH_LOW) silenceMs = 0; else silenceMs += 100;
                        long uttLen = total - speechStart;
                        // 短句（多半是唤醒词）500ms 静默即截断——唤醒提速的关键
                        boolean shortUtt = uttLen < sr * 3;
                        boolean endOfSpeech = silenceMs >= (shortUtt ? 500 : 800) && uttLen >= sr * 800 / 1000;
                        boolean tooLong = uttLen >= sr * 10;
                        if (endOfSpeech || tooLong) {
                            int len = (int) Math.min(uttLen, ringN);
                            int sPos = speechStartIdx;
                            short[] seg = new short[len];
                            for (int i = 0; i < len; i++) seg[i] = ring[(sPos + i) % ringN];
                            state = 0;
                            handleUtterance(seg, sr, ring, wpos, total, ringN);
                        }
                    }
                } catch (Exception e) { Log.w("PiBridge", "wake loop: " + e); Thread.sleep(800); }
            }
        } catch (Exception e) {
            Log.w("PiBridge", "wake fatal: " + e);
        } finally {
            releaseMic();
            running = false;
            wl.release();
        }
    }

    /** 整句转写 → 唤醒匹配 → 携带指令执行/对话 */
    private void handleUtterance(short[] seg, int sr, short[] ring, int wpos, long total, int ringN) {
        try {
            byte[] pcm = new byte[seg.length * 2];
            for (int i = 0; i < seg.length; i++) { pcm[i*2] = (byte)(seg[i] & 255); pcm[i*2+1] = (byte)((seg[i] >> 8) & 255); }
            File chunkWav = new File(getCacheDir(), "wake-utt.wav");
            com.binbin.pibridge.WavUtil.writeWav(chunkWav, pcm, sr, 1, 16);
            String txt = "";
            String localTxt = "";
            boolean fromCloud = false;
            // 短句（<3.5s，多为唤醒词）：云端 GLM-ASR 优先——更快更准
            if (seg.length < sr * 35 / 10) {
                try { txt = Tools.cloudStt(chunkWav); } catch (Exception ignore) { txt = null; }
                if (txt == null) txt = "";
                fromCloud = !txt.isEmpty();
                Log.d("PiBridge", "唤醒转写(云): " + txt);
            }
            if (!fromCloud) {
                try {
                    JSONObject env = Tools.call("stt_transcribe", new JSONObject().put("file", chunkWav.getAbsolutePath()));
                    if (env != null && env.optBoolean("ok")) { txt = env.optString("data", ""); localTxt = txt; }
                } catch (Exception ignore) {}
            }
            txt = txt.replaceAll("<\\|[^>]*\\|>", "").replace(" ", "").trim();
            Log.d("PiBridge", "唤醒转写: " + txt);
            if (txt.isEmpty() || txt.startsWith("(")) return;
            boolean hit = wakeHit(txt);
            if (!hit && !localTxt.isEmpty()) hit = wakeHit(localTxt); // 云端没中→本地转写再判（口音关键兜底）
            if (!hit) return;
            Log.d("PiBridge", "唤醒命中源: " + (fromCloud ? "云" : "本"));
            if (sessionActive) return; // 会话中不重复触发（KWS 主路+ASR 兜底并存）
            if (Tools.ttsSpeaking || System.currentTimeMillis() - lastSpokenAt < 1800 || isEcho(txt)) {
                Log.i("PiBridge", "🛡 回声/保护窗拦截: " + txt); // 自己说话的回声不唤醒（根治自循环）
                return;
            }
            Log.i("PiBridge", "🔔 唤醒命中(ASR): " + txt);
            if (!voiceGate(ring, wpos, total, sr, ringN, "asr")) return; // ASR 兜底路同装声纹门禁
            sendBroadcast(new android.content.Intent("com.pihost.WAKE_ANIM"));
            if (ar != null) { ar.stop(); ar.release(); ar = null; }
            // 提取唤醒词后跟的首段指令（有则直接作为第一轮，免重录）
            String said = txt.replaceAll("[，。！？,.!?、\\s]+", "");
            String nn = wakeNorm(said);
            String carryCmd = "";
            for (String w0 : new String[]{"小丘小丘", "你好小丘", "嘿小丘", "嗨小丘", "小丘", "丘丘"}) {
                if (nn.startsWith(w0)) {
                    String rest = nn.substring(w0.length());
                    carryCmd = rest.isEmpty() ? "" : rest; // 归一化文本也接受（指令会被快脑再优化）
                    break;
                }
            }
            sessionLoop("wake", carryCmd);
            Thread.sleep(400);
        } catch (Exception e) { Log.w("PiBridge", "utt: " + e); }
    }


    private static boolean wakeHit(String raw) {
        String norm = wakeNorm(raw == null ? "" : raw);
        for (String w2 : new String[]{"小丘", "小丘丘", "你好小丘", "嘿小丘", "嗨小丘", "丘丘"}) {
            if (norm.contains(w2)) return true;
        }
        return false;
    }

    /** 唤醒词同音归一化：只影响命中匹配与剥离，不改指令内容（实测误转样本：小舅） */
    private static String wakeNorm(String s) {
        for (String h : new String[]{"秋", "邱", "舅", "九", "球", "求", "桥", "乔", "巧", "酒", "瞧", "邱", "囚", "丘"})
            s = s.replace(h, "丘");
        return s;
    }

    private final StringBuilder ctxBuf = new StringBuilder(); // 会话内快脑上下文（最近几轮）
    private void appendCtx(String line) {
        ctxBuf.append(line).append("\n");
        // 只留最近 ~1200 字
        if (ctxBuf.length() > 1200) ctxBuf.delete(0, ctxBuf.length() - 1200);
    }
    private void setGlow(String mode) {
        try { sendBroadcast(new android.content.Intent("com.pihost.GLOW_MODE").putExtra("mode", mode)); } catch (Exception ignore) {}
    }
    /** 等本地/文件播报完（带起播窗） */
    private void waitSpeakMs(long maxMs) { waitLocalSpeak(maxMs); }

    /** 工具名 → 口语化进度（空=不播） */
    private static String progZh(String tool) {
        String t = tool.toLowerCase();
        if (t.contains("location")) return "在查位置";
        if (t.contains("screenshot") || t.contains("vision") || t.contains("ocr") || t.contains("shot")) return "在看屏幕";
        if (t.contains("bash") || t.contains("env_run") || t.contains("termux") || t.contains("l2")) return "在跑命令";
        if (t.contains("apps_launch") || t.contains("ui_") || t.contains("vd") || t.contains("intent") || t.contains("app")) return "在操作手机";
        if (t.contains("notify") || t.contains("sms") || t.contains("contacts") || t.contains("calllog")) return "在查消息";
        if (t.contains("files")) return "在整理文件";
        if (t.contains("memory")) return "在记事情";
        if (t.contains("network") || t.contains("battery") || t.contains("device") || t.contains("sensor")) return "在查设备";
        if (t.contains("read") || t.contains("search") || t.contains("list")) return "在查资料";
        return "在处理";
    }

    private static final String[] NOISE_HINT = {"没听清，再说一遍？", "嗯？没听到，大声点试试", "再说一次？"};
    private static final String[] WAKE_REPLIES = {"在！", "我在！", "诶！", "嗯！"};
    private static final String[] BYE_TIMEOUT = {"嗯，我先退下", "先这样，叫我", "我在这儿呢，有事叫我"};
    private static final String[] BYE_BYE = {"好嘞", "嗯呐", "好的", "行"};

    // ── 会话总线状态（:kws 侧，主线程广播接收器写，会话线程轮询读）──
    private volatile boolean turnDone = false;
    private volatile boolean turnAck = false;
    private volatile long lastProgSpeak = 0;
    private volatile int progCount = 0;
    private volatile boolean sessionStop = false;
    private volatile String[] pendingSpeak = null; // {text, token}

    // ── 回声免疫（自唤醒根治）：本进程播过什么，麦克风听到相同内容=回声不算唤醒 ──
    private volatile String lastSpokenText = "";
    private volatile long lastSpokenAt = 0;
    private static final String BS = new String(new char[]{92}); // 反斜杠常量（免转义地狱）
    private void markSpoken(String t) { lastSpokenText = t == null ? "" : t; lastSpokenAt = System.currentTimeMillis(); }
    /** 转写文本是否像自己刚说的话（含"小丘"的自播文本回声） */
    private boolean isEcho(String txt) {
        if (lastSpokenText.isEmpty()) return false;
        if (System.currentTimeMillis() - lastSpokenAt > 25000) { lastSpokenText = ""; return false; } // 25s 记忆窗
        String a = txt.replaceAll("[，。！？,.!?、" + BS + "s]+", "");
        String b = lastSpokenText.replaceAll("[，。！？,.!?、" + BS + "s]+", "");
        return a.equals(b) || (a.length() >= 4 && (b.contains(a) || a.contains(b)));
    }

    /** 统一语音会话循环：录音(VAD)→交脑(VOICE_TURN)→等引擎(VOICE_DONE/VOICE_SPEAK)→续听。
     *  意图分流/prompt优化/结论播报全部在页面引擎；本进程只做 耳+嘴+打断。 */
    private void sessionLoop(String from, String carryIn) {
        sessionThread = Thread.currentThread();
        sessionActive = true;
        ctxBuf.setLength(0);
        sendBroadcast(new android.content.Intent("com.pihost.WAKE_GLOW_ON"));
        try {
            speakMarked(WAKE_REPLIES[new java.util.Random().nextInt(WAKE_REPLIES.length)]);
            waitLocalSpeak(1600);
            String carry = carryIn == null ? "" : carryIn;
            int noiseRounds = 0;
            while (running && !sessionStop) {
                String heard = carry; carry = "";
                if (heard.isEmpty()) {
                    File wav = WavUtil.recordAutoStop(this, 12, 6000); // 6秒无人声→收尾
                    if (wav == null) {
                        speakMarked(BYE_TIMEOUT[new java.util.Random().nextInt(BYE_TIMEOUT.length)]);
                        break;
                    }
                    heard = transcribe(wav);
                    if (heard == null || heard.isEmpty()) {
                        if (++noiseRounds >= 3) { speakMarked(NOISE_HINT[new java.util.Random().nextInt(NOISE_HINT.length)]); noiseRounds = 0; }
                        continue;
                    }
                }
                noiseRounds = 0;
                try { sendBroadcast(new android.content.Intent("com.pihost.VOICE_HEARD").putExtra("text", heard)); } catch (Exception ignore) {}
                if (heard.matches(".*(结束对话|结束|说完了|退下|没事了|不用了|再见).*")) {
                    speakMarked(BYE_BYE[new java.util.Random().nextInt(BYE_BYE.length)]);
                    break;
                }
                // ── 快脑分流（:kws 直答，不依赖后台页面——后台 WebView 不可靠的根治）──
                setGlow("think");
                JSONObject fr = null;
                try { fr = Tools.call("chat_fast", new JSONObject().put("q", heard).put("context", ctxBuf.toString())); } catch (Exception ignore) {}
                JSONObject fd = (fr != null && fr.optBoolean("ok")) ? fr.optJSONObject("data") : null;
                if (fd != null && "chat".equals(fd.optString("type"))) {
                    // 闲聊快答：全程 :kws；口语化统一走 voiceFriendly（尊重"口语化改写"开关+格式清洗+长文改写）
                    String ans = Tools.voiceFriendly(fd.optString("answer", ""));
                    setGlow("speak");
                    speakMarked(ans.length() > 400 ? ans.substring(0, 400) : ans);
                    waitSpeakMs(90000);
                    appendCtx("用户:" + heard + "\n小丘:" + ans);
                    setGlow("listen");
                    continue; // 本轮闭环，续听
                }
                // ── 任务：确认语 :kws 说（快脑生成的 reply），执行交页面 ──
                String ack = (fd != null && !fd.optString("reply", "").isEmpty()) ? fd.optString("reply") : "好嘞，这就去办，办完告诉你";
                String optPrompt = (fd != null && !fd.optString("prompt", "").isEmpty()) ? fd.optString("prompt") : heard;
                appendCtx("用户:" + heard + "\n小丘:" + ack);
                setGlow("speak");
                speakMarked(ack); // 确认语开说
                setGlow("exec");
                Log.i("PiBridge", "🔔 任务交脑(已预分类): " + optPrompt);
                turnDone = false; pendingSpeak = null; progCount = 0; lastProgSpeak = System.currentTimeMillis() + 8000; // 起步 8s 内不抢确认语
                android.content.Intent ti = new android.content.Intent("com.pihost.VOICE_TURN");
                ti.putExtra("text", heard).putExtra("from", from)
                  .putExtra("pre", true).putExtra("prompt", optPrompt);
                sendBroadcast(ti); // 交脑与确认语【并行】——执行不等播完（提速 2-3s）
                // 握手自愈：4 秒无页面回执 = 界面被回收 → 拉起 App 重发（任务不丢）
                turnAck = false;
                long ackT0 = System.currentTimeMillis();
                while (!turnAck && System.currentTimeMillis() - ackT0 < 4000) Thread.sleep(100);
                if (!turnAck && running && !sessionStop) {
                    Log.i("PiBridge", "页面无回执，拉起 App 自愈");
                    speakMarked("界面没开，我打开小丘来办");
                    waitSpeakMs(8000);
                    try {
                        Intent ai = new Intent(this, MainActivity.class);
                        ai.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(ai);
                    } catch (Exception ignore) {}
                    Thread.sleep(3000); // 等页面就绪
                    sendBroadcast(ti); // 重发（页面活了会回执+执行）
                }
                                long t0 = System.currentTimeMillis();
                while (!turnDone && !sessionStop && running && System.currentTimeMillis() - t0 < 150000) {
                    if (sessionStop || !running) break;
                    String[] sp = pendingSpeak;
                    if (sp != null) { pendingSpeak = null; speakTurn(sp[0], sp[1], sp.length > 2 && "1".equals(sp[2])); }
                    Thread.sleep(60);
                }
                if (sessionStop || !running) break;
                if (!turnDone) {
                    speakMarked("这单有点久，进应用里看进度吧");
                    break;
                }
            }
        } catch (Exception e) {
            Log.w("PiBridge", "sessionLoop: " + e);
        } finally {
            sessionStop = false; sessionActive = false; sessionThread = null;
            sendBroadcast(new android.content.Intent("com.pihost.SESSION_END").putExtra("reason", "bye"));
            sendBroadcast(new android.content.Intent("com.pihost.WAKE_GLOW_OFF"));
        }
    }

    /** 引理发来的播报：快缓存秒播/本地TTS顶上，完成后回执 TTS_STATE(off)+token → 引擎解锁。
     *  播报期间并发监听 RMS=打断（停播即解锁，本轮引擎流程自然收尾）。 */
    private void speakTurn(String text, String token, boolean humanize) {
        if (humanize && text.length() > 90) {
            // 结论播报=口语摘要（一两句），不是整文改写（voiceFriendly 会保留全文→听感=念原文）
            try {
                JSONObject hz = Tools.call("ai_humanize", new JSONObject().put("kind", "reply").put("text", text));
                if (hz != null && hz.optBoolean("ok")) {
                    JSONObject d = hz.optJSONObject("data");
                    if (d != null) {
                        String h = d.optString("data", d.optString("say", d.optString("digest", "")));
                        if (!h.isEmpty() && !h.startsWith("ERR")) {
                            Log.i("PiBridge", "🗣 结论摘要: " + h.length() + "字 ← 原文" + text.length() + "字");
                            text = h;
                        }
                    }
                }
            } catch (Exception ignore) {}
            if (text.length() > 400) { String h2 = Tools.voiceFriendly(text); if (h2 != null && !h2.isEmpty()) text = h2; } // 摘要失败且超长→整文改写兜底
        }
        markSpoken(text); // 回声免疫登记（登记最终播报稿）
        String eng = Tools.loadCfg().optString("tts_engine", "auto");
        if (!"xiaomi".equals(eng) && text.length() > 200) {
            // 长文云引擎：句级流式（首句 3-8s 即响，不等整文 40-60s）
            final String tk3 = token; final String tx3 = text; final String eng3 = eng;
            new Thread(() -> {
                Tools.speakCloudStream(tx3, eng3);
                sendBroadcast(new android.content.Intent("com.pihost.TTS_STATE").putExtra("on", false).putExtra("token", tk3));
            }, "stream-tts").start();
            return;
        }
        try {
            File f = fastFileOf(text);
            if (f != null && !f.isFile() && !"xiaomi".equals(eng)) {
                byte[] w = Tools.synthCloud(text);
                if (w != null) {
                    f.getParentFile().mkdirs();
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    fo.write(w); fo.close();
                }
            }
            if (f != null && f.isFile()) { playFastFile(f, token); return; }
        } catch (Exception ignore) {}
        Tools.speakLocal(text); // 兜底：本地（xiaomi 引擎或云失败）
        waitLocalSpeak(60000);
        sendBroadcast(new android.content.Intent("com.pihost.TTS_STATE").putExtra("on", false).putExtra("token", token));
    }
    private File fastFileOf(String p) {
        try { java.io.File d = getFilesDir(); return d == null ? null : new File(d, "wake-sounds/" + (p.hashCode() & 0x7fffffff) + ".wav"); } catch (Exception e) { return null; }
    }
    private void playFastFile(File f, String token) {
        try {
            android.media.MediaPlayer mp = android.media.MediaPlayer.create(this, android.net.Uri.fromFile(f));
            if (mp == null) { sendBroadcast(new android.content.Intent("com.pihost.TTS_STATE").putExtra("on", false).putExtra("token", token)); return; }
            final String tk = token;
            Tools.ttsSpeaking = true; // 播放期间占位：主监听让位（防回声）——上一版漏置=自唤醒根因之一
            mp.setOnCompletionListener(m -> { m.release(); Tools.ttsSpeaking = false; sendBroadcast(new android.content.Intent("com.pihost.TTS_STATE").putExtra("on", false).putExtra("token", tk)); });
            mp.start();
            long t0 = System.currentTimeMillis();
            while (mp.isPlaying() && System.currentTimeMillis() - t0 < 60000) Thread.sleep(80);
        } catch (Exception e) {
            Tools.ttsSpeaking = false;
            sendBroadcast(new android.content.Intent("com.pihost.TTS_STATE").putExtra("on", false).putExtra("token", token));
        }
    }
    /** 等本地 TTS 播完：先等"开始播"（首绑引擎可慢至秒级——直接等false会瞬间放行=无声跳过），再等播完 */
    private void waitLocalSpeak(long maxMs) {
        long t0 = System.currentTimeMillis();
        while (!Tools.ttsSpeaking && System.currentTimeMillis() - t0 < 2500) { // 起播窗 2.5s
            try { Thread.sleep(60); } catch (Exception ignore) {}
        }
        while (Tools.ttsSpeaking && System.currentTimeMillis() - t0 < maxMs) {
            try { Thread.sleep(80); } catch (Exception ignore) {}
        }
    }

    /** 声纹门禁（KWS 与 ASR 兜底两路共用）：已录入则校验触发音频是不是主人。阈值 cfg voiceprint_threshold 默认 0.60 */
    boolean voiceGate(short[] ring, int wpos, long total, int sr, int ringN, String src) {
        try {
            float[] master = Tools.vpLoad();
            if (master == null) return true; // 未录入=不设防
            int need = (int) Math.min(sr * 3 / 2, Math.max(0, total));
            if (need < sr / 2) return true;
            float[] seg = new float[need];
            for (int i = 0; i < need; i++) seg[i] = ring[(int)((wpos - need + i + ringN * 4L) % ringN)] / 32768.0f;
            float[] e = Tools.spkEmbedF(seg);
            float sim = Tools.cosine(e, master);
            double th = 0.60;
            try { th = Double.parseDouble(Tools.loadCfg().optString("voiceprint_threshold", "0.60")); } catch (Exception ignore) {}
            if (sim < th) {
                Log.i("PiBridge", "🛡 声纹不匹配 (" + String.format("%.3f", sim) + " < " + th + ") [" + src + "]，忽略");
                return false;
            }
            Log.i("PiBridge", "✅ 声纹通过 (" + String.format("%.3f", sim) + ") [" + src + "]");
            return true;
        } catch (Throwable t) {
            Log.w("PiBridge", "声纹门禁异常(放行): " + t);
            return true;
        }
    }

    /** 固定语播报+回声登记 */
    private void speakMarked(String t) { markSpoken(t); Tools.speakFast(t); }
    /** 短进度播报：引擎感知（cloud/xiaomi），无回执——fire and forget */
    private void speakPSay(String text) {
        try {
            markSpoken(text);
            String eng2 = Tools.loadCfg().optString("tts_engine", "auto");
            if (!"xiaomi".equals(eng2) && text.length() > 200) { Tools.speakCloudStream(text, eng2); return; }
            File f = fastFileOf(text);
            if (f != null && !f.isFile() && !"xiaomi".equals(Tools.loadCfg().optString("tts_engine", "auto"))) {
                byte[] w = Tools.synthCloud(text);
                if (w != null) {
                    f.getParentFile().mkdirs();
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    fo.write(w); fo.close();
                }
            }
            if (f != null && f.isFile()) {
                android.media.MediaPlayer mp = android.media.MediaPlayer.create(this, android.net.Uri.fromFile(f));
                if (mp != null) {
                    Tools.ttsSpeaking = true;
                    mp.setOnCompletionListener(m -> { m.release(); Tools.ttsSpeaking = false; });
                    mp.start();
                    long t0 = System.currentTimeMillis();
                    while (mp.isPlaying() && System.currentTimeMillis() - t0 < 30000) Thread.sleep(80);
                    return;
                }
            }
            Tools.speakLocal(text);
            waitLocalSpeak(30000);
        } catch (Exception ignore) {}
    }

    /** 转写：会话内云优先（快+准），失败落本地 */
    private String transcribe(File wav) {
        try {
            String cs = Tools.cloudStt(wav);
            if (cs != null && !cs.isEmpty()) {
                Log.d("PiBridge", "会话转写(云): " + cs);
                return cs.replaceAll("<\\|[^>]*\\|>", "").replace(" ", "").trim();
            }
        } catch (Exception ignore) {}
        try {
            JSONObject env = Tools.call("stt_transcribe", new JSONObject().put("file", wav.getAbsolutePath()));
            if (env != null && env.optBoolean("ok")) {
                return env.optString("data", "").replaceAll("<\\|[^>]*\\|>", "").replace(" ", "").trim();
            }
        } catch (Exception ignore) {}
        return "";
    }

    @Override public void onDestroy() {
        running = false;
        releaseMic();                 // 立刻关闭麦克风（不等录音块读完）
        writeState(this, false);
        super.onDestroy();
    }
    private void releaseMic() {
        try { if (ar != null) { ar.stop(); ar.release(); ar = null; } } catch (Exception ignore) {}
    }
}
