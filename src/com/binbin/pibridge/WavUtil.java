package com.binbin.pibridge;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** WAV 录音与读取（16k 单声道 16bit PCM，供语音识别用） */
public class WavUtil {
    private static final int RATE = 16000;

    public static File record(Context c, int seconds) throws Exception {
        int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuf, RATE * 2 * 2));
        if (ar.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
            ar.release();
            throw new IllegalStateException("麦克风初始化失败（检查 RECORD_AUDIO 权限与 MIUI 麦克风开关）");
        }
        int totalSamples = RATE * seconds;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(totalSamples * 2);
        short[] chunk = new short[RATE / 2];
        ar.startRecording();
        int got = 0;
        while (got < totalSamples) {
            int n = ar.read(chunk, 0, Math.min(chunk.length, totalSamples - got));
            if (n <= 0) break;
            for (int i = 0; i < n; i++) {
                pcm.write(chunk[i] & 0xFF);
                pcm.write((chunk[i] >> 8) & 0xFF);
            }
            got += n;
        }
        ar.stop();
        ar.release();

        File dir = new File("/storage/emulated/0/Download/pibridge");
        if (!dir.isDirectory()) dir.mkdirs();
        File wav = new File(dir, "cap-" + System.currentTimeMillis() + ".wav");
        byte[] data = pcm.toByteArray();
        writeWav(wav, data, RATE, 1, 16);
        return wav;
    }

    public static void writeWav(File f, byte[] pcmData, int rate, int channels, int bits) throws IOException {
        int blockAlign = channels * bits / 8;
        int dataLen = pcmData.length;
        FileOutputStream fo = new FileOutputStream(f);
        DataOutputStream o = new DataOutputStream(fo);
        o.writeBytes("RIFF"); o.writeInt(Integer.reverseBytes(36 + dataLen));
        o.writeBytes("WAVE"); o.writeBytes("fmt ");
        o.writeInt(Integer.reverseBytes(16)); o.writeShort(Short.reverseBytes((short) 1));
        o.writeShort(Short.reverseBytes((short) channels)); o.writeInt(Integer.reverseBytes(rate));
        o.writeInt(Integer.reverseBytes(rate * blockAlign)); o.writeShort(Short.reverseBytes((short) blockAlign));
        o.writeShort(Short.reverseBytes((short) bits));
        o.writeBytes("data"); o.writeInt(Integer.reverseBytes(dataLen));
        o.write(pcmData);
        o.close();
    }

    /** 读 WAV（16bit PCM），返回归一化 float 样本与采样率 */

    /** VAD 录音：说完自动停（静音 1.2s 结束），最少 minSec 秒，最长 maxSec 秒 */
    public static File recordVad(Context c, int maxSec, int minSec, int silenceMs) throws Exception {
        int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuf, RATE * 2 * 2));
        if (ar.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
            ar.release();
            throw new IllegalStateException("麦克风初始化失败");
        }
        int chunkSamples = RATE / 10; // 100ms
        short[] chunk = new short[chunkSamples];
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(maxSec * RATE * 2);
        ar.startRecording();
        long start = System.currentTimeMillis();
        int speechChunks = 0, silenceChunks = 0, totalChunks = 0;
        boolean heardSpeech = false;
        int ambient = 0;
        // 环境音采样（前 400ms）
        for (int i = 0; i < 4; i++) {
            int n = ar.read(chunk, 0, chunkSamples);
            long sum = 0;
            for (int j = 0; j < n; j++) sum += (long) chunk[j] * chunk[j];
            ambient += (int) Math.sqrt(sum / Math.max(1, n));
        }
        ambient /= 4;
        int speechThreshold = Math.max(ambient * 2 + 150, 400);
        int silenceThreshold = Math.max(ambient + 100, 300);
        while (totalChunks < maxSec * 10) {
            int n = ar.read(chunk, 0, chunkSamples);
            if (n <= 0) continue;
            long sum = 0;
            for (int j = 0; j < n; j++) sum += (long) chunk[j] * chunk[j];
            int rms = (int) Math.sqrt(sum / Math.max(1, n));
            boolean isSpeech = rms > speechThreshold;
            if (isSpeech) { heardSpeech = true; silenceChunks = 0; }
            else if (heardSpeech) silenceChunks++;
            for (int j = 0; j < n; j++) {
                pcm.write(chunk[j] & 0xFF);
                pcm.write((chunk[j] >> 8) & 0xFF);
            }
            totalChunks++;
            // 说完话 + 静音 1.2s + 已录超 minSec → 结束
            if (heardSpeech && totalChunks * 100 > minSec * 1000 && silenceChunks >= silenceMs / 100) break;
            // 无人声 5 秒快速退出
            if (!heardSpeech && totalChunks * 100 >= 5000) break;
        }
        ar.stop();
        ar.release();
        File dir = new File("/storage/emulated/0/Download/pibridge");
        if (!dir.isDirectory()) dir.mkdirs();
        File wav = new File(dir, "vad-" + System.currentTimeMillis() + ".wav");
        byte[] data = pcm.toByteArray();
        writeWav(wav, data, RATE, 1, 16);
        return wav;
    }

    /** 按住说话模式：持续录音直到 stop 置位（上限 maxSec 秒） */
    public static File recordUntil(Context c, java.util.concurrent.atomic.AtomicBoolean stop, int maxSec) throws Exception {
        int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuf, RATE * 2 * 2));
        if (ar.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
            ar.release();
            throw new IllegalStateException("麦克风初始化失败");
        }
        int totalSamples = RATE * maxSec;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(totalSamples * 2);
        short[] chunk = new short[RATE / 5];
        ar.startRecording();
        long start = System.currentTimeMillis();
        while (!stop.get() && (System.currentTimeMillis() - start) < maxSec * 1000L) {
            int n = ar.read(chunk, 0, chunk.length);
            if (n > 0) {
                for (int i = 0; i < n; i++) {
                    pcm.write(chunk[i] & 0xFF);
                    pcm.write((chunk[i] >> 8) & 0xFF);
                }
            }
        }
        ar.stop();
        ar.release();
        File dir = new File("/storage/emulated/0/Download/pibridge");
        if (!dir.isDirectory()) dir.mkdirs();
        File wav = new File(dir, "hold-" + System.currentTimeMillis() + ".wav");
        writeWav(wav, pcm.toByteArray(), RATE, 1, 16);
        return wav;
    }

    /** 连续对话录音：等说话→自动断句（静音1.2秒）→返回wav；6秒无人声返回null */
    public static File recordAutoStop(Context c, int maxSec) throws Exception { return recordAutoStop(c, maxSec, 6000); }

    public static volatile boolean abortRecord = false; // 会话停止钮：立即中止录音
    public static void abortAll() { abortRecord = true; }

    /** noSpeechMs：等待人声的超时（超时返回 null=本轮没人说话）——唤醒会话用 3000（3秒静默收尾） */
    public static File recordAutoStop(Context c, int maxSec, int noSpeechMs) throws Exception {
        abortRecord = false;
        int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuf, RATE * 2 * 2));
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) { ar.release(); throw new IllegalStateException("麦克风初始化失败"); }
        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        java.util.ArrayDeque<byte[]> pre = new java.util.ArrayDeque<>(); // 预滚：保留说话前0.6秒
        short[] chunk = new short[RATE / 10];
        ar.startRecording();
        long t0 = System.currentTimeMillis();
        int state = 0, speechWin = 0, silentAfter = 0; // 0=等说话 1=说话中
        try {
            while (System.currentTimeMillis() - t0 < maxSec * 1000L) {
                if (abortRecord) { try { ar.stop(); ar.release(); } catch (Exception ignore) {} return null; } // 停止钮：录音即断
                int n = ar.read(chunk, 0, chunk.length);
                if (n <= 0) continue;
                int peak = 0;
                for (int i = 0; i < n; i++) { int a = Math.abs(chunk[i]); if (a > peak) peak = a; }
                byte[] bytes = new byte[n * 2];
                for (int i = 0; i < n; i++) { bytes[i*2] = (byte)(chunk[i] & 255); bytes[i*2+1] = (byte)((chunk[i] >> 8) & 255); }
                if (state == 0) {
                    pre.addLast(bytes);
                    while (pre.size() > 6) pre.removeFirst();
                    if (peak > 1500) { speechWin++; if (speechWin >= 2) { state = 1; for (byte[] p : pre) pcm.write(p, 0, p.length); pre.clear(); } }
                    else speechWin = 0;
                    if (state == 0 && System.currentTimeMillis() - t0 > noSpeechMs) return null; // 无人声超时=本轮没说话
                } else {
                    pcm.write(bytes, 0, bytes.length);
                    if (peak < 800) { if (++silentAfter >= 9) break; } // 静音0.9秒=说完（提速执行感）
                    else silentAfter = 0;
                }
            }
        } finally { ar.stop(); ar.release(); }
        if (state == 0 || pcm.size() < RATE) return null;
        File dir = new File("/storage/emulated/0/Download/pibridge");
        if (!dir.isDirectory()) dir.mkdirs();
        File wav = new File(dir, "convo-" + System.currentTimeMillis() + ".wav");
        writeWav(wav, pcm.toByteArray(), RATE, 1, 16);
        return wav;
    }

    /** wav → float[]（readWav 别名，声纹用） */
    public static float[] readWavF(File f, int[] rateOut) throws IOException { return readWav(f, rateOut); }

    public static float[] readWav(File f, int[] rateOut) throws IOException {
        FileInputStream fi = new FileInputStream(f);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = fi.read(b)) > 0) all.write(b, 0, n);
        fi.close();
        byte[] d = all.toByteArray();
        // 解析 RIFF
        int pos = 12, rate = 16000, dataPos = -1, dataLen = 0;
        while (pos + 8 <= d.length) {
            String id = new String(d, pos, 4);
            int sz = (d[pos+4]&0xFF) | ((d[pos+5]&0xFF)<<8) | ((d[pos+6]&0xFF)<<16) | ((d[pos+7]&0xFF)<<24);
            if (id.equals("fmt ")) { rate = (d[pos+12]&0xFF) | ((d[pos+13]&0xFF)<<8) | ((d[pos+14]&0xFF)<<16) | ((d[pos+15]&0xFF)<<24); }
            if (id.equals("data")) { dataPos = pos + 8; dataLen = sz; break; }
            pos += 8 + sz + (sz % 2);
        }
        if (dataPos < 0) throw new IOException("非 WAV 文件");
        int samples = dataLen / 2;
        float[] out = new float[samples];
        for (int i = 0; i < samples; i++) {
            int lo = d[dataPos + i*2] & 0xFF, hi = d[dataPos + i*2 + 1];
            out[i] = (short)((hi << 8) | lo) / 32768.0f;
        }
        if (rateOut != null && rateOut.length > 0) rateOut[0] = rate;
        return out;
    }

    public static float[] readWav(String path) throws IOException {
        return readWav(new File(path), null);
    }
}
