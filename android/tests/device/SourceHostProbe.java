package com.pilipala.marcomovie;

import android.app.Application;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/** Exercises shipped QuickJS JNI and JsEngine with local synthetic sources; no remote media. */
public final class SourceHostProbe extends Instrumentation {
    final JSONObject report = new JSONObject();
    final List<File> files = new ArrayList<>();
    final List<JsEngine> engines = new ArrayList<>();
    File folder;
    int checks;

    @Override
    public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    void check(String name, boolean passed) throws Exception {
        report.put(name, passed);
        if (!passed) throw new AssertionError(name);
        checks++;
    }

    File write(String name, String source) throws Exception {
        File target = new File(folder, name);
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(source.getBytes("UTF-8"));
        }
        files.add(target);
        return target;
    }

    JsEngine engine(String name, String source) throws Exception {
        File file = write(name, source);
        Cms.Site site = new Cms.Site();
        site.key = "source-host-probe-" + name;
        site.name = name;
        site.type = 3;
        site.api = Cms.fileUri(file);
        site.ext = "ready";
        JsEngine engine =
                new JsEngine(
                        (Application) getTargetContext().getApplicationContext(),
                        new Cms.Config(),
                        site);
        engines.add(engine);
        return engine;
    }

    interface CheckedCall {
        void run() throws Exception;
    }

    void fails(String name, String marker, CheckedCall call) throws Exception {
        String error = "";
        try {
            call.run();
        } catch (Exception failure) {
            error = failure.toString();
        }
        check(name, error.contains(marker));
    }

    JSONObject first(String value) throws Exception {
        return new JSONObject(value).getJSONArray("list").getJSONObject(0);
    }

    /** Two loopback requests only; independent of device Wi-Fi and mobile data. */
    static final class PythonSessionServer implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        final Thread thread;
        volatile Throwable error;
        volatile String cookie = "";
        volatile String sessionHeader = "";
        volatile int requests;

        PythonSessionServer() throws Exception {
            listener.setSoTimeout(10000);
            thread =
                    new Thread(
                            () -> {
                                try {
                                    for (int index = 0; index < 2; index++) {
                                        try (Socket socket = listener.accept()) {
                                            socket.setSoTimeout(3000);
                                            BufferedReader reader =
                                                    new BufferedReader(
                                                            new InputStreamReader(
                                                                    socket.getInputStream(),
                                                                    "UTF-8"));
                                            String line = reader.readLine();
                                            String expected =
                                                    index == 0
                                                            ? "GET /login "
                                                            : "GET /auth/resource ";
                                            if (line == null || !line.startsWith(expected))
                                                throw new IllegalStateException(
                                                        "unexpected local session path");
                                            int size = line.length(), count = 0;
                                            while ((line = reader.readLine()) != null
                                                    && !line.isEmpty()) {
                                                size += line.length();
                                                if (++count > 64 || size > 8192)
                                                    throw new IllegalStateException(
                                                            "oversized local request");
                                                String lower = line.toLowerCase(Locale.ROOT);
                                                if (index == 1 && lower.startsWith("cookie:"))
                                                    cookie = line.substring(7).trim();
                                                if (index == 1 && lower.startsWith("x-session:"))
                                                    sessionHeader = line.substring(10).trim();
                                            }
                                            String body =
                                                    index == 0
                                                            ? "{}"
                                                            : "{\"list\":[{\"vod_id\":\"session\",\"vod_name\":\"Authenticated local session\"}]}";
                                            byte[] bytes = body.getBytes("UTF-8");
                                            String headers =
                                                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                                            + bytes.length
                                                            + "\r\nConnection: close\r\n";
                                            if (index == 0)
                                                headers +=
                                                        "Set-Cookie: sid=fixture; Path=/auth; HttpOnly\r\nSet-Cookie: foreign=reject; Domain=unrelated.invalid; Path=/\r\n";
                                            OutputStream output = socket.getOutputStream();
                                            output.write((headers + "\r\n").getBytes("UTF-8"));
                                            output.write(bytes);
                                            output.flush();
                                            requests++;
                                        }
                                    }
                                } catch (Throwable failure) {
                                    error = failure;
                                }
                            },
                            "python-session-loopback");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void close() throws Exception {
            listener.close();
            thread.join(1000);
        }
    }

    @Override
    public void onStart() {
        try {
            folder =
                    new File(
                            getTargetContext().getFilesDir(),
                            "source-host-probe-" + System.nanoTime());
            if (!folder.mkdir()) throw new IllegalStateException("fixture directory unavailable");
            write("data.json", "{\"title\":\"Local fixture\"}");
            write("helper.js", "exports.echo = value => Promise.resolve(value);");
            JsEngine js =
                    engine(
                            "index.js",
                            "const data=require('./data.json'), helper=require('./helper');"
                                    + "module.exports={"
                                    + "async init(ext){this.ready=await helper.echo(ext);},"
                                    + "async home(){await Promise.resolve();return {list:[{vod_id:this.ready,vod_name:data.title}]};},"
                                    + "async search(word,quick,page){return {list:[{vod_id:String(page),vod_name:await helper.echo(word)}]};},"
                                    + "async detail(id){return {list:[{vod_id:await helper.echo(id),vod_play_from:'Main',vod_play_url:'Film$fixture'}]};},"
                                    + "async play(flag,id){return {url:'https://fixture.invalid/'+await helper.echo(id)+'.mp4',header:{Referer:'https://fixture.invalid/'}};}"
                                    + "};");
            check(
                    "commonjs_async_init_before_home",
                    first(js.home()).getString("vod_id").equals("ready"));
            check(
                    "commonjs_json_dependency",
                    first(js.home()).getString("vod_name").equals("Local fixture"));
            JSONObject search = first(js.search("中文", 3));
            check(
                    "commonjs_async_search",
                    search.getString("vod_name").equals("中文")
                            && search.getString("vod_id").equals("3"));
            check("commonjs_async_detail", first(js.detail("42")).getString("vod_id").equals("42"));
            JSONObject play = new JSONObject(js.player("Main", "42"));
            check(
                    "commonjs_async_play",
                    play.getString("url").equals("https://fixture.invalid/42.mp4")
                            && play.getJSONObject("header")
                                    .getString("Referer")
                                    .equals("https://fixture.invalid/"));
            fails("missing_method_reported", "homeVod", () -> js.homeVideos());

            JsEngine named =
                    engine(
                            "named.mjs",
                            "export async function home(){return {list:[{vod_id:'named'}]}};");
            check(
                    "named_export_without_init",
                    first(named.home()).getString("vod_id").equals("named"));
            JsEngine errors =
                    engine(
                            "errors.js",
                            "module.exports={home:async()=>{throw Error('fixture rejection')},search:()=>new Promise(()=>{})};");
            fails("async_rejection_preserved", "fixture rejection", () -> errors.home());
            fails(
                    "pending_promise_fails_without_spin",
                    "did not settle",
                    () -> errors.search("x", 1));
            fails(
                    "async_init_failure_preserved",
                    "fixture init failure",
                    () ->
                            engine(
                                    "init-error.js",
                                    "module.exports={init:async()=>{throw Error('fixture init failure')},home:()=>({})};"));

            JsEngine py =
                    engine(
                            "fixture.py",
                            "from base.spider import Spider as Base\n"
                                    + "class Spider(Base):\n"
                                    + "    def init(self, ext): self.ready = ext\n"
                                    + "    def homeContent(self, filter): return {'list':[{'vod_id':self.ready,'vod_name':'Python'}]}\n"
                                    + "    def searchContent(self, key, quick, pg='1'): return {'list':[{'vod_id':pg,'vod_name':key}]}\n"
                                    + "    def detailContent(self, ids): return {'list':[{'vod_id':ids[0],'vod_play_from':'Main','vod_play_url':'Film$fixture'}]}\n"
                                    + "    def playerContent(self, flag, id, vip): return {'url':'https://fixture.invalid/'+id+'.mp4'}\n");
            check("python_init_home", first(py.home()).getString("vod_id").equals("ready"));
            check("python_search", first(py.search("中文", 2)).getString("vod_name").equals("中文"));
            check("python_detail", first(py.detail("42")).getString("vod_id").equals("42"));
            check(
                    "python_play",
                    new JSONObject(py.player("Main", "42"))
                            .getString("url")
                            .equals("https://fixture.invalid/42.mp4"));
            try (PythonSessionServer server = new PythonSessionServer()) {
                String base = "http://127.0.0.1:" + server.listener.getLocalPort();
                JsEngine session =
                        engine(
                                "session.py",
                                "import requests\n"
                                        + "class Spider:\n"
                                        + "    def homeContent(self, filter):\n"
                                        + "        with requests.Session() as s:\n"
                                        + "            s.headers.update({'X-Session':'local-fixture'})\n"
                                        + "            r = s.get('"
                                        + base
                                        + "/login', timeout=3)\n"
                                        + "            assert r.cookies.get('sid') == 'fixture'\n"
                                        + "            return s.get('"
                                        + base
                                        + "/auth/resource', timeout=3).json()\n");
                check(
                        "python_session_real_http",
                        first(session.home()).getString("vod_id").equals("session"));
                server.thread.join(1000);
                check(
                        "python_session_cookie_scope",
                        server.requests == 2
                                && server.error == null
                                && server.cookie.equals("sid=fixture"));
                check(
                        "python_session_headers_persist",
                        server.sessionHeader.equals("local-fixture"));
            }
            report.put("passed", true);
        } catch (Throwable failure) {
            try {
                report.put("passed", false).put("error", failure.toString());
            } catch (Exception ignored) {
            }
        } finally {
            for (JsEngine engine : engines) engine.close();
            for (File file : files) file.delete();
            if (folder != null) folder.delete();
        }
        try {
            report.put("checks", checks);
        } catch (Exception ignored) {
        }
        Bundle result = new Bundle();
        result.putString("result", report.toString());
        sendStatus(0, result);
        finish(android.app.Activity.RESULT_OK, new Bundle());
    }
}
