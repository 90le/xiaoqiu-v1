package com.binbin.pibridge;

import android.os.Build;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 环境引擎：工作台自带 pi 环境安装器（吸收自 TermuxInstaller 思路，无 gradle/NDK 依赖）。
 * 流程：取 zip（共享存储优先 → GitHub 下载）→ 解压 staging（文件统一 0700）→
 * SYMLINKS.txt 建链（格式：目标←链接位置，U+2190）→ 改名转正 → 跑 login 二阶段。
 * 包名 com.pihost（10 字符）与 bootstrap 内路径字节兼容。
 */
public class EnvInstaller {
    public static final String APP_ID = "com.pihost";
    public static final String PREFIX = "/data/data/com.pihost/files/usr";
    public static final String STAGING = PREFIX + "-staging";
    public static final String HOME = "/data/data/com.pihost/files/home";
    public static final String URL_ZIP =
            "https://github.com/90le/piark/releases/download/v0.1.0-bootstrap/pi-bootstrap-aarch64-v1.0-pihost.zip";

    public interface Cb {
        void onEvent(String line);
        void onDone(boolean ok, String msg);
    }

    private static volatile boolean running = false;

    public static boolean isReady() {
        return new File(PREFIX + "/bin/pi").canExecute();
    }

    public static boolean isRunning() { return running; }

    public static void installAsync(final Cb cb) {
        if (running) { cb.onDone(false, "已有安装任务在跑"); return; }
        // pi 存在但引擎可能不存在（清数据后 bootstrap 恢复了 pi 但 bundle 未解压）
        File webuiFile = new File("/data/data/com.pihost/files/home/.pi/agent/npm/node_modules/pi-web-ui/dist/server/index.js");
        if (isReady() && webuiFile.exists()) { cb.onDone(true, "环境已就绪"); return; }
        if (isReady() && !webuiFile.exists()) {
            // pi 就绪但引擎缺失 → 只跑 home-bundle 解压（跳过 bootstrap）
            running = true;
            new Thread(() -> {
                try {
                    expandHomeBundle();
                    running = false;
                    File check = new File("/data/data/com.pihost/files/home/.pi/agent/npm/node_modules/pi-web-ui/dist/server/index.js");
                    cb.onDone(check.exists(), check.exists() ? "离线引擎恢复完成" : "引擎解压失败");
                } catch (Exception e) {
                    running = false;
                    cb.onDone(false, "引擎恢复失败: " + e.getMessage());
                }
            }, "engine-restore").start();
            return;
        }
        running = true;
        new Thread(() -> {
            try {
                File zip = obtainZip(cb);
                cb.onEvent("解压中（2.1 万文件，约 1-2 分钟）…");
                extract(zip, cb);
                cb.onEvent("建立符号链接…");
                symlinks();
                File st = new File(STAGING);
                if (!st.renameTo(new File(PREFIX))) throw new Exception("staging 改名失败（转正失败）");
                cb.onEvent("运行引导二阶段（dpkg 配置）…");
                secondStage(cb);
                if (!isReady()) throw new Exception("安装后 pi 仍不可执行");
                markReady();
                // 离线引擎 bundle 解压（bootstrap 只有 Node 运行时，引擎在此恢复）
                expandHomeBundle();
                cb.onDone(true, "pi 环境安装完成（" + PREFIX + "）");
            } catch (Exception e) {
                cb.onDone(false, e.toString());
            } finally {
                running = false;
            }
        }, "env-install").start();
    }

    private static void markReady() {
        try {
            File f = new File("/data/data/com.pihost/files/envReady");
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(String.valueOf(System.currentTimeMillis()).getBytes());
            fo.close();
        } catch (Exception ignore) {}
        // 家庭包：从 APK assets 解压完整离线引擎
        selfGrantViaQueue();
    }

    /** L2 自举：经队列桥让 ZeroTermux 用 adbc 特权恢复小丘的全部权限（免手动） */
    private static void selfGrantViaQueue() {
        try {
            File q = new File("/storage/emulated/0/Download/pibridge-queue");
            if (!q.isDirectory()) { android.util.Log.w("PiBridge", "队列桥未部署，跳过自举授权"); return; }
            String ts = String.valueOf(System.currentTimeMillis());
            File job = new File(q, "selfgrant-" + ts + ".cmd");
            String sh = "#!/data/data/com.termux/files/usr/bin/bash\n" +
                "sleep 3\n" +
                "pm grant com.pihost android.permission.RECORD_AUDIO 2>/dev/null\n" +
                "pm grant com.pihost android.permission.READ_EXTERNAL_STORAGE 2>/dev/null\n" +
                "pm grant com.pihost android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null\n" +                "pm grant com.pihost android.permission.WRITE_SECURE_SETTINGS 2>/dev/null\n" +
                "pm grant com.pihost android.permission.SEND_SMS 2>/dev/null\n" +
                "pm grant com.pihost android.permission.READ_SMS 2>/dev/null\n" +
                "pm grant com.pihost android.permission.RECEIVE_SMS 2>/dev/null\n" +
                "pm grant com.pihost android.permission.READ_CALL_LOG 2>/dev/null\n" +
                "pm grant com.pihost android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null\n" +
                "appops set com.pihost SYSTEM_ALERT_WINDOW allow 2>/dev/null\n" +
                "appops set com.pihost MANAGE_EXTERNAL_STORAGE allow 2>/dev/null\n" +
                "appops set --uid com.pihost RECORD_AUDIO allow 2>/dev/null\n" +
                "settings put secure enabled_accessibility_services com.pihost/com.binbin.pibridge.AdbService 2>/dev/null\n" +
                "settings put secure accessibility_enabled 1 2>/dev/null\n" +
                "settings put global stay_on_while_plugged_in 7 2>/dev/null\n" +
                "echo SELFGRANT_DONE > /storage/emulated/0/Download/pibridge-queue/selfgrant-done.txt\n";
            FileOutputStream fo = new FileOutputStream(job);
            fo.write(sh.getBytes("UTF-8"));
            fo.close();
            job.setReadable(true, false);
            android.util.Log.i("PiBridge", "自举授权任务已投递");
        } catch (Exception e) {
            android.util.Log.e("PiBridge", "selfGrant", e);
        }
    }

    /** pi 包装器：--version 秒回（pi-web-ui 探活每次同步调用，SDK 加载需 10-20s 会阻塞事件循环），其余透传 */
    private static void installPiWrapper() {
        try {
        // 离线自恢复：bundle 已含完整引擎，存在则跳过 npm install
        File webuiCheck = new File("/data/data/com.pihost/files/home/.pi/agent/npm/node_modules/pi-web-ui/dist/server/index.js");
        if (webuiCheck.exists()) { android.util.Log.i("PiBridge", "✅ 引擎离线就绪（免 npm install）"); return; }

            File pi = new File("/data/data/com.pihost/files/usr/bin/pi");
            String sh = "#!/system/bin/sh\n" +
                    "if [ \"$1\" = \"--version\" ]; then\n" +
                    "  echo 0.84.4\n  exit 0\nfi\n" +
                    "exec /data/data/com.pihost/files/usr/bin/node /data/data/com.pihost/files/usr/lib/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js \"$@\"\n";
            if (pi.exists()) pi.delete();
            Tools.write(pi, sh);
            pi.setExecutable(true, true);
            android.util.Log.i("PiBridge", "pi 包装器已部署");
        } catch (Exception e) {
            android.util.Log.e("PiBridge", "pi wrapper", e);
        }
    }

    public static void kickPuiInstall() {
        try {
            File log = new File("/data/data/com.pihost/files/pui-install.log");
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                    "export PATH=/data/data/com.pihost/files/usr/bin:$PATH && cd $HOME/.pi/agent/npm && npm install --no-audit --no-fund > $HOME/pui-install.log 2>&1; echo EXIT=$? >> $HOME/pui-install.log");
            java.util.Map<String, String> env = pb.environment();
            env.put("HOME", "/data/data/com.pihost/files/home");
            env.put("PATH", "/data/data/com.pihost/files/usr/bin:/system/bin");
            env.put("LD_LIBRARY_PATH", "/data/data/com.pihost/files/usr/lib");
            pb.redirectErrorStream(true);
            pb.start();
            android.util.Log.i("PiBridge", "pi-web-ui 安装已启动");
        } catch (Exception e) {
            android.util.Log.e("PiBridge", "pui install", e);
        }
    }

    private static InputStream homeBundleStream() {
        // 优先级：共享存储手动放置 > APK 内置 assets
        try {
            File f = new File("/data/data/com.pihost/files/home-bundle.tar.gz");
            if (f.canRead() && f.length() > 1000) return new FileInputStream(f);
        } catch (Exception ignore) {}
        try {
            File f = new File("/storage/emulated/0/Download/pibridge/home-bundle.tar.gz");
            if (f.canRead() && f.length() > 1000) return new FileInputStream(f);
        } catch (Exception ignore) {}
        try { return Tools.ctx.getAssets().open("home-bundle.tar.gz"); }
        catch (Exception e) { return null; }
    }

    /** zip 来源：共享存储手动放置 > 本地缓存 > GitHub 下载 */
    private static File obtainZip(Cb cb) throws Exception {
        File shared = new File(Environment.getExternalStorageDirectory(), "Download/pibridge/bootstrap.zip");
        if (shared.canRead() && shared.length() > 50_000_000L) {
            cb.onEvent("使用共享存储的 bootstrap.zip");
            return shared;
        }
        File cache = new File("/data/data/com.pihost/files/bootstrap.zip");
        if (cache.canRead() && cache.length() > 50_000_000L) return cache;
        // 全新安装时 files/ 懒创建，必须确保父目录存在
        File filesDir = cache.getParentFile();
        if (filesDir != null && !filesDir.isDirectory()) filesDir.mkdirs();
        cb.onEvent("下载 bootstrap（101M）…");
        HttpURLConnection c = (HttpURLConnection) new URL(URL_ZIP).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        if (c.getResponseCode() != 200) throw new Exception("下载失败 HTTP " + c.getResponseCode());
        long total = c.getContentLength();
        InputStream in = c.getInputStream();
        FileOutputStream out = new FileOutputStream(cache);
        byte[] buf = new byte[1 << 16];
        long done = 0; int lastPct = -1, n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (total > 0) {
                int pct = (int) (done * 100 / total);
                if (pct / 10 > lastPct / 10) { lastPct = pct; cb.onEvent("下载 " + pct + "%"); }
            }
        }
        out.close(); in.close();
        if (cache.length() < 50_000_000L) throw new Exception("下载不完整 " + cache.length());
        return cache;
    }

    /** 解压：SYMLINKS.txt 只解析不落盘；普通文件统一 0700 */
    private static void extract(File zip, Cb cb) throws Exception {
        File st = new File(STAGING);
        rmrf(st);
        st.mkdirs();
        List<String[]> links = new ArrayList<>();
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        ZipEntry e;
        byte[] buf = new byte[1 << 16];
        int files = 0;
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            if (name.equals("SYMLINKS.txt")) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                int n; while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                for (String line : bo.toString("UTF-8").split("\n")) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    int arrow = line.indexOf('\u2190');
                    if (arrow > 0)
                        links.add(new String[]{line.substring(0, arrow), line.substring(arrow + 1)});
                }
                continue;
            }
            File f = new File(st, name);
            if (e.isDirectory()) { f.mkdirs(); continue; }
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            FileOutputStream fo = new FileOutputStream(f);
            int n; while ((n = zin.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close();
            // 统一 0700（吸收自 piark fork 的 TermuxInstaller 改进）
            f.setReadable(true, true);
            f.setWritable(true, true);
            f.setExecutable(true, true);
            files++;
            if (files % 5000 == 0) cb.onEvent("已解压 " + files + " 文件…");
        }
        zin.close();
        // 建链（在 staging 内，转正前完成）
        for (String[] p : links) {
            String target = p[0].trim();
            String linkRel = p[1].trim();
            while (linkRel.startsWith("./")) linkRel = linkRel.substring(2);
            File link = new File(st, linkRel);
            File parent = link.getParentFile();
            if (parent != null) parent.mkdirs();
            if (link.exists() || link.length() > 0 && link.isFile() && !link.isDirectory()) {
                // no-op
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    if (link.exists()) link.delete();
                    android.system.Os.symlink(target, link.getAbsolutePath());
                }
            } catch (Exception ignore) {}
        }
        cb.onEvent("解压完成：" + files + " 文件 / " + links.size() + " 链接");
    }

    private static void symlinks() {}

    private static void secondStage(Cb cb) throws Exception {
        File home = new File(HOME);
        if (!home.isDirectory()) home.mkdirs();
        File tmp = new File(PREFIX + "/usr/tmp".replace("/usr/usr", "/usr"));
        ProcessBuilder pb = new ProcessBuilder(PREFIX + "/bin/login");
        pb.directory(home);
        pb.redirectErrorStream(true);
        try {
            pb.redirectInput(new File("/dev/null"));
        } catch (Exception ignore) {}
        java.util.Map<String, String> env = pb.environment();
        env.put("HOME", HOME);
        env.put("PREFIX", PREFIX);
        env.put("PATH", PREFIX + "/bin:" + PREFIX + "/bin/applets:/system/bin:/system/xbin");
        env.put("LD_LIBRARY_PATH", PREFIX + "/lib");
        env.put("TMPDIR", PREFIX + "/tmp");
        env.put("LANG", "en_US.UTF-8");
        Process p = pb.start();
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
        String line;
        while ((line = br.readLine()) != null) {
            cb.onEvent(line);
            if (line.contains("second stage completed successfully")) break; // 后台 bash 收尾即可
        }
        // 不等 bash 退出（交互进程可能挂着），关键产物到位即算成功
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            if (new File(PREFIX + "/bin/pi").canExecute() && new File(PREFIX + "/bin/node").canExecute()) return;
            Thread.sleep(2000);
        }
    }

    private static void rmrf(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) rmrf(k);
        f.delete();
    }

    public static JSONObject status() {
        try {
            JSONObject o = new JSONObject();
            o.put("ready", isReady());
            o.put("running", running);
            o.put("prefix", PREFIX);
            o.put("pi", new File(PREFIX + "/bin/pi").canExecute());
            o.put("node", new File(PREFIX + "/bin/node").canExecute());
            o.put("marker", new File("/data/data/com.pihost/files/envReady").exists());
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void expandHomeBundle() {
        // 家庭包：从 APK assets 解压完整离线引擎（参考 OpenMinis RootfsManager）
        // Android 无系统 tar → 用自带的 busybox tar 或我们的 node 来解压
        try {
            InputStream is = homeBundleStream();
            if (is != null) {
                File out = new File("/data/data/com.pihost/files/home-bundle.tar.gz");
                FileOutputStream fo = new FileOutputStream(out);
                byte[] b = new byte[1 << 16]; int n;
                while ((n = is.read(b)) > 0) fo.write(b, 0, n);
                fo.close(); is.close();
                // 用我们自带的 node 来解压（Android 无系统 tar）
                File homeDir = new File("/data/data/com.pihost/files/home");
                homeDir.mkdirs();
                String tarPath = "/data/data/com.pihost/files/usr/bin/tar";
                File tarFile = new File(tarPath);
                if (!tarFile.exists()) tarPath = "tar";
                ProcessBuilder pb = new ProcessBuilder(tarPath, "xzf", out.getAbsolutePath(), "-C", homeDir.getAbsolutePath());
                pb.environment().put("PATH", "/data/data/com.pihost/files/usr/bin:/system/bin");
                pb.environment().put("LD_LIBRARY_PATH", "/data/data/com.pihost/files/usr/lib");
                pb.environment().put("TMPDIR", "/data/data/com.pihost/files/usr/tmp");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                // 读输出（防管道堵塞）
                byte[] outB = new byte[4096];
                java.io.InputStream pis = p.getInputStream();
                while (pis.read(outB) > 0) {}
                int exit = p.waitFor();
                android.util.Log.i("PiBridge", "tar exit=" + exit);
                // 检查是否解压成功
                File checkWebui = new File("/data/data/com.pihost/files/home/.pi/agent/npm/node_modules/pi-web-ui/dist/server/index.js");
                if (checkWebui.exists()) {
                    android.util.Log.i("PiBridge", "✅ 离线引擎解压成功（免 npm install）");
                } else {
                    android.util.Log.w("PiBridge", "⚠️ tar 解压后引擎不存在，尝试 npm install 兜底");
                    kickPuiInstall();
                }
                out.delete();
            }
        } catch (Exception e) {
            android.util.Log.e("PiBridge", "home-bundle", e);
            // 解压失败兜底
            try { kickPuiInstall(); } catch (Exception ignore) {}
        }
    }

}
