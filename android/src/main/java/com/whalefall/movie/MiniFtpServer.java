package com.whalefall.movie;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 极简 FTP 服务（被动模式），让手机/电脑用任意 FTP 客户端往 App 里传文件。
 *
 * <p>只支持上传/下载/列目录/删除，匿名登录（用户名/密码任意），监听 2121。
 * 文件落在与「文件管理」相同的 pushed/ 目录。
 */
final class MiniFtpServer implements Closeable {
    static volatile MiniFtpServer RUNNING;
    static final int PORT = 2121;

    private final MainActivity a;
    private final ServerSocket socket;
    private final String password;
    private volatile boolean closed;

    /** 面板上展示的一次性口令（每次打开管理员后台重新生成）。 */
    static String password() {
        MiniFtpServer server = RUNNING;
        return server == null ? "" : server.password;
    }

    MiniFtpServer(MainActivity host) throws IOException {
        a = host;
        StringBuilder seed = new StringBuilder();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 6; i++) seed.append(random.nextInt(10));
        password = seed.toString();
        socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress("0.0.0.0", PORT));
        Thread accept =
                new Thread(
                        () -> {
                            while (!closed)
                                try {
                                    Socket client = socket.accept();
                                    Thread t = new Thread(() -> session(client), "whalefall-ftp");
                                    t.setDaemon(true);
                                    t.start();
                                } catch (IOException e) {
                                    if (!closed) close();
                                }
                        },
                        "whalefall-ftp-listener");
        accept.setDaemon(true);
        accept.start();
    }

    static void start(MainActivity a) {
        if (RUNNING != null) return;
        try {
            RUNNING = new MiniFtpServer(a);
        } catch (Exception ignored) {
        }
    }

    static void stop() {
        MiniFtpServer server = RUNNING;
        RUNNING = null;
        if (server != null) server.close();
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void session(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(60000);
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();
            ServerSocket dataServer = null;
            java.net.InetAddress remote = s.getInetAddress();
            boolean localPeer =
                    remote != null
                            && (remote.isLoopbackAddress() || remote.isSiteLocalAddress());
            if (!localPeer) {
                reply(out, "421 only LAN allowed");
                return;
            }
            boolean authenticated = false;
            String user = "";
            reply(out, "220 WhaleFall FTP ready (user wf)");

            String line;
            while ((line = in.readLine()) != null) {
                if (closed) break;
                int space = line.indexOf(' ');
                String cmd = (space < 0 ? line : line.substring(0, space)).toUpperCase(Locale.ROOT);
                String arg = space < 0 ? "" : line.substring(space + 1).trim();
                if (cmd.equals("USER")) {
                    user = arg;
                    reply(out, "331 password required");
                    continue;
                }
                if (cmd.equals("PASS")) {
                    if (user.equals("wf") && arg.equals(password)) {
                        authenticated = true;
                        reply(out, "230 logged in");
                    } else {
                        reply(out, "530 login incorrect");
                    }
                    continue;
                }
                if (!authenticated) {
                    reply(out, "530 please login");
                    continue;
                }
                switch (cmd) {
                    case "TYPE":
                    case "MODE":
                    case "STRU":
                    case "OPTS":
                    case "NOOP":
                        reply(out, "200 OK");
                        break;
                    case "SYST":
                        reply(out, "215 UNIX Type: L8");
                        break;
                    case "FEAT":
                        reply(out, "211-Features:\n SIZE\n MDTM\n PASV\n211 End");
                        break;
                    case "PWD":
                    case "XPWD":
                        reply(out, "257 \"/\"");
                        break;
                    case "CWD":
                    case "CDUP":
                        reply(out, "250 OK");
                        break;
                    case "PASV": {
                        if (dataServer != null) dataServer.close();
                        dataServer = new ServerSocket(0);
                        String ip = AdminConsole.lanIp();
                        String[] p = ip.split("\\.");
                        int port = dataServer.getLocalPort();
                        reply(
                                out,
                                "227 Entering Passive Mode ("
                                        + p[0]
                                        + ","
                                        + p[1]
                                        + ","
                                        + p[2]
                                        + ","
                                        + p[3]
                                        + ","
                                        + (port / 256)
                                        + ","
                                        + (port % 256)
                                        + ")");
                        break;
                    }
                    case "LIST":
                    case "NLST": {
                        reply(out, "150 Here comes the directory listing");
                        try (Socket data = dataServer == null ? null : dataServer.accept()) {
                            if (data != null) {
                                StringBuilder sb = new StringBuilder();
                                for (File f : PushFiles.list(a))
                                    sb.append("-rw-r--r-- 1 owner group ")
                                            .append(f.length())
                                            .append(" Jan 01 00:00 ")
                                            .append(f.getName())
                                            .append("\r\n");
                                data.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
                            }
                        }
                        reply(out, "226 Directory send OK");
                        break;
                    }
                    case "STOR": {
                        String name = PushFiles.sanitize(arg);
                        reply(out, "150 Ok to send data");
                        try (Socket data = dataServer == null ? null : dataServer.accept()) {
                            if (data == null) throw new IOException("no data connection");
                            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                            byte[] chunk = new byte[8192];
                            int n;
                            while ((n = data.getInputStream().read(chunk)) > 0) {
                                buffer.write(chunk, 0, n);
                                if (buffer.size() > PushFiles.MAX_BYTES) throw new IOException("too large");
                            }
                            PushFiles.save(a, name, buffer.toByteArray());
                        }
                        reply(out, "226 Transfer complete");
                        a.ui.post(() -> a.toast("FTP 收到文件：" + PushFiles.sanitize(arg)));
                        break;
                    }
                    case "RETR": {
                        File f = PushFiles.file(a, arg);
                        if (!f.isFile()) {
                            reply(out, "550 No such file");
                            break;
                        }
                        reply(out, "150 Opening data connection");
                        try (Socket data = dataServer == null ? null : dataServer.accept()) {
                            if (data != null) {
                                try (InputStream fin = new FileInputStream(f)) {
                                    byte[] chunk = new byte[8192];
                                    int n;
                                    while ((n = fin.read(chunk)) > 0)
                                        data.getOutputStream().write(chunk, 0, n);
                                }
                            }
                        }
                        reply(out, "226 Transfer complete");
                        break;
                    }
                    case "SIZE": {
                        File f = PushFiles.file(a, arg);
                        reply(out, f.isFile() ? "213 " + f.length() : "550 No such file");
                        break;
                    }
                    case "DELE":
                        PushFiles.delete(a, arg);
                        reply(out, "250 Deleted");
                        break;
                    case "QUIT":
                        reply(out, "221 Bye");
                        return;
                    default:
                        reply(out, "502 Command not implemented");
                        break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void reply(OutputStream out, String text) throws IOException {
        out.write((text + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
