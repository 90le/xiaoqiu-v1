package com.binbin.pibridge;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 内嵌 MCP（Streamable HTTP · 无状态 JSON 模式，学习 MT 管理器实现）+ 简易 /api 直调 */
public class Mcp implements Runnable {
    public static final int PORT = 8181;
    private volatile boolean run = true;

    public void shutdown() {
        run = false;
        try { if (ss != null) ss.close(); } catch (Exception ignore) {}
    }
    private ServerSocket ss;

    public void stop() {
        run = false;
        try { ss.close(); } catch (Exception e) {}
    }

    @Override public void run() {
        // 永生循环：任何 Throwable（含 OOM）都不终结服务，1.5 秒后重建端口
        while (run) {
            try {
                ss = new ServerSocket(PORT, 64, InetAddress.getByName("127.0.0.1"));
                Log.i("PiBridge", "MCP listening on 127.0.0.1:" + PORT);
                while (run) {
                    final Socket s = ss.accept();
                    new Thread(new Runnable() { public void run() { handle(s); } }).start();
                }
            } catch (Throwable e) {
                Log.e("PiBridge", "mcp server crash, restarting", e);
            }
            try { Thread.sleep(1500); } catch (InterruptedException ignore) {}
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream is = s.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int b;
            while ((b = is.read()) != -1) {
                head.write(b);
                byte[] a = head.toByteArray();
                int n = a.length;
                if (n >= 4 && a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') break;
                if (n > 65536) break;
            }
            String h = new String(head.toByteArray(), StandardCharsets.UTF_8);
            String[] lines = h.split("\r\n");
            String reqLine = lines.length > 0 ? lines[0] : "";
            String[] parts = reqLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "";
            int cl = 0;
            for (String line : lines) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    cl = Integer.parseInt(line.substring(15).trim());
                }
            }
            byte[] body = new byte[cl];
            int off = 0;
            while (off < cl) {
                int n = is.read(body, off, cl - off);
                if (n < 0) break;
                off += n;
            }

            if (path.equals("/ping")) {
                resp(s, 200, new JSONObject().put("pong", true).put("ts", System.currentTimeMillis()));
                return;
            }
            if (path.startsWith("/mcp")) {
                if (!"POST".equals(method)) {
                    resp(s, 405, new JSONObject().put("error", "use POST"));
                    return;
                }
                JSONObject req = cl > 0 ? new JSONObject(new String(body, StandardCharsets.UTF_8)) : new JSONObject();
                handleRpc(s, req);
            } else if (path.startsWith("/api/")) {
                String name = path.substring(5);
                JSONObject args = cl > 0 ? new JSONObject(new String(body, StandardCharsets.UTF_8)) : new JSONObject();
                JSONObject res = Tools.call(name, args);
                JSONObject out = new JSONObject()
                        .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", res.toString())))
                        .put("structuredContent", res);
                resp(s, 200, out);
            } else if (path.startsWith("/shots/")) {
                // 副屏截图预览：白名单目录，防路径穿越
                try {
                    String fname = path.substring(7);
                    if (fname.contains("..") || fname.contains("/")) throw new Exception("bad path");
                    java.io.File sf = new java.io.File("/storage/emulated/0/pibridge/shots/", fname);
                    if (!sf.canRead()) throw new Exception("not found");
                    java.io.FileInputStream fis = new java.io.FileInputStream(sf);
                    ByteArrayOutputStream abo = new ByteArrayOutputStream();
                    byte[] ab = new byte[8192]; int an;
                    while ((an = fis.read(ab)) > 0) abo.write(ab, 0, an);
                    fis.close();
                    byte[] bodyBytes = abo.toByteArray();
                    s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: " + bodyBytes.length + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    s.getOutputStream().write(bodyBytes);
                    s.close();
                } catch (Exception e2) { resp(s, 404, new JSONObject().put("error", "shot not found")); }
            } else if (path.equals("/") || path.startsWith("/www/") || path.endsWith(".html") || path.endsWith(".css") || path.endsWith(".js") || path.endsWith(".svg")) {
                // 静态资源（assets/www），同端口零冲突
                try {
                    String apath = path.equals("/") || path.equals("/index.html") ? "www/index.html"
                            : (path.startsWith("/www/") ? path.substring(1) : "www" + path);
                    if (apath.contains("..")) throw new Exception("bad path");
                    InputStream ais;
                    try { ais = Tools.ctx.getAssets().open(apath); }
                    catch (Exception notFound) { ais = Tools.ctx.getAssets().open("www/index.html"); } // SPA fallback
                    ByteArrayOutputStream abo = new ByteArrayOutputStream();
                    byte[] ab = new byte[8192]; int an;
                    while ((an = ais.read(ab)) > 0) abo.write(ab, 0, an);
                    ais.close();
                    byte[] bodyBytes = abo.toByteArray();
                    String mime = apath.endsWith(".html") ? "text/html; charset=utf-8"
                            : apath.endsWith(".css") ? "text/css; charset=utf-8"
                            : apath.endsWith(".js") ? "text/javascript"
                            : apath.endsWith(".svg") ? "image/svg+xml"
                            : apath.endsWith(".png") ? "image/png" : "application/octet-stream";
                    s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\nContent-Length: " + bodyBytes.length + "\r\nCache-Control: no-cache, no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    s.getOutputStream().write(bodyBytes);
                    s.close();
                } catch (Exception e2) {
                    resp(s, 404, new JSONObject().put("error", "not found"));
                }
            } else {
                resp(s, 404, new JSONObject().put("error", "not found"));
            }
        } catch (Exception e) {
            Log.w("PiBridge", "conn: " + e);
            try { s.close(); } catch (Exception ignore) {}
        }
    }

    private void handleRpc(Socket s, JSONObject req) throws Exception {
        String m = req.optString("method");
        Object id = req.opt("id");
        if (id == null || m.startsWith("notifications/")) {
            respRaw(s, 202, "");
            return;
        }
        JSONObject result;
        if ("initialize".equals(m)) {
            JSONObject p = req.optJSONObject("params");
            String ver = p == null ? "2024-11-05" : p.optString("protocolVersion", "2024-11-05");
            result = new JSONObject()
                    .put("protocolVersion", ver)
                    .put("serverInfo", new JSONObject().put("name", "xiaoqiu").put("version", "0.3.0"))
                    .put("capabilities", new JSONObject().put("tools", new JSONObject()));
        } else if ("tools/list".equals(m)) {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, Tools.Tool> e : Tools.REG.entrySet()) {
                arr.put(new JSONObject()
                        .put("name", e.getKey())
                        .put("description", e.getValue().desc)
                        .put("inputSchema", e.getValue().schema));
            }
            result = new JSONObject().put("tools", arr);
        } else if ("tools/call".equals(m)) {
            JSONObject p = req.optJSONObject("params");
            String name = p == null ? "" : p.optString("name");
            JSONObject args = p == null ? new JSONObject() : p.optJSONObject("arguments");
            if (args == null) args = new JSONObject();
            JSONObject res = Tools.call(name, args);
            result = new JSONObject()
                    .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", res.toString())))
                    .put("structuredContent", res);
        } else {
            resp(s, 200, new JSONObject().put("jsonrpc", "2.0").put("id", id)
                    .put("error", new JSONObject().put("code", -32601).put("message", "method not found: " + m)));
            return;
        }
        resp(s, 200, new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result));
    }

    private void resp(Socket s, int code, JSONObject o) throws Exception {
        respRaw(s, code, o == null ? "" : o.toString());
    }

    private void respRaw(Socket s, int code, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 " + code + " OK\r\nContent-Type: application/json\r\nContent-Length: "
                + b.length + "\r\nConnection: close\r\n\r\n";
        OutputStream os = s.getOutputStream();
        os.write(h.getBytes(StandardCharsets.UTF_8));
        os.write(b);
        os.flush();
        try { s.close(); } catch (Exception ignore) {}
    }
}
