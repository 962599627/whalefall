package com.whalefall.movie;

import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 1.9.26 TV 扫码遥控：局域网 HTTP 服务，手机浏览器扫码后当遥控器/输入法（无需装 App）。
 * 一次性 token、仅局域网、命令队列由 TV UI 线程每 100ms 消费。
 */
final class LanRemoteServer implements Closeable {
    interface StatusProvider {
        String hint();
    }

    private static final int BASE_PORT = 8790;
    final ServerSocket socket;
    final String token;
    final MainActivity activity;
    volatile boolean closed;
    volatile String hint = "";
    volatile long lastSeen;
    volatile boolean sawClient;
    private final long createdAt = System.currentTimeMillis();
    private static final long TTL_MS = 30 * 60_000L;

    boolean expired() {
        return System.currentTimeMillis() - createdAt > TTL_MS;
    }

    long startedAt() {
        return createdAt;
    }
    private final Deque<String[]> queue = new ArrayDeque<>();
    private final ThreadPoolExecutor requests =
            new ThreadPoolExecutor(
                    0,
                    3,
                    10,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "whale-lan-remote");
                        t.setDaemon(true);
                        return t;
                    });

    LanRemoteServer(MainActivity a) throws IOException {
        activity = a;
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        StringBuilder key = new StringBuilder();
        for (byte b : bytes) key.append(String.format(Locale.ROOT, "%02x", b & 255));
        token = key.toString();
        socket = bind(BASE_PORT);
        Thread accept =
                new Thread(
                        () -> {
                            while (!closed)
                                try {
                                    Socket client = socket.accept();
                                    try {
                                        requests.execute(() -> serve(client));
                                    } catch (RejectedExecutionException full) {
                                        client.close();
                                    }
                                } catch (IOException error) {
                                    if (!closed) close();
                                }
                        },
                        "whale-lan-remote-listener");
        accept.setDaemon(true);
        accept.start();
    }

    private static ServerSocket bind(int base) throws IOException {
        IOException last = null;
        for (int port = base; port < base + 10; port++) {
            try {
                ServerSocket server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress("0.0.0.0", port));
                return server;
            } catch (IOException error) {
                last = error;
            }
        }
        throw last == null ? new IOException("端口不可用") : last;
    }

    String address() {
        String ip = AdminConsole.lanIp();
        return "http://" + ip + ":" + socket.getLocalPort() + "/?t=" + token;
    }

    /** TV UI 线程每 100ms 消费一次命令队列。 */
    void pump() {
        String[] command;
        synchronized (queue) {
            command = queue.pollFirst();
        }
        if (command == null) return;
        final MainActivity a = activity;
        if (a == null || a.isFinishing()) return;
        String key = command[0];
        String value = command[1];
        // 1.9.27 隐私：不记录输入文本明文，只记录长度
        AppLog.i(
                "LanRemote",
                "key=" + key + ("text".equals(key) ? " textLen=" + value.length() : ""));
        if ("text".equals(key)) {
            View focus = a.getCurrentFocus();
            if (focus instanceof EditText) {
                EditText input = (EditText) focus;
                int variation = input.getInputType() & android.text.InputType.TYPE_MASK_VARIATION;
                boolean password =
                        variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                                || variation
                                        == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                || variation
                                        == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                                || variation
                                        == android.text.InputType
                                                .TYPE_TEXT_VARIATION_WEB_PASSWORD;
                if (password) {
                    hint = "该输入框不允许手机输入";
                    AppLog.i("LanRemote", "text rejected reason=password-field");
                    return;
                }
                int start = Math.max(0, input.getSelectionStart());
                int end = Math.max(0, input.getSelectionEnd());
                if (start > end) {
                    int swap = start;
                    start = end;
                    end = swap;
                }
                try {
                    input.getText().replace(start, end, value);
                } catch (Throwable ignored) {
                    input.append(value);
                }
                hint = "已输入 " + value.length() + " 字";
            } else {
                hint = "当前没有聚焦输入框";
            }
            return;
        }
        hint = "已发送：" + key;
        int keyCode;
        switch (key) {
            case "up":
                keyCode = KeyEvent.KEYCODE_DPAD_UP;
                break;
            case "down":
                keyCode = KeyEvent.KEYCODE_DPAD_DOWN;
                break;
            case "left":
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT;
                break;
            case "right":
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT;
                break;
            case "ok":
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER;
                break;
            case "back":
                keyCode = KeyEvent.KEYCODE_BACK;
                break;
            case "play":
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                break;
            case "vol+":
                keyCode = KeyEvent.KEYCODE_VOLUME_UP;
                break;
            case "vol-":
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN;
                break;
            default:
                return;
        }
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private void serve(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(15000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            InetAddress peer = s.getInetAddress();
            if (!peer.isSiteLocalAddress() && !peer.isLoopbackAddress()) {
                reply(out, 403, "text/plain", "仅允许局域网连接");
                return;
            }
            if (!sameSubnet(peer)) {
                reply(out, 403, "text/plain", "手机与电视不在同一网段");
                return;
            }
            String[] first = line(in, 4096).split(" ");
            if (first.length != 3) return;
            Map<String, String> headers = new HashMap<>();
            for (; ; ) {
                String row = line(in, 4096);
                if (row.isEmpty()) break;
                int colon = row.indexOf(':');
                if (colon > 0)
                    headers.put(
                            row.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            row.substring(colon + 1).trim());
            }
            URI uri;
            try {
                uri = new URI(first[1]);
            } catch (Throwable error) {
                reply(out, 400, "text/plain", "bad request");
                return;
            }
            if (!token.equals(param(uri.getRawQuery(), "t"))) {
                reply(out, 403, "text/plain", "token 无效或已过期，请重新扫码");
                return;
            }
            if (System.currentTimeMillis() - createdAt > TTL_MS) {
                AppLog.i("LanRemote", "token expired ttl=30min");
                reply(out, 410, "text/plain", "二维码已过期（30 分钟），请在电视上重新打开");
                return;
            }
            lastSeen = System.currentTimeMillis();
            sawClient = true;
            String path = uri.getPath() == null ? "/" : uri.getPath();
            if (!first[0].equals("GET")) {
                if (path.equals("/cmd") && first[0].equals("POST")) {
                    String k = param(uri.getRawQuery(), "k");
                    String v = param(uri.getRawQuery(), "v");
                    if (k != null && !k.isEmpty()) {
                        synchronized (queue) {
                            queue.addLast(new String[] {k, v == null ? "" : v});
                        }
                        reply(
                                out,
                                200,
                                "application/json; charset=utf-8",
                                "{\"ok\":true}");
                        return;
                    }
                }
                reply(out, 405, "text/plain", "请求方式不支持");
                return;
            }
            if (path.equals("/")) {
                reply(out, 200, "text/html; charset=utf-8", page());
                return;
            }
            if (!path.equals("/poll")) {
                reply(out, 404, "text/plain", "not found");
                return;
            }
            reply(
                    out,
                    200,
                    "application/json; charset=utf-8",
                    "{\"ok\":true,\"hint\":\"" + jsonEscape(hint) + "\"}");
        } catch (Throwable ignored) {
        }
    }

    private static boolean sameSubnet(InetAddress peer) {
        String peerText = peer == null ? "null" : peer.getHostAddress();
        try {
            if (peer == null) {
                AppLog.i("LanRemote", "peer=null subnet=reject reason=null-peer");
                return false;
            }
            if (peer.isLoopbackAddress()) {
                AppLog.i("LanRemote", "peer=" + peerText + " subnet=accept reason=loopback");
                return true;
            }
            byte[] a = peer.getAddress();
            // 1.9.27：优先用本机网卡前缀长度；兜底 /16
            int prefixLen = 16;
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            byte[] local = null;
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (network == null || !network.isUp() || network.isLoopback()) continue;
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    InetAddress localAddress = address.getAddress();
                    if (localAddress instanceof Inet4Address && localAddress.isSiteLocalAddress()) {
                        local = localAddress.getAddress();
                        prefixLen = address.getNetworkPrefixLength();
                        if (prefixLen <= 0 || prefixLen > 32) prefixLen = 16;
                        break;
                    }
                }
                if (local != null) break;
            }
            if (local == null) local = InetAddress.getByName(AdminConsole.lanIp()).getAddress();
            if (a == null || local == null || a.length != 4 || local.length != 4) {
                AppLog.i("LanRemote", "peer=" + peerText + " subnet=reject reason=bad-address");
                return false;
            }
            int mask = prefixLen >= 32 ? -1 : (0xffffffff << (32 - prefixLen));
            int peerValue =
                    ((a[0] & 255) << 24)
                            | ((a[1] & 255) << 16)
                            | ((a[2] & 255) << 8)
                            | (a[3] & 255);
            int localValue =
                    ((local[0] & 255) << 24)
                            | ((local[1] & 255) << 16)
                            | ((local[2] & 255) << 8)
                            | (local[3] & 255);
            boolean same = (peerValue & mask) == (localValue & mask);
            AppLog.i(
                    "LanRemote",
                    "peer="
                            + peerText
                            + " subnet="
                            + (same ? "accept" : "reject")
                            + " prefix="
                            + prefixLen);
            return same;
        } catch (Throwable error) {
            AppLog.i(
                    "LanRemote",
                    "peer=" + peerText + " subnet=reject reason=" + error.getClass().getSimpleName());
            return false;
        }
    }

    static String param(String query, String name) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            if (part.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(part.substring(eq + 1), "UTF-8");
                } catch (Throwable ignored) {
                    return part.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private static String jsonEscape(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                    break;
            }
        }
        return out.toString();
    }

    private String page() {
        String t = token;
        return "<!doctype html><html lang='zh'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'>"
                + "<title>鲸落遥控器</title><style>"
                + "body{margin:0;background:#0b1220;color:#f5f7fc;font-family:sans-serif;padding:16px calc(16px + env(safe-area-inset-right)) calc(24px + env(safe-area-inset-bottom)) calc(16px + env(safe-area-inset-left))}"
                + ".card{background:#16283f;border:1px solid #33ffffff;border-radius:16px;padding:14px;margin-bottom:12px}"
                + ".title{font-size:16px;font-weight:600;margin-bottom:8px}"
                + ".row{display:flex;gap:8px}input{flex:1;background:#0d1a2b;color:#fff;border:1px solid #33ffffff;border-radius:10px;padding:10px;font-size:16px}"
                + "button{background:#ff8fb1;color:#0b1220;border:0;border-radius:10px;padding:10px 14px;font-size:16px;font-weight:600}"
                + ".pad{display:grid;grid-template-columns:repeat(3,64px);gap:8px;justify-content:center;margin:12px 0}"
                + ".pad button{height:56px;background:#22344f;color:#f5f7fc;font-size:20px}"
                + ".mini{display:flex;gap:8px;flex-wrap:wrap;justify-content:center}"
                + ".mini button{background:#1d3350;color:#f5f7fc;font-size:14px;padding:10px 12px}"
                + "#hint{font-size:13px;color:#8d9cb6;margin-top:8px;min-height:18px}"
                + "</style></head><body>"
                + "<div class='card'><div class='title'><span id='conn' style='color:#8d9cb6'>● 正在连接…</span></div>"
                + "<div class='row'><input id='text' placeholder='输入文字，支持中文' autocomplete='off'>"
                + "<button onclick='sendText()'>发送</button></div>"
                + "<div id='hint'>与电视保持同一 WiFi</div></div>"
                + "<div class='card'><div class='title'>方向键 / 确认</div>"
                + "<div class='pad'><span></span><button onclick=\"cmd('up')\">▲</button><span></span>"
                + "<button onclick=\"cmd('left')\">◀</button><button onclick=\"cmd('ok')\">OK</button>"
                + "<button onclick=\"cmd('right')\">▶</button>"
                + "<span></span><button onclick=\"cmd('down')\">▼</button><button onclick=\"cmd('back')\">返回</button></div></div>"
                + "<div class='card'><div class='title'>常用</div><div class='mini'>"
                + "<button onclick=\"cmd('play')\">播放/暂停</button>"
                + "<button onclick=\"cmd('vol+')\">音量+</button>"
                + "<button onclick=\"cmd('vol-')\">音量-</button>"
                + "<button onclick=\"cmd('back')\">返回</button></div></div>"
                + "<script>var t='"
                + t
                + "';function cmd(k,v){var u='/cmd?t='+encodeURIComponent(t)+'&k='+encodeURIComponent(k);"
                + "if(v!==undefined)u+='&v='+encodeURIComponent(v);"
                + "fetch(u,{method:'POST'}).then(function(r){return r.text()}).then(function(x){document.getElementById('hint').textContent='已发送';}).catch(function(){document.getElementById('hint').textContent='发送失败，请检查 WiFi';});}"
                + "function sendText(){var v=document.getElementById('text').value;if(!v)return;cmd('text',v);document.getElementById('text').value='';}"
                + "function setConn(ok){var c=document.getElementById('conn');if(!c)return;c.textContent=ok?'● 已连接':'● 已断开，请确认手机与电视同一 WiFi';c.style.color=ok?'#2ad47a':'#ffb454';}"
                + "function poll(){fetch('/poll?t='+encodeURIComponent(t)).then(function(r){return r.json()}).then(function(j){setConn(true);document.getElementById('hint').textContent=j.hint||'已连接';}).catch(function(){setConn(false);document.getElementById('hint').textContent='连接中断，点击重试';});}"
                + "document.getElementById('hint').onclick=poll;poll();"
                + "setInterval(function(){if(!document.hidden)poll();},3000);"
                + "</script></body></html>";
    }

    private static void reply(OutputStream out, int code, String type, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes("UTF-8");
        String head =
                "HTTP/1.1 "
                        + code
                        + " OK\r\nContent-Type: "
                        + type
                        + "\r\nContent-Length: "
                        + bytes.length
                        + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(bytes);
        out.flush();
    }

    private static String line(InputStream in, int max) throws IOException {
        StringBuilder out = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') out.append((char) c);
            if (out.length() > max) throw new IOException("line limit");
        }
        return out.toString();
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
        requests.shutdownNow();
        synchronized (queue) {
            queue.clear();
        }
    }
}
