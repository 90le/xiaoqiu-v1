package com.binbin.pibridge;

import android.service.notification.StatusBarNotification;
import android.app.RemoteInput;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import android.util.Log;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.MediaPlayer;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.KeyEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 工具注册表：每个工具 = 名称 + 描述 + schema + 处理器。结果信封照 MT 管理器风格 {ok,data,error} */
public class Tools {
    public interface H { JSONObject run(JSONObject a) throws Exception; }

    public static class Tool {
        public String desc; public JSONObject schema; public H h;
        Tool(String d, JSONObject s, H hh) { desc = d; schema = s; h = hh; }
    }

    public static final Map<String, Tool> REG = new LinkedHashMap<>();
    public static Context ctx;
    private static TextToSpeech tts;
    private static volatile TextToSpeech miTts;   // 小米大脑引擎（小爱音色）
    private static volatile boolean miReady = false;
    private static volatile boolean ttsReady = false;
    private static int notifId = 100;

    /** 本地兜底朗读：小米优先，系统其次（主线程投递）。注：本机系统默认引擎=mibrain，音色相同属正常 */
    static void speakLocal(final String text) {
        new Handler(Looper.getMainLooper()).post(new Runnable() { public void run() {
            if (miReady) {
                miTts.setPitch(1.1f); miTts.setSpeechRate(1.05f);
                ttsSpeaking = true;
                miTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pi");
            } else if (ttsInit()) {
                tts.setPitch(1.12f); tts.setSpeechRate(1.05f);
                ttsSpeaking = true;
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pi");
            }
        }});
    }

    private static MediaPlayer cloudPlayer;
    /** 立即停播（用户打断） */
    public static void stopTts() {
        new Handler(Looper.getMainLooper()).post(new Runnable() { public void run() {
            try { if (miReady) miTts.stop(); } catch (Exception ignore) {}
            try { if (tts != null) tts.stop(); } catch (Exception ignore) {}
            try { if (cloudPlayer != null) { cloudPlayer.stop(); cloudPlayer.release(); cloudPlayer = null; } } catch (Exception ignore) {}
            ttsSpeaking = false;
        }});
    }
    /** 朗读完成回调（连续对话推进用），主线程投递 */
    public static volatile Runnable onSpeakDone;
    static void fireSpeakDone() {
        ttsSpeaking = false;
        Runnable cb = onSpeakDone;
        if (cb != null) new Handler(Looper.getMainLooper()).post(cb);
    }
    /** 云端语音识别 GLM-ASR：失败返回 null */
    static String cloudStt(File wav) {
        try {
            String key = fastKey();
            if (key == null) return null;
            String B = "----qiu" + System.currentTimeMillis();
            javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection)
                    new java.net.URL("https://open.bigmodel.cn/api/paas/v4/audio/transcriptions").openConnection();
            c.setRequestMethod("POST"); c.setConnectTimeout(5000); c.setReadTimeout(30000); c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + B);
            java.io.OutputStream os = c.getOutputStream();
            os.write(("--" + B + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nglm-asr\r\n").getBytes("UTF-8"));
            os.write(("--" + B + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes("UTF-8"));
            java.io.FileInputStream fi = new java.io.FileInputStream(wav);
            byte[] buf = new byte[8192]; int n; while ((n = fi.read(buf)) > 0) os.write(buf, 0, n);
            fi.close();
            os.write(("\r\n--" + B + "--\r\n").getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            java.io.InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b2 = new byte[8192]; int n2; while (is != null && (n2 = is.read(b2)) > 0) bo.write(b2, 0, n2);
            if (is != null) is.close();
            if (code != 200) { Log.w("PiBridge", "云ASR HTTP " + code + ": " + bo.toString("UTF-8")); return null; }
            return new JSONObject(bo.toString("UTF-8")).optString("text", "");
        } catch (Exception e) { Log.w("PiBridge", "云ASR 失败: " + e); return null; }
    }
    // ═══ 声纹（唤醒主人校验：sherpa speaker embedding）═══
    private static com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor spkX;
    static boolean initVoiceprint() {
        if (spkX != null) return true;
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("kws", 0);
            if (sp.getInt("spkCrashes", 0) >= 2) return false;
            if (sp.getBoolean("spkIniting", false)) {
                sp.edit().putInt("spkCrashes", sp.getInt("spkCrashes", 0) + 1).putBoolean("spkIniting", false).apply();
                if (sp.getInt("spkCrashes", 0) >= 2) { Log.w("PiBridge", "声纹模型二次崩，熔断"); return false; }
            }
            sp.edit().putBoolean("spkIniting", true).apply();
            com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig cfg =
                    new com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig("sherpa-spk/voiceprint.onnx", 1, false, "cpu");
            spkX = new com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor(ctx.getAssets(), cfg);
            sp.edit().putBoolean("spkIniting", false).putInt("spkCrashes", 0).apply(); // 成功=根因已修，清熔断计数
            Log.i("PiBridge", "✅ 声纹模型就绪 dim=" + spkX.dim());
            return true;
        } catch (Throwable t) {
            Log.w("PiBridge", "声纹 init: " + t);
            try { ctx.getSharedPreferences("kws", 0).edit().putBoolean("spkIniting", false).apply(); } catch (Exception ignore) {}
            return false;
        }
    }
    /** wav 文件 → 说话人向量（真采样率；未就绪补 0.8s 静音；零范数=null） */
    static float[] spkEmbed(File wav) {
        if (!initVoiceprint()) return null;
        try {
            int[] rate = {16000};
            float[] s = com.binbin.pibridge.WavUtil.readWavF(wav, rate);
            return spkEmbedSamples(s, rate[0]);
        } catch (Throwable t) { Log.w("PiBridge", "spkEmbed: " + t); return null; }
    }
    static float[] spkEmbedSamples(float[] s, int rate) {
        if (s == null || s.length < rate / 2) return null;
        com.k2fsa.sherpa.onnx.OnlineStream st = spkX.createStream();
        st.acceptWaveform(s, rate);
        if (!spkX.isReady(st)) { // 帧不够 → 补 0.8s 静音
            float[] s2 = new float[s.length + rate * 4 / 5];
            System.arraycopy(s, 0, s2, 0, s.length);
            st = spkX.createStream();
            st.acceptWaveform(s2, rate);
        }
        if (!spkX.isReady(st)) { Log.w("PiBridge", "声纹: 帧仍不足"); return null; }
        float[] e = spkX.compute(st);
        if (e == null || e.length == 0) return null;
        double norm = 0; for (float v : e) norm += (double) v * v;
        norm = Math.sqrt(norm);
        if (norm < 1e-3) { Log.w("PiBridge", "声纹: 零向量（" + e.length + "维）"); return null; } // 全零=未就绪产物，拒收
        return e;
    }
    /** 采样直入 → 向量（唤醒门禁用；真采样率+就绪+零范数防护） */
    static float[] spkEmbedF(float[] s) {
        if (!initVoiceprint() || s == null || s.length < 8000) return null;
        try { return spkEmbedSamples(s, 16000); } catch (Throwable t) { return null; }
    }
    static java.io.File vpFile() { return new java.io.File(ctx.getFilesDir(), "voiceprint.bin"); }
    static void vpSave(float[][] embs) {
        try {
            int d = embs[0].length;
            float[] mean = new float[d];
            for (float[] e : embs) for (int i = 0; i < d; i++) mean[i] += e[i] / embs.length;
            java.io.FileOutputStream fo = new java.io.FileOutputStream(vpFile());
            java.io.DataOutputStream ds = new java.io.DataOutputStream(fo);
            ds.writeInt(d);
            for (float v : mean) ds.writeFloat(v);
            ds.close();
        } catch (Exception ignore) {}
    }
    static float[] vpLoad() {
        try {
            if (!vpFile().isFile()) return null;
            java.io.DataInputStream ds = new java.io.DataInputStream(new java.io.FileInputStream(vpFile()));
            int d = ds.readInt();
            float[] m = new float[d];
            for (int i = 0; i < d; i++) m[i] = ds.readFloat();
            ds.close();
            return m;
        } catch (Exception e) { return null; }
    }
    static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1;
        float dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-8));
    }

    // ═══ 全局唤醒词（sherpa KWS）═══
    public static volatile boolean micBusy = false; // 按住说话等场景让出麦克风
    public static volatile boolean ttsSpeaking = false; // TTS 播报中（唤醒监听让位）
    private static KeywordSpotter kws;
    private static OnlineStream kwsStream;

    static boolean initKwsOnce(File dir) {
        // 2026-09-09 重试：当年崩溃根因=给 assets 构造器传文件系统绝对路径（本 aar 只有 AssetManager 版构造器）
        // 现模型已内置 APK assets/sherpa-kws/，走正确的 asset 相对路径。
        // 熔断器：native 崩=进程死 → 下次启动检测"initing 未清"计数+1，≥2 次永久回退 ASR 唤醒。
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("kws", 0);
            if (sp.getInt("crashes", 0) >= 2) { Log.w("PiBridge", "KWS 熔断（曾崩" + sp.getInt("crashes", 0) + "次），ASR 模式"); return false; }
            if (sp.getBoolean("initing", false)) {
                sp.edit().putInt("crashes", sp.getInt("crashes", 0) + 1).putBoolean("initing", false).apply();
                if (sp.getInt("crashes", 0) >= 2) { Log.w("PiBridge", "KWS 第二次崩溃，永久熔断"); return false; }
            }
            sp.edit().putBoolean("initing", true).apply();
            OnlineTransducerModelConfig tr = new OnlineTransducerModelConfig(
                    "sherpa-kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    "sherpa-kws/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    "sherpa-kws/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    new com.k2fsa.sherpa.onnx.QnnConfig());
            OnlineModelConfig mc = new OnlineModelConfig();
            mc.setTransducer(tr);
            mc.setTokens("sherpa-kws/tokens.txt");
            mc.setNumThreads(2);
            KeywordSpotterConfig cfg = new KeywordSpotterConfig(new FeatureConfig(), mc, 4,
                    "sherpa-kws/keywords.txt", 2.0f, 0.25f, 1);
            Log.i("PiBridge", "KWS init: 构造中（assets 路径）…");
            kws = new KeywordSpotter(ctx.getAssets(), cfg);
            sp.edit().putBoolean("initing", false).apply();
            Log.i("PiBridge", "✅ KWS 构造成功——流式唤醒上线");
            return true;
        } catch (Throwable e) {
            Log.w("PiBridge", "KWS 初始化失败: " + e);
            try { ctx.getSharedPreferences("kws", 0).edit().putBoolean("initing", false).apply(); } catch (Exception ignore) {}
            return false;
        }
    }
    static OnlineStream kwsCreateStream() { try { return kws.createStream(null); } catch (Exception e) { return null; } }
    static void kwsSetStream(OnlineStream s) { kwsStream = s; }
    /** 流式喂音（外部持有 stream），命中返回词条 */
    static String kwsFeedStream(OnlineStream s, float[] samples, int n) {
        try {
            s.acceptWaveform(samples, 0);
            while (kws.isReady(s)) kws.decode(s);
            KeywordSpotterResult r = kws.getResult(s);
            String kw = r == null ? "" : r.getKeyword();
            if (kw != null && !kw.isEmpty()) { kws.reset(s); return kw; }
        } catch (Throwable e) { Log.w("PiBridge", "kwsFeedStream: " + e); }
        return "";
    }
    static void kwsReset(OnlineStream s) { try { kws.reset(s); } catch (Throwable ignore) {} }
    static void kwsClearStream() { kwsStream = null; }
    /** 喂音频，命中唤醒词返回词条，否则空串 */
    static String kwsFeed(float[] samples) {
        try {
            if (kws == null || kwsStream == null) return "";
            kwsStream.acceptWaveform(samples, 0);
            while (kws.isReady(kwsStream)) kws.decode(kwsStream);
            KeywordSpotterResult r = kws.getResult(kwsStream);
            String kw = r == null ? "" : r.getKeyword();
            if (kw != null && !kw.isEmpty()) {
                kws.reset(kwsStream);
                return kw;
            }
        } catch (Exception e) { Log.w("PiBridge", "kwsFeed: " + e); }
        return "";
    }

    /** 通用对话补全（glm-5.3-flash），失败返回 null */
    /** 快脑模型配置解析：{model, thinking, url, key}——默认 glm-5.3-flash@coding；自定义模型走 models.json 路由 */
    static JSONObject fastModelCfg() {
        JSONObject r = new JSONObject();
        try {
            String model = loadCfg().optString("fast_model", "");
            boolean think = "true".equals(loadCfg().optString("fast_thinking", "false"));
            think = "true".equals(loadCfg().optString("fast_thinking", "false")); // 兼容旧开关
            r.put("thinking", think);
            r.put("think_level", loadCfg().optString("fast_thinking_level", "off")); // 7档（对齐模型大脑）
            r.put("max_tokens", Math.max(512, loadCfg().optInt("fast_max_tokens", 32768))); // 用户：输出上限默认32k（模型都是1M上下文，别卡小）
            if (model.isEmpty()) { r.put("model", "glm-5.3").put("url", "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions").put("key", fastKey()); return r; }
            // 自定义模型路由（models.json providers）
            try {
                File mf = new File(ctx.getFilesDir(), "home/.pi/agent/models.json");
                JSONObject models = new JSONObject(new String(java.nio.file.Files.readAllBytes(mf.toPath()), "UTF-8"));
                JSONObject provs = models.optJSONObject("providers");
                if (provs != null) {
                    java.util.Iterator<String> it = provs.keys();
                    while (it.hasNext()) {
                        String pid = it.next();
                        JSONObject p = provs.optJSONObject(pid);
                        JSONArray ms = p != null ? p.optJSONArray("models") : null;
                        if (ms == null) continue;
                        for (int i = 0; i < ms.length(); i++) {
                            if (model.equals(ms.optJSONObject(i).optString("id"))) {
                                String key = p.optString("apiKey", "");
                                if (key.isEmpty()) { // 从 auth.json 取
                                    File af = new File(ctx.getFilesDir(), "home/.pi/agent/auth.json");
                                    JSONObject auth = new JSONObject(new String(java.nio.file.Files.readAllBytes(af.toPath()), "UTF-8"));
                                    key = auth.optJSONObject(pid) != null ? auth.optJSONObject(pid).optString("key", "") : "";
                                }
                                String base = p.optString("baseUrl", "https://open.bigmodel.cn/api/paas/v4");
                                if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                                return r.put("model", model).put("url", base + "/chat/completions").put("key", key);
                            }
                        }
                    }
                }
            } catch (Exception ignore) {}
            // 内置家族：zhipu 开放平台端点
            String key = fastKey();
            try {
                File af = new File(ctx.getFilesDir(), "home/.pi/agent/auth.json");
                JSONObject auth = new JSONObject(new String(java.nio.file.Files.readAllBytes(af.toPath()), "UTF-8"));
                if (auth.optJSONObject("zhipu") != null && !auth.optJSONObject("zhipu").optString("key", "").isEmpty())
                    key = auth.optJSONObject("zhipu").optString("key");
            } catch (Exception ignore) {}
            return r.put("model", model).put("url", "https://open.bigmodel.cn/api/paas/v4/chat/completions").put("key", key);
        } catch (Exception e) {
            try { return r.put("model", "glm-5.3-flash").put("url", "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions").put("key", fastKey()); } catch (Exception ignore) { return r; }
        }
    }

    /** 思考参数构造（完全照 pi thinkingLevelMap 语义）：
     *  off → 返回 null=整个 thinking 字段不发（模型默认——glm-5.3 系思考关不掉，pi 同款 off:null 映射）
     *  low/high/max → enabled+level 透传；极简/中/极高（map=null 档）→ enabled 不带 level 回落模型默认 */
    static JSONObject thinkBody(JSONObject mc) throws Exception {
        String lv = mc.optString("think_level", "");
        if (lv.isEmpty() || "off".equals(lv)) return null;
        JSONObject t = new JSONObject().put("type", "enabled");
        if ("low".equals(lv) || "high".equals(lv) || "max".equals(lv)) t.put("level", lv);
        return t;
    }

    static String llmRaw(String system, String user) {
        try {
            JSONObject mc = fastModelCfg();
            String key = mc.optString("key", "");
            if (key == null || key.isEmpty()) return null;
            JSONObject body = new JSONObject()
                    .put("model", mc.optString("model", "glm-5.3-flash"))
                    .put("messages", new org.json.JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", system))
                            .put(new JSONObject().put("role", "user").put("content", user)))
                    .put("max_tokens", mc.optInt("max_tokens", 32768)).put("temperature", 0.4)
                    .put("thinking", thinkBody(mc));
            javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection)
                    new java.net.URL(mc.optString("url", "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions")).openConnection();
            c.setRequestMethod("POST"); c.setConnectTimeout(5000); c.setReadTimeout(60000); c.setDoOutput(true); // 思考型模型要长超时
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "application/json");
            java.io.OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8")); os.close();
            int code = c.getResponseCode();
            java.io.InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n; while (is != null && (n = is.read(b)) > 0) bo.write(b, 0, n);
            if (is != null) is.close();
            if (code >= 400) return null;
            org.json.JSONObject msg = new JSONObject(bo.toString("UTF-8")).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message");
            String content = msg.optString("content", "").trim();
            if (content.isEmpty()) { // 思考型模型（coding端点 glm-5.3 永远思考）：content 空 → reasoning_content 尾部提取最终产出
                String rc = msg.optString("reasoning_content", "").trim();
                if (!rc.isEmpty()) {
                    content = rc.length() > 600 ? rc.substring(rc.length() - 600) : rc;
                    Log.i("PiBridge", "llmRaw: content空，思考尾段兜底");
                }
            }
            return content;
        } catch (Exception e) { return null; }
    }

    /** 口语化改写：长文本/含格式 → 短口语朗读稿；短文本原样返回 */
    static String voiceFriendly(String text) {
        if ("false".equals(loadCfg().optString("voice_rewrite", "true"))) {
            return text.replaceAll("```[\\s\\S]*?```", "，代码部分从略，").replaceAll("[#*`>\\[\\]]", "").replaceAll("\\n+", "，").trim();
        }
        String clean = text.replaceAll("```[\\s\\S]*?```", "，代码部分从略，").replaceAll("[#*`>\\[\\]]", "").replaceAll("\\n+", "，").trim();
        boolean markdowny = text.contains("```") || text.contains("\n- ") || text.contains("\n#") || text.contains("**");
        if (!markdowny && clean.length() <= 200) return clean;
        String r = llmRaw("把内容改写成适合朗读的中文口语。要求：保留全部关键信息和数字，不遗漏要点；去掉所有格式符号、emoji和列表标记；句子通顺自然；篇幅以不遗漏信息为准，尽量精炼。只输出改写结果。",
                clean.length() > 1000 ? clean.substring(0, 1000) : clean);
        return (r == null || r.isEmpty()) ? clean : r;
    }

    /** 云 TTS 异步版：任意线程可调（内部子线程联网），失败自动回退本地；连接超时放宽+重试1次 */
    static void cloudSpeakAsync(final String text) {
        new Thread(() -> {
            boolean ok = false;
            for (int attempt = 0; attempt < 2 && !ok; attempt++) {
                if (attempt > 0) { try { Thread.sleep(1500); } catch (Exception ignore) {} }
                ok = cloudSpeak(text);
            }
            if (!ok) {
                Log.w("PiBridge", "云TTS两次失败，回退本地");
                speakLocal(text);
            }
        }, "cloud-tts").start();
    }

    /** 云 TTS 合成（不播放）：返回去提示音的 wav 字节，失败 null。非主线程 */
    static byte[] synthCloud(String text) {
        try {
            if (text != null && text.length() > 400) return synthCloudChunked(text);
            return synthCloudOne(text);
        } catch (Exception e) { Log.w("PiBridge", "synthCloud失败: " + e); return null; }
    }
    static byte[] synthCloudOne(String text) throws Exception {
        String key = fastKey();
        if (key == null) return null;
            javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection)
                    new java.net.URL("https://open.bigmodel.cn/api/paas/v4/audio/speech").openConnection();
            c.setRequestMethod("POST"); c.setConnectTimeout(8000); c.setReadTimeout(60000); c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "application/json");
            String voice = loadCfg().optString("tts_voice", "tongtong");
            JSONObject body = new JSONObject().put("model", "glm-tts").put("input", text)
                    .put("voice", voice).put("response_format", "wav");
            java.io.OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8")); os.close();
            if (c.getResponseCode() != 200) return null;
            java.io.ByteArrayOutputStream ab = new java.io.ByteArrayOutputStream();
            java.io.InputStream is = c.getInputStream();
            byte[] b = new byte[8192]; int n; while ((n = is.read(b)) > 0) ab.write(b, 0, n);
            is.close();
            return trimLeadingBeep(ab.toByteArray());
        
    }
    /** 长文分段合成：句界切≤400字/段，3路并行，PCM 拼接单 wav（API 1024字符硬上限+300字38s 慢的对策） */
    static byte[] synthCloudChunked(String text) {
        try {
            java.util.List<String> parts = new java.util.ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (String sent : text.split("(?<=[。！？；.!?;\n])")) {
                if (cur.length() + sent.length() > 400 && cur.length() > 0) { parts.add(cur.toString()); cur.setLength(0); }
                if (sent.length() > 400) { for (int i2 = 0; i2 < sent.length(); i2 += 400) parts.add(sent.substring(i2, Math.min(i2 + 400, sent.length()))); }
                else cur.append(sent);
            }
            if (cur.length() > 0) parts.add(cur.toString());
            while (parts.size() > 6) parts.remove(parts.size() - 1); // 上限~2400字
            Log.i("PiBridge", "☁ 长文分段合成: " + parts.size() + " 段");
            final java.util.List<byte[]> wavs = new java.util.ArrayList<>(java.util.Collections.nCopies(parts.size(), (byte[]) null));
            java.util.List<Thread> ts = new java.util.ArrayList<>();
            for (int i2 = 0; i2 < parts.size(); i2++) {
                final int idx = i2; final String p = parts.get(i2);
                Thread t = new Thread(() -> { try { wavs.set(idx, synthCloudOne(p)); } catch (Exception ignore) { wavs.set(idx, null); } });
                t.start(); ts.add(t);
                int alive; do { alive = 0; for (Thread x : ts) if (x.isAlive()) alive++; if (alive >= 3) { try { Thread.sleep(80); } catch (Exception ignore) {} } } while (alive >= 3);
            }
            for (Thread t : ts) t.join(90000);
            return wavConcat(wavs);
        } catch (Exception e) { Log.w("PiBridge", "分段合成失败: " + e); return null; }
    }
    /** wav 列表 → PCM 拼接单 wav */
    static byte[] wavConcat(java.util.List<byte[]> wavs) {
        try {
            int rate = 16000; java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
            for (byte[] w : wavs) {
                if (w == null || w.length < 44) continue;
                int pos = 12, dataPos = -1, dataLen = 0;
                while (pos + 8 <= w.length) {
                    String id = new String(w, pos, 4, "ASCII");
                    int sz = (w[pos+4]&255) | (w[pos+5]&255)<<8 | (w[pos+6]&255)<<16 | (w[pos+7]&255)<<24;
                    if (id.equals("fmt ")) rate = (w[pos+12]&255) | (w[pos+13]&255)<<8;
                    if (id.equals("data")) { dataPos = pos + 8; dataLen = Math.min(sz, w.length - dataPos); break; }
                    pos += 8 + sz + (sz % 2);
                }
                if (dataPos > 0) pcm.write(w, dataPos, dataLen);
            }
            if (pcm.size() == 0) return null;
            byte[] d = pcm.toByteArray();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write("RIFF".getBytes("ASCII")); out.write(intLE(d.length + 36)); out.write("WAVE".getBytes("ASCII"));
            out.write("fmt ".getBytes("ASCII")); out.write(intLE(16)); out.write(shortLE(1)); out.write(shortLE(1));
            out.write(intLE(rate)); out.write(intLE(rate * 2)); out.write(shortLE(2)); out.write(shortLE(16));
            out.write("data".getBytes("ASCII")); out.write(intLE(d.length)); out.write(d);
            return out.toByteArray();
        } catch (Exception e) { return null; }
    }
    static byte[] intLE(int v) { return new byte[]{(byte)v, (byte)(v>>8), (byte)(v>>16), (byte)(v>>24)}; }
    static byte[] shortLE(int v) { return new byte[]{(byte)v, (byte)(v>>8)}; }

    // ═══ 流式句级合成（边合成边播：首句 3-8s 即响，不等整文 40-60s）═══
    /** 句级流式播报：按句切分→3线程预合成→按序逐句播放。阻塞至播完。失败已播部分后落本地续播剩余。 */
    public static void speakCloudStream(String text, String engine) {
        java.util.List<String> sents = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String s : text.split("(?<=[。！？；.!?;\n])")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            if (cur.length() + t.length() > 120 && cur.length() > 0) { sents.add(cur.toString()); cur.setLength(0); }
            if (t.length() > 120) { for (int i = 0; i < t.length(); i += 120) sents.add(t.substring(i, Math.min(i + 120, t.length()))); }
            else cur.append(t);
        }
        if (cur.length() > 0) sents.add(cur.toString());
        while (sents.size() > 40) sents.remove(sents.size() - 1);
        Log.i("PiBridge", "🌊 句级流式: " + sents.size() + " 句（首句即播）");
        ttsSpeaking = true;
        try {
            final java.util.List<java.io.File> wavs = new java.util.ArrayList<>(java.util.Collections.nCopies(sents.size(), (java.io.File) null));
            final java.util.List<Boolean> done = new java.util.ArrayList<>(java.util.Collections.nCopies(sents.size(), false));
            java.util.List<Thread> ts = new java.util.ArrayList<>();
            for (int i = 0; i < sents.size(); i++) {
                final int idx = i; final String p = sents.get(i);
                Thread t = new Thread(() -> {
                    try {
                        byte[] w = synthCloudOne(p);
                        if (w != null) {
                            java.io.File f = java.io.File.createTempFile("sent" + idx, ".wav", ctx.getCacheDir());
                            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                            fo.write(w); fo.close();
                            wavs.set(idx, f);
                        }
                    } catch (Exception ignore) {} finally { done.set(idx, true); }
                }, "sent-" + idx);
                t.start(); ts.add(t);
                int alive; do { alive = 0; for (Thread x : ts) if (x.isAlive()) alive++; if (alive >= 3) { try { Thread.sleep(60); } catch (Exception ignore) {} } } while (alive >= 3);
            }
            // 按序播放：首句就绪即播，不等全部
            for (int i = 0; i < sents.size(); i++) {
                long t0 = System.currentTimeMillis();
                while (!done.get(i) && System.currentTimeMillis() - t0 < 90000) { try { Thread.sleep(80); } catch (Exception ignore) {} }
                java.io.File f = wavs.get(i);
                if (f == null || !f.isFile()) { // 该句云合成失败→本地续播这句
                    speakLocal(sents.get(i));
                    waitLocalSpeakingStatic(30000);
                    continue;
                }
                playWavBlocking(f);
                f.delete();
            }
            for (Thread t : ts) t.join(1000);
        } catch (Exception e) {
            Log.w("PiBridge", "流式合成异常: " + e);
        } finally {
            ttsSpeaking = false;
            fireSpeakDone();
        }
    }
    static void waitLocalSpeakingStatic(long maxMs) {
        long t0 = System.currentTimeMillis();
        while (ttsSpeaking && System.currentTimeMillis() - t0 < maxMs) { try { Thread.sleep(80); } catch (Exception ignore) {} }
    }
    static void playWavBlocking(java.io.File f) {
        try {
            MediaPlayer mp = MediaPlayer.create(ctx, android.net.Uri.fromFile(f));
            if (mp == null) return;
            final Object lock = new Object();
            mp.setOnCompletionListener(m -> { m.release(); synchronized (lock) { lock.notify(); } });
            mp.start();
            synchronized (lock) { try { lock.wait(120000); } catch (Exception ignore) {} }
        } catch (Exception ignore) {}
    }


    // ══ 唤醒回应词预生成：云端合成一次，本地秒播（用户设计：零合成延迟）═══
    public static final String[] FAST_PHRASES = {
            "在！", "我在！", "诶！", "嗯！",
            "好嘞，这就办", "好嘞", "嗯，我先退下", "我先歇着啦",
            "没听清，需要我做什么直接说", "小丘的连接断了，打开小丘再试一次"
    };
    static java.io.File fastFile(String p) {
        String v = "";
        try { v = loadCfg().optString("tts_voice", "tongtong"); } catch (Exception ignore) {}
        return new java.io.File(ctx.getFilesDir(), "wake-sounds/" + (p.hashCode() & 0x7fffffff) + "-" + v + ".wav");
    }
    /** 预生成缺失的回应音频（服务启动后台跑一次；换音色后可删目录重生成） */
    public static void pregenFastSounds() {
        new Thread(() -> {
            for (String p : FAST_PHRASES) {
                try {
                    java.io.File f = fastFile(p);
                    if (f.isFile()) continue;
                    byte[] w = synthCloud(p);
                    if (w == null) continue;
                    f.getParentFile().mkdirs();
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    fo.write(w); fo.close();
                    Log.i("PiBridge", "预生成回应: " + p + " " + w.length + "B");
                } catch (Exception ignore) {}
            }
        }, "pregen-tts").start();
    }
    /** 秒播预生成回应；无缓存按设置引擎：xiaomi=本地 / auto·cloud=云合成优先（用户配置的音色），失败落本地 */
    public static void speakFast(String phrase) {
        try {
            java.io.File f = fastFile(phrase);
            if (!f.isFile() && !"xiaomi".equals(loadCfg().optString("tts_engine", "auto"))) {
                byte[] w = synthCloud(phrase); // 同步云合成（cfg 音色）
                if (w != null) {
                    f.getParentFile().mkdirs();
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    fo.write(w); fo.close();
                }
            }
            if (f.isFile()) {
                ttsSpeaking = true;
                MediaPlayer mp = new MediaPlayer();
                mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
                mp.setDataSource(f.getAbsolutePath());
                mp.setOnCompletionListener(m -> { m.release(); ttsSpeaking = false; fireSpeakDone(); });
                mp.setOnErrorListener((m, what, extra) -> { m.release(); ttsSpeaking = false; fireSpeakDone(); return true; });
                mp.prepare(); mp.start();
                return;
            }
        } catch (Exception e) { Log.w("PiBridge", "speakFast: " + e); }
        speakLocal(phrase); // 兜底
        new Thread(() -> { // 补缓存
            byte[] w = synthCloud(phrase);
            if (w != null) try {
                java.io.File f = fastFile(phrase);
                f.getParentFile().mkdirs();
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                fo.write(w); fo.close();
            } catch (Exception ignore) {}
        }).start();
    }

    /** 云 TTS：智谱 GLM-TTS（童童音色）；失败返回 false 由调用方回退。注意：只能在非主线程调用 */
    static boolean cloudSpeak(String text) {
        try {
            String key = fastKey();
            if (key == null) return false;
            stopCloud();
            javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection)
                    new java.net.URL("https://open.bigmodel.cn/api/paas/v4/audio/speech").openConnection();
            c.setRequestMethod("POST"); c.setConnectTimeout(8000); c.setReadTimeout(25000); c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "application/json");
            String voice = loadCfg().optString("tts_voice", "tongtong");
            JSONObject body = new JSONObject().put("model", "glm-tts").put("input", text)
                    .put("voice", voice).put("response_format", "wav");
            java.io.OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8")); os.close();
            int code = c.getResponseCode();
            if (code != 200) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                java.io.InputStream es = c.getErrorStream();
                byte[] b = new byte[4096]; int n; while (es != null && (n = es.read(b)) > 0) bo.write(b, 0, n);
                Log.w("PiBridge", "云TTS HTTP " + code + ": " + bo.toString("UTF-8"));
                return false;
            }
            java.io.ByteArrayOutputStream ab = new java.io.ByteArrayOutputStream();
            java.io.InputStream is = c.getInputStream();
            byte[] b = new byte[8192]; int n; while ((n = is.read(b)) > 0) ab.write(b, 0, n);
            is.close();
            byte[] wav = trimLeadingBeep(ab.toByteArray());
            java.io.File out = new java.io.File(ctx.getCacheDir(), "cloud-tts.wav");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            fo.write(wav); fo.close();
            cloudPlayer = new MediaPlayer();
            cloudPlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
            cloudPlayer.setDataSource(out.getAbsolutePath());
            cloudPlayer.setOnCompletionListener(mp2 -> { mp2.release(); if (cloudPlayer == mp2) cloudPlayer = null; fireSpeakDone(); });
            ttsSpeaking = true; // 播放期间占用（唤醒监听让位，防自唤醒）
            cloudPlayer.prepare();
            cloudPlayer.start();
            Log.i("PiBridge", "云TTS 播放中 " + out.length() + " 字节");
            return true;
        } catch (Exception e) { Log.w("PiBridge", "云TTS 失败: " + e); return false; }
    }
    /** 裁掉 GLM-TTS 音频开头的提示嘟嘟声（恒定峰值纯音+静音段），定位到语音真实起点 */
    static byte[] trimLeadingBeep(byte[] wav) {
        try {
            int dataPos = -1, dataLen = 0;
            for (int i = 12; i + 8 <= wav.length; ) {
                String id = new String(wav, i, 4, "ASCII");
                int sz = (wav[i+4]&255) | (wav[i+5]&255)<<8 | (wav[i+6]&255)<<16 | (wav[i+7]&255)<<24;
                if (id.equals("data")) { dataPos = i + 8; dataLen = sz; break; }
                i += 8 + sz + (sz & 1);
            }
            if (dataPos < 0 || dataLen <= 0) return wav;
            int n = Math.min(dataLen, wav.length - dataPos) / 2;
            int win = 2400; // 0.1s @24kHz
            int windows = n / win;
            if (windows < 10) return wav;
            int[] peaks = new int[windows];
            for (int w = 0; w < windows; w++) {
                int peak = 0;
                for (int j = 0; j < win; j++) {
                    int idx = dataPos + (w*win + j)*2;
                    if (idx + 1 >= wav.length) break;
                    int s = (short)((wav[idx]&255) | (wav[idx+1]<<8));
                    if (Math.abs(s) > peak) peak = Math.abs(s);
                }
                peaks[w] = peak;
            }
            // 寻找语音真实起点：连续5窗有声 且 峰值自然波动(排除嘟嘟的恒定纯音)
            int w = 0, skip = 0;
            boolean hasBeep = peaks[0] > 300;
            if (hasBeep) {
                int start = -1;
                for (int i = 0; i + 5 < windows; i++) {
                    if (peaks[i] < 150) continue;
                    int mx = 0, mn = Integer.MAX_VALUE;
                    boolean allLoud = true;
                    for (int j = i; j < i + 5; j++) {
                        if (peaks[j] < 150) { allLoud = false; break; }
                        if (peaks[j] > mx) mx = peaks[j];
                        if (peaks[j] < mn) mn = peaks[j];
                    }
                    if (allLoud && (mx - mn) > mx * 0.25f) { start = i; break; }
                }
                if (start < 0) return wav;
                skip = start * win;
            }
            if (skip <= 0 || skip >= n) return wav;
            int newLen = (n - skip) * 2;
            byte[] outB = new byte[dataPos + newLen];
            System.arraycopy(wav, 0, outB, 0, dataPos);
            System.arraycopy(wav, dataPos + skip*2, outB, dataPos, newLen);
            // 修正长度字段：RIFF size(@4)、data size(@dataPos-4)
            patchInt(outB, 4, outB.length - 8);
            patchInt(outB, dataPos - 4, newLen);
            Log.i("PiBridge", "云TTS 已裁剪提示音 " + skip + " 样本");
            return outB;
        } catch (Exception e) { Log.w("PiBridge", "裁剪失败(用原音频): " + e); return wav; }
    }
    static void patchInt(byte[] b, int pos, int v) {
        b[pos] = (byte)(v & 255); b[pos+1] = (byte)((v>>8) & 255); b[pos+2] = (byte)((v>>16) & 255); b[pos+3] = (byte)((v>>24) & 255);
    }

    static void stopCloud() {
        try { if (cloudPlayer != null) { cloudPlayer.stop(); cloudPlayer.release(); cloudPlayer = null; } } catch (Exception ignore) {}
    }

    static volatile String preferredVoice = null;

    /** 自动挑选好听的中文发音人：讯飞/小米引擎优先，记入缓存 */
    static void applyPreferredVoice() {
        try {
            if (preferredVoice != null) {
                for (android.speech.tts.Voice v : tts.getVoices())
                    if (v.getName().equals(preferredVoice)) { tts.setVoice(v); return; }
                preferredVoice = null;
            }
            android.speech.tts.Voice best = null; int bestScore = -1;
            for (android.speech.tts.Voice v : tts.getVoices()) {
                String name = v.getName().toLowerCase();
                String loc = v.getLocale().toString().toLowerCase();
                if (!loc.startsWith("zh")) continue;
                int score = 0;
                if (name.contains("xiaomi")) score += 5;
                if (name.contains("iflytek") || name.contains("xunfei")) score += 4;
                if (name.contains("female") || name.contains("girl") || name.contains("xiaomei") || name.contains("wanwan")) score += 3;
                if (name.contains("network")) score += 1;
                if (score > bestScore) { bestScore = score; best = v; }
            }
            if (best != null) { preferredVoice = best.getName(); tts.setVoice(best);
                Log.i("PiBridge", "TTS 发音人选定: " + best.getName()); }
        } catch (Exception e) { Log.w("PiBridge", "选声失败: " + e); }
    }

    /** 读 pi 的 API Key（快脑与慢脑共用同一智谱账号） */
    /** 通知播报拟人化：快脑把通知改写成一句自然口语；失败返回null（调用方回退原文） */
    static String appCn(String app) {
        if ("com.tencent.mm".equals(app)) return "微信";
        if ("com.xingin.xhs".equals(app)) return "小红书";
        if ("com.sankuai.meituan".equals(app)) return "美团";
        if ("com.tencent.mobileqq".equals(app)) return "QQ";
        if ("com.eg.android.AlipayGphone".equals(app)) return "支付宝";
        if ("com.taobao.taobao".equals(app)) return "淘宝";
        if ("com.ss.android.ugc.aweme".equals(app)) return "抖音";
        return app == null ? "" : app;
    }

    /** 快脑短文本生成核心：thinking禁用（改写不需推理）+失败重试1次；失败返回ERR:串 */
    public static String llmShort(String sys, String userMsg, int maxTok) { return llmShort(sys, userMsg, maxTok, 120); }
    // llmShort 也走 fast_model 配置：经 llmRaw（已 cfg 感知），仅收敛输出上限
    /** maxOutLen：输出长度上限（120=唤醒短语级；摘要类用 400） */
    public static String llmShort(String sys, String userMsg, int maxTok, int maxOutLen) {
        try {
            JSONObject mc = fastModelCfg();
            String key = mc.optString("key", "");
            if (key == null || key.isEmpty()) return null;
            JSONObject body = new JSONObject()
                .put("model", mc.optString("model", "glm-5.3"))
                .put("messages", new org.json.JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", sys))
                    .put(new JSONObject().put("role", "user").put("content", userMsg)))
                .put("max_tokens", mc.optInt("max_tokens", 32768)).put("temperature", 0.4)
                .put("thinking", thinkBody(mc));
            String content = null;
            for (int attempt = 0; attempt < 2 && (content == null || content.isEmpty()); attempt++) {
                if (attempt > 0) {
                    try { Thread.sleep(1200); } catch (Exception ignore) {}
                    String bs = body.toString().replaceAll(",?\"thinking\":\\{\"type\":\"disabled\"\\}", "");
                    body = new JSONObject(bs); // 400时去掉thinking重试
                }
                javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection)
                    new java.net.URL(mc.optString("url", "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions")).openConnection();
                c.setRequestMethod("POST"); c.setConnectTimeout(5000); c.setReadTimeout(60000); c.setDoOutput(true);
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setRequestProperty("Content-Type", "application/json");
                java.io.OutputStream os = c.getOutputStream();
                os.write(body.toString().getBytes("UTF-8")); os.close();
                int code = c.getResponseCode();
                java.io.InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n; while (is != null && (n = is.read(buf)) > 0) bo.write(buf, 0, n);
                if (is != null) is.close();
                if (code >= 400) continue;
                org.json.JSONObject lmsg = new JSONObject(bo.toString("UTF-8")).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message");
                content = lmsg.optString("content", "").trim();
                if (content.isEmpty()) { // 思考型兜底（同 llmRaw）
                    String lrc = lmsg.optString("reasoning_content", "").trim();
                    if (!lrc.isEmpty()) content = lrc.length() > 800 ? lrc.substring(lrc.length() - 800) : lrc;
                }
                content = content.replace("\u300c", "").replace("\u300d", "").replace("\u3010", "").replace("\u3011", "")
                    .replace("\"", "").replace("'", "").trim();
            }
            if (content == null || content.isEmpty() || content.length() > maxOutLen) return "ERR:EMPTY_OR_LONG";
            return content;
        } catch (Throwable e) { return "ERR:" + e.getClass().getSimpleName() + ":" + e.getMessage(); }
    }

    /** 单条通知拟人化 */
    public static String aiHumanize(String app, String title, String text) {
        String appCn = appCn(app);
        String sys = "你是智能语音助手「小丘」的播报改写器。把一条通知改写成一句自然中文口语（10~35字），像朋友随口提醒，禁止复读机式照念标题。规则："
            + "1)验证码/订单号/金额/地址等数字信息必须一字不差保留；"
            + "2)个人聊天消息：说谁找你、大意是什么，如'妈妈问你晚上回不回家吃饭'；"
            + "3)广告推广：一句带过即可，如'美团发来一条推广'；"
            + "4)系统类通知：极简一句，如'微信更新包下好了'；"
            + "5)不要出现'通知/标题/正文'这些词，不要引号，不要emoji；"
            + "6)只输出要念的一句话，不要任何解释。";
        String userMsg = "来自App：" + appCn + "\n标题：" + (title == null ? "" : title)
            + "\n正文：" + (text == null ? "" : text.substring(0, Math.min(200, text.length())));
        return llmShort(sys, userMsg, 512);
    }

    /** 连发消息聚合播报：同一发送人的多条消息+历史语境 → 一句有连续感的转述；失败返回ERR:串 */
    public static String aiHumanizeBurst(String app, String sender, String prevCtx, org.json.JSONArray texts) {
        int n = Math.min(texts.length(), 12);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String s = texts.optString(i);
            sb.append(i + 1).append(") ").append(s, 0, Math.min(120, s.length())).append("\n");
        }
        String sys = "你是智能语音助手「小丘」。" + sender + " 给用户连发了几条消息，你的任务像帮朋友捎话：把他的话原汁原味带给用户，而不是总结他。"
            + "\n\n【捎话原则——最重要】"
            + "\n- 忠实保留原话的语气、情绪、用词习惯：他怎么说，你就怎么捎"
            + "\n- 语气词（嘻嘻/哈哈/～/啦）是性格，必须保留"
            + "\n- 多条消息顺着合并成连贯的一段话，但别把原话书面化、别提炼成干巴巴的结论"
            + "\n\n【示例】"
            + "\n妈妈连发：知道了啦/小宝/我本来过去找你吃饭。但是没时间～/嘻嘻"
            + "\n好：妈妈跟你说，知道啦小宝，她本来想过去找你吃饭，但没时间，嘻嘻～"
            + "\n坏：妈妈发来四条消息，表示知道了并说明无法赴约（这是总结，把人的味道丢了）"
            + "\n- 如果正文带'名字：'前缀，那是群聊：要提到群名和说话的人（如'项目群里张三喊你…'），几个人说话都点到"
            + "\n\n【格式】"
            + "\n- 开头点明是谁（妈妈说/老王喊你/项目群里张三说）"
            + "\n- 验证码/金额/地址/取件码等数字一字不差"
            + "\n- 有上一轮语境就自然衔接（可以'又''还是'），别重复旧内容"
            + "\n- 15~60字，一句说完，不要引号不要emoji，只输出要念的话";
        String userMsg = "App：" + appCn(app) + "\n发送人：" + sender + "\n"
            + (prevCtx == null || prevCtx.isEmpty() ? "" : "上一轮你播报过：" + prevCtx + "\n")
            + "这次连发" + n + "条：\n" + sb;
        return llmShort(sys, userMsg, 512);
    }

    static String fastKey() {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "home/.pi/agent/auth.json");
            if (!f.isFile()) return null;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.io.FileInputStream fi = new java.io.FileInputStream(f);
            byte[] b = new byte[4096]; int n; while ((n = fi.read(b)) > 0) bo.write(b, 0, n);
            fi.close();
            JSONObject auth = new JSONObject(bo.toString("UTF-8"));
            JSONObject prov = auth.optJSONObject("zai-coding-cn");
            return prov == null ? null : prov.optString("key", null);
        } catch (Exception e) { return null; }
    }

    // ═══ sysctl 系统开关辅助 ═══
    static JSONObject sysctlReadAll(java.util.regex.Pattern p, String out) throws Exception { return null; }
    static String sysctlVal(String out, String key) {
        for (String pair : out.split("[;\\s]+")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).trim().equals(key)) return pair.substring(i + 1).trim();
        }
        return "?";
    }
    static JSONArray veCache; static String veCacheKey; static long veCacheTime; // vision_elements 同图缓存
    static String rpcStash; // pi_rpc 会话轮换时的状态摘要
    static final Object piRpcLock = new Object(); // pi_rpc 串行锁
    static String firstSeg(String key) { int i = key.indexOf('.'); return i > 0 ? key.substring(0, i) : "fact"; }
    /** 提取类长输出（400字内，多条行） */
    static String llmShortLong(String sys, String userMsg, int maxTok) { return llmShort(sys, userMsg, maxTok, 500); }

    /** 记忆分类推断：条目显式 cat > key 前缀 */
    static String memCat(String k, JSONObject e) {
        String c = e.optString("cat", "");
        if (!c.isEmpty()) return c;
        if (k.startsWith("voice.")) return "voice";
        if (k.startsWith("app.")) return "app";
        if (k.startsWith("user.")) return "user";
        if (k.startsWith("person.")) return "person";
        if (k.startsWith("fact.")) return "fact";
        if (k.startsWith("skill.")) return "skill";
        return "other";
    }
    /** 核心记忆串（pin 条目，注入上下文=Letta core blocks 模式）。无 pin 返回 "" */
    static String coreMemBlock() {
        try {
            File f = new File(ctx.getFilesDir(), "memory.json");
            if (!f.canRead()) return "";
            JSONObject mem = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            java.util.Iterator<String> it = mem.keys();
            int n = 0;
            while (it.hasNext() && n < 12) {
                String k = it.next();
                JSONObject e = mem.optJSONObject(k);
                if (e == null || !e.has("pin")) continue;
                String v = e.optString("v", "");
                if (v.isEmpty()) continue;
                sb.append("- ").append(v.length() > 60 ? v.substring(0, 60) : v).append("\n");
                n++;
            }
            return sb.length() == 0 ? "" : "\n[小丘对用户的核心认知]\n" + sb;
        } catch (Exception e) { return ""; }
    }

    static org.json.JSONArray relatedMemories(String pkg) {
        org.json.JSONArray rel = new org.json.JSONArray();
        if (pkg == null || pkg.isEmpty() || pkg.equals("?")) return rel;
        try {
            File mf = new File(ctx.getFilesDir(), "memory.json");
            if (!mf.canRead()) return rel;
            JSONObject mem = new JSONObject(new String(java.nio.file.Files.readAllBytes(mf.toPath()), "UTF-8"));
            String sn = pkg.substring(pkg.lastIndexOf('.') + 1);
            java.util.Iterator<String> it = mem.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.contains(sn) || k.contains(pkg)) {
                    JSONObject e = mem.optJSONObject(k);
                    if (e != null) rel.put(new JSONObject().put("key", k).put("v", e.optString("v")));
                }
            }
        } catch (Exception ignore) {}
        return rel;
    }
    static final String SYSCTL_DUMP =
        "echo \"wifi=$(settings get global wifi_on);bt=$(settings get global bluetooth_on);"
        + "airplane=$(settings get global airplane_mode_on);rotate=$(settings get system accelerometer_rotation);"
        + "location=$(settings get secure location_mode);zen=$(settings get global zen_mode);"
        + "bauto=$(settings get system screen_brightness_mode);mobile_data=$(settings get global mobile_data)\"";
    static JSONObject sysctlReadAll(String out) throws Exception {
        JSONObject o = new JSONObject();
        o.put("wifi", sysctlVal(out, "wifi")).put("bluetooth", sysctlVal(out, "bt")).put("airplane", sysctlVal(out, "airplane"));
        o.put("rotate", sysctlVal(out, "rotate")).put("location", sysctlVal(out, "location")).put("dnd", sysctlVal(out, "zen"));
        o.put("brightnessAuto", sysctlVal(out, "bauto")).put("mobileData", sysctlVal(out, "mobile_data"));
        return o;
    }

    static JSONObject ok(Object data) {
        try { return new JSONObject().put("ok", true).put("data", data == null ? JSONObject.NULL : data).put("error", JSONObject.NULL); }
        catch (Exception e) { return err("INTERNAL", e.toString()); }
    }
    static JSONObject err(String code, String msg) {
        try { return new JSONObject().put("ok", false).put("data", JSONObject.NULL)
                .put("error", new JSONObject().put("code", code).put("message", msg)); }
        catch (Exception e) { return new JSONObject(); }
    }
    static JSONObject props(Object... kv) throws Exception {
        JSONObject o = new JSONObject();
        for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        return o;
    }
    static JSONObject prop(String type, String desc) throws Exception {
        return new JSONObject().put("type", type).put("description", desc);
    }
    static JSONObject schema(JSONObject props, String... req) throws Exception {
        JSONArray r = new JSONArray();
        for (String s : req) r.put(s);
        return new JSONObject().put("type", "object").put("properties", props).put("required", r);
    }
    static void def(String name, String desc, JSONObject schema, H h) { REG.put(name, new Tool(desc, schema, h)); }

    public static void init(Context c) {
        if (doneInit) return;
        doneInit = true;
        // TTS 预热：后台线程初始化（ttsInit 的 await 绝不能发生在主线程，否则 onInit 回调死锁）
        new Thread(() -> { boolean r = ttsInit(); Log.i("PiBridge", "TTS 预热: " + (r ? "就绪" : "失败")); }, "tts-prewarm").start();
        new Thread(() -> {
            try {
                final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
                miTts = new TextToSpeech(ctx, st -> { miReady = (st == TextToSpeech.SUCCESS); l.countDown(); },
                        "com.xiaomi.mibrain.speech");
                l.await(5, java.util.concurrent.TimeUnit.SECONDS);
                if (miReady) {
                    miTts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                        @Override public void onStart(String id) {}
                        @Override public void onDone(String id) { if ("pi".equals(id)) fireSpeakDone(); }
                        @Override public void onError(String id) { if ("pi".equals(id)) fireSpeakDone(); }
                    });
                    miTts.setLanguage(new Locale("zh", "CN"));
                    StringBuilder sb = new StringBuilder();
                    android.speech.tts.Voice cur = miTts.getVoice();
                    for (android.speech.tts.Voice v : miTts.getVoices())
                        sb.append(v.getName()).append("(").append(v.getLocale()).append(") ");
                    Log.i("PiBridge", "小米TTS 就绪 当前=" + (cur == null ? "?" : cur.getName()) + " 音色: " + sb);
                } else Log.i("PiBridge", "小米TTS 初始化失败");
            } catch (Throwable e) { Log.w("PiBridge", "小米TTS 异常: " + e); }
        }, "mi-prewarm").start();
        ctx = c;
        if (REG.isEmpty()) reg();
    }

    public static JSONObject call(String name, JSONObject args) {
        Tool t = REG.get(name);
        if (t == null) return err("NOT_FOUND", "未知工具: " + name + "（/api/tools_list 可查全部）");
        try {
            return t.h.run(args == null ? new JSONObject() : args);
        } catch (Throwable e) {
            return err("EXCEPTION", e.toString());
        }
    }

    private static void reg() {
        try { reg0(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void reg0() throws Exception {
        // ═══ 设备信息 ═══
        def("battery_status", "查电池：电量、充电状态、温度", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            Intent i = ctx.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            JSONObject o = new JSONObject();
            o.put("percent", level < 0 ? -1 : level * 100 / scale);
            o.put("charging", i.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING);
            o.put("plugged", i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0));
            o.put("tempC", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0);
            return ok(o);
        }});

        def("device_info", "查设备：型号、系统、分辨率、存储", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            JSONObject o = new JSONObject();
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("model", Build.MODEL);
            o.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            File f = Environment.getExternalStorageDirectory();
            StatFs sf = new StatFs(f.getAbsolutePath());
            o.put("storageFreeGB", Math.round(sf.getAvailableBytes() / 1073741824.0 * 10) / 10.0);
            o.put("storageTotalGB", Math.round(sf.getTotalBytes() / 1073741824.0 * 10) / 10.0);
            return ok(o);
        }});

        def("screen_state", "查屏幕：亮灭状态", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return ok(new JSONObject().put("screenOn", pm.isInteractive()));
        }});

        // ═══ 通知（可点击！这是 adb 通知做不到的）═══
        def("notify_post", "发可点击通知：点了会打开 url（Web UI 或任意链接）",
            schema(props("title", prop("string", "标题"), "text", prop("string", "正文"),
                         "url", prop("string", "点击后打开的链接，可选")), "title", "text"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel("pi", "pi 指令", NotificationManager.IMPORTANCE_DEFAULT));
                Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, "pi") : new Notification.Builder(ctx);
                b.setSmallIcon(android.R.drawable.ic_dialog_info)
                 .setContentTitle(a.optString("title")).setContentText(a.optString("text"))
                 .setStyle(new Notification.BigTextStyle().bigText(a.optString("text")))
                 .setAutoCancel(true);
                String url = a.optString("url", "");
                if (!url.isEmpty()) {
                    b.setContentIntent(PendingIntent.getActivity(ctx, notifId,
                            new Intent(Intent.ACTION_VIEW, Uri.parse(url)), PendingIntent.FLAG_IMMUTABLE));
                }
                int id = notifId++;
                nm.notify(id, b.build());
                return ok(new JSONObject().put("id", id));
            }});

        def("notify_cancel", "撤销通知", schema(props("id", prop("number", "notify_post 返回的 id")), "id"),
            new H() { public JSONObject run(JSONObject a) {
                ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(a.optInt("id"));
                return ok("已撤销");
            }});

        // ═══ 剪贴板 ═══
        def("clipboard_write", "写剪贴板", schema(props("text", prop("string", "内容")), "text"),
            new H() { public JSONObject run(JSONObject a) {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("pi", a.optString("text")));
                return ok("已写入剪贴板");
            }});

        def("clipboard_read", "读剪贴板（Android10+ 后台读取可能被系统拒绝）", schema(props()), new H() { public JSONObject run(JSONObject a) {
            try {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    String txt = cm.getPrimaryClip().getItemAt(0).coerceToText(ctx).toString();
                    return ok(txt);
                }
                return ok(""); // 空剪贴板属正常状态，不算错误
            } catch (Exception e) { return err("RESTRICTED", "Android 10+ 限制后台读取剪贴板: " + e); }
        }});

        // ═══ TTS / 震动 / 手电 ═══
        def("tts_speak", "语音朗读（引擎可配置：auto/cloud/xiaomi）", schema(props("text", prop("string", "要念的话"), "engine", prop("string", "临时引擎(auto/cloud/xiaomi)，试听用")), "text", "engine"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                final String text = a.optString("text");
                String engine = "auto";
                try { engine = loadCfg().optString("tts_engine", "auto"); } catch (Exception ignore) {}
                try { if (a.has("engine") && !a.isNull("engine")) engine = a.getString("engine"); } catch (Exception ignore) {}
                // 云：异步拉音频（主线程禁止联网！），两次失败自动回退小米
                if ("cloud".equals(engine) || "auto".equals(engine)) {
                    cloudSpeakAsync(text);
                    return ok("开始朗读（云端童童，失败自动回退本地）");
                }
                if ("xiaomi".equals(engine)) { speakLocal(text); return ok("开始朗读（小米本地）"); }
                // system
                if (ttsInit()) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() { public void run() {
                        tts.setPitch(1.12f); tts.setSpeechRate(1.05f);
                        ttsSpeaking = true;
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pi");
                    }});
                    return ok("开始朗读（系统引擎）");
                }
                return err("TTS_FAIL", "无可用语音引擎");
            }});

        def("tts_voices", "列出可用发音人", schema(new JSONObject()), new H() { public JSONObject run(JSONObject a) throws Exception {
            if (!ttsInit()) return err("TTS_FAIL", "TTS 未初始化");
            android.speech.tts.Voice defaultV = tts.getVoice();
            JSONArray list = new JSONArray();
            for (android.speech.tts.Voice v : tts.getVoices()) {
                if (!v.getFeatures().contains("notInstalled")) {
                    JSONObject o = new JSONObject();
                    o.put("name", v.getName());
                    o.put("locale", v.getLocale().toString());
                    list.put(o);
                }
            }
            JSONObject out = new JSONObject();
            out.put("current", defaultV == null ? "" : defaultV.getName());
            out.put("voices", list);
            return ok(out);
        }});

        // ═══ 快脑：闲聊直答/意图分流 ═══
        def("wake_service", "全局唤醒词开关(息屏喊小丘)", schema(props("action", prop("string", "start/stop/status")), "action"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                String act = a.optString("action", "status");
                if ("start".equals(act)) {
                    WakeService.start(ctx);
                    boolean up = false;
                    for (int i = 0; i < 10 && !up; i++) { Thread.sleep(500); up = WakeService.readState(ctx); }
                    return ok(new JSONObject().put("running", up));
                }
                if ("stop".equals(act)) { WakeService.stop(ctx); WakeService.writeState(ctx, false); return ok(new JSONObject().put("running", false)); }
                return ok(new JSONObject().put("running", WakeService.readState(ctx)));
            }});

        def("apps_launch", "启动应用", schema(props("package", prop("string", "包名，如 com.android.chrome")), "package"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                PackageManager pm = ctx.getPackageManager();
                Intent i = pm.getLaunchIntentForPackage(a.optString("package"));
                if (i == null) return err("NOT_FOUND", "该应用不可启动或不存在");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return ok("已启动");
            }});

        // ═══ 位置 / 短信 / 通话 / 联系人 ═══
        // ═══ 虚拟屏后台操控 v2（App级隐形副屏：无悬浮窗/主屏无感/画面直进内存）═══
        def("vd", "隐形虚拟屏后台操控（用户无感）：create 创建 / launch 发射App到副屏 / shot 截副屏画面 / tap 点击 / swipe 滑动 / text 输入 / info 状态 / stop 销毁。坐标默认千分比0-1000(unit=pm)，传unit=px才是像素",
            schema(props("action", prop("string", "create/launch/shot/tap/swipe/text/info/stop"),
                    "pkg", prop("string", "launch: 应用包名"),
                    "x", prop("number", "tap/swipe: x"), "y", prop("number", "tap/swipe: y"),
                    "x2", prop("number", "swipe: 终点x"), "y2", prop("number", "swipe: 终点y"),
                    "text", prop("string", "text: 要输入的文字"),
                    "w", prop("number", "副屏宽 默认900"), "h", prop("number", "副屏高 默认2000")), "action"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                VdManager.touch(); // 任何 vd 操作都刷新空闲计时
                String act = a.optString("action");
                if ("create".equals(act)) {
                    JSONObject r = VdManager.create(ctx, a.optInt("w", 0), a.optInt("h", 0));
                    return r.has("error") ? err(r.getString("error"), r.optString("msg")) : ok(r);
                }
                if ("stop".equals(act)) {
                    // 关键：先杀掉副屏上的App，否则系统会把任务回迁主屏前台，打扰用户
                    for (String pkg : VdManager.launchedPkgs())
                        Tools.call("l2_exec", new JSONObject().put("cmd", "am force-stop " + pkg));
                    VdManager.destroy();
                    return ok("副屏已销毁（App已清理，无回迁）");
                }
                if ("info".equals(act)) {
                    JSONObject o = new JSONObject();
                    o.put("alive", VdManager.alive());
                    if (VdManager.alive()) o.put("displayId", VdManager.displayId());
                    return ok(o);
                }
                if (!VdManager.alive()) return err("NO_VD", "虚拟屏未创建，先 action=create");
                if ("shot".equals(act)) {
                    JSONObject r = VdManager.shot(ctx);
                    if (r.has("error")) return err(r.getString("error"), r.optString("msg"));
                    // 黑屏自愈：重发射最近App → 等渲染 → 重截
                    if (r.optLong("size", 999999) < 30000 && !VdManager.lastLaunchedPkg().isEmpty()) {
                        String pkg = VdManager.lastLaunchedPkg();
                        Tools.call("l2_exec", new JSONObject().put("timeout_sec", 40).put("cmd",
                                "am start --display " + VdManager.displayId()
                                + " -n $(cmd package resolve-activity --brief " + pkg + " | tail -1) >/dev/null 2>&1"));
                        try { Thread.sleep(4500); } catch (Exception ignore) {}
                        JSONObject r2 = VdManager.shot(ctx);
                        if (!r2.has("error") && r2.optLong("size", 0) >= 30000) {
                            r2.put("recovered", true).put("recovered_pkg", pkg);
                            return ok(r2);
                        }
                        r.put("warning", "黑屏且自动恢复失败，建议销毁副屏重来");
                    }
                    return ok(r);
                }
                if ("shot_grid".equals(act)) {
                    JSONObject r = VdManager.shotGrid(ctx);
                    return r.has("error") ? err(r.getString("error"), r.optString("msg")) : ok(r);
                }
                if ("launch".equals(act)) {
                    // 主屏正在用 → 拒绝发射（绝不从用户手里抢App，防MIUI自动切后台）
                    JSONObject busy = (JSONObject) Tools.call("l2_exec", new JSONObject().put("cmd",
                            "dumpsys activity activities 2>/dev/null | grep topResumedActivity | grep -q '" + a.optString("pkg") + "' && echo BUSY_YES || echo BUSY_NO"));
                    if (String.valueOf(busy).contains("BUSY_YES"))
                        return err("IN_USE", "该App正在主屏前台使用中，为避免打扰你，已取消副屏发射");
                    JSONObject r = VdManager.launch(ctx, a.optString("pkg"));
                    if (r != null) return r.has("error") ? err(r.getString("error"), r.optString("msg")) : ok(r);
                    // 锁屏检测：锁屏时系统禁止App渲染，诚实报错
                    JSONObject kg = (JSONObject) Tools.call("l2_exec", new JSONObject().put("cmd",
                            "dumpsys window policy 2>/dev/null | grep 'showing='"));
                    if (String.valueOf(kg).contains("showing=true"))
                        return err("LOCKED", "设备锁屏中，系统禁止后台App渲染。请解锁手机后重试");
                    // App内通道失败 → L2 shell 兜底（发射后校验落点，防污染主屏）
                    VdManager.track(a.optString("pkg"));
                    int did = VdManager.displayId();
                    JSONObject c = (JSONObject) Tools.call("l2_exec", new JSONObject().put("timeout_sec", 40)
                        .put("cmd", "am force-stop " + a.optString("pkg") + " 2>/dev/null; sleep 1; "
                            + "am start --display " + did
                            + " -n $(cmd package resolve-activity --brief " + a.optString("pkg") + " | tail -1) >/dev/null 2>&1; sleep 2; "
                            + "if dumpsys activity activities | grep -A3 'Display #" + did + " ' | grep -q '" + a.optString("pkg") + "'; "
                            + "then echo __ON_VD__; else echo __ON_MAIN__; fi"));
                    String out = String.valueOf(c);
                    if (out.contains("__ON_VD__")) {
                        JSONObject lo = new JSONObject().put("msg", "已发射到隐形副屏" + did)
                                .put("displayId", did).put("memories", relatedMemories(a.optString("pkg")));
                        return ok(lo);
                    }
                    // 落主屏了：先挪回副屏（保任务少闪断），挪不动再清场
                    JSONObject mv = (JSONObject) Tools.call("l2_exec", new JSONObject().put("timeout_sec", 40)
                        .put("cmd", "tid=$(am stack list 2>/dev/null | grep '" + a.optString("pkg") + "' | grep -oE 'taskId=[0-9]+' | head -1 | cut -d= -f2); "
                            + "if [ -n \"$tid\" ]; then am stack move-task $tid " + did + " 2>/dev/null; fi; sleep 1; "
                            + "if dumpsys activity activities | grep -A3 'Display #" + did + " ' | grep -q '" + a.optString("pkg") + "'; then echo __RESCUED__; else am force-stop " + a.optString("pkg") + "; echo __KILLED__; fi"));
                    if (String.valueOf(mv).contains("__RESCUED__"))
                        return ok(new JSONObject().put("msg", "App原本落主屏，已挪回副屏" + did));
                    return err("LAUNCH_LANDED_MAIN", "未能发射到副屏（已清理），主屏未受影响");
                }
                // tap / swipe / text / key / longpress / scroll_until
                if ("scroll_until".equals(act)) {
                    String target = a.optString("text", "");
                    if (target.isEmpty()) return err("BAD", "scroll_until 需要 text");
                    for (int round = 0; round < 8; round++) {
                        AdbService.readTree(VdManager.displayId());
                        JSONObject n = AdbService.findNodeByText(VdManager.displayId(), target);
                        if (n != null) return ok(new JSONObject().put("found", true).put("node", n).put("rounds", round));
                        Tools.call("l2_exec", new JSONObject().put("cmd", "input -d " + VdManager.displayId()
                                + " swipe " + (VdManager.dispW()/2) + " " + (VdManager.dispH()*3/4) + " "
                                + (VdManager.dispW()/2) + " " + (VdManager.dispH()/4)));
                        Thread.sleep(900);
                    }
                    return ok(new JSONObject().put("found", false).put("hint", "滚到底也未出现"));
                }
                String cmd;
                boolean pm = !"px".equals(a.optString("unit", "pm"));
                int[] dsz = AdbService.displaySize(VdManager.displayId());
                int cx1 = pm ? a.optInt("x") * dsz[0] / 1000 : a.optInt("x");
                int cy1 = pm ? a.optInt("y") * dsz[1] / 1000 : a.optInt("y");
                if ("tap".equals(act)) cmd = "input -d " + VdManager.displayId() + " tap " + cx1 + " " + cy1;
                else if ("longpress".equals(act)) cmd = "input -d " + VdManager.displayId() + " swipe "
                        + cx1 + " " + cy1 + " " + (cx1 + 2) + " " + (cy1 + 1) + " "
                        + Math.max(a.optInt("ms", 600), 400); // +2px偏移：MIUI忽略零距离滑动
                else if ("doubletap".equals(act)) cmd = "input -d " + VdManager.displayId() + " tap "
                        + cx1 + " " + cy1 + "; input -d " + VdManager.displayId() + " tap "
                        + cx1 + " " + cy1;
                else if ("key".equals(act)) cmd = "input -d " + VdManager.displayId() + " swipe "
                        + a.optInt("x") + " " + a.optInt("y") + " " + (a.optInt("x") + 2) + " " + (a.optInt("y") + 1) + " "
                        + Math.max(a.optInt("ms", 600), 400); // +2px偏移：MIUI忽略零距离滑动
                else if ("doubletap".equals(act)) cmd = "input -d " + VdManager.displayId() + " tap "
                        + a.optInt("x") + " " + a.optInt("y") + "; input -d " + VdManager.displayId() + " tap "
                        + a.optInt("x") + " " + a.optInt("y");
                else if ("key".equals(act)) {
                    String k = a.optString("text", "back");
                    int code = k.equals("back") ? 4 : k.equals("home") ? 3 : k.equals("enter") ? 66 : k.equals("esc") ? 111 : 4;
                    cmd = "input -d " + VdManager.displayId() + " keyevent " + code;
                }
                else if ("swipe".equals(act)) cmd = "input -d " + VdManager.displayId() + " swipe "
                        + cx1 + " " + cy1 + " "
                        + (pm ? a.optInt("x2") * dsz[0] / 1000 : a.optInt("x2")) + " "
                        + (pm ? a.optInt("y2") * dsz[1] / 1000 : a.optInt("y2"));
                else if ("text".equals(act)) cmd = "input -d " + VdManager.displayId() + " text '"
                        + a.optString("text", "").replace(" ", "%s").replace("'", "") + "'";
                else return err("BAD_ACTION", "未知 action: " + act);
                JSONObject c = (JSONObject) Tools.call("l2_exec", new JSONObject().put("cmd", cmd));
                return c.optBoolean("ok", false) ? ok(act + " 完成@副屏" + VdManager.displayId()) : err("INJECT_FAIL", String.valueOf(c));
            }});

        // ═══ 系统开关静默控制（L1：免界面、秒级、带回读校验）═══
        def("sysctl", "系统开关静默控制：read 读全部状态；wifi/bluetooth/rotate/location/dnd/brightness_auto 切换（on=true开 false关）。命令级实现不开界面，执行后回读校验",
            schema(props("action", prop("string", "read/wifi/bluetooth/rotate/location/dnd/brightness_auto"),
                    "on", prop("boolean", "true=开 false=关")), "action"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                String act = a.optString("action", "read");
                if ("read".equals(act)) {
                    JSONObject c = (JSONObject) Tools.call("l2_exec", new JSONObject().put("cmd", SYSCTL_DUMP));
                    return ok(sysctlReadAll(c.optString("data")));
                }
                boolean on = a.optBoolean("on");
                String getNs, getKey, want;
                switch (act) {
                    case "wifi": getNs = "global"; getKey = "wifi_on"; want = on ? "1" : "0"; break;
                    case "bluetooth": getNs = "global"; getKey = "bluetooth_on"; want = on ? "1" : "0"; break;
                    case "rotate": getNs = "system"; getKey = "accelerometer_rotation"; want = on ? "1" : "0"; break;
                    case "location": getNs = "secure"; getKey = "location_mode"; want = on ? "3" : "0"; break;
                    case "dnd": getNs = "global"; getKey = "zen_mode"; want = on ? "1" : "0"; break;
                    case "brightness_auto": getNs = "system"; getKey = "screen_brightness_mode"; want = on ? "1" : "0"; break;
                    default: return err("BAD_ACTION", "未知开关: " + act);
                }
                // 原生优先（免L2）：Settings API 直写
                boolean nativeDone = false;
                try {
                    if ("rotate".equals(act)) { Settings.System.putInt(ctx.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0); nativeDone = true; }
                    else if ("location".equals(act)) { Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.LOCATION_MODE, on ? 3 : 0); nativeDone = true; }
                    else if ("dnd".equals(act)) { Settings.Global.putInt(ctx.getContentResolver(), "zen_mode", on ? 1 : 0); nativeDone = true; }
                    else if ("brightness_auto".equals(act)) { Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, on ? 1 : 0); nativeDone = true; }
                } catch (Exception ignore) {}
                if (!nativeDone) { // wifi/bt 等必须 shell
                    String svc = act.equals("wifi") ? "svc wifi " + (on ? "enable" : "disable")
                            : act.equals("bluetooth") ? "svc bluetooth " + (on ? "enable" : "disable") : null;
                    if (svc != null) Tools.call("l2_exec", new JSONObject().put("cmd", svc));
                    else Tools.call("l2_exec", new JSONObject().put("cmd", "settings put " + getNs + " " + getKey + " " + want));
                }
                Thread.sleep(700);
                JSONObject c2 = (JSONObject) Tools.call("l2_exec", new JSONObject().put("cmd", SYSCTL_DUMP));
                JSONObject state = sysctlReadAll(c2.optString("data"));
                String got = act.equals("brightness_auto") ? state.optString("brightnessAuto") : state.optString(act);
                if (got.equals(want)) return ok(new JSONObject().put("applied", true).put(act, want).put("state", state));
                return err("VERIFY_FAIL", "已执行但回读不符 期望=" + want + " 实际=" + got + " 状态=" + state);
            }});

        def("ime_switch", "切换输入法：adb=ADBKeyboard（自动化中文注入用）/ sogou=搜狗（还给用户打字）。任务结束记得切回 sogou",
            schema(props("target", prop("string", "adb/sogou")), "target"), new H() { public JSONObject run(JSONObject a) throws Exception {
            String target = a.optString("target", "sogou");
            String ime = target.equals("adb") ? "com.android.adbkeyboard/.AdbIME" : "com.sohu.inputmethod.sogou.xiaomi/.SogouIME";
            JSONObject c = (JSONObject) Tools.call("l2_exec", new JSONObject().put("cmd",
                    "ime enable " + ime + "; ime set " + ime + "; ime list -s | head -2"));
            return c.optBoolean("ok", false) ? ok("已切换到 " + target) : err("IME_FAIL", String.valueOf(c));
        }});

        // ═══ 宏系统（操作录像回放）：把跑通的流程存成可复用技能 ═══
        // ═══ 结构化视觉（无树App的"树"：GLM-4V 输出带坐标的元素清单）═══
        def("apps_list", "列出已装应用（可按关键词过滤）",
            schema(props("filter", prop("string", "包名/应用名包含关键词"), "limit", prop("number", "上限默认 50"))), new H() { public JSONObject run(JSONObject a) throws Exception {
                PackageManager pm = ctx.getPackageManager();
                List<PackageInfo> all = pm.getInstalledPackages(0);
                String f = a.optString("filter", "").toLowerCase();
                int limit = a.optInt("limit", 50);
                JSONArray arr = new JSONArray(); int n = 0;
                for (PackageInfo pi : all) {
                    String label = pm.getApplicationLabel(pi.applicationInfo).toString();
                    if (!f.isEmpty() && !label.toLowerCase().contains(f) && !pi.packageName.contains(f)) continue;
                    arr.put(label + " (" + pi.packageName + ")");
                    if (++n >= limit) break;
                }
                return ok(new JSONObject().put("total", all.size()).put("shown", n).put("items", arr));
            }});

        def("brightness_get", "查屏幕亮度", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            return ok(Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1));
        }});

        // ═══ 网络 ═══
        def("brightness_set", "设屏幕亮度（0-255，需 WRITE_SETTINGS）",
            schema(props("value", prop("number", "0-255")), "value"), new H() { public JSONObject run(JSONObject a) throws Exception {
                int v = Math.max(1, Math.min(255, a.optInt("value")));
                try {
                    android.content.ContentResolver cr = ctx.getContentResolver();
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v);
                    return ok("亮度 " + v);
                } catch (SecurityException e) {
                    return err("NO_PERM", "需要授权: adbc shell appops set com.binbin.pibridge WRITE_SETTINGS allow");
                }
            }});

        def("calllog_list", "读最近通话（需通话记录权限）", schema(props("limit", prop("number", "条数默认 10"))), new H() { public JSONObject run(JSONObject a) throws Exception {
            Cursor c = ctx.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.TYPE},
                    null, null, CallLog.Calls.DATE + " DESC");
            JSONArray arr = new JSONArray(); int n = a.optInt("limit", 10); int i = 0;
            if (c != null) {
                while (c.moveToNext() && i < n) {
                    String type = c.getInt(3) == CallLog.Calls.INCOMING_TYPE ? "入" : c.getInt(3) == CallLog.Calls.OUTGOING_TYPE ? "出" : "未接";
                    arr.put(new JSONObject().put("number", c.getString(0)).put("name", c.getString(1))
                            .put("type", type).put("time", new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(c.getLong(2)))));
                    i++;
                }
                c.close();
            }
            return ok(arr);
        }});

        def("voice_bus", "统一语音会话总线（页面引擎→原生广播）",
            schema(props("action", prop("string", "done|session|speak|psay|ack|prog|glow"),
                    "cmd", prop("string", "session: start/stop"),
                    "from", prop("string", "wake|mic"),
                    "text", prop("string", "speak: 文本"),
                    "token", prop("string", "speak: 完成回执对账"),
                    "mode", prop("string", "glow: listen|think|exec|speak|off"))),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                String act = a.optString("action");
                android.content.Intent i;
                if ("done".equals(act)) i = new android.content.Intent("com.pihost.VOICE_DONE");
                else if ("session".equals(act)) {
                    i = new android.content.Intent("com.pihost.SESSION_CMD");
                    i.putExtra("cmd", a.optString("cmd", "start")).putExtra("from", a.optString("from", ""));
                }
                else if ("speak".equals(act)) {
                    i = new android.content.Intent("com.pihost.VOICE_SPEAK");
                    i.putExtra("text", a.optString("text", "")).putExtra("token", a.optString("token", ""));
                    if (a.optBoolean("humanize")) i.putExtra("humanize", "1");
                }
                else if ("abort_bash".equals(act)) {
                    i = new android.content.Intent("com.pihost.ABORT_BASH");
                }
                else if ("psay".equals(act)) {
                    i = new android.content.Intent("com.pihost.VOICE_PSAY");
                    i.putExtra("text", a.optString("text", ""));
                }
                else if ("ack".equals(act)) {
                    i = new android.content.Intent("com.pihost.VOICE_ACK");
                }
                else if ("prog".equals(act)) {
                    i = new android.content.Intent("com.pihost.VOICE_PROG");
                    i.putExtra("text", a.optString("text", ""));
                }
                else if ("glow".equals(act)) {
                    i = new android.content.Intent("com.pihost.GLOW_MODE");
                    i.putExtra("mode", a.optString("mode", "off"));
                }
                else return err("bad_action", "unknown: " + act);
                ctx.sendBroadcast(i);
                return ok(new JSONObject().put("sent", act));
            }});
        def("chat_fast", "快脑（意图脑）：带上下文理解输入。闲聊→chat直接答；任务→task并输出优化后的prompt（修ASR错字/结合上下文补全指代/明确目标）交慢脑执行",
            schema(props("q", prop("string", "用户的话"),
                    "context", prop("string", "可选：最近对话上下文摘要（快脑据此理解指代和意图）")), "q"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                String q = a.optString("q");
                JSONObject fmc = fastModelCfg();
                String key = fmc.optString("key", ""); if (key.isEmpty()) key = fastKey();
                if (key == null) return err("NO_KEY", "未配置 API Key");
                String now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", java.util.Locale.CHINA).format(new java.util.Date());
                // 时间日期快速回答（代码级拦截，零延迟零成本，不走LLM路由）
                if (q.matches(".*[几哪]点了?[?？]?") || q.matches(".*现在[几哪]点.*") || q.matches(".*今天.*(几号|星期[几_frequency]|[周周][几哪六日天]).*") || q.matches(".*(星期|周|礼拜)[一二三四五六日天].*")) {
                    String tstr = new java.text.SimpleDateFormat("现在是 HH:mm，今天 yyyy年MM月dd日 EEEE", java.util.Locale.CHINA).format(new java.util.Date());
                    return ok(new JSONObject().put("type", "chat").put("answer", tstr));
                }
                String ctx = a.optString("context", "");
                { // 核心记忆注入（pin 条目=小丘对用户的核心认知，所有快脑调用自动携带）
                    String cm = coreMemBlock();
                    if (!cm.isEmpty()) ctx = cm + "\n" + ctx;
                }
                String ctxBlock = ctx.isEmpty() ? "" : "\n【最近对话上下文】\n" + ctx + "\n";
                String sysPrompt = "你是「小丘」的快脑（意图脑）。当前时间：" + now + "。结合上下文理解用户这句话的意图，只输出一行JSON不要其他内容：\n"
                  + "A.闲聊/常识/计算/翻译/时间日期等无需动手的 → {\"type\":\"chat\",\"answer\":\"<口语回答，AI自判长短，像朋友聊天>\"}\n"
                  + "B.需要动手执行的（写代码/改文件/操作App/控制设备/多步骤）→ {\"type\":\"task\",\"reply\":\"<一句话口语确认>\"，\"prompt\":\"<必填！优化后发给执行脑的完整指令：修正语音错字、把它/那个/继续等指代结合上下文展开成具体对象、写清要做什么和完成标准。这是执行脑唯一能看到的内容，它看不到上下文>\"}\n"
                  + ctxBlock
                  + "规则：上下文里的指代（它/那个/继续）必须结合理解；语音输入可能有错字要纠正；answer/reply/prompt用中文。";
                JSONObject body = new JSONObject()
                        .put("model", fmc.optString("model", "glm-5.3"))
                        .put("messages", new org.json.JSONArray()
                                .put(new JSONObject().put("role", "system").put("content", sysPrompt))
                                .put(new JSONObject().put("role", "user").put("content", q)))
                        .put("max_tokens", 800).put("temperature", 0.3);
                javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection)
                        new java.net.URL(fmc.optString("url", "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions")).openConnection();
                c.setRequestMethod("POST"); c.setConnectTimeout(5000); c.setReadTimeout(20000); c.setDoOutput(true);
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setRequestProperty("Content-Type", "application/json");
                java.io.OutputStream os = c.getOutputStream();
                os.write(body.toString().getBytes("UTF-8")); os.close();
                int code = c.getResponseCode();
                java.io.InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n; while (is != null && (n = is.read(buf)) > 0) bo.write(buf, 0, n);
                if (is != null) is.close();
                String resp = bo.toString("UTF-8");
                if (code >= 400) return err("API_ERR", code + ": " + resp.substring(0, Math.min(200, resp.length())));
                String content = new JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content", "").trim();
                try {
                    String j = content;
                    if (j.contains("{")) j = j.substring(j.indexOf('{'), j.lastIndexOf('}') + 1);
                    JSONObject r = new JSONObject(j);
                    if ("task".equals(r.optString("type")))
                        return ok(new JSONObject().put("type", "task").put("reply", r.optString("reply", "好的，交给我处理"))
                        .put("prompt", r.optString("prompt", ""))); // 优化后的执行指令（快脑给慢脑）
                    return ok(new JSONObject().put("type", "chat").put("answer", r.optString("answer", content)));
                } catch (Exception e) {
                    return ok(new JSONObject().put("type", "chat").put("answer", content));
                }
            }});

        // ═══ 视觉问答（"看"的兜底王牌：GLM-4V 读图，无树App的界面理解）═══
        def("macro_del", "删除指定宏", schema(props("name", prop("string", "宏名")), "name"), new H() { public JSONObject run(JSONObject a) throws Exception {
            String name = a.optString("name").replaceAll("[^a-zA-Z0-9_]", "");
            File f = new File(new File(ctx.getFilesDir(), "macros"), name + ".json");
            return f.delete() ? ok("已删除") : err("NO_MACRO", "不存在");
        }});


        def("macro_export", "导出宏到公共目录（跨设备移植或备份）",
            schema(props("name", prop("string", "宏名，all=全部导出")), "name"), new H() { public JSONObject run(JSONObject a) throws Exception {
            String name = a.optString("name", "all");
            File src = new File(ctx.getFilesDir(), "macros");
            File dst = new File("/storage/emulated/0/pibridge/macros");
            if (!dst.isDirectory()) dst.mkdirs();
            int n = 0;
            for (File f : src.listFiles()) {
                String base = f.getName().replace(".json", "");
                if (!name.equals("all") && !base.equals(name)) continue;
                try { java.nio.file.Files.copy(f.toPath(), new File(dst, f.getName()).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING); n++; } catch (Exception ignore) {}
            }
            return n > 0 ? ok(new JSONObject().put("exported", n).put("dir", dst.getAbsolutePath()))
                    : err("NONE", "没有可导出的宏");
        }});
        def("macro_import", "从公共目录导入宏（pibridge/macros 下的 json）",
            schema(props("name", prop("string", "宏名，all=全部导入")), "name"), new H() { public JSONObject run(JSONObject a) throws Exception {
            String name = a.optString("name", "all");
            File src = new File("/storage/emulated/0/pibridge/macros");
            File dst = new File(ctx.getFilesDir(), "macros");
            if (!dst.isDirectory()) dst.mkdirs();
            if (!src.isDirectory()) return err("NO_SRC", "无导入目录");
            int n = 0;
            for (File f : src.listFiles()) {
                String base = f.getName().replace(".json", "");
                if (!name.equals("all") && !base.equals(name)) continue;
                try { java.nio.file.Files.copy(f.toPath(), new File(dst, f.getName()).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING); n++; } catch (Exception ignore) {}
            }
            return n > 0 ? ok(new JSONObject().put("imported", n)) : err("NONE", "没有可导入的宏");
        }});

        def("macro_list", "列出全部已保存宏", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            File dir = new File(ctx.getFilesDir(), "macros");
            JSONArray out = new JSONArray();
            if (dir.isDirectory()) for (File f : dir.listFiles()) {
                try {
                    JSONObject m = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                    out.put(new JSONObject().put("name", m.optString("name")).put("desc", m.optString("desc"))
                            .put("steps", m.optJSONArray("steps") == null ? 0 : m.optJSONArray("steps").length()));
                } catch (Exception ignore) {}
            }
            return ok(new JSONObject().put("macros", out).put("count", out.length()));
        }});
        def("macro_run", "运行宏：顺序执行全部步骤；支持参数p1-p3、动态副屏{{vd}}、上一步结果{{prev}}与{{prev.路径}}、skip_if_text条件跳步、speak语音播报结果；关键步骤失败自动中止",
            schema(props("name", prop("string", "宏名"), "p1", prop("string", "参数1可空"),
                    "p2", prop("string", "参数2可空"), "p3", prop("string", "参数3可空")), "name"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                String name = a.optString("name").replaceAll("[^a-zA-Z0-9_]", "");
                File f = new File(new File(ctx.getFilesDir(), "macros"), name + ".json");
                if (!f.canRead()) return err("NO_MACRO", "宏不存在，可先macro_list查询");
                JSONObject m = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                JSONArray steps = m.optJSONArray("steps");
                JSONArray results = new JSONArray();
                Object prevData = null;
                String lastAnswer = null;
                for (int i = 0; i < steps.length(); i++) {
                    JSONObject s = steps.optJSONObject(i);
                    String skipIf = s.optString("skip_if_text", "");
                    if (!skipIf.isEmpty()) {
                        int chkDisp = VdManager.alive() ? VdManager.displayId() : 0;
                        JSONArray chkNodes = AdbService.readTree(chkDisp);
                        boolean hitSkip = false;
                        if (chkNodes != null) for (int k = 0; k < chkNodes.length(); k++) {
                            JSONObject n = chkNodes.optJSONObject(k);
                            if (n != null && (n.optString("text","").contains(skipIf) || n.optString("desc","").contains(skipIf))) { hitSkip = true; break; }
                        }
                        if (hitSkip) {
                            results.put(new JSONObject().put("step", i + 1).put("tool", s.optString("tool")).put("skipped", true));
                            continue;
                        }
                    }
                    JSONObject args = new JSONObject(s.optString("args", "{}"));
                    java.util.Iterator<String> ai = args.keys();
                    while (ai.hasNext()) {
                        String k2 = ai.next();
                        Object v2 = args.opt(k2);
                        if (!(v2 instanceof String)) continue;
                        String sv = (String) v2;
                        for (int p = 1; p <= 3; p++) sv = sv.replace("{{p" + p + "}}", a.optString("p" + p));
                        sv = sv.replace("{{vd}}", String.valueOf(VdManager.displayId()));
                        sv = sv.replace("{{now}}", new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(new java.util.Date()));
                        if (prevData != null) {
                            sv = sv.replace("{{prev}}", String.valueOf(prevData));
                            java.util.regex.Matcher pm2 = java.util.regex.Pattern.compile("\\{\\{prev\\.([a-zA-Z0-9_.]+)\\}\\}").matcher(sv);
                            StringBuffer sbx = new StringBuffer();
                            while (pm2.find()) {
                                Object cur = prevData;
                                for (String seg : pm2.group(1).split("\\.")) {
                                    if (cur instanceof JSONObject) cur = ((JSONObject) cur).opt(seg);
                                    else if (cur instanceof org.json.JSONArray) {
                                        try { cur = ((org.json.JSONArray) cur).opt(Integer.parseInt(seg)); } catch (Exception ignore2) { cur = null; }
                                    } else { cur = null; break; }
                                }
                                pm2.appendReplacement(sbx, java.util.regex.Matcher.quoteReplacement(cur == null ? "" : String.valueOf(cur)));
                            }
                            pm2.appendTail(sbx);
                            sv = sbx.toString();
                        }
                        args.put(k2, sv);
                    }
                    JSONObject r = (JSONObject) Tools.call(s.optString("tool"), args);
                    prevData = r.opt("data");
                    JSONObject rec = new JSONObject().put("step", i + 1).put("tool", s.optString("tool"))
                            .put("ok", r.optBoolean("ok", false));
                    try {
                        rec.put("data", r.opt("data"));
                        JSONObject dd = r.optJSONObject("data");
                        if (dd != null && dd.has("answer")) lastAnswer = dd.optString("answer");
                    } catch (Exception ignore3) {}
                    results.put(rec);
                    if (!r.optBoolean("ok", false) && s.optBoolean("critical", true)) {
                        // 中止清场：宏若创建过副屏则销毁，防僵尸display泄漏
                        try {
                            for (int ri = 0; ri < results.length(); ri++) {
                                JSONObject rr = results.optJSONObject(ri);
                                if (rr != null && "vd".equals(rr.optString("tool")) && rr.optBoolean("ok", false)) {
                                    Tools.call("vd", new JSONObject().put("action", "stop"));
                                    break;
                                }
                            }
                        } catch (Exception ignore5) {}
                        return ok(new JSONObject().put("aborted", true).put("atStep", i + 1).put("results", results));
                    }
                }
                if (a.optBoolean("speak", false) && lastAnswer != null) {
                    try {
                        String say = lastAnswer.length() > 80 ? lastAnswer.substring(0, 80) : lastAnswer;
                        Tools.call("tts_speak", new JSONObject().put("text", say));
                    } catch (Exception ignore4) {}
                }
                JSONObject done = new JSONObject().put("done", true).put("steps", steps.length()).put("results", results);
                if (lastAnswer != null) done.put("lastAnswer", lastAnswer);
                return ok(done);
            }});


        def("macro_from_session", "复利飞轮：从最近的pi会话自动提取操作流程保存为宏。lean=true剔除验证性shot/等待（精简宏）",
            schema(props("name", prop("string", "宏名"), "desc", prop("string", "中文说明 可空"), "lean", prop("boolean", "精简模式 默认true"),
                    "max_steps", prop("number", "最多提取步数 默认30"), "session", prop("string", "会话文件路径 可空=最新")), "name"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                String name = a.optString("name").replaceAll("[^a-zA-Z0-9_]", "");
                if (name.isEmpty()) return err("BAD_NAME", "宏名只能用英文数字下划线");
                java.util.Set<String> auto = new java.util.HashSet<>(java.util.Arrays.asList(
                        "vd","ui_tap_text","ui_tap_node","ui_set_text","ui_scroll_node","ui_find_tap","ui_find",
                        "wait_node","ui_wait_gone","l2_exec","screenshot","vision_ask","vision_elements","vision_tap",
                        "apps_launch","settings_write","ime_switch","notify_read","notify_reply","sysctl","shot_diff","memory_save"));
                // 找最新会话
                File sfile;
                String sp = a.optString("session", "");
                if (!sp.isEmpty()) sfile = new File(sp);
                else {
                    File sdir = new File(ctx.getFilesDir(), "home/.pi/agent/sessions");
                    sfile = null; long best = 0;
                    java.util.Deque<File> dq = new java.util.ArrayDeque<>();
                    dq.add(sdir);
                    while (!dq.isEmpty()) {
                        File d = dq.poll();
                        File[] fs = d.listFiles();
                        if (fs == null) continue;
                        for (File x : fs) {
                            if (x.isDirectory()) dq.add(x);
                            else if (x.getName().endsWith(".jsonl") && x.lastModified() > best) { best = x.lastModified(); sfile = x; }
                        }
                    }
                    if (sfile == null) return err("NO_SESSION", "未找到会话文件");
                }
                // 解析提取
                int maxS = Math.min(a.optInt("max_steps", 30), 60);
                JSONArray steps = new JSONArray();
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(sfile), "UTF-8"));
                String ln;
                while ((ln = br.readLine()) != null && steps.length() < maxS) {
                    if (!ln.contains("toolCall")) continue;
                    try {
                        JSONObject e = new JSONObject(ln);
                        JSONObject msg = e.optJSONObject("message");
                        if (msg == null || !"assistant".equals(msg.optString("role"))) continue;
                        JSONArray cc = msg.optJSONArray("content");
                        if (cc == null) continue;
                        boolean lean = a.optBoolean("lean", true);
                        for (int j = 0; j < cc.length() && steps.length() < maxS; j++) {
                            JSONObject item = cc.optJSONObject(j);
                            if (item == null || !"toolCall".equals(item.optString("type"))) continue;
                            String tn = item.optString("name");
                            if (!auto.contains(tn)) continue;
                            JSONObject sargs = item.optJSONObject("arguments") == null ? new JSONObject() : item.optJSONObject("arguments");
                            if (lean && "vd".equals(tn) && "shot".equals(sargs.optString("action"))) continue; // 验证性截图剔除
                            JSONObject st = new JSONObject().put("tool", tn).put("args", sargs);
                            steps.put(st);
                        }
                    } catch (Exception ignore) {}
                }
                br.close();
                if (steps.length() == 0) return err("EMPTY", "会话中未提取到自动化步骤");
                File dir = new File(ctx.getFilesDir(), "macros");
                if (!dir.isDirectory()) dir.mkdirs();
                JSONObject mm = new JSONObject().put("name", name).put("desc", a.optString("desc", "从会话自动提取"))
                        .put("steps", steps).put("saved", System.currentTimeMillis());
                write(new File(dir, name + ".json"), mm.toString());
                StringBuilder preview = new StringBuilder();
                for (int k = 0; k < steps.length() && k < 8; k++) preview.append(steps.optJSONObject(k).optString("tool")).append(" ");
                return ok(new JSONObject().put("name", name).put("steps", steps.length())
                        .put("preview", preview.toString().trim()));
            }});

        // ═══ 权限体检医生（检查→自动修复→引导跳转）═══
        // ═══ 设备状态与唤醒（锁屏能力体系）═══
        def("device_state", "设备状态：locked锁屏/screen亮灭/can_full_operate锁屏下能否全功能操作。操作前先查此工具",
            schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
                JSONObject o = new JSONObject();
                android.app.KeyguardManager km = (android.app.KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
                android.os.PowerManager pm2 = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                boolean locked = km.isKeyguardLocked();
                boolean screenOn = pm2.isInteractive();
                o.put("locked", locked).put("screen_on", screenOn);
                o.put("secure_lock", km.isKeyguardSecure());
                o.put("can_full_operate", !locked);
                if (locked) o.put("hint", "锁屏中：L1命令/settings/通知/记忆可用，UI类操作需解锁（device_wake可试唤醒）");
                return ok(o);
            }});
        def("device_wake", "唤醒屏幕并尝试解锁（仅滑动锁有效，PIN/密码锁只能唤醒到锁屏）。返回唤醒后的设备状态",
            schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
                JSONArray seq = new JSONArray();
                seq.put("input keyevent 224"); // KEYCODE_WAKEUP
                seq.put("input keyevent 82");  // 解锁滑动锁
                for (int i = 0; i < seq.length(); i++) {
                    try { Tools.call("l2_exec", new JSONObject().put("cmd", seq.optString(i)).put("timeout_sec", 15)); } catch (Exception ignore) {}
                    try { Thread.sleep(800); } catch (Exception ignore) {}
                }
                try { Thread.sleep(1500); } catch (Exception ignore) {}
                android.app.KeyguardManager km = (android.app.KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
                return ok(new JSONObject().put("unlocked", !km.isKeyguardLocked())
                        .put("hint", km.isKeyguardSecure() ? "安全锁需手动输入PIN" : "滑动锁已尝试自动解锁"));
            }});

        def("perm_doctor", "权限体检医生：check=全量检查14项能力状态；fix_all=自动修复全部可自动修复项；fix=修复单项",
            schema(props("action", prop("string", "check/fix_all/fix"), "name", prop("string", "fix时的单项名")), "action"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                String act = a.optString("action", "check");
                JSONArray items = new JSONArray();
                boolean a11yOn = false, nlsOn = false, secureOk = false, storageOk = false;
                boolean camOk = false, micOk = false, conOk = false, smsOk = false, callOk = false;
                boolean locOk = false, overlayOk = false, battOk = false;
                try { a11yOn = String.valueOf(Settings.Secure.getString(ctx.getContentResolver(), "enabled_accessibility_services")).contains("com.pihost"); } catch (Exception ignore) {}
                try { nlsOn = String.valueOf(Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners")).contains("com.pihost"); } catch (Exception ignore) {}
                try { Settings.Secure.putString(ctx.getContentResolver(), "__probe__", "1"); Settings.Secure.putString(ctx.getContentResolver(), "__probe__", null); secureOk = true; } catch (Exception ignore) {}
                try { storageOk = new java.io.File("/storage/emulated/0/pibridge/.wtest").createNewFile(); if (storageOk) new java.io.File("/storage/emulated/0/pibridge/.wtest").delete(); } catch (Exception ignore) {}
                try { camOk = ctx.checkPermission("android.permission.CAMERA", android.os.Process.myPid(), android.os.Process.myUid()) == 0; } catch (Exception ignore) {}
                try { micOk = ctx.checkPermission("android.permission.RECORD_AUDIO", android.os.Process.myPid(), android.os.Process.myUid()) == 0; } catch (Exception ignore) {}
                try { conOk = ctx.checkPermission("android.permission.READ_CONTACTS", android.os.Process.myPid(), android.os.Process.myUid()) == 0; } catch (Exception ignore) {}
                try { smsOk = ctx.checkPermission("android.permission.READ_SMS", android.os.Process.myPid(), android.os.Process.myUid()) == 0; } catch (Exception ignore) {}
                try { callOk = ctx.checkPermission("android.permission.READ_CALL_LOG", android.os.Process.myPid(), android.os.Process.myUid()) == 0; } catch (Exception ignore) {}
                try { locOk = ctx.checkPermission("android.permission.ACCESS_FINE_LOCATION", android.os.Process.myPid(), android.os.Process.myUid()) == 0; } catch (Exception ignore) {}
                try { overlayOk = Settings.canDrawOverlays(ctx); } catch (Exception ignore) {}
                try { android.os.PowerManager pm2 = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE); battOk = pm2.isIgnoringBatteryOptimizations("com.pihost"); } catch (Exception ignore) {}
                String[][] table = {
                    {"accessibility", String.valueOf(a11yOn), "settings", "辅助服务"},
                    {"notification", String.valueOf(nlsOn), "cmd", "通知监听"},
                    {"secure_settings", String.valueOf(secureOk), "settings", "安全设置写入"},
                    {"storage", String.valueOf(storageOk), "grant", "文件存储"},
                    {"camera", String.valueOf(camOk), "grant", "相机"},
                    {"mic", String.valueOf(micOk), "grant", "麦克风"},
                    {"contacts", String.valueOf(conOk), "grant", "联系人"},
                    {"sms", String.valueOf(smsOk), "grant", "短信"},
                    {"calllog", String.valueOf(callOk), "grant", "通话记录"},
                    {"location", String.valueOf(locOk), "grant", "精确定位"},
                    {"overlay", String.valueOf(overlayOk), "appops", "悬浮窗"},
                    {"battery", String.valueOf(battOk), "l2", "电池白名单"},
                };
                for (String[] row : table) {
                    JSONObject it = new JSONObject();
                    it.put("name", row[0]).put("ok", row[1]).put("desc", row[3]);
                    it.put("auto_fixable", true);
                    items.put(it);
                }
                if ("check".equals(act)) {
                    boolean allOk = a11yOn && nlsOn && secureOk && storageOk && camOk && micOk && conOk && smsOk && callOk && locOk && overlayOk && battOk;
                    return ok(new JSONObject().put("items", items).put("all_ok", allOk));
                }
                String single = "fix".equals(act) ? a.optString("name", "") : null;
                JSONArray fixed = new JSONArray();
                for (String[] row : table) {
                    String nm = row[0];
                    if (row[1].equals("true")) continue;
                    if (single != null && !nm.equals(single)) continue;
                    String fcmd = null;
                    if ("accessibility".equals(nm)) fcmd = "settings put secure enabled_accessibility_services com.pihost/com.binbin.pibridge.AdbService; settings put secure accessibility_enabled 1";
                    else if ("notification".equals(nm)) fcmd = "cmd notification allow_listener com.pihost/com.binbin.pibridge.NotifyListener";
                    else if ("storage".equals(nm)) fcmd = "appops set com.pihost MANAGE_EXTERNAL_STORAGE allow";
                    else if ("overlay".equals(nm)) fcmd = "appops set com.pihost SYSTEM_ALERT_WINDOW allow";
                    else if ("battery".equals(nm)) fcmd = "dumpsys deviceidle whitelist +com.pihost";
                    else if ("camera".equals(nm)) fcmd = "pm grant com.pihost android.permission.CAMERA";
                    else if ("mic".equals(nm)) fcmd = "pm grant com.pihost android.permission.RECORD_AUDIO";
                    else if ("contacts".equals(nm)) fcmd = "pm grant com.pihost android.permission.READ_CONTACTS";
                    else if ("sms".equals(nm)) fcmd = "pm grant com.pihost android.permission.READ_SMS; pm grant com.pihost android.permission.SEND_SMS";
                    else if ("calllog".equals(nm)) fcmd = "pm grant com.pihost android.permission.READ_CALL_LOG";
                    else if ("location".equals(nm)) fcmd = "pm grant com.pihost android.permission.ACCESS_FINE_LOCATION";
                    if (fcmd != null) {
                        Tools.call("l2_exec", new JSONObject().put("cmd", fcmd).put("timeout_sec", 20));
                        fixed.put(nm);
                    }
                }
                JSONObject res = new JSONObject().put("fixed", fixed).put("note", "建议force-stop重启App使服务重新绑定");
                return ok(res);
            }
            });

        def("voice_digest", "汇总最近的语音播报记忆（谁找过你、说了什么）→ AI整理成一段'你错过的事'口语摘要。适合用户问'刚才谁找我/错过什么'时调用",
            schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
                java.io.File f = new java.io.File(ctx.getFilesDir(), "memory.json");
                if (!f.isFile()) return err("NO_MEMORY", "暂无播报记忆");
                JSONObject mem = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                java.util.Iterator<String> it = mem.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (k.startsWith("voice.")) sb.append(k.substring(6)).append("：").append(mem.optString(k)).append("\n");
                }
                if (sb.length() == 0) return err("NO_MEMORY", "暂无播报记录");
                String digest = llmShort("把下面的最近播报记录整理成一段自然口语摘要（50字内），按人归组，把提问/邀约/等你回复的事放最前面说。只输出摘要本身。",
                        sb.toString(), 512, 400);
                if (digest == null || digest.startsWith("ERR:")) return err("DIGEST_FAIL", String.valueOf(digest));
                return ok(new JSONObject().put("digest", digest).put("raw", sb.toString()));
            }});
        def("ai_humanize", "播报拟人化：把通知或助手回复改写成自然口语播报（kind=reply 时面向长回复做口语摘要）",
            schema(props("app", prop("string", "包名如com.tencent.mm"), "title", prop("string", "标题"),
                    "text", prop("string", "正文"), "kind", prop("string", "可选 reply=助手回复播报摘要")), "app", "title", "text"), new H() { public JSONObject run(JSONObject a) throws Exception {
            String h = ("reply".equals(a.optString("kind")))
                ? llmShort("你是语音助手「小丘」。把助手的回复内容转成口头汇报（2-4句，80-200字）：像同事当面汇报工作结果——先说结论，再讲关键要点/数字/下一步，让人听一遍就明白事情办得怎样；内容多就多讲要点，别硬压缩丢信息；去掉markdown/代码/列表符号。只输出要念的话。",
                    String.valueOf(a.optString("text", "")).substring(0, Math.min(1500, a.optString("text", "").length())), 2000, 500)
                : Tools.aiHumanize(a.optString("app"), a.optString("title"), a.optString("text"));
            return h == null ? err("HUMANIZE_FAIL", "改写失败（回退原文播报）") : ok(new JSONObject().put("say", h));
        }});
        def("xhs_search_direct", "小红书深链直达搜索结果页（绕开输入框自动化；内置全套防护：主屏占用检测→清场→副屏发射→落点校验→误落救援）",
            schema(props("keyword", prop("string", "搜索关键词（自动URL编码）")), "keyword"), new H() { public JSONObject run(JSONObject a) throws Exception {
            String kw = a.optString("keyword", "");
            if (kw.isEmpty()) return err("BAD", "缺keyword");
            String pkg = "com.xingin.xhs";
            // ① 主屏正在用 → 拒绝（绝不抢用户App）
            JSONObject busy = (JSONObject) Tools.call("l2_exec", new JSONObject().put("cmd",
                    "dumpsys activity activities 2>/dev/null | grep topResumedActivity | grep -q '" + pkg + "' && echo BUSY_YES || echo BUSY_NO"));
            if (String.valueOf(busy).contains("BUSY_YES"))
                return err("IN_USE", "小红书正在你主屏前台使用中，为避免打扰已取消");
            // ② 确保副屏存在（复用带防护的 vd create/launch）
            if (!VdManager.alive()) {
                Tools.call("vd", new JSONObject().put("action", "create"));
                JSONObject rl = (JSONObject) Tools.call("vd", new JSONObject().put("action", "launch").put("pkg", pkg));
                if (!rl.optBoolean("ok", false)) return rl; // 只在真失败时返回（成功响应里error:null不能误判）
            }
            int did = VdManager.displayId();
            // ③ 记住用户当前前台App → 深链(会瞬间抢焦点) → 立刻把用户的App拉回前台 → 验证搜索页在副屏
            String enc = android.net.Uri.encode(kw);
            JSONObject c = (JSONObject) Tools.call("l2_exec", new JSONObject().put("timeout_sec", 60)
                .put("cmd", "top=$(dumpsys activity activities | grep topResumedActivity | head -1 | awk '{print $3}'); "
                    + "sleep 5; am start --display " + did + " -a android.intent.action.VIEW -d 'xhsdiscover://search/result?keyword=" + enc + "' >/dev/null 2>&1; sleep 4; "
                    + "if [ -n \"$top\" ]; then am start --display 0 -n \"$top\" >/dev/null 2>&1; fi; sleep 1; "
                    + "if dumpsys activity activities | grep -A3 'Display #" + did + " ' | grep -q GlobalSearchActivity; then echo __SEARCH_OK__; "
                    + "elif dumpsys activity activities | grep -A3 'Display #" + did + " ' | grep -q '" + pkg + "'; then echo __ON_VD_NO_SEARCH__; "
                    + "else echo __MISS__; fi"));
            String out = String.valueOf(c);
            VdManager.touch();
            if (out.contains("__SEARCH_OK__")) return ok(new JSONObject().put("displayId", did).put("keyword", kw).put("note", "搜索结果页已在副屏打开，可 vd shot + vision_elements 提取"));
            if (out.contains("__ON_VD_NO_SEARCH__")) return err("SEARCH_NOT_OPEN", "App在副屏但深链未生效（可重试一次或用 vision 手动流程）");
            if (out.contains("__RESCUED__")) return ok(new JSONObject().put("displayId", did).put("keyword", kw).put("note", "曾落主屏已挪回副屏"));
            return err("LAUNCH_LANDED_MAIN", "深链落到主屏已清理（防打扰），可重试");
        }});
        // ═══ 通用 Intent（Android 万能动作语言：一个工具=几十个系统能力）═══
        def("intent_start", "启动任意Android Intent（万能动作）：action+data+extras。适合闹钟/定时器/网页/地图/分享等系统能力",
            schema(props("action", prop("string", "action常量 如 android.intent.action.SET_ALARM"),
                    "data", prop("string", "URI数据 可空"),
                    "extras", prop("string", "附加参数JSON 可空 如 {\"android.intent.extra.alarm.HOUR\":7}"),
                    "pkg", prop("string", "指定包名 可空")), "action"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                String action = a.optString("action");
                if (action.isEmpty()) return err("BAD", "缺action");
                android.content.Intent i = new android.content.Intent(action);
                String data = a.optString("data", "");
                if (!data.isEmpty()) i.setData(android.net.Uri.parse(data));
                String extras = a.optString("extras", "{}");
                try {
                    JSONObject ex = new JSONObject(extras);
                    java.util.Iterator<String> it = ex.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        Object v = ex.opt(k);
                        if (v instanceof String) i.putExtra(k, (String) v);
                        else if (v instanceof Integer) i.putExtra(k, (Integer) v);
                        else if (v instanceof Boolean) i.putExtra(k, (Boolean) v);
                        else if (v instanceof Double) i.putExtra(k, (Double) v);
                        else if (v instanceof Long) i.putExtra(k, (Long) v);
                    }
                } catch (Exception ignore) {}
                String pkg = a.optString("pkg", "");
                if (!pkg.isEmpty()) i.setPackage(pkg);
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ctx.startActivity(i);
                    return ok("已启动: " + action);
                } catch (Exception e) { return err("START_FAIL", e.toString()); }
            }});
        def("alarm_set", "设置闹钟（闹钟App预填，用户确认保存）",
            schema(props("hour", prop("number", "时 0-23"), "minute", prop("number", "分 0-59"),
                    "label", prop("string", "闹钟标签 可空"), "skip_ui", prop("boolean", "跳过UI直接设 可空默认false")), "hour", "minute"), new H() { public JSONObject run(JSONObject a) throws Exception {
            int h = a.optInt("hour"), m = a.optInt("minute");
            if (h < 0 || h > 23 || m < 0 || m > 59) return err("BAD", "时间无效");
            boolean skipUi = a.optBoolean("skip_ui", false);
            if (skipUi) { // AlarmManager 直设（系统级闹钟，无UI）
                JSONObject c = (JSONObject) Tools.call("l2_exec", new JSONObject().put("cmd",
                        "am broadcast -a android.intent.action.ALARM_CHANGED 2>/dev/null; true"));
            }
            android.content.Intent i = new android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM);
            i.putExtra(android.provider.AlarmClock.EXTRA_HOUR, h);
            i.putExtra(android.provider.AlarmClock.EXTRA_MINUTES, m);
            if (a.has("label")) i.putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, a.optString("label"));
            i.putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, skipUi);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
                return ok(new JSONObject().put("alarm", h + ":" + (m < 10 ? "0" + m : m))
                        .put("note", skipUi ? "已跳过UI直接设置" : "闹钟App已打开预填，请在界面确认保存"));
            } catch (Exception e) { return err("SET_FAIL", e.toString()); }
        }});
        def("timer_set", "设置倒计时（秒）",
            schema(props("seconds", prop("number", "倒计时秒数"), "label", prop("string", "标签 可空"), "skip_ui", prop("boolean", "跳过UI")), "seconds"), new H() { public JSONObject run(JSONObject a) throws Exception {
            int secs = a.optInt("seconds", 60);
            android.content.Intent i = new android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER);
            i.putExtra(android.provider.AlarmClock.EXTRA_LENGTH, secs);
            if (a.has("label")) i.putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, a.optString("label"));
            i.putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, a.optBoolean("skip_ui", false));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            try { ctx.startActivity(i); return ok(new JSONObject().put("timer", secs + "秒")); }
            catch (Exception e) { return err("SET_FAIL", e.toString()); }
        }});

        def("macro_save", "保存宏：name英文标识，desc中文说明，steps为步骤数组JSON文本，每步包含tool与args两个字段，args支持p1到p3占位符",
            schema(props("name", prop("string", "宏名英文数字下划线"), "desc", prop("string", "中文说明"),
                    "steps", prop("string", "步骤数组JSON文本")), "name", "desc", "steps"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                String name = a.optString("name").replaceAll("[^a-zA-Z0-9_]", "");
                if (name.isEmpty()) return err("BAD_NAME", "宏名只能用英文数字下划线");
                JSONArray steps = new JSONArray(a.optString("steps", "[]"));
                if (steps.length() == 0) return err("EMPTY", "steps 为空");
                for (int i = 0; i < steps.length(); i++) {
                    JSONObject s = steps.optJSONObject(i);
                    if (s == null || s.optString("tool").isEmpty()) return err("BAD_STEP", "第" + (i+1) + "步缺少tool");
                }
                File dir = new File(ctx.getFilesDir(), "macros");
                if (!dir.isDirectory()) dir.mkdirs();
                JSONObject m = new JSONObject().put("name", name).put("desc", a.optString("desc"))
                        .put("steps", steps).put("saved", System.currentTimeMillis());
                write(new File(dir, name + ".json"), m.toString());
                return ok(new JSONObject().put("name", name).put("steps", steps.length()));
            }});
        def("memory_del", "删除知识", schema(props("key", prop("string", "标识")), "key"), new H() { public JSONObject run(JSONObject a) throws Exception {
            File f = new File(ctx.getFilesDir(), "memory.json");
            try {
                JSONObject mem = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                mem.remove(a.optString("key"));
                write(f, mem.toString());
                return ok("已删除");
            } catch (Exception e) { return err("EMPTY", "记忆库为空"); }
        }});

        // ═══ 变化检测（操作效果验证的通用武器：两张截图像素级对比）═══
        def("memory_list", "列出全部记忆（key+摘要），pi 每次操作陌生App前先看一眼",
            schema(props("kw", prop("string", "按key关键词过滤 可空"))), new H() { public JSONObject run(JSONObject a) throws Exception {
            File f = new File(ctx.getFilesDir(), "memory.json");
            JSONObject mem;
            try { mem = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8")); }
            catch (Exception e) { return ok(new JSONObject().put("count", 0).put("items", new JSONArray())); }
            String kw = a.optString("kw", "");
            JSONArray out = new JSONArray();
            java.util.Iterator<String> it = mem.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!kw.isEmpty() && !k.contains(kw)) continue;
                JSONObject e = mem.optJSONObject(k);
                if (e == null) continue;
                String v = e.optString("v");
                JSONObject r = new JSONObject().put("key", k).put("v", v.length() > 80 ? v.substring(0, 80) + "…" : v).put("t", e.optLong("t"));
                r.put("cat", memCat(k, e)).put("pin", e.has("pin")).put("src", e.optString("src", "manual"));
                out.put(r);
            }
            return ok(new JSONObject().put("count", out.length()).put("items", out));
        }});
        def("memory_extract", "对话后自动沉淀记忆：从一段对话提取值得长期记住的用户事实/偏好（无则不动）",
            schema(props("text", prop("string", "用户说的话"), "reply", prop("string", "助手回答摘要 可空"))),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                if ("false".equals(loadCfg().optString("memory_auto", "true"))) return ok(new JSONObject().put("saved", 0).put("off", true));
                String conv = "用户：" + a.optString("text", "") + "\n小丘：" + a.optString("reply", "");
                if (conv.trim().length() < 12) return ok(new JSONObject().put("saved", 0));
                String r = llmShortLong("从下面的对话里提取【值得长期记住的用户信息】：偏好/习惯/事实/人物关系/常用路径等。\n" +
                        "规则：只提取关于用户的稳定信息（一次性问题不算）；没有值得记的就只输出 NONE；最多3条。\n" +
                        "输出格式（每行一条，严格遵守）：cat.key: 内容\n其中 cat 是 user/fact/person/app/skill 之一，key 用简短英文标识。\n例如：user.pref.concise: 用户喜欢简洁回答",
                        conv, 400);
                if (r == null || r.contains("NONE") || r.startsWith("ERR")) return ok(new JSONObject().put("saved", 0));
                int n = 0;
                for (String line : r.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("cat.")) continue;
                    int ci = line.indexOf(':');
                    if (ci < 5) continue;
                    String key = line.substring(0, ci).trim();
                    String val = line.substring(ci + 1).trim();
                    if (key.isEmpty() || val.isEmpty() || val.length() > 200) continue;
                    if (!key.matches("[a-zA-Z0-9._-]{3,60}")) continue;
                    Tools.call("memory_save", new JSONObject().put("key", key).put("value", val).put("cat", firstSeg(key)).put("src", "auto"));
                    n++;
                }
                if (n > 0) Log.i("PiBridge", "🧠 自动沉淀 " + n + " 条");
                return ok(new JSONObject().put("saved", n));
            }});
        def("voiceprint_enroll", "录入声纹样本（唤醒主人校验用）：传 wav 路径，说一遍唤醒词",
            schema(props("file", prop("string", "wav 路径（mic_record 产物）")), "file"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                float[] e = spkEmbed(new java.io.File(a.optString("file")));
                if (e == null) return err("EMBED_FAIL", "提取失败（模型未就绪或音频太短）");
                java.io.File pool = new java.io.File(ctx.getFilesDir(), "voiceprint-pool.bin");
                java.util.List<float[]> list = new java.util.ArrayList<>();
                try (java.io.DataInputStream ds = new java.io.DataInputStream(new java.io.FileInputStream(pool))) {
                    int n = ds.readInt();
                    for (int k = 0; k < n && k < 7; k++) {
                        int d = ds.readInt(); float[] x = new float[d];
                        for (int i = 0; i < d; i++) x[i] = ds.readFloat();
                        double nn = 0; for (float v : x) nn += (double) v * v;
                        if (Math.sqrt(nn) > 1e-3) list.add(x); // 滤掉历史零向量
                    }
                } catch (Exception ignore) {}
                list.add(e);
                try (java.io.DataOutputStream ds = new java.io.DataOutputStream(new java.io.FileOutputStream(pool))) {
                    ds.writeInt(list.size());
                    for (float[] x : list) { ds.writeInt(x.length); for (float v : x) ds.writeFloat(v); }
                }
                vpSave(list.toArray(new float[0][]));
                return ok(new JSONObject().put("count", list.size()).put("dim", e.length)
                        .put("active", list.size() >= 3));
            }});
        def("voiceprint_verify", "声纹验证（试一试）：传 wav，返回与主人声纹的相似度",
            schema(props("file", prop("string", "wav 路径")), "file"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                float[] e = spkEmbed(new java.io.File(a.optString("file")));
                float[] m = vpLoad();
                if (e == null || m == null) return err("NOVP", e == null ? "提取失败" : "尚未录入声纹");
                float s = cosine(e, m);
                return ok(new JSONObject().put("score", Math.round(s * 1000) / 1000.0).put("pass", s >= 0.55));
            }});
        def("voiceprint_status", "声纹状态",
            schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
                java.io.File pool = new java.io.File(ctx.getFilesDir(), "voiceprint-pool.bin");
                int n = 0;
                try (java.io.DataInputStream ds = new java.io.DataInputStream(new java.io.FileInputStream(pool))) { n = ds.readInt(); } catch (Exception ignore) {}
                return ok(new JSONObject().put("samples", n).put("enrolled", vpFile().isFile())
                        .put("model", new java.io.File(ctx.getFilesDir(), "voiceprint-model.onnx").isFile())
                        .put("active", n >= 3 && vpFile().isFile()));
            }});
        def("voiceprint_clear", "清除声纹",
            schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
                try { vpFile().delete(); new java.io.File(ctx.getFilesDir(), "voiceprint-pool.bin").delete(); } catch (Exception ignore) {}
                return ok(new JSONObject().put("cleared", true));
            }});
        def("memory_pin", "置顶/取消核心记忆（核心记忆每次对话自动注入上下文=小丘对你的核心认知）",
            schema(props("key", prop("string", "标识"), "on", prop("boolean", "true=置顶 false=取消")), "key", "on"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                File f = new File(ctx.getFilesDir(), "memory.json");
                try {
                    JSONObject mem = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                    JSONObject e = mem.optJSONObject(a.optString("key"));
                    if (e == null) return err("NOT_FOUND", "无此记忆");
                    if (a.optBoolean("on")) e.put("pin", true); else e.remove("pin");
                    write(f, mem.toString());
                    return ok(new JSONObject().put("key", a.optString("key")).put("pin", e.has("pin")));
                } catch (Exception e) { return err("EMPTY", "记忆库为空"); }
            }});
        def("memory_edit", "编辑记忆内容",
            schema(props("key", prop("string", "标识"), "value", prop("string", "新内容")), "key", "value"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                return Tools.call("memory_save", new JSONObject().put("key", a.optString("key")).put("value", a.optString("value")));
            }});
        def("memory_clean", "批量清理记忆",
            schema(props("cat", prop("string", "按分类：voice/app/user/fact/person/skill，空=全部"), "olderThanDays", prop("number", "只清 N 天前的，0=不限")), "cat"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                File f = new File(ctx.getFilesDir(), "memory.json");
                try {
                    JSONObject mem = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                    String cat = a.optString("cat", "");
                    long cut = a.optInt("olderThanDays", 0) > 0 ? System.currentTimeMillis() - a.optInt("olderThanDays") * 86400000L : Long.MAX_VALUE;
                    java.util.Iterator<String> it = mem.keys();
                    int n = 0;
                    while (it.hasNext()) {
                        String k = it.next();
                        JSONObject e = mem.optJSONObject(k);
                        if (e == null || e.has("pin")) continue; // 置顶永不清
                        if (!cat.isEmpty() && !memCat(k, e).equals(cat)) continue;
                        if (e.optLong("t", 0) > cut) continue;
                        it.remove(); n++;
                    }
                    write(f, mem.toString());
                    return ok(new JSONObject().put("cleaned", n).put("total", mem.length()));
                } catch (Exception e) { return ok(new JSONObject().put("cleaned", 0)); }
            }});
        def("memory_read", "读取知识", schema(props("key", prop("string", "标识")), "key"), new H() { public JSONObject run(JSONObject a) throws Exception {
            File f = new File(ctx.getFilesDir(), "memory.json");
            try {
                JSONObject mem = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                JSONObject e = mem.optJSONObject(a.optString("key"));
                if (e == null) return err("NOT_FOUND", "无此知识");
                return ok(new JSONObject().put("value", e.optString("v")).put("saved", e.optLong("t")));
            } catch (Exception e) { return err("EMPTY", "记忆库为空"); }
        }});
        def("memory_save", "持久保存知识（跨会话永存）：key 唯一标识，value 内容。用于：用户偏好、App界面特性（如'微信搜索按钮需偏移-30px'）、常用地址等",
            schema(props("key", prop("string", "唯一标识 如 user.pref.address / app.wechat.search_offset"), "value", prop("string", "知识内容")), "key", "value"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                String key = a.optString("key").trim();
                if (key.isEmpty()) return err("BAD", "key 不能为空");
                File f = new File(ctx.getFilesDir(), "memory.json");
                JSONObject mem = new JSONObject();
                try { mem = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8")); } catch (Exception ignore) {}
                JSONObject e = new JSONObject().put("v", a.optString("value")).put("t", System.currentTimeMillis());
                if (!a.optString("cat", "").isEmpty()) e.put("cat", a.optString("cat"));
                if (!a.optString("src", "").isEmpty()) e.put("src", a.optString("src"));
                if (a.has("pin") && a.optBoolean("pin")) e.put("pin", true);
                JSONObject oldE = mem.optJSONObject(key);
                if (oldE != null && oldE.has("pin")) e.put("pin", true); // 更新保留置顶
                mem.put(key, e);
                write(f, mem.toString());
                return ok(new JSONObject().put("key", key).put("total", mem.length()));
            }});
        def("pi_rpc", "与长驻pi会话对话（免冷启动，保留上下文记忆）。new=true 开新会话。返回pi的文字回复",
            schema(props("prompt", prop("string", "给pi的指令"), "wait_sec", prop("number", "最长等待 默认120秒"),
                    "new", prop("boolean", "true=先开新会话")), "prompt"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                String prompt = a.optString("prompt");
                { // 核心记忆注入（慢脑同享）
                    String cm = coreMemBlock();
                    if (!cm.isEmpty()) prompt = cm + "\n" + prompt;
                }
                int waitS = a.optInt("wait_sec", 120);

                String home = "/data/data/com.pihost/files/home";
                File fifo = new File(home, ".pi-rpc-in");
                File out = new File(home, ".pi-rpc-out.jsonl");
                // 守护自愈（检查与启动分离，避免 pgrep 自匹配误判）
                JSONObject chk = (JSONObject) Tools.call("env_run", new JSONObject().put("timeout_sec", 15)
                        .put("cmd", "pgrep -f 'mode rp[c]' | head -1"));
                boolean alive = String.valueOf(chk).matches("(?s).*\\d{2,}.*");
                if (!alive) {
                    Tools.call("env_run", new JSONObject().put("timeout_sec", 20)
                            .put("cmd", "cd $HOME && [ -p .pi-rpc-in ] || mkfifo .pi-rpc-in; "
                                    + "setsid sh -c 'tail -f .pi-rpc-in | pi --mode rpc >> .pi-rpc-out.jsonl 2>&1' >/dev/null 2>&1 < /dev/null & "
                                    + "sleep 4; echo started"));
                    try { Thread.sleep(1500); } catch (Exception ignore) {}
                    chk = (JSONObject) Tools.call("env_run", new JSONObject().put("timeout_sec", 15)
                            .put("cmd", "pgrep -f 'mode rp[c]' | head -1"));
                    alive = String.valueOf(chk).matches("(?s).*\\d{2,}.*");
                }
                if (!alive) return err("DAEMON_FAIL", "RPC守护启动失败: " + chk);
                if (a.optBoolean("new", false)) {
                    Tools.call("env_run", new JSONObject().put("timeout_sec", 15)
                            .put("cmd", "printf '%s\\n' '{\"type\":\"new_session\"}' > $HOME/.pi-rpc-in"));
                    try { Thread.sleep(1500); } catch (Exception ignore) {}
                }
                long start = out.canRead() ? out.length() : 0;
                // 写prompt到FIFO（tail -f 持有读端，写入不阻塞）；带上轮换摘要
                String finalPrompt = prompt;
                if (rpcStash != null) {
                    finalPrompt = "【此前任务状态摘要】" + rpcStash + "\n\n" + prompt;
                    rpcStash = null;
                }
                JSONObject line = new JSONObject().put("id", "rpc" + System.currentTimeMillis())
                        .put("type", "prompt").put("message", finalPrompt);
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(fifo, true)) {
                    fo.write((line.toString() + "\n").getBytes("UTF-8"));
                    fo.flush();
                }
                // 轮询 out 新增字节，找最后一个 agent_end 的 assistant 文本
                long deadline = System.currentTimeMillis() + waitS * 1000L;
                StringBuilder acc = new StringBuilder();
                String answer = null; String usage = null;
                while (System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(1200); } catch (Exception ignore) {}
                    if (!out.canRead()) continue;
                    byte[] all = java.nio.file.Files.readAllBytes(out.toPath());
                    if (all.length <= start) continue;
                    String fresh = new String(java.util.Arrays.copyOfRange(all, (int) start, all.length), "UTF-8");
                    acc.setLength(0); acc.append(fresh);
                    long lastInput = 0;
                    for (String ln : fresh.split("\n")) {
                        if (!ln.contains("agent_end")) continue;
                        try {
                            JSONObject ev = new JSONObject(ln);
                            lastInput = ev.optJSONObject("usage") == null ? 0 : ev.optJSONObject("usage").optLong("input", 0);
                            JSONArray msgs = ev.optJSONArray("messages");
                            if (msgs == null) continue;
                            for (int k = msgs.length() - 1; k >= 0; k--) {
                                JSONObject mm = msgs.optJSONObject(k);
                                if (mm == null || !"assistant".equals(mm.optString("role"))) continue;
                                JSONArray cc = mm.optJSONArray("content");
                                if (cc == null) continue;
                                StringBuilder txt = new StringBuilder();
                                for (int j = 0; j < cc.length(); j++) {
                                    JSONObject part = cc.optJSONObject(j);
                                    if (part != null && "text".equals(part.optString("type"))) txt.append(part.optString("text"));
                                }
                                if (txt.length() > 0) { answer = txt.toString(); usage = String.valueOf(ev.optJSONObject("usage")); }
                            }
                        } catch (Exception ignore) {}
                    }
                    if (answer != null && fresh.contains("agent_settled")) break;
                }
                if (answer == null) {
                    String tail = acc.length() > 0 ? acc.substring(Math.max(0, acc.length() - 300)) : "无新增输出";
                    return err("RPC_TIMEOUT", waitS + "秒内未完成。尾部输出: " + tail);
                }
                JSONObject o = new JSONObject();
                try { o.put("answer", answer); if (usage != null) o.put("usage", new JSONObject(usage)); } catch (Exception ignore) {}
                // 上下文自动轮换：input超40k → 摘要→new_session→摘要留给下次prompt
                long usedInput = 0;
                try { usedInput = o.getJSONObject("usage").optLong("input", 0); } catch (Exception ignore) {}
                if (usedInput > 40000) {
                    JSONObject sumReq = new JSONObject().put("id", "sum" + System.currentTimeMillis())
                            .put("type", "prompt").put("message", "用150字总结当前对话的关键状态、已完成与未完成事项，只输出总结");
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(fifo, true)) {
                        fo.write((sumReq + "\n").getBytes("UTF-8")); fo.flush();
                    }
                    String summary = null;
                    long dl2 = System.currentTimeMillis() + 60000;
                    long mark = out.length();
                    while (System.currentTimeMillis() < dl2 && summary == null) {
                        try { Thread.sleep(1200); } catch (Exception ignore) {}
                        if (!out.canRead()) continue;
                        byte[] all2 = java.nio.file.Files.readAllBytes(out.toPath());
                        if (all2.length <= mark) continue;
                        String fresh2 = new String(java.util.Arrays.copyOfRange(all2, (int) mark, all2.length), "UTF-8");
                        mark = all2.length;
                        for (String ln : fresh2.split("\n")) {
                            if (!ln.contains("agent_end")) continue;
                            try {
                                JSONObject ev = new JSONObject(ln);
                                JSONArray msgs = ev.optJSONArray("messages");
                                if (msgs == null) continue;
                                for (int k = msgs.length() - 1; k >= 0; k--) {
                                    JSONObject mm = msgs.optJSONObject(k);
                                    if (mm == null || !"assistant".equals(mm.optString("role"))) continue;
                                    JSONArray cc = mm.optJSONArray("content");
                                    if (cc == null) continue;
                                    StringBuilder tx = new StringBuilder();
                                    for (int j = 0; j < cc.length(); j++) {
                                        JSONObject part = cc.optJSONObject(j);
                                        if (part != null && "text".equals(part.optString("type"))) tx.append(part.optString("text"));
                                    }
                                    if (tx.length() > 0) summary = tx.toString();
                                }
                            } catch (Exception ignore) {}
                        }
                    }
                    Tools.call("env_run", new JSONObject().put("timeout_sec", 15)
                            .put("cmd", "printf '%s\\n' '{\"type\":\"new_session\"}' > $HOME/.pi-rpc-in"));
                    rpcStash = summary == null ? "" : summary;
                    o.put("session_rotated", true);
                    if (summary != null) o.put("summary", summary);
                }
                return ok(o);
            }});

        // ═══ 持久记忆（脑的长期存储：用户偏好/App特性/坐标校准/任何学到的知识）═══
        def("shot_diff", "对比两张截图：返回变化区域占比+变化区域裁剪图路径。配合 vision_ask(裁剪图,'这里发生了什么变化') = 通用操作效果验证",
            schema(props("before", prop("string", "前图路径"), "after", prop("string", "后图路径")), "before", "after"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                android.graphics.Bitmap b1 = android.graphics.BitmapFactory.decodeFile(a.optString("before"));
                android.graphics.Bitmap b2 = android.graphics.BitmapFactory.decodeFile(a.optString("after"));
                if (b1 == null || b2 == null) return err("BAD_IMG", "图片解码失败");
                int w = Math.min(b1.getWidth(), b2.getWidth()), h = Math.min(b1.getHeight(), b2.getHeight());
                int[] p1 = new int[w * h], p2 = new int[w * h];
                b1.getPixels(p1, 0, w, 0, 0, w, h); b2.getPixels(p2, 0, w, 0, 0, w, h);
                int minX = w, minY = h, maxX = 0, maxY = 0, changed = 0;
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    int d = Math.abs(((p1[i] >> 16) & 255) - ((p2[i] >> 16) & 255))
                          + Math.abs(((p1[i] >> 8) & 255) - ((p2[i] >> 8) & 255))
                          + Math.abs((p1[i] & 255) - (p2[i] & 255));
                    if (d > 60) { changed++; if (x<minX)minX=x; if (x>maxX)maxX=x; if (y<minY)minY=y; if (y>maxY)maxY=y; }
                }
                JSONObject o = new JSONObject();
                double pct = 100.0 * changed / (w * h);
                o.put("changedPercent", Math.round(pct * 10) / 10.0);
                if (changed > 0) {
                    int pad = 20;
                    int cx = Math.max(0, minX - pad), cy = Math.max(0, minY - pad);
                    int cw = Math.min(w - cx, maxX - minX + pad * 2), ch = Math.min(h - cy, maxY - minY + pad * 2);
                    android.graphics.Bitmap crop = android.graphics.Bitmap.createBitmap(b2, cx, cy, cw, ch);
                    File cf = new File("/storage/emulated/0/pibridge/shots/diff-crop.png");
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(cf);
                    crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 85, fo); fo.close();
                    o.put("region", cx + "," + cy + " " + cw + "x" + ch);
                    o.put("crop", cf.getAbsolutePath());
                }
                b1.recycle(); b2.recycle();
                return ok(o);
            }});

        def("ui_wait_gone", "等待元素消失（加载圈/弹窗关闭/页面切走的反向验证）",
            schema(props("text", prop("string", "等待消失的文本"), "display", prop("number", "屏幕ID 默认0主屏"),
                    "timeout_sec", prop("number", "最长等待 默认15秒")), "text"), new H() { public JSONObject run(JSONObject a) throws Exception {
            int disp = a.optInt("display", 0);
            long deadline = System.currentTimeMillis() + a.optInt("timeout_sec", 15) * 1000L;
            String target = a.optString("text");
            while (System.currentTimeMillis() < deadline) {
                JSONArray nodes = AdbService.readTree(disp);
                boolean found = false;
                if (nodes != null) for (int k = 0; k < nodes.length(); k++) {
                    JSONObject n = nodes.optJSONObject(k);
                    if (n != null && (n.optString("text","").contains(target) || n.optString("desc","").contains(target))) { found = true; break; }
                }
                if (!found) return ok(new JSONObject().put("gone", true));
                try { Thread.sleep(900); } catch (Exception ignore) {}
            }
            return ok(new JSONObject().put("gone", false).put("hint", "超时仍在"));
        }});

        def("vision_tap", "视觉千分比定位点击（自验证闭环，跨设备通用）：截图→vision_elements千分比定位→换算像素点击→页面签名复查→千分比偏移重试→校准自动入库",
            schema(props("label", prop("string", "要点击的元素名称（含匹配即可）"), "display", prop("number", "副屏ID 当前活着的"),
                    "kind", prop("string", "元素类型筛选 可空")), "label"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                String label = a.optString("label");
                String kind = a.optString("kind", "");
                JSONObject s1 = VdManager.shot(ctx);
                if (s1.has("error")) return err(s1.getString("error"), s1.optString("msg"));
                String f1 = s1.optString("file");
                JSONObject els = (JSONObject) Tools.call("vision_elements", new JSONObject().put("file", f1).put("kind", kind));
                if (!els.optBoolean("ok", false)) return els;
                JSONArray list = els.optJSONObject("data") == null ? new JSONArray()
                        : els.optJSONObject("data").optJSONArray("elements");
                int nx = -1, ny = -1; String matched = "";
                StringBuilder sigB1 = new StringBuilder();
                for (int k = 0; k < list.length(); k++) {
                    JSONObject e = list.optJSONObject(k);
                    if (e == null) continue;
                    sigB1.append(e.optString("label","")).append("|");
                    if (e.optString("label","").contains(label) && nx < 0) {
                        nx = e.optInt("x"); ny = e.optInt("y"); matched = e.optString("label");
                    }
                }
                String sig1 = sigB1.toString();
                if (nx < 0) return err("NOT_FOUND", "截图中未找到含『" + label + "』的元素，共" + list.length() + "个元素");
                // 千分比→像素（按当前副屏尺寸）
                int[] ds = AdbService.displaySize(VdManager.displayId());
                // 记忆优先：历史校准偏移直接应用（第二次点击第一次就准）
                String appliedCal = "";
                try {
                    String wpkg0 = VdManager.lastLaunchedPkg();
                    if (!wpkg0.isEmpty()) {
                        JSONObject mr = (JSONObject) Tools.call("memory_read", new JSONObject()
                                .put("key", "app." + wpkg0 + ".vision_offset." + label));
                        if (mr.optBoolean("ok", false)) {
                            String v = mr.optJSONObject("data") == null ? "" : mr.getJSONObject("data").optString("value", "");
                            java.util.regex.Matcher cm = java.util.regex.Pattern.compile("偏移(-?\\d+),(-?\\d+)").matcher(v);
                            if (cm.find()) {
                                nx += Integer.parseInt(cm.group(1));
                                ny += Integer.parseInt(cm.group(2));
                                appliedCal = "已应用历史校准" + cm.group(1) + "," + cm.group(2);
                            }
                        }
                    }
                } catch (Exception ignore) {}
                int px = nx * ds[0] / 1000, py = ny * ds[1] / 1000;
                JSONObject c = (JSONObject) Tools.call("l2_exec", new JSONObject().put("cmd",
                        "input -d " + VdManager.displayId() + " tap " + px + " " + py));
                if (!c.optBoolean("ok", false)) return err("TAP_FAIL", String.valueOf(c));
                try { Thread.sleep(2500); } catch (Exception ignore) {}
                JSONObject s2 = VdManager.shot(ctx);
                if (s2.has("error")) return ok(new JSONObject().put("tapped", true).put("verified", false)
                        .put("at", nx + "," + ny + "‰").put("hint", "复查截图失败，请人工确认"));
                JSONObject els2 = (JSONObject) Tools.call("vision_elements", new JSONObject().put("file", s2.optString("file")).put("kind", kind));
                boolean pageChanged = false;
                if (els2.optBoolean("ok", false)) {
                    JSONArray l2 = els2.optJSONObject("data") == null ? new JSONArray()
                            : els2.optJSONObject("data").optJSONArray("elements");
                    StringBuilder sigB2 = new StringBuilder();
                    for (int k = 0; k < l2.length(); k++) {
                        JSONObject e = l2.optJSONObject(k);
                        if (e != null) sigB2.append(e.optString("label","")).append("|");
                    }
                    pageChanged = !sigB2.toString().equals(sig1);
                    if (pageChanged) {
                        JSONObject rok = new JSONObject().put("tapped", true).put("verified", true)
                                .put("pageChanged", true).put("at", nx + "," + ny + "‰").put("matched", matched);
                        if (!appliedCal.isEmpty()) rok.put("applied_calibration", appliedCal);
                        return ok(rok);
                    }
                }
                // 千分比十字偏移重试（±40‰）
                int[][] offs = {{40,0},{-40,0},{0,40},{0,-40}};
                for (int[] off : offs) {
                    int rpx = (nx + off[0]) * ds[0] / 1000, rpy = (ny + off[1]) * ds[1] / 1000;
                    Tools.call("l2_exec", new JSONObject().put("cmd",
                            "input -d " + VdManager.displayId() + " tap " + rpx + " " + rpy));
                    try { Thread.sleep(2000); } catch (Exception ignore) {}
                    JSONObject s3 = VdManager.shot(ctx);
                    if (s3.has("error")) continue;
                    JSONObject els3 = (JSONObject) Tools.call("vision_elements", new JSONObject().put("file", s3.optString("file")).put("kind", kind));
                    if (els3.optBoolean("ok", false)) {
                        JSONArray l3 = els3.optJSONObject("data") == null ? new JSONArray()
                                : els3.optJSONObject("data").optJSONArray("elements");
                        StringBuilder sigB3 = new StringBuilder();
                        for (int k = 0; k < l3.length(); k++) {
                            JSONObject e = l3.optJSONObject(k);
                            if (e != null) sigB3.append(e.optString("label","")).append("|");
                        }
                        if (!sigB3.toString().equals(sig1)) {
                            String wpkg = VdManager.lastLaunchedPkg();
                            if (!wpkg.isEmpty()) {
                                try {
                                    Tools.call("memory_save", new JSONObject().put("key",
                                            "app." + wpkg + ".vision_offset." + label)
                                            .put("value", "千分比原坐标(" + nx + "," + ny + ") 命中偏移" + off[0] + "," + off[1]));
                                } catch (Exception ignore5) {}
                            }
                            return ok(new JSONObject().put("tapped", true).put("verified", true)
                                .put("pageChanged", true).put("at", rpx + "," + rpy).put("retried", true)
                                .put("matched", matched).put("calibration", "千分比修正" + off[0] + "," + off[1]));
                        }
                    }
                }
                return ok(new JSONObject().put("tapped", true).put("verified", false).put("pageChanged", false)
                        .put("hint", "页面签名未变化：点击可能无效果、或目标页与原页元素相同"));
            }});

        // ═══ 结构化视觉（无树App的"树"：GLM-4V 输出千分比坐标元素清单，跨设备通用）═══
        def("vision_elements", "结构化视觉：截图→GLM-4V 返回可交互元素JSON清单（千分比坐标label/x/y/type）。微信等无树App用它替代 ui_screen_read。配套：vd shot 后调用",
            schema(props("file", prop("string", "图片路径"), "kind", prop("string", "筛选 可空：button/input/item 全部")), "file"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                String file = a.optString("file");
                String kind = a.optString("kind", "");
                byte[] img = java.nio.file.Files.readAllBytes(new File(file).toPath());
                String b64 = android.util.Base64.encodeToString(img, android.util.Base64.NO_WRAP);
                String mime = file.toLowerCase().endsWith(".jpg") || file.toLowerCase().endsWith(".jpeg") ? "image/jpeg" : "image/png";
                String key = fastKey();
                if (key == null) return err("NO_KEY", "未配置 API Key");
                String md5 = android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("MD5").digest(img), android.util.Base64.NO_WRAP);
                if (veCache != null && veCacheTime > System.currentTimeMillis() - 300000 && veCacheKey.equals(md5))
                    return ok(new JSONObject().put("count", veCache.length()).put("elements", veCache).put("cached", true).put("unit", "permille-0-1000"));
                String q = "分析这张手机截图。用千分比坐标系描述元素位置：横向从左到右0-1000，纵向从上到下0-1000（例如屏幕正中=(500,500)，右下角=(1000,1000)）。"
                        + "找出最醒目的至多15个可交互元素（按钮/输入框/图标/页签/列表项/聊天行），label精简不超过10个字。"
                        + "只输出严格JSON数组不要任何其他文字（不要思考过程）：[{\"label\":\"元素名称\",\"x\":千分比x整数,\"y\":千分比y整数,\"type\":\"button/input/item\"}]。"
                        + (kind.isEmpty() ? "" : "只保留type为" + kind + "的元素。");
                JSONObject body = new JSONObject()
                        .put("model", "glm-5v-turbo")
                        .put("messages", new org.json.JSONArray()
                                .put(new JSONObject().put("role", "user").put("content", new org.json.JSONArray()
                                        .put(new JSONObject().put("type", "image_url")
                                                .put("image_url", new JSONObject().put("url", "data:" + mime + ";base64," + b64)))
                                        .put(new JSONObject().put("type", "text").put("text", q)))))
                        .put("max_tokens", 2048);
                javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection)
                        new java.net.URL("https://open.bigmodel.cn/api/paas/v4/chat/completions").openConnection();
                c.setRequestMethod("POST"); c.setConnectTimeout(8000); c.setReadTimeout(90000); c.setDoOutput(true);
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setRequestProperty("Content-Type", "application/json");
                java.io.OutputStream os = c.getOutputStream();
                os.write(body.toString().getBytes("UTF-8")); os.close();
                int code = c.getResponseCode();
                java.io.InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
                java.io.ByteArrayOutputStream bo2 = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int nn; while (is != null && (nn = is.read(buf)) > 0) bo2.write(buf, 0, nn);
                if (is != null) is.close();
                String resp = bo2.toString("UTF-8");
                if (code >= 400) return err("API_ERR", code + ": " + resp.substring(0, Math.min(300, resp.length())));
                String content = new JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content", "").trim();
                String jc = content.replace("```json", "").replace("```", "").trim();
                int lb = jc.indexOf('['), rb = jc.lastIndexOf(']');
                if (lb >= 0 && rb > lb) jc = jc.substring(lb, rb + 1);
                else if (lb >= 0) {
                    jc = jc.substring(lb);
                    int lastBrace = jc.lastIndexOf('}');
                    jc = lastBrace >= 0 ? jc.substring(0, lastBrace + 1) + "]" : "[]";
                }
                try {
                    JSONArray els = new JSONArray(jc);
                    veCache = els; veCacheKey = md5; veCacheTime = System.currentTimeMillis();
                    return ok(new JSONObject().put("count", els.length()).put("elements", els).put("cached", false).put("unit", "permille-0-1000"));
                } catch (Exception e) {
                    return err("PARSE_FAIL", "模型未返回合法JSON: " + content.substring(0, Math.min(300, content.length())));
                }
            }});

        def("vision_bench", "视觉千分比定位基准：GLM-4V千分比坐标 vs 树真值千分比，输出匹配数/平均偏差‰/最大偏差‰（跨设备通用度量）",
            schema(props("display", prop("number", "屏幕ID 默认0主屏"))), new H() { public JSONObject run(JSONObject a) throws Exception {
            int disp = a.optInt("display", 0);
            String shotPath;
            if (disp > 0) {
                JSONObject s = VdManager.shot(ctx);
                if (s.has("error")) return err(s.getString("error"), s.optString("msg"));
                shotPath = s.optString("file");
            } else {
                JSONObject s = (JSONObject) Tools.call("screenshot", new JSONObject());
                if (!s.optBoolean("ok", false)) return err("SHOT_FAIL", String.valueOf(s));
                shotPath = String.valueOf(s.get("data")).split(" ")[0];
            }
            JSONArray tree = AdbService.readTree(disp);
            if (tree == null || tree.length() == 0) return err("NO_TREE", "该屏无结构树真值");
            int[] ds = AdbService.displaySize(disp);
            JSONObject els = (JSONObject) Tools.call("vision_elements", new JSONObject().put("file", shotPath));
            if (!els.optBoolean("ok", false)) return els;
            JSONArray ve = els.optJSONObject("data") == null ? new org.json.JSONArray()
                    : els.optJSONObject("data").optJSONArray("elements");
            java.util.List<double[]> deltas = new java.util.ArrayList<>();
            org.json.JSONArray details = new org.json.JSONArray();
            for (int k = 0; k < tree.length(); k++) {
                JSONObject n = tree.optJSONObject(k);
                if (n == null) continue;
                String txt = n.optString("text","").trim();
                if (txt.length() < 2 || txt.length() > 12) continue;
                String[] parts = n.optString("xy").split(" ");
                if (parts.length < 2) continue;
                String[] lt = parts[0].split(",");
                String[] wh = parts[1].split("x");
                int tcx = Integer.parseInt(lt[0]) + Integer.parseInt(wh[0]) / 2;
                int tcy = Integer.parseInt(lt[1]) + Integer.parseInt(wh[1]) / 2;
                int tnx = tcx * 1000 / ds[0], tny = tcy * 1000 / ds[1]; // 树像素→千分比
                for (int j = 0; j < ve.length(); j++) {
                    JSONObject e = ve.optJSONObject(j);
                    if (e == null) continue;
                    String el = e.optString("label","").trim();
                    if (el.equals(txt)) {
                        int vx = e.optInt("x"), vy = e.optInt("y");
                        double dd = Math.sqrt(Math.pow(vx - tnx, 2) + Math.pow(vy - tny, 2));
                        deltas.add(new double[]{dd});
                        org.json.JSONArray det = new org.json.JSONArray();
                        org.json.JSONObject d2 = new org.json.JSONObject();
                        try { d2.put("text", txt).put("treePermille", tnx + "," + tny).put("visionPermille", vx + "," + vy).put("deltaPermille", Math.round(dd)); } catch (Exception ignore) {}
                        details.put(d2);
                        break;
                    }
                }
            }
            if (deltas.isEmpty()) return ok(new org.json.JSONObject().put("matched", 0).put("hint", "无可匹配元素"));
            double sum = 0, max = 0;
            for (double[] d2 : deltas) { sum += d2[0]; max = Math.max(max, d2[0]); }
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("unit", "permille");
            o.put("matched", deltas.size());
            o.put("avgDelta", Math.round(sum / deltas.size()));
            o.put("maxDelta", Math.round(max));
            o.put("details", details);
            return ok(o);
            }});

        def("ocr_read", "文字精读（OCR）：提取截图中的全部文字内容（保持阅读顺序与段落），支持长文/聊天记录/文章/设置页。GLM-4.6V 128K上下文",
            schema(props("file", prop("string", "截图路径"), "q", prop("string", "附加问题 可空（如'只要聊天内容'）")), "file"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                String file = a.optString("file");
                String q2 = a.optString("q", "");
                byte[] img = java.nio.file.Files.readAllBytes(new File(file).toPath());
                String b64 = android.util.Base64.encodeToString(img, android.util.Base64.NO_WRAP);
                String mime = file.toLowerCase().endsWith(".jpg") || file.toLowerCase().endsWith(".jpeg") ? "image/jpeg" : "image/png";
                String key = fastKey();
                if (key == null) return err("NO_KEY", "未配置 API Key");
                String q = "提取这张手机截图中全部文字内容，按阅读顺序排列，保持段落结构。"
                        + (q2.isEmpty() ? "" : "附加要求：" + q2);
                JSONObject body = new JSONObject()
                        .put("model", "glm-4.6v")
                        .put("messages", new org.json.JSONArray()
                                .put(new JSONObject().put("role", "user").put("content", new org.json.JSONArray()
                                        .put(new JSONObject().put("type", "image_url")
                                                .put("image_url", new JSONObject().put("url", "data:" + mime + ";base64," + b64)))
                                        .put(new JSONObject().put("type", "text").put("text", q)))))
                        .put("max_tokens", 4096);
                javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection)
                        new java.net.URL("https://open.bigmodel.cn/api/paas/v4/chat/completions").openConnection();
                c.setRequestMethod("POST"); c.setConnectTimeout(8000); c.setReadTimeout(90000); c.setDoOutput(true);
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setRequestProperty("Content-Type", "application/json");
                java.io.OutputStream os = c.getOutputStream();
                os.write(body.toString().getBytes("UTF-8")); os.close();
                int code = c.getResponseCode();
                java.io.InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n; while (is != null && (n = is.read(buf)) > 0) bo.write(buf, 0, n);
                if (is != null) is.close();
                String resp = bo.toString("UTF-8");
                if (code >= 400) return err("API_ERR", code + ": " + resp.substring(0, Math.min(300, resp.length())));
                String content = new JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content", "").trim();
                return ok(new JSONObject().put("text", content));
            }});

        def("vision_precise", "小目标二次精读：vision_elements粗定位→裁剪局部放大→GLM-4V精读→返回全图精确坐标。解决视觉坐标10-20%偏差问题",
            schema(props("file", prop("string", "截图路径"), "label", prop("string", "目标元素名"), "pad", prop("number", "裁剪半径 默认200px")), "file", "label"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                String file = a.optString("file");
                String label = a.optString("label");
                int pad = Math.max(a.optInt("pad", 200), 80);
                // 1. 粗定位
                JSONObject els = (JSONObject) Tools.call("vision_elements", new JSONObject().put("file", file));
                if (!els.optBoolean("ok", false)) return els;
                JSONArray list = els.optJSONObject("data") == null ? new JSONArray()
                        : els.optJSONObject("data").optJSONArray("elements");
                int rx = -1, ry = -1;
                for (int k = 0; k < list.length(); k++) {
                    JSONObject e = list.optJSONObject(k);
                    if (e != null && e.optString("label","").contains(label)) { rx = e.optInt("x"); ry = e.optInt("y"); break; }
                }
                if (rx < 0) return err("NOT_FOUND", "粗定位未找到『" + label + "』");
                // 2. 千分比→像素（需要图片尺寸）
                android.graphics.BitmapFactory.Options bo = new android.graphics.BitmapFactory.Options();
                bo.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(file, bo);
                int W = bo.outWidth, H = bo.outHeight;
                int px = rx * W / 1000, py = ry * H / 1000;
                // 3. 裁剪局部（边界保护）
                int cx = Math.max(0, Math.min(W - 1, px - pad)), cy = Math.max(0, Math.min(H - 1, py - pad));
                int cw = Math.min(W - cx, pad * 2), ch = Math.min(H - cy, pad * 2);
                android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeFile(file);
                if (src == null) return err("BAD_IMG", "图片解码失败");
                android.graphics.Bitmap crop = android.graphics.Bitmap.createBitmap(src, cx, cy, Math.min(cw, src.getWidth()-cx), Math.min(ch, src.getHeight()-cy));
                File cf = new File("/storage/emulated/0/pibridge/shots/precise-crop.png");
                java.io.FileOutputStream fo = new java.io.FileOutputStream(cf);
                crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 95, fo); fo.close();
                src.recycle(); crop.recycle();
                // 4. GLM-4V 精读裁剪图
                JSONObject va = (JSONObject) Tools.call("vision_ask", new JSONObject()
                        .put("file", cf.getAbsolutePath())
                        .put("q", "这个裁剪图里『" + label + "』元素的中心坐标是多少？裁剪图尺寸" + crop.getWidth() + "x" + crop.getHeight()
                                + "。只输出严格JSON：{\"x\":整数,\"y\":整数}，不要其他文字"));
                if (!va.optBoolean("ok", false)) return va;
                String ans = String.valueOf(va.optJSONObject("data").get("answer"));
                String jc = ans; 
                if (jc.contains("{")) jc = jc.substring(jc.indexOf('{'), jc.lastIndexOf('}') + 1);
                JSONObject pos = new JSONObject(jc);
                int fx = cx + pos.optInt("x"), fy = cy + pos.optInt("y");
                return ok(new JSONObject().put("x", fx).put("y", fy)
                        .put("method", "precise").put("rough_was", rx + "," + ry)
                        .put("improved", true).put("crop", cf.getAbsolutePath()));
            }});

        def("vision_ask", "视觉问答：对本地图片提问（GLM-4V）。无结构树的App用它理解界面：问'列出图中店铺名和评分'、'找到搜索按钮的坐标'等",
            schema(props("file", prop("string", "图片路径"), "q", prop("string", "问题")), "file", "q"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                String file = a.optString("file");
                String q = a.optString("q", "描述这张图片的内容");
                byte[] img = java.nio.file.Files.readAllBytes(new File(file).toPath());
                String b64 = android.util.Base64.encodeToString(img, android.util.Base64.NO_WRAP);
                String mime = file.toLowerCase().endsWith(".jpg") || file.toLowerCase().endsWith(".jpeg") ? "image/jpeg" : "image/png";
                String key = fastKey();
                if (key == null) return err("NO_KEY", "未配置 API Key");
                JSONObject body = new JSONObject()
                        .put("model", "glm-5.3-flash")
                        .put("messages", new org.json.JSONArray()
                                .put(new JSONObject().put("role", "user").put("content", new org.json.JSONArray()
                                        .put(new JSONObject().put("type", "image_url")
                                                .put("image_url", new JSONObject().put("url", "data:" + mime + ";base64," + b64)))
                                        .put(new JSONObject().put("type", "text").put("text", q)))))
                        .put("max_tokens", 2048);
                javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection)
                        new java.net.URL("https://open.bigmodel.cn/api/paas/v4/chat/completions").openConnection();
                c.setRequestMethod("POST"); c.setConnectTimeout(8000); c.setReadTimeout(90000); c.setDoOutput(true);
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setRequestProperty("Content-Type", "application/json");
                java.io.OutputStream os = c.getOutputStream();
                os.write(body.toString().getBytes("UTF-8")); os.close();
                int code = c.getResponseCode();
                java.io.InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n; while (is != null && (n = is.read(buf)) > 0) bo.write(buf, 0, n);
                if (is != null) is.close();
                String resp = bo.toString("UTF-8");
                if (code >= 400) return err("API_ERR", code + ": " + resp.substring(0, Math.min(300, resp.length())));
                String content = new JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content", "").trim();
                return ok(new JSONObject().put("answer", content));
            }});

        def("contacts_search", "搜联系人", schema(props("q", prop("string", "姓名关键词")), "q"), new H() { public JSONObject run(JSONObject a) throws Exception {
            Cursor c = ctx.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + a.optString("q") + "%"}, null);
            JSONArray arr = new JSONArray(); int n = 20; int i = 0;
            if (c != null) {
                while (c.moveToNext() && i < n) { arr.put(c.getString(0) + " " + c.getString(1)); i++; }
                c.close();
            }
            return ok(arr);
        }});

        // ═══ 文件（共享存储）═══
        def("env_status", "查工作台 pi 环境状态（就绪/安装中/路径）", schema(props()), new H() { public JSONObject run(JSONObject a) {
            return ok(EnvInstaller.status());
        }});
        def("files_list", "列目录（共享存储）", schema(props("path", prop("string", "默认 /storage/emulated/0")), "path"), new H() { public JSONObject run(JSONObject a) throws Exception {
            File d = new File(a.optString("path", "/storage/emulated/0"));
            File[] fs = d.listFiles();
            if (fs == null) return err("NO_ACCESS", "目录不可读: " + d.getAbsolutePath());
            JSONArray arr = new JSONArray();
            for (File f : fs) {
                arr.put(new JSONObject().put("name", f.getName()).put("dir", f.isDirectory())
                        .put("size", f.length()).put("modified", f.lastModified()));
            }
            return ok(new JSONObject().put("path", d.getAbsolutePath()).put("items", arr));
        }});

        def("files_read_text", "读文本文件", schema(props("path", prop("string", "路径"), "maxKB", prop("number", "最多读 KB 默认 64")), "path"), new H() { public JSONObject run(JSONObject a) throws Exception {
            File f = new File(a.optString("path"));
            long max = a.optLong("maxKB", 64) * 1024;
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) Math.min(f.length(), max)];
            int n = in.read(b); in.close();
            return ok(new JSONObject().put("size", f.length()).put("truncated", f.length() > max)
                    .put("text", new String(b, 0, Math.max(0, n), "UTF-8")));
        }});

        def("files_write_text", "写文本文件（可新建）", schema(props("path", prop("string", "路径"),
                "content", prop("string", "内容"), "append", prop("boolean", "追加模式")), "path", "content"), new H() { public JSONObject run(JSONObject a) throws Exception {
            File f = new File(a.optString("path"));
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            FileOutputStream o = new FileOutputStream(f, a.optBoolean("append"));
            o.write(a.optString("content").getBytes("UTF-8")); o.close();
            MediaScannerConnection.scanFile(ctx, new String[]{f.getAbsolutePath()}, null, null);
            return ok(new JSONObject().put("path", f.getAbsolutePath()).put("size", f.length()));
        }});

        // ═══ Termux 联动（免插件！RUN_COMMAND 内置通道）═══
        def("flashlight", "手电筒开关", schema(props("on", prop("boolean", "true 开 / false 关")), "on"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
                String id = null;
                for (String cid : cm.getCameraIdList()) {
                    if (cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) { id = cid; break; }
                }
                if (id == null) return err("NO_FLASH", "没有闪光灯");
                cm.setTorchMode(id, a.optBoolean("on"));
                return ok("手电筒 " + (a.optBoolean("on") ? "开" : "关"));
            }});

        // ═══ 音量 / 媒体 / 亮度 ═══
        def("location_last", "最近位置（需定位权限）", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            android.location.LocationManager lm = (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            android.location.Location best = null;
            for (String p : new String[]{"gps", "network", "passive"}) {
                android.location.Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best == null) return err("NO_FIX", "暂无缓存位置");
            return ok(new JSONObject().put("lat", best.getLatitude()).put("lon", best.getLongitude())
                    .put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(best.getTime()))));
        }});

        def("media_play_pause", "媒体播放/暂停（控制正在放的歌/视频）", schema(props()), new H() { public JSONObject run(JSONObject a) {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            am.dispatchMediaKeyEvent(down); am.dispatchMediaKeyEvent(up);
            return ok("已发送播放/暂停键");
        }});

        def("network_info", "查网络：WiFi/蓝牙/飞行模式/流量开关（系统设置级免权限）+ 在线状态", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            JSONObject o = new JSONObject();
            o.put("wifiOn", Settings.Global.getInt(ctx.getContentResolver(), "wifi_on", 0) == 1);
            o.put("btOn", Settings.Global.getInt(ctx.getContentResolver(), "bluetooth_on", 0) == 1);
            o.put("airplane", Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
            o.put("mobileData", Settings.Global.getInt(ctx.getContentResolver(), "mobile_data", -1));
            try {
                ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
                o.put("online", nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                o.put("onWifi", nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
            } catch (Throwable e) { o.put("connectivityError", "无权读取"); }
            return ok(o);
        }});

        // ═══ 应用 ═══
        def("screenshot", "截取当前屏幕并保存（无障碍通道，返回 PNG 路径）", schema(props()), new H() { public JSONObject run(JSONObject a) {
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final String[] out = new String[2];
            AdbService.screenshot(new AdbService.ShotCb() { public void onShot(boolean ok, String msg) {
                out[0] = ok ? "1" : "0"; out[1] = msg; latch.countDown();
            }});
            try { latch.await(8, TimeUnit.SECONDS); } catch (Exception ignore) {}
            if (out[0] == null) return err("TIMEOUT", "截图超时");
            return out[0].equals("1") ? ok(out[1]) : err("SHOT_FAIL", out[1]);
        }});

        // ═══ 环境引擎（工作台自带 pi 环境）═══
        def("sms_list", "读最近短信（需短信权限）", schema(props("limit", prop("number", "条数默认 10"))), new H() { public JSONObject run(JSONObject a) throws Exception {
            Cursor c = ctx.getContentResolver().query(Uri.parse("content://sms/inbox"),
                    new String[]{"address", "body", "date"}, null, null, "date DESC");
            JSONArray arr = new JSONArray(); int n = a.optInt("limit", 10); int i = 0;
            if (c != null) {
                while (c.moveToNext() && i < n) {
                    arr.put(new JSONObject().put("from", c.getString(0)).put("body", c.getString(1))
                            .put("time", new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(c.getLong(2)))));
                    i++;
                }
                c.close();
            }
            return ok(arr);
        }});

        def("termux_run", "在 Termux 里执行命令（后台模式；命令须为绝对路径）",
            schema(props("path", prop("string", "可执行文件绝对路径，如 /data/data/com.termux/files/usr/bin/bash"),
                    "args", prop("array", "参数数组"), "background", prop("boolean", "默认 true 后台执行")),
                    "path"), new H() { public JSONObject run(JSONObject a) throws Exception {
                Intent i = new Intent();
                i.setClassName("com.termux", "com.termux.app.RunCommandService");
                i.setAction("com.termux.RUN_COMMAND");
                i.putExtra("com.termux.RUN_COMMAND_PATH", a.optString("path"));
                JSONArray ar = a.optJSONArray("args");
                if (ar != null) {
                    String[] ss = new String[ar.length()];
                    for (int k = 0; k < ar.length(); k++) ss[k] = ar.getString(k);
                    i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", ss);
                }
                i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", a.optBoolean("background", true));
                try {
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
                    else ctx.startService(i);
                    return ok("已发往 Termux");
                } catch (Exception e) {
                    return err("TERMUX_FAIL", e + "（检查 termux.properties 里 allow-external-apps=true）");
                }
            }});

        // ═══ UI 操控（需辅助服务开启）═══
        def("ui_back", "按返回键", schema(props()), new H() { public JSONObject run(JSONObject a) {
            return AdbService.global(AdbService.GLOBAL_BACK) ? ok("已返回") : err("NO_SERVICE", "辅助服务未开启");
        }});
        def("ui_home", "按主页键", schema(props()), new H() { public JSONObject run(JSONObject a) {
            return AdbService.global(AdbService.GLOBAL_HOME) ? ok("已回主页") : err("NO_SERVICE", "辅助服务未开启");
        }});
        def("ui_recents", "打开最近任务", schema(props()), new H() { public JSONObject run(JSONObject a) {
            return AdbService.global(AdbService.GLOBAL_RECENTS) ? ok("已打开最近任务") : err("NO_SERVICE", "辅助服务未开启");
        }});
        def("ui_screen_read", "读取屏幕控件树（结构化 JSON：编号/文本/ID/坐标/可点击性）。display=0 主屏；display=N 读隐形副屏（配合 vd 工具）；副屏建议传 pkg 过滤系统窗口串扰",
            schema(props("display", prop("number", "屏幕ID 默认0主屏；读副屏传 vd create 返回的 displayId"),
                    "pkg", prop("string", "只保留该包名的窗口节点（副屏强烈建议传，过滤系统窗口串扰）"))), new H() { public JSONObject run(JSONObject a) {
            String fp = a.optString("pkg", "");
            JSONArray nodes = AdbService.readTree(a.optInt("display", 0), fp.isEmpty() ? null : fp);
            if (nodes == null) return err("NO_SERVICE", "辅助服务未开启或该屏无活动窗口; diag=" + AdbService.diag);
            try { return ok(new JSONObject().put("count", nodes.length()).put("nodes", nodes)); }
            catch (Exception e) { return err("INTERNAL", e.toString()); }
        }});
        def("ui_find", "在结构树中查找全部含指定文本的节点（返回所有匹配的编号/坐标/可点击性），比 wait_node 更全面",
            schema(props("text", prop("string", "要找的文本"), "display", prop("number", "屏幕ID 默认0主屏")), "text"), new H() { public JSONObject run(JSONObject a) throws Exception {
            int disp = a.optInt("display", 0);
            JSONArray nodes = AdbService.readTree(disp);
            JSONArray hits = new JSONArray();
            if (nodes != null) for (int k = 0; k < nodes.length(); k++) {
                JSONObject n = nodes.optJSONObject(k);
                if (n == null) continue;
                if (n.optString("text","").contains(a.optString("text")) || n.optString("desc","").contains(a.optString("text"))) {
                    JSONObject h = new JSONObject();
                    try { h.put("i", n.optInt("i")).put("text", n.optString("text")).put("xy", n.optString("xy")).put("click", n.optBoolean("click")); } catch (Exception ignore) {}
                    hits.put(h);
                }
            }
            return ok(new JSONObject().put("matches", hits.length()).put("nodes", hits));
        }});
        def("notify_wait", "阻塞等待新通知（来微信/短信消息即刻返回，用于'消息来了叫我'场景）",
            schema(props("timeout_sec", prop("number", "最长等待 默认30秒"), "pkg", prop("string", "按包名过滤 可空"))), new H() { public JSONObject run(JSONObject a) throws Exception {
            File f = new File(ctx.getFilesDir(), "notify-log.json");
            long base = f.canRead() ? f.lastModified() : 0;
            long deadline = System.currentTimeMillis() + a.optInt("timeout_sec", 30) * 1000L;
            String pf = a.optString("pkg", "");
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1500);
                if (!f.canRead() || f.lastModified() <= base) continue;
                JSONArray arr = new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                JSONArray out = new JSONArray();
                for (int i = arr.length() - 1; i >= 0 && out.length() < 5; i--) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    if (!pf.isEmpty() && !o.optString("pkg","").contains(pf)) continue;
                    out.put(o);
                }
                if (out.length() > 0) return ok(new JSONObject().put("got", true).put("items", out));
            }
            return ok(new JSONObject().put("got", false).put("hint", "等待期内无新通知"));
        }});
        def("ui_find_tap", "查找并点击含指定文本的节点（自动重试等页面，宏的最佳搭档；display=0 主屏）",
            schema(props("text", prop("string", "要点击的节点文本"), "display", prop("number", "屏幕ID 默认0主屏"),
                    "retries", prop("number", "重试次数 默认4")), "text"), new H() { public JSONObject run(JSONObject a) throws Exception {
            int disp = a.optInt("display", 0);
            int retries = Math.max(a.optInt("retries", 4), 1);
            String target = a.optString("text");
            for (int attempt = 0; attempt < retries; attempt++) {
                if (attempt > 0) { try { Thread.sleep(1200); } catch (Exception ignore) {} }
                JSONArray nodes = AdbService.readTree(disp);
                if (nodes == null) continue;
                for (int k = 0; k < nodes.length(); k++) {
                    JSONObject n = nodes.optJSONObject(k);
                    if (n == null) continue;
                    if (n.optString("text","").contains(target) || n.optString("desc","").contains(target)) {
                        String[] parts = n.optString("xy").split(" ");
                        String[] lt = parts[0].split(",");
                        String[] wh = parts[1].split("x");
                        int x = Integer.parseInt(lt[0]) + Integer.parseInt(wh[0]) / 2;
                        int y = Integer.parseInt(lt[1]) + Integer.parseInt(wh[1]) / 2;
                        boolean okTap;
                        if (disp == 0) okTap = AdbService.tap(x, y);
                        else {
                            JSONObject c = (JSONObject) Tools.call("l2_exec", new JSONObject().put("cmd",
                                    "input -d " + disp + " tap " + x + " " + y));
                            okTap = c.optBoolean("ok", false);
                        }
                        if (okTap) return ok(new JSONObject().put("tapped", true).put("node", n.optString("text"))
                                .put("xy", x + "," + y).put("attempt", attempt + 1));
                    }
                }
            }
            return err("NOT_FOUND", "重试" + retries + "次未见可点击的『" + target + "』");
        }});

        def("settings_write", "原生设置写入（WRITE_SECURE_SETTINGS，免L2免界面）：ns=global/secure/system",
            schema(props("ns", prop("string", "global/secure/system"), "key", prop("string", "设置键"),
                    "value", prop("string", "值")), "ns", "key", "value"), new H() { public JSONObject run(JSONObject a) throws Exception {
            String ns = a.optString("ns", "global"), key = a.optString("key"), value = a.optString("value");
            boolean w;
            if ("secure".equals(ns)) w = Settings.Secure.putString(ctx.getContentResolver(), key, value);
            else if ("system".equals(ns)) w = Settings.System.putString(ctx.getContentResolver(), key, value);
            else w = Settings.Global.putString(ctx.getContentResolver(), key, value);
            String back;
            if ("secure".equals(ns)) back = Settings.Secure.getString(ctx.getContentResolver(), key);
            else if ("system".equals(ns)) back = Settings.System.getString(ctx.getContentResolver(), key);
            else back = Settings.Global.getString(ctx.getContentResolver(), key);
            return w ? ok(new JSONObject().put("key", key).put("value", value).put("readback", back)) : err("WRITE_FAIL", key);
        }});

        def("ui_tap_text", "按文本原生点击（无障碍节点ACTION_CLICK，主屏副屏通用，免L2）。比坐标点击更可靠的首选方式",
            schema(props("text", prop("string", "节点文本（含匹配）"), "display", prop("number", "屏幕ID 默认0主屏")), "text"), new H() { public JSONObject run(JSONObject a) {
            String r = AdbService.clickByText(a.optInt("display", 0), a.optString("text"));
            if (r.startsWith("已点击")) return ok(r);
            JSONObject ef = err("TAP_FAIL", r);
            try { ef.put("memory_hint", relatedMemories(AdbService.pkgOf(a.optInt("display", 0)))); } catch (Exception ignore) {}
            return ef;
        }});
        def("ui_scroll_node", "原生节点滚动（找可滚动容器执行滚动动作，免坐标免L2）",
            schema(props("display", prop("number", "屏幕ID 默认0主屏"), "forward", prop("boolean", "true=向下滚动 默认"))), new H() { public JSONObject run(JSONObject a) {
            String r = AdbService.scrollNode(a.optInt("display", 0), a.optBoolean("forward", true));
            return r.startsWith("已滚动") ? ok(r) : err("SCROLL_FAIL", r);
        }});

        def("ui_tap_node", "按结构树编号点击（先 ui_screen_read 得到编号 i，直接点击该节点中心，免算坐标；display=0 主屏，副屏传 displayId）",
            schema(props("i", prop("number", "节点编号"), "display", prop("number", "屏幕ID 默认0主屏")), "i"), new H() { public JSONObject run(JSONObject a) throws Exception {
            int disp = a.optInt("display", 0);
            JSONObject n = AdbService.nodeByIndex(disp, a.optInt("i", -1));
            if (n == null) return err("NO_NODE", "缓存树中无此编号，请先 ui_screen_read");
            String[] parts = n.optString("xy").split(" ");
            String[] lt = parts[0].split(",");
            String[] wh = parts[1].split("x");
            int x = Integer.parseInt(lt[0]) + Integer.parseInt(wh[0]) / 2;
            int y = Integer.parseInt(lt[1]) + Integer.parseInt(wh[1]) / 2;
            if (disp == 0) return AdbService.tap(x, y) ? ok("已点击节点#" + a.optInt("i") + " (" + x + "," + y + ")") : err("NO_SERVICE", "点击失败");
            JSONObject c = (JSONObject) Tools.call("l2_exec", new JSONObject().put("cmd", "input -d " + disp + " tap " + x + " " + y));
            return c.optBoolean("ok", false) ? ok("已点击副屏" + disp + "节点#" + a.optInt("i") + " (" + x + "," + y + ")") : err("INJECT_FAIL", String.valueOf(c));
        }});
        def("wait_node", "等待界面内容出现（页面加载/跳转后轮询树，找到即返回节点信息；比盲等可靠）",
            schema(props("text", prop("string", "等待出现的文本"), "display", prop("number", "屏幕ID 默认0主屏"),
                    "timeout_sec", prop("number", "最长等待 默认10秒")), "text"), new H() { public JSONObject run(JSONObject a) throws Exception {
            int disp = a.optInt("display", 0);
            long deadline = System.currentTimeMillis() + a.optInt("timeout_sec", 10) * 1000L;
            String target = a.optString("text");
            while (System.currentTimeMillis() < deadline) {
                AdbService.readTree(disp);
                JSONObject n = AdbService.findNodeByText(disp, target);
                if (n != null) return ok(new JSONObject().put("found", true).put("node", n));
                Thread.sleep(800);
            }
            return ok(new JSONObject().put("found", false).put("hint", "超时未出现，可能页面未加载或文本不符"));
        }});
        def("notify_read", "读取捕获的通知（微信/短信/物流等，最新在前）。首次使用需授权一次（返回 HINT 时转告用户去系统设置→通知使用权 勾选小丘）",
            schema(props("limit", prop("number", "条数 默认20"), "pkg", prop("string", "按包名过滤 如 com.tencent.mm"))), new H() { public JSONObject run(JSONObject a) throws Exception {
            File f = new File(ctx.getFilesDir(), "notify-log.json");
            java.util.List<org.json.JSONObject> list = new java.util.ArrayList<>();
            try {
                JSONArray arr = new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                for (int i = 0; i < arr.length(); i++) list.add(arr.optJSONObject(i));
            } catch (Exception ignore) {}
            String pf = a.optString("pkg", "");
            int limit = a.optInt("limit", 20);
            JSONArray out = new JSONArray();
            for (int i = list.size() - 1; i >= 0 && out.length() < limit; i--) {
                JSONObject o = list.get(i);
                if (o == null) continue;
                if (!pf.isEmpty() && !o.optString("pkg","").contains(pf)) continue;
                out.put(o);
            }
            if (list.isEmpty()) return err("HINT", "暂无捕获通知。请用户到 系统设置→通知使用权 允许小丘（或让用户在L2执行授权后发条通知测试）");
            return ok(new JSONObject().put("count", out.length()).put("items", out));
        }});
        def("notify_clear", "清空通知捕获缓冲", schema(props()), new H() { public JSONObject run(JSONObject a) {
            try { Tools.write(new File(ctx.getFilesDir(), "notify-log.json"), "[]"); } catch (Exception ignore) {}
            return ok("已清空");
        }});
        def("ui_list_extract", "列表批量抽取v2（信息流/商品/搜索结果）：自动滚动+去重合并；until_text 命中即停；连续空转自动早停",
            schema(props("display", prop("number", "屏幕ID 默认0主屏"),
                    "max_swipes", prop("number", "最多滑动次数 默认5"),
                    "contains", prop("string", "只保留含此关键词的条目 可空"),
                    "until_text", prop("string", "滚动直到此文本出现即停 可空"))), new H() { public JSONObject run(JSONObject a) throws Exception {
            int disp = a.optInt("display", 0);
            int maxS = Math.min(a.optInt("max_swipes", 5), 12);
            String kw = a.optString("contains", "");
            String until = a.optString("until_text", "");
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            JSONArray items = new JSONArray();
            JSONObject hit = null;
            int emptyRounds = 0;
            for (int round = 0; round <= maxS; round++) {
                JSONArray nodes = AdbService.readTree(disp);
                int fresh = 0;
                if (nodes != null) for (int k = 0; k < nodes.length(); k++) {
                    JSONObject n = nodes.optJSONObject(k);
                    if (n == null || !n.has("text")) continue;
                    String txt = n.optString("text").replace("\n", " ").trim();
                    if (txt.length() < 2 || seen.contains(txt)) continue;
                    if (!kw.isEmpty() && !txt.contains(kw)) continue;
                    seen.add(txt); fresh++;
                    JSONObject item = new JSONObject();
                    try { item.put("text", txt).put("xy", n.optString("xy")); } catch (Exception ignore) {}
                    items.put(item);
                }
                if (!until.isEmpty()) {
                    JSONObject n = AdbService.findNodeByText(disp, until);
                    if (n != null) { hit = n; break; }
                }
                if (round == maxS) break;
                if (fresh == 0) { emptyRounds++; if (emptyRounds >= 2) break; } else emptyRounds = 0;
                boolean moved;
                if (disp == 0) moved = AdbService.swipe(640, 2000, 640, 1000, 250);
                else {
                    JSONObject c = (JSONObject) Tools.call("l2_exec", new JSONObject().put("cmd",
                            "input -d " + disp + " swipe 450 1500 450 750"));
                    moved = c.optBoolean("ok", false);
                }
                if (!moved) break;
                try { Thread.sleep(900); } catch (Exception ignore) {}
            }
            JSONObject r = new JSONObject().put("items", items).put("count", items.length()).put("pkg", AdbService.pkgOf(disp));
            if (hit != null) try { r.put("until_hit", hit); } catch (Exception ignore) {}
            return ok(r);
        }});
        def("ui_page_info", "页面速览：前台包名/节点数/主要文本（快速判断当前在哪）",
            schema(props("display", prop("number", "屏幕ID 默认0主屏"))), new H() { public JSONObject run(JSONObject a) throws Exception {
            int disp = a.optInt("display", 0);
            JSONArray nodes = AdbService.readTree(disp);
            JSONObject o = new JSONObject();
            o.put("pkg", AdbService.pkgOf(disp));
            o.put("nodes", nodes == null ? 0 : nodes.length());
            JSONArray texts = new JSONArray();
            if (nodes != null) for (int k = 0; k < nodes.length() && texts.length() < 20; k++) {
                JSONObject n = nodes.optJSONObject(k);
                if (n != null && n.has("text")) texts.put(n.optString("text"));
            }
            o.put("texts", texts);
            // 自动附带该App的相关记忆（历史校准/特性）
            try {
                String pkg = AdbService.pkgOf(disp);
                if (!pkg.equals("?")) {
                    File mf = new File(ctx.getFilesDir(), "memory.json");
                    if (mf.canRead()) {
                        JSONObject mem = new JSONObject(new String(java.nio.file.Files.readAllBytes(mf.toPath()), "UTF-8"));
                        String sn = pkg.substring(pkg.lastIndexOf('.') + 1);
                        JSONArray rel = new JSONArray();
                        java.util.Iterator<String> it = mem.keys();
                        while (it.hasNext()) { String k = it.next(); if (k.contains(sn) || k.contains(pkg)) rel.put(k); }
                        if (rel.length() > 0) o.put("memories", rel);
                    }
                }
            } catch (Exception ignore) {}
            return ok(o);
        }});

        def("ui_set_text", "直接设置焦点输入框文本（display=0 主屏；副屏自动兜底：L2聚焦+IME自动切换还原+ADBKeyboard中文注入+回读验证；无可编辑节点时可传x/y千分比坐标指定输入框）",
            schema(props("text", prop("string", "要设置的文本"), "display", prop("number", "屏幕ID 默认0主屏"),
                    "x", prop("number", "可选：输入框位置千分比x（副屏无a11y可编辑节点时用）"),
                    "y", prop("number", "可选：输入框位置千分比y")), "text"), new H() { public JSONObject run(JSONObject a) throws Exception {
            int disp = a.optInt("display", 0);
            String txt = a.optString("text", "");
            String r = disp > 0 ? AdbService.setTextOnDisplay(disp, txt) : AdbService.setText(txt);
            if (r.equals("已设置")) return ok(r);
            // 副屏兜底：a11y写入无效时 → L2聚焦 + IME切换(存原IME,完事还原) + ADBKeyboard中文注入 + 回读验证
            if (disp > 0) {
                int[] c = AdbService.editableCenterOnDisplay(disp);
                if (c == null && a.has("x") && a.has("y")) {
                    int[] dsz = AdbService.displaySize(disp);
                    c = new int[]{ a.optInt("x") * dsz[0] / 1000, a.optInt("y") * dsz[1] / 1000 };
                }
                if (c == null) return err("SET_FAIL", r + "；副屏无可编辑节点(可传x/y坐标兜底)");
                JSONObject gi = (JSONObject) Tools.call("l2_exec", new JSONObject().put("cmd", "settings get secure default_input_method"));
                String gs = String.valueOf(gi);
                String prevIme = "com.sohu.inputmethod.sogou.xiaomi/.SogouIME";
                int p1 = gs.indexOf("\"data\"");
                if (p1 >= 0) {
                    int p2 = gs.indexOf('"', gs.indexOf(':', p1) + 1);
                    int p3 = gs.indexOf('"', p2 + 1);
                    if (p2 > 0 && p3 > p2) prevIme = gs.substring(p2 + 1, p3);
                }
                boolean needSwitch = !prevIme.contains("adbkeyboard");
                try {
                    if (needSwitch)
                        Tools.call("l2_exec", new JSONObject().put("cmd", "ime enable com.android.adbkeyboard/.AdbIME; ime set com.android.adbkeyboard/.AdbIME"));
                    Tools.call("l2_exec", new JSONObject().put("cmd", "input -d " + disp + " tap " + c[0] + " " + c[1]));
                    try { Thread.sleep(900); } catch (Exception ignore) {}
                    ctx.sendBroadcast(new android.content.Intent("ADB_INPUT_TEXT").putExtra("msg", txt));
                    try { Thread.sleep(800); } catch (Exception ignore) {}
                    String v = AdbService.readEditableTextOnDisplay(disp);
                    if (v != null && v.contains(txt)) return ok(new JSONObject().put("msg", "已设置(ADBKeyboard兜底)").put("text", v));
                    return err("SET_FAIL", r + "；兜底后回读=" + (v == null ? "null" : v));
                } finally {
                    if (needSwitch)
                        Tools.call("l2_exec", new JSONObject().put("cmd", "ime set " + prevIme));
                }
            }
            return err("SET_FAIL", r);
        }});
        def("ui_swipe", "模拟滑动", schema(props("x1", prop("number", "起x"), "y1", prop("number", "起y"),
                "x2", prop("number", "终x"), "y2", prop("number", "终y"), "ms", prop("number", "时长默认300")),
                "x1", "y1", "x2", "y2"), new H() { public JSONObject run(JSONObject a) {
            return AdbService.swipe(a.optInt("x1"), a.optInt("y1"), a.optInt("x2"), a.optInt("y2"), a.optLong("ms", 300))
                    ? ok("已滑动") : err("NO_SERVICE", "辅助服务未开启");
        }});

        // ═══ 感知与直设（比截图喂视觉更快更准）═══
        def("ui_tap", "模拟点击屏幕坐标", schema(props("x", prop("number", "x"), "y", prop("number", "y")), "x", "y"), new H() { public JSONObject run(JSONObject a) {
            return AdbService.tap(a.optInt("x"), a.optInt("y")) ? ok("已点击") : err("NO_SERVICE", "辅助服务未开启");
        }});
        def("vibrate", "震动", schema(props("ms", prop("number", "毫秒，默认 300"))), new H() { public JSONObject run(JSONObject a) {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            long ms = a.optLong("ms", 300);
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
            return ok("震动 " + ms + "ms");
        }});

        def("volume_get", "查音量（media/ring/alarm/call/notify）", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            JSONObject o = new JSONObject();
            int[][] st = {{3,2,4,0,5}};
            String[] names = {"media","ring","alarm","call","notify"};
            for (int i = 0; i < names.length; i++) {
                o.put(names[i], am.getStreamVolume(st[0][i]) + "/" + am.getStreamMaxVolume(st[0][i]));
            }
            return ok(o);
        }});

        def("volume_set", "设音量", schema(props("stream", prop("string", "media/ring/alarm/call/notify"),
                "percent", prop("number", "0-100")), "stream", "percent"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                Map<String,Integer> m = new LinkedHashMap<>();
                m.put("media",3); m.put("ring",2); m.put("alarm",4); m.put("call",0); m.put("notify",5);
                Integer st = m.get(a.optString("stream"));
                if (st == null) return err("BAD_STREAM", "stream 取值: media/ring/alarm/call/notify");
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(st);
                am.setStreamVolume(st, Math.max(0, Math.min(max, (int) Math.round(a.optDouble("percent") / 100.0 * max))), 0);
                return ok(am.getStreamVolume(st) + "/" + max);
            }});

        def("cfg_set", "写全局配置项", schema(props("key", prop("string", "键"), "value", prop("string", "值")), "key", "value"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                JSONObject c = loadCfg(); c.put(a.getString("key"), a.getString("value")); saveCfg(c);
                return ok("已保存");
            }});
        def("cfg_get", "读全局配置", schema(new JSONObject()), new H() { public JSONObject run(JSONObject a) throws Exception {
            return ok(loadCfg());
        }});
        def("env_install", "安装 pi 环境（若未就绪则后台自动：下载→解压→二阶段），完成后 pi 可用", schema(props()), new H() { public JSONObject run(JSONObject a) {
            if (EnvInstaller.isReady()) return ok("环境已就绪");
            EnvInstaller.installAsync(new EnvInstaller.Cb() {
                public void onEvent(String line) { android.util.Log.i("PiBridge", "env: " + line); }
                public void onDone(boolean ok, String msg) { android.util.Log.i("PiBridge", "env done: " + msg); }
            });
            return ok("安装已后台启动，可用 env_status 轮询");
        }});
        def("setkey", "保存 API Key 与默认模型（写入小丘的 auth.json/settings.json）",
            schema(props("provider", prop("string", "供应商，如 zai-coding-cn"),
                    "key", prop("string", "API Key"),
                    "model", prop("string", "默认模型，如 glm-5.3-flash")),
                    "provider", "key"), new H() { public JSONObject run(JSONObject a) throws Exception {
            String provider = a.optString("provider", "zai-coding-cn");
            String key = a.optString("key", "");
            String model = a.optString("model", "");
            if (key.length() < 8) return err("BAD", "Key 太短");
            File dir = new File(EnvInstaller.HOME, ".pi/agent");
            if (!dir.isDirectory()) dir.mkdirs();
            File af = new File(dir, "auth.json");
            JSONObject auth = new JSONObject();
            try { if (af.canRead()) auth = new JSONObject(readFile(af)); } catch (Exception ignore) {}
            auth.put(provider, new JSONObject().put("type", "api_key").put("key", key));
            write(af, auth.toString());
            if (model.length() > 0) {
                File sf = new File(dir, "settings.json");
                JSONObject st = new JSONObject();
                try { if (sf.canRead()) st = new JSONObject(readFile(sf)); } catch (Exception ignore) {}
                st.put("defaultProvider", provider).put("defaultModel", model);
                write(sf, st.toString());
            }
            return ok("已保存（" + provider + "，key 尾号 " + key.substring(Math.max(0, key.length() - 4)) + "）");
        }});
        // ═══ 设备感知（pi-api 能力原生吸收）═══
        def("sensors_read", "读取手机传感器瞬时值（加速度/磁场/光照/接近/重力）", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            android.hardware.SensorManager sm = (android.hardware.SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return err("NO_SENSOR", "无传感器服务");
            JSONObject out = new JSONObject();
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            android.hardware.SensorEventListener li = new android.hardware.SensorEventListener() {
                public void onSensorChanged(android.hardware.SensorEvent ev) {
                    try {
                        String t = ev.sensor.getStringType();
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < ev.values.length && i < 3; i++) sb.append(i > 0 ? "," : "").append(String.format("%.2f", ev.values[i]));
                        if (!out.has(t)) out.put(t, sb.toString());
                    } catch (Exception ignore) {}
                }
                public void onAccuracyChanged(android.hardware.Sensor s2, int ac) {}
            };
            String[] names = {"accelerometer", "magnetic", "light", "proximity", "gravity"};
            int[] types = {android.hardware.Sensor.TYPE_ACCELEROMETER, android.hardware.Sensor.TYPE_MAGNETIC_FIELD,
                    android.hardware.Sensor.TYPE_LIGHT, android.hardware.Sensor.TYPE_PROXIMITY, android.hardware.Sensor.TYPE_GRAVITY};
            boolean any = false;
            for (int i = 0; i < types.length; i++) {
                android.hardware.Sensor s2 = sm.getDefaultSensor(types[i]);
                if (s2 != null) { sm.registerListener(li, s2, android.hardware.SensorManager.SENSOR_DELAY_NORMAL); any = true; }
            }
            if (!any) return err("NO_SENSOR", "无可用传感器");
            try { latch.await(900, TimeUnit.MILLISECONDS); } catch (Exception ignore) {}
            sm.unregisterListener(li);
            JSONObject friendly = new JSONObject();
            java.util.Iterator<String> it = out.keys();
            while (it.hasNext()) {
                String k = it.next();
                String fk = k;
                for (String n : names) if (k.endsWith(n)) fk = n;
                friendly.put(fk, out.get(k));
            }
            return ok(friendly);
        }});
        def("mic_record", "麦克风录音 N 秒保存 WAV 16k（语音识别用）",
            schema(props("seconds", prop("number", "录音秒数默认10，上限120"))), new H() { public JSONObject run(JSONObject a) throws Exception {
                try { ctx.sendBroadcast(new android.content.Intent("com.pihost.MIC_BUSY").putExtra("on", true)); } catch (Exception ignore) {}
            int sec = a.optInt("seconds", 10);
            if (sec < 2) sec = 2; if (sec > 120) sec = 120;
            File wav = WavUtil.record(ctx, sec);
            try { ctx.sendBroadcast(new android.content.Intent("com.pihost.MIC_BUSY").putExtra("on", false)); } catch (Exception ignore) {}
        return ok(wav.getAbsolutePath() + " " + wav.length() + "B");
        }});
        def("voice_chat", "语音对话（VAD 自动断句 + 持续会话上下文）：说完自动停→识别→pi 回答",
            schema(props("max_seconds", prop("number", "最长录音秒默认20"))), new H() { public JSONObject run(JSONObject a) throws Exception {
            int maxSec = a.optInt("max_seconds", 20);
            if (maxSec < 4) maxSec = 4; if (maxSec > 60) maxSec = 60;
            File wav = WavUtil.recordVad(ctx, maxSec, 2, 1200);
            JSONObject sttRes = Tools.call("stt_transcribe", new JSONObject().put("file", wav.getAbsolutePath()));
            JSONObject sttEnv = sttRes.optJSONObject("structured") != null ? sttRes.getJSONObject("structured") : sttRes;
            String heard = "";
            Object d = sttEnv.opt("data");
            if (d instanceof String) heard = (String) d;
            else if (d instanceof JSONObject) heard = ((JSONObject) d).optString("text", "");
            if (heard.isEmpty() || heard.startsWith("("))
                return ok(new JSONObject().put("heard", "").put("reply", "没听清，请靠近手机再说一遍"));
            heard = heard.replace("'", "'\''");
            JSONObject piRes = Tools.call("env_run", new JSONObject()
                .put("cmd", "cd ~ && pi -p --session-id xiaoqiu-voice '" + heard + "'")
                .put("timeout_sec", 150));
            JSONObject pd = piRes.optJSONObject("data") != null ? piRes.getJSONObject("data") : new JSONObject();
            String reply = pd.optString("output", "pi 无响应");
            return ok(new JSONObject().put("heard", heard).put("reply", reply));
        }});

        def("stt_transcribe", "语音转文字（引擎可配：local/cloud，输入 WAV 路径）",
            schema(props("file", prop("string", "WAV 文件路径（16k 单声道）")), "file"), new H() { public JSONObject run(JSONObject a) throws Exception {
            String file = a.optString("file", "");
            if (file.isEmpty()) return err("BAD", "缺文件路径");
            if ("cloud".equals(loadCfg().optString("stt_engine", "local"))) {
                String txt = cloudStt(new File(file));
                if (txt != null && !txt.trim().isEmpty()) return ok(txt.trim());
                return err("CLOUD_STT_FAIL", "云端识别失败（已配置仅云端）");
            }
            File mf = new File(EnvInstaller.HOME + "/sherpa/model/model.int8.onnx");
            File tk = new File(EnvInstaller.HOME + "/sherpa/model/tokens.txt");
            if (!mf.exists() || !tk.exists()) return err("NO_MODEL", "模型未下载（239M，稍候自动完成）");
            if (sttRec == null) {
                String nat = ctx.getApplicationInfo().nativeLibraryDir;
                System.load(nat + "/libonnxruntime.so");
                System.load(nat + "/libsherpa-onnx-jni.so");
                com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig sv =
                        new com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig(
                                mf.getAbsolutePath(), "", false, new com.k2fsa.sherpa.onnx.QnnConfig());
                com.k2fsa.sherpa.onnx.OfflineModelConfig mc = new com.k2fsa.sherpa.onnx.OfflineModelConfig();
                mc.setSenseVoice(sv);
                mc.setTokens(tk.getAbsolutePath());
                mc.setNumThreads(2);
                mc.setDebug(true);
                com.k2fsa.sherpa.onnx.OfflineRecognizerConfig cfg = new com.k2fsa.sherpa.onnx.OfflineRecognizerConfig();
                cfg.setModelConfig(mc);
                cfg.setDecodingMethod("greedy_search");
                sttRec = new com.k2fsa.sherpa.onnx.OfflineRecognizer(null, cfg);
            }
            float[] samples = WavUtil.readWav(file);
            com.k2fsa.sherpa.onnx.OfflineStream st = sttRec.createStream();
            st.acceptWaveform(samples, 16000);
            sttRec.decode(st);
            String text = sttRec.getResult(st).getText();
            st.release();
            return ok(text.isEmpty() ? "(未识别到语音)" : text);
        }});

        // ═══ 权限中心（向导/设置页数据源）═══
        def("perm_status", "查询小丘权限与环境状态（无障碍/悬浮窗/所有文件/队列桥/环境）", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            JSONObject o = new JSONObject();
            String enabled = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            o.put("accessibility", enabled != null && enabled.contains("com.pihost"));
            boolean overlay = false;
            try { overlay = Settings.canDrawOverlays(ctx); } catch (Throwable ignore) {}
            o.put("overlay", overlay);
            boolean allFiles = false;
            try { allFiles = (Build.VERSION.SDK_INT < 30) || android.os.Environment.isExternalStorageManager(); } catch (Throwable ignore) {}
            o.put("allFiles", allFiles);
            File q = new File("/storage/emulated/0/Download/pibridge-queue");
            o.put("queueBridge", q.isDirectory());
            // 通知使用权
            try {
                android.app.NotificationManager nm = (android.app.NotificationManager) ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
                android.content.ComponentName[] cn = nm.getEnabledNotificationListeners();
                boolean hasNotif = false;
                if (cn != null) for (android.content.ComponentName x : cn) if (x.getPackageName().equals("com.pihost")) hasNotif = true;
                o.put("notification", hasNotif);
            } catch (Throwable ignore) { o.put("notification", false); }
            JSONObject env = EnvInstaller.status();
            o.put("envReady", env.optBoolean("ready", false));
            File pui = new File("/data/data/com.pihost/files/home/.pi/agent/npm/node_modules/pi-web-ui/bin/pi-web-ui.mjs");
            o.put("webui", pui.exists());
            o.put("floatball", com.binbin.pibridge.FloatBall.isOn());
            return ok(o);
        }});
        def("open_permission_settings", "打开指定权限的系统设置页", schema(props("type", prop("string", "a11y=无障碍 overlay=悬浮窗 allfiles=所有文件")), "type"), new H() { public JSONObject run(JSONObject a) throws Exception {
            String t = a.optString("type", "");
            Intent i;
            if (t.equals("a11y")) i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            else if (t.equals("overlay")) {
                i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:com.pihost"));
            } else if (t.equals("allfiles")) {
                i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:com.pihost"));
            } else return err("BAD", "type 须为 a11y/overlay/allfiles");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return ok("已打开设置页");
        }});

        // ═══ L2 特权通道（经队列桥 → ZeroTermux → adbc shell，shell 权限）═══
        def("l2_exec", "以 shell 特权执行系统命令（静默授权/系统设置/输入注入/强制停止应用等）。普通命令请用 env_run",
            schema(props("cmd", prop("string", "特权命令"), "timeout_sec", prop("number", "超时秒默认30")), "cmd"), new H() { public JSONObject run(JSONObject a) throws Exception {
            String cmd = a.optString("cmd", "");
            if (cmd.contains("\n") || cmd.isEmpty()) return err("BAD", "命令需单行且非空");
            // 危险命令护栏（防误操作毁机；命中即拒）
            String slash = String.valueOf('/');
            String[] danger = {"rm -rf " + slash, "reboot", "shutdown", "mkfs", "dd if=", "burn"};
            for (String d : danger) if (cmd.contains(d)) return err("DANGEROUS", "已拦截危险命令片段");
            if (cmd.contains("pm uninstall com.pihost")) return err("DANGEROUS", "不允许卸载小丘自己");
            int timeout = a.optInt("timeout_sec", 30);
            if (timeout < 5) timeout = 5; if (timeout > 300) timeout = 300;
            File qdir = new File("/storage/emulated/0/Download/pibridge-queue");
            if (!qdir.isDirectory()) return err("NO_QUEUE", "队列桥未部署（ZeroTermux 侧缺失）");
            String id = String.valueOf(System.currentTimeMillis());
            File cmdFile = new File(qdir, "l2-cmd-" + id + ".sh");
            write(cmdFile, "#!/system/bin/sh\n" + cmd + "\n");
            cmdFile.setReadable(true, false); cmdFile.setExecutable(true, false);
            File job = new File(qdir, "l2-" + id + ".cmd");
            write(job, "adbc shell sh /storage/emulated/0/Download/pibridge-queue/l2-cmd-" + id + ".sh"
                    + " > /storage/emulated/0/Download/pibridge-queue/l2-out-" + id + ".txt 2>&1\n"
                    + "echo __DONE >> /storage/emulated/0/Download/pibridge-queue/l2-out-" + id + ".txt\n"
                    + "chmod 644 /storage/emulated/0/Download/pibridge-queue/l2-out-" + id + ".txt\n");
            job.setReadable(true, false);
            // 轮询输出
            File out = new File(qdir, "l2-out-" + id + ".txt");
            long deadline = System.currentTimeMillis() + timeout * 1000L;
            while (System.currentTimeMillis() < deadline) {
                if (out.canRead()) {
                    String s = readFile(out);
                    if (s.contains("__DONE")) {
                        s = s.replace("__DONE", "").trim();
                        return ok(s.isEmpty() ? "(无输出，执行成功)" : (s.length() > 8000 ? s.substring(0, 8000) : s));
                    }
                }
                Thread.sleep(1000);
            }
            // 自愈：adbd 端口轮换/假死 → app 内软重启无线调试（无需 adbc），下次 l2_exec 通常即恢复
            try {
                android.provider.Settings.Global.putInt(ctx.getContentResolver(), "adb_wifi_enabled", 0);
                Thread.sleep(1500);
                android.provider.Settings.Global.putInt(ctx.getContentResolver(), "adb_wifi_enabled", 1);
                Log.w("PiBridge", "l2_exec超时，已触发adbd软重启自愈");
            } catch (Exception ignore2) {}
            return err("TIMEOUT", "特权执行超时（已触发adbd软重启自愈，10秒后重试通常恢复）");
        }});

        def("floatball", "开关小丘悬浮球（on=开 off=关 缺省=切换）", schema(props("on", prop("string", "on/off，缺省切换"))), new H() { public JSONObject run(JSONObject a) {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx))
                return err("NO_OVERLAY", "请先授权悬浮窗");
            String on = a.optString("on", "toggle");
            if (on.equals("on") || (on.equals("toggle") && !FloatBall.isOn())) FloatBall.showAsync(ctx);
            else if (on.equals("off") || on.equals("toggle")) FloatBall.hideAsync(ctx);
            return ok("指令已发，当前：" + (FloatBall.isOn() ? "开" : "关"));
        }});

        def("env_run", "在工作台 pi 环境内执行 shell 命令（pi/node/npm/全部 Linux 工具可用），返回输出", 
            schema(props("cmd", prop("string", "命令"), "timeout_sec", prop("number", "超时秒默认120")), "cmd"), new H() { public JSONObject run(JSONObject a) throws Exception {
            if (!EnvInstaller.isReady()) return err("NOT_READY", "环境未就绪，先调 env_install");
            String cmd = a.optString("cmd", "");
            int timeout = a.optInt("timeout_sec", 120);
            if (timeout < 5) timeout = 5; if (timeout > 900) timeout = 900;
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
            pb.directory(new java.io.File(EnvInstaller.HOME));
            java.util.Map<String, String> env = pb.environment();
            env.put("HOME", EnvInstaller.HOME);
            env.put("PREFIX", EnvInstaller.PREFIX);
            env.put("PATH", EnvInstaller.PREFIX + "/bin:" + EnvInstaller.PREFIX + "/bin/applets:/system/bin");
            env.put("LD_LIBRARY_PATH", EnvInstaller.PREFIX + "/lib");
            env.put("TMPDIR", EnvInstaller.PREFIX + "/tmp");
            env.put("LANG", "en_US.UTF-8");
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try {
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (sb) { if (sb.length() < 16000) sb.append(line).append('\n'); }
                    }
                } catch (Exception ignore) {}
            });
            reader.start();
            if (!p.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); return err("TIMEOUT", "超时（已部分输出）\n" + sb); }
            reader.join(3000);
            String out = sb.toString();
            if (out.length() > 12000) out = out.substring(0, 12000) + "\n…(截断)";
            return ok(new JSONObject().put("exit", p.exitValue()).put("output", out));
        }});

        def("app_request_permission", "弹出系统授权对话框（用于设置页不显示的自定义权限，如 Termux 命令）",
            schema(props("permission", prop("string", "权限全名")), "permission"), new H() { public JSONObject run(JSONObject a) throws Exception {
                Intent i = new Intent(ctx, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                i.putExtra("req_perm", a.optString("permission"));
                ctx.startActivity(i);
                return ok("已在手机上弹出授权对话框，请点允许");
            }});

        def("termux_queue", "把 bash 脚本投到队列桥，Termux 每分钟自动执行（无需 RUN_COMMAND 权限，确定性 100%）",
            schema(props("script", prop("string", "bash 脚本内容")), "script"), new H() { public JSONObject run(JSONObject a) throws Exception {
                File dir = new File("/storage/emulated/0/Download/pibridge-queue");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, System.currentTimeMillis() + ".cmd");
                FileOutputStream o = new FileOutputStream(f);
                o.write(("#!/data/data/com.termux/files/usr/bin/bash\n" + a.optString("script") + "\n").getBytes("UTF-8"));
                o.close();
                return ok(new JSONObject().put("file", f.getName()).put("latency", "≤1分钟").put("log", "Termux: $PREFIX/tmp/queue-runner.log"));
            }});

        def("tools_list", "列出全部可用工具（fmt=full 返回含参数 schema 的富对象，供表单化 UI）", schema(props("fmt", prop("string", "full=返回{name,desc,schema}"))), new H() { public JSONObject run(JSONObject a) {
            if ("full".equals(a.optString("fmt"))) {
                JSONArray arr = new JSONArray();
                for (Map.Entry<String, Tool> e : REG.entrySet()) {
                    try { arr.put(new JSONObject().put("name", e.getKey()).put("desc", e.getValue().desc).put("schema", e.getValue().schema)); } catch (Exception ignore) {}
                }
                return ok(arr);
            }
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, Tool> e : REG.entrySet()) arr.put(e.getKey() + " — " + e.getValue().desc);
            return ok(arr);
        }});
    }

    static synchronized boolean ttsInit() {
        if (ttsReady) return true;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 主线程：不能 await（onInit 也投递在主线程→死锁）。异步初始化，本次先失败，预热线程通常会先行完成
            if (tts == null) tts = new TextToSpeech(ctx, st -> { ttsReady = (st == TextToSpeech.SUCCESS); });
            return false;
        }
        final CountDownLatch l = new CountDownLatch(1);
        final boolean[] ok = {false};
        tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
            public void onInit(int st) { ok[0] = (st == TextToSpeech.SUCCESS); ttsReady = ok[0]; l.countDown(); }
        });
        try { l.await(3, TimeUnit.SECONDS); } catch (Exception ignore) {}
        ttsReady = ok[0];
        if (ttsReady) {
            try { tts.setLanguage(new Locale("zh", "CN")); } catch (Exception ignore) {}
            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { if ("pi".equals(id)) fireSpeakDone(); }
                @Override public void onError(String id) { if ("pi".equals(id)) fireSpeakDone(); }
            });
        }
        return ttsReady;
    }

    static String ipStr(int ip) {
        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
    }

    /** IntentFilter 不能用匿名类继承简写，补个小类 */

    static com.k2fsa.sherpa.onnx.OfflineRecognizer sttRec;

    static boolean rmRecur(File f) {
        if (f == null || !f.exists()) return false;
        if (f.isDirectory()) { File[] cs = f.listFiles(); if (cs != null) for (File c : cs) rmRecur(c); }
        return f.delete();
    }

    private static volatile boolean doneInit = false;

    /** 全局配置 KV（files/cfg.json）：引擎选择等 */
    static JSONObject loadCfg() {
        try { return new JSONObject(readFile(new File(ctx.getFilesDir(), "cfg.json"))); }
        catch (Exception e) { return new JSONObject(); }
    }
    static void saveCfg(JSONObject c) {
        try { write(new File(ctx.getFilesDir(), "cfg.json"), c.toString()); } catch (Exception ignore) {}
    }

    static String readFile(File f) throws Exception {
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder(); String l;
        while ((l = br.readLine()) != null) sb.append(l);
        br.close();
        return sb.toString();
    }

    static void write(File f, String s) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(s.getBytes("UTF-8"));
        fo.close();
    }
}
