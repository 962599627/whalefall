package com.pilipala.marcomovie;

import android.app.Application;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
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
        return engine(name, source, 45000);
    }

    JsEngine engine(String name, String source, long timeoutMillis) throws Exception {
        return engine(name, source, timeoutMillis, "ready");
    }

    JsEngine engine(String name, String source, long timeoutMillis, Object extension)
            throws Exception {
        File file = write(name, source);
        Cms.Site site = new Cms.Site();
        site.key = "source-host-probe-" + name;
        site.name = name;
        site.type = 3;
        site.api = Cms.fileUri(file);
        site.ext = extension;
        JsEngine engine =
                new JsEngine(
                        (Application) getTargetContext().getApplicationContext(),
                        new Cms.Config(),
                        site,
                        timeoutMillis);
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

    static final class ProxyResult {
        int status;
        byte[] body;
        String count;
        String range;
        String length;
    }

    ProxyResult proxyRequest(String address, String method, String range) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod(method);
        if (range != null) connection.setRequestProperty("Range", range);
        try {
            ProxyResult result = new ProxyResult();
            result.status = connection.getResponseCode();
            result.count = connection.getHeaderField("X-Count");
            result.range = connection.getHeaderField("Content-Range");
            result.length = connection.getHeaderField("Content-Length");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            InputStream input =
                    result.status < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (input != null)
                try (InputStream stream = input) {
                    byte[] buffer = new byte[256];
                    int read;
                    while ((read = stream.read(buffer)) != -1) {
                        if (body.size() + read > 4096)
                            throw new AssertionError("proxy fixture body too large");
                        body.write(buffer, 0, read);
                    }
                }
            result.body = body.toByteArray();
            return result;
        } finally {
            connection.disconnect();
        }
    }

    void exerciseScriptProxy() throws Exception {
        Application app = (Application) getTargetContext().getApplicationContext();
        try (LocalProxy proxy = new LocalProxy(app, new Cms.Config())) {
            String python =
                    "from base.spider import Spider as Base\n"
                            + "class Spider(Base):\n"
                            + "    def init(self, ext):\n"
                            + "        self.base = self.getProxyUrl(); self.count = 0\n"
                            + "    def playerContent(self, flag, id, vipFlags):\n"
                            + "        return {'url':self.base + '&mode=' + id}\n"
                            + "    def localProxy(self, params):\n"
                            + "        self.count += 1\n"
                            + "        headers = {'X-Count':str(self.count)}\n"
                            + "        if params.get('range') == 'bytes=1-2':\n"
                            + "            headers['Content-Range'] = 'bytes 1-2/4'\n"
                            + "            return [206,'application/octet-stream',bytes([255,128]),headers]\n"
                            + "        return [200,'application/octet-stream',bytes([0,255,128,65]),headers]\n";
            JsEngine py = engine("proxy.py", python);
            py.activate();
            String pyUrl = new JSONObject(py.player("Main", "bytes")).getString("url");
            check(
                    "python_proxy_local_route",
                    pyUrl.startsWith(ProxyAddress.base() + "/proxy?do=py&siteKey=")
                            && pyUrl.contains("&_mm_token="));
            ProxyResult first = proxyRequest(pyUrl, "GET", null);
            check(
                    "python_proxy_jni_binary",
                    first.status == 200
                            && Arrays.equals(
                                    first.body, new byte[] {0, (byte) 255, (byte) 128, 65}));
            check(
                    "python_proxy_jni_state",
                    "1".equals(first.count) && "2".equals(proxyRequest(pyUrl, "GET", null).count));
            ProxyResult pyRange = proxyRequest(pyUrl, "GET", "bytes=1-2");
            check(
                    "python_proxy_jni_range",
                    pyRange.status == 206
                            && "bytes 1-2/4".equals(pyRange.range)
                            && Arrays.equals(pyRange.body, new byte[] {(byte) 255, (byte) 128}));
            ProxyResult pyHead = proxyRequest(pyUrl, "HEAD", null);
            check(
                    "python_proxy_jni_head",
                    pyHead.status == 200 && pyHead.body.length == 0 && "4".equals(pyHead.length));
            JsEngine js =
                    engine(
                            "proxy.js",
                            "module.exports={init(){this.base=getProxy();this.count=0;},"
                                    + "play(flag,id){return {url:id==='bytes'?this.base+'&mode='+id:proxyUrl({mode:id})};},"
                                    + "proxy(p){const h={'X-Count':String(++this.count)};"
                                    + "if(p.range==='bytes=1-2'){h['Content-Range']='bytes 1-2/4';return [206,'application/octet-stream',[255,128],h];}"
                                    + "const typed=new Uint8Array([0,255,128,65]);"
                                    + "if(p.mode==='typed')return [200,'application/octet-stream',typed,h];"
                                    + "if(p.mode==='buffer')return [200,'application/octet-stream',typed.buffer,h];"
                                    + "if(p.mode==='flag')return [200,'application/octet-stream',typed,h,2];"
                                    + "if(p.mode==='headers')return [200,'application/octet-stream',typed,false];"
                                    + "return [200,'application/octet-stream','AP+AQQ==',h,1];}};");
            js.activate();
            String jsUrl = new JSONObject(js.player("Main", "bytes")).getString("url");
            check(
                    "js_proxy_local_route",
                    jsUrl.startsWith(ProxyAddress.base() + "/proxy?do=js&site="));
            ProxyResult jsFirst = proxyRequest(jsUrl, "GET", null);
            check(
                    "js_proxy_jni_base64",
                    jsFirst.status == 200
                            && "1".equals(jsFirst.count)
                            && Arrays.equals(
                                    jsFirst.body, new byte[] {0, (byte) 255, (byte) 128, 65}));
            ProxyResult jsRange = proxyRequest(jsUrl, "GET", "bytes=1-2");
            check(
                    "js_proxy_jni_range",
                    jsRange.status == 206
                            && "bytes 1-2/4".equals(jsRange.range)
                            && Arrays.equals(jsRange.body, new byte[] {(byte) 255, (byte) 128}));
            ProxyResult jsHead = proxyRequest(jsUrl, "HEAD", null);
            check(
                    "js_proxy_jni_head",
                    jsHead.status == 200 && jsHead.body.length == 0 && "4".equals(jsHead.length));
            for (String mode : new String[] {"typed", "buffer"}) {
                String url = new JSONObject(js.player("Main", mode)).getString("url");
                ProxyResult result = proxyRequest(url, "GET", null);
                check(
                        "js_proxy_jni_" + mode,
                        result.status == 200
                                && Arrays.equals(
                                        result.body, new byte[] {0, (byte) 255, (byte) 128, 65}));
            }
            for (String mode : new String[] {"flag", "headers"}) {
                String url = new JSONObject(js.player("Main", mode)).getString("url");
                check("js_proxy_jni_rejects_" + mode, proxyRequest(url, "GET", null).status == 502);
            }
            JsEngine replacement = engine("proxy.py", python);
            replacement.activate();
            String replacementUrl =
                    new JSONObject(replacement.player("Main", "bytes")).getString("url");
            check(
                    "python_proxy_replacement_revokes_old",
                    !replacementUrl.equals(pyUrl)
                            && proxyRequest(pyUrl, "GET", null).status == 502);
            check(
                    "python_proxy_replacement_fresh_state",
                    "1".equals(proxyRequest(replacementUrl, "GET", null).count));
            replacement.close();
            check(
                    "python_proxy_close_revokes_url",
                    proxyRequest(replacementUrl, "GET", null).status == 502);
            check(
                    "script_proxy_source_isolation",
                    "8".equals(proxyRequest(jsUrl, "GET", null).count));
            js.close();
            check("js_proxy_close_revokes_url", proxyRequest(jsUrl, "GET", null).status == 502);
        }
    }

    void exerciseNativeDeadlines() throws Exception {
        Application app = (Application) getTargetContext().getApplicationContext();
        try (LocalProxy proxy = new LocalProxy(app, new Cms.Config())) {
            JsEngine looping =
                    engine(
                            "deadline.js",
                            "module.exports={play(){return {url:getProxy()};},home(){while(true){}}};",
                            1500);
            looping.activate();
            String oldUrl = new JSONObject(looping.player("Main", "1")).getString("url");
            QueuedSourceEngine queued = new QueuedSourceEngine(looping);
            long started = android.os.SystemClock.elapsedRealtime();
            fails("native_instruction_deadline", "时间限制", queued::home);
            long elapsed = android.os.SystemClock.elapsedRealtime() - started;
            check("native_deadline_before_java_wait", elapsed >= 500 && elapsed < 7000);
            check(
                    "native_deadline_invalidates_cached_source",
                    looping.isClosed() && queued.isWedged());
            check("native_deadline_revokes_proxy", proxyRequest(oldUrl, "GET", null).status == 502);
            // Same logical key, as SourceController recreates after isWedged().
            JsEngine fresh =
                    engine(
                            "deadline.js",
                            "module.exports={home(){return {list:[{vod_id:'fresh'}]}}};",
                            1500);
            fresh.activate();
            check(
                    "native_deadline_recreated_source",
                    first(fresh.home()).getString("vod_id").equals("fresh"));
            Thread.sleep(1600);
            check(
                    "native_deadline_resets_each_operation",
                    first(fresh.home()).getString("vod_id").equals("fresh"));
            fresh.close();
            fresh.close();
            check("native_deadline_close_idempotent", fresh.isClosed());
            fails(
                    "native_module_init_deadline",
                    "时间限制",
                    () -> engine("deadline-init.js", "while(true){};module.exports={};", 1500));

            String name = "deadline-cancel.js";
            byte[] keyHash =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(("source-host-probe-" + name).getBytes("UTF-8"));
            StringBuilder hash = new StringBuilder();
            for (byte value : keyHash) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
            android.content.SharedPreferences storage =
                    app.getSharedPreferences("js-source-" + hash, 0);
            storage.edit().clear().commit();
            JsEngine cancelled =
                    engine(
                            name,
                            "module.exports={home(){local.set('probe','entered','yes');while(true){}}};");
            cancelled.activate();
            RequestScope scope = new RequestScope();
            java.util.concurrent.atomic.AtomicReference<Throwable> error =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread request =
                    new Thread(
                            () -> {
                                try {
                                    scope.call(cancelled::home);
                                } catch (Throwable failure) {
                                    error.set(failure);
                                }
                            },
                            "native-deadline-cancel-probe");
            request.setDaemon(true);
            request.start();
            long waitUntil = android.os.SystemClock.elapsedRealtime() + 5000;
            while (!"yes".equals(storage.getString("probe\nentered", ""))
                    && request.isAlive()
                    && android.os.SystemClock.elapsedRealtime() < waitUntil) Thread.sleep(10);
            boolean entered = "yes".equals(storage.getString("probe\nentered", ""));
            scope.cancel();
            request.join(5000);
            check("native_cancel_entered_instruction_loop", entered);
            check(
                    "native_scope_cancel_interrupts_cpu",
                    !request.isAlive()
                            && error.get() != null
                            && error.get().toString().contains("取消"));
            check("native_cancel_source_closed", cancelled.isClosed());
            JsEngine afterCancel =
                    engine(
                            name,
                            "module.exports={home(){return {list:[{vod_id:'after-cancel'}]}}};");
            afterCancel.activate();
            scope.cancel(); // Late cancellation of an old operation must not affect its successor.
            check(
                    "native_cancel_new_source_unaffected",
                    first(afterCancel.home()).getString("vod_id").equals("after-cancel"));
            afterCancel.close();
            storage.edit().clear().commit();
        }
    }

    void exerciseFullSpiderContract() throws Exception {
        String rawExtension = " \n { \"title\" : \"本机配置\" } \t";
        String spiderClass =
                "class CompleteSpider {"
                        + "constructor(){globalThis.created++;this.calls=0;}"
                        + "async init(ext){await Promise.resolve();this.ext=ext;}"
                        + "homeContent(){return {list:[{vod_id:'home'}],ext:this.ext,created:globalThis.created,calls:++this.calls};}"
                        + "categoryContent(id,pg,filtered,extend){if(typeof pg!=='string'||pg!=='2'||extend.type!=='opaque')throw Error('category ABI');this.calls++;return {list:[{vod_id:pg}]};}"
                        + "searchContent(word,quick,pg){if(typeof pg!=='string'||pg!=='3')throw Error('search ABI');this.calls++;return {list:[{vod_id:pg}]};}"
                        + "detailContent(ids){if(!Array.isArray(ids)||ids.length!==1||ids[0]!=='42')throw Error('detail ABI');this.calls++;return {list:[{vod_id:ids[0]}]};}"
                        + "playerContent(flag,id,vips){if(!Array.isArray(vips))throw Error('player ABI');return {url:'https://fixture.invalid/'+id+'.mp4',calls:++this.calls};}"
                        + "}";
        for (String kind : new String[] {"cjs", "esm"}) {
            String source =
                    "globalThis.created=0;\n"
                            + (kind.equals("cjs") ? "module.exports=" : "export default ")
                            + spiderClass
                            + ";";
            JsEngine full =
                    engine(
                            "complete-" + kind + (kind.equals("cjs") ? ".js" : ".mjs"),
                            source,
                            45000,
                            rawExtension);
            JSONObject first = new JSONObject(full.home()), second = new JSONObject(full.home());
            check(
                    "complete_" + kind + "_raw_ext_instance_state",
                    first.getString("ext").equals(rawExtension)
                            && first.getInt("created") == 1
                            && first.getInt("calls") == 1
                            && second.getInt("created") == 1
                            && second.getInt("calls") == 2);
            java.util.Map<String, String> filters = new java.util.LinkedHashMap<>();
            filters.put("type", "opaque");
            check(
                    "complete_" + kind + "_string_pages",
                    first(full.category("movie", 2, filters)).getString("vod_id").equals("2")
                            && first(full.search("fixture", 3)).getString("vod_id").equals("3"));
            JSONObject detail = first(full.detail("42")),
                    player = new JSONObject(full.player("Main", "42"));
            check(
                    "complete_" + kind + "_detail_array_player_state",
                    detail.getString("vod_id").equals("42")
                            && player.getString("url").equals("https://fixture.invalid/42.mp4")
                            && player.getInt("calls") == 6);
            full.close();
        }
        JsEngine mixed =
                engine(
                        "complete-short-priority.js",
                        "module.exports={init(ext){this.ext=ext;},home(){return {list:[{vod_id:'short',kind:typeof this.ext}]};},"
                                + "homeContent(){throw Error('wrong alias');},category(id,pg){return {list:[{vod_id:typeof pg}]};},"
                                + "categoryContent(){throw Error('wrong category alias');}};",
                        45000,
                        new JSONObject().put("key", "value"));
        check(
                "complete_short_names_keep_legacy_contract",
                first(mixed.home()).getString("kind").equals("object")
                        && first(mixed.home()).getString("vod_id").equals("short")
                        && first(mixed.category("movie", 2)).getString("vod_id").equals("number"));
        mixed.close();
        JsEngine objectExt =
                engine(
                        "complete-object-ext.js",
                        "module.exports={init(ext){this.ext=ext;},homeContent(){return {list:[{vod_id:typeof this.ext,key:JSON.parse(this.ext).key}]};}};",
                        45000,
                        new JSONObject().put("key", "value"));
        check(
                "complete_object_extension_serialized",
                first(objectExt.home()).getString("vod_id").equals("string")
                        && first(objectExt.home()).getString("key").equals("value"));
        objectExt.close();
        JsEngine cat =
                engine(
                        "complete-cat.js",
                        "function __jsEvalReturn(){return {init(cfg){if(cfg.stype!==3||cfg.skey!=='source-host-probe-complete-cat.js'||typeof cfg.ext!=='string')throw Error('cat init');this.cfg=cfg;},"
                                + "homeContent(){return {list:[{vod_id:this.cfg.ext}]};},"
                                + "detailContent(ids){if(!Array.isArray(ids)||ids.length!==1||typeof ids[0]!=='string')throw Error('cat detail');return {list:[{vod_id:ids[0]}]};}};}",
                        45000,
                        rawExtension);
        check(
                "complete_cat_keeps_init_metadata_and_array",
                first(cat.home()).getString("vod_id").equals(rawExtension)
                        && first(cat.detail("42")).getString("vod_id").equals("42"));
        cat.close();
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

    /** Reads raw request bytes, without a character reader consuming the binary body. */
    static final class PythonBinaryServer implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        final Thread thread;
        volatile Throwable error;
        volatile byte[] uploaded;
        volatile byte[] empty;
        volatile int requests;

        PythonBinaryServer() throws Exception {
            listener.setSoTimeout(10000);
            thread =
                    new Thread(
                            () -> {
                                try {
                                    for (int index = 0; index < 2; index++) {
                                        try (Socket socket = listener.accept()) {
                                            socket.setSoTimeout(3000);
                                            InputStream input = socket.getInputStream();
                                            ByteArrayOutputStream header =
                                                    new ByteArrayOutputStream();
                                            int end = 0;
                                            while (end != 0x0d0a0d0a) {
                                                int value = input.read();
                                                if (value < 0 || header.size() >= 8192)
                                                    throw new IllegalStateException(
                                                            "incomplete binary request headers");
                                                header.write(value);
                                                end = (end << 8) | value;
                                            }
                                            String[] lines =
                                                    new String(header.toByteArray(), "US-ASCII")
                                                            .split("\r\n");
                                            String expected =
                                                    index == 0 ? "POST /upload " : "POST /empty ";
                                            if (!lines[0].startsWith(expected))
                                                throw new IllegalStateException(
                                                        "unexpected binary request path");
                                            int length = -1;
                                            for (String line : lines)
                                                if (line.toLowerCase(Locale.ROOT)
                                                        .startsWith("content-length:"))
                                                    length =
                                                            Integer.parseInt(
                                                                    line.substring(15).trim());
                                            if (length != (index == 0 ? 4 : 0))
                                                throw new IllegalStateException(
                                                        "unexpected binary content length "
                                                                + length);
                                            byte[] body = new byte[length];
                                            for (int offset = 0; offset < length; ) {
                                                int read =
                                                        input.read(body, offset, length - offset);
                                                if (read < 0)
                                                    throw new IllegalStateException(
                                                            "incomplete binary request body");
                                                offset += read;
                                            }
                                            if (index == 0) uploaded = body;
                                            else empty = body;
                                            String reply =
                                                    index == 0
                                                            ? "{\"bytes\":[0,255,128,65]}"
                                                            : "{\"bytes\":[]}";
                                            byte[] bytes = reply.getBytes("UTF-8");
                                            OutputStream output = socket.getOutputStream();
                                            output.write(
                                                    ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                                                    + bytes.length
                                                                    + "\r\nConnection: close\r\n\r\n")
                                                            .getBytes("US-ASCII"));
                                            output.write(bytes);
                                            output.flush();
                                            requests++;
                                        }
                                    }
                                } catch (Throwable failure) {
                                    error = failure;
                                }
                            },
                            "python-binary-loopback");
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
                                    + "from bs4 import BeautifulSoup\n"
                                    + "import re, json\n"
                                    + "class Spider(Base):\n"
                                    + "    def init(self, ext): self.ready = ext\n"
                                    + "    def homeContent(self, filter):\n"
                                    + "        self.soup = BeautifulSoup('<div class=slide><a href=\"/a?x=1&amp;y=2\"> One &amp; <em>二</em> </a></div><div class=duplicate>bad</div>', 'html.parser')\n"
                                    + "        link = self.soup.select_one('.slide:not(.duplicate) a')\n"
                                    + "        return {'list':[{'vod_id':self.ready,'vod_name':'Python'}], 'html':link.get_text(' ', strip=True), 'href':link.get('href'), 'regex':re.sub(r'[#\\s]+', ' ', '## A 线').strip()}\n"
                                    + "    def searchContent(self, key, quick, pg='1'): return {'list':[{'vod_id':pg,'vod_name':key}], 'signed':json.dumps({'z':'中文','a':9007199254740993123456789},sort_keys=True,ensure_ascii=False,separators=(',',':')), 'big':str(json.loads('9007199254740993123456789'))}\n"
                                    + "    def detailContent(self, ids): return {'list':[{'vod_id':ids[0],'vod_play_from':'Main','vod_play_url':'Film$fixture'}]}\n"
                                    + "    def playerContent(self, flag, id, vip): return {'url':'https://fixture.invalid/'+id+'.mp4'}\n");
            check("python_init_home", first(py.home()).getString("vod_id").equals("ready"));
            JSONObject html = new JSONObject(py.home());
            check("python_html_selector_text", html.getString("html").equals("One & 二"));
            check("python_html_entity_attribute", html.getString("href").equals("/a?x=1&y=2"));
            check("python_regex_substitution", html.getString("regex").equals("A 线"));
            check("python_search", first(py.search("中文", 2)).getString("vod_name").equals("中文"));
            JSONObject jsonResult = new JSONObject(py.search("中文", 2));
            check(
                    "python_json_signed_payload",
                    jsonResult
                            .getString("signed")
                            .equals("{\"a\":9007199254740993123456789,\"z\":\"中文\"}"));
            check(
                    "python_json_big_integer",
                    jsonResult.getString("big").equals("9007199254740993123456789"));
            JsEngine streams =
                    engine(
                            "streams.py",
                            "import io, json\n"
                                    + "class Spider:\n"
                                    + "    def homeContent(self, filter):\n"
                                    + "        text = io.StringIO(); json.dump({'name':'中文'}, text, ensure_ascii=False); text.seek(0)\n"
                                    + "        binary = io.BytesIO(bytes([0,255,128,65])); binary.seek(1); binary.write(b'X'); binary.seek(0)\n"
                                    + "        error = io.UnsupportedOperation('fixture')\n"
                                    + "        return {'text':json.load(text)['name'], 'bytes':list(binary.read()), 'exceptions':isinstance(error,OSError) and isinstance(error,ValueError)}\n");
            JSONObject streamResult = new JSONObject(streams.home());
            check("python_io_json_text", streamResult.getString("text").equals("中文"));
            check(
                    "python_io_binary",
                    streamResult.getJSONArray("bytes").toString().equals("[0,88,128,65]"));
            check("python_io_exception_bases", streamResult.getBoolean("exceptions"));
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
                                        + "            cookie = list(s.cookies)[0]\n"
                                        + "            assert cookie.name == 'sid' and cookie.value == 'fixture'\n"
                                        + "            assert cookie.domain == '127.0.0.1' and cookie.path == '/auth'\n"
                                        + "            assert not cookie.secure and cookie.expires is None and not cookie.domain_specified\n"
                                        + "            assert dict(s.cookies) == {'sid':'fixture'}\n"
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
            try (PythonBinaryServer server = new PythonBinaryServer()) {
                String base = "http://127.0.0.1:" + server.listener.getLocalPort();
                JsEngine binary =
                        engine(
                                "binary.py",
                                "import requests\n"
                                        + "class Spider:\n"
                                        + "    def homeContent(self, filter):\n"
                                        + "        data = bytes([0,255,128,65])\n"
                                        + "        r = requests.post('"
                                        + base
                                        + "/upload',data=data,timeout=3,headers={'Content-Type':'application/octet-stream'})\n"
                                        + "        assert r.json()['bytes'] == list(data)\n"
                                        + "        assert requests.post('"
                                        + base
                                        + "/empty',data=b'',timeout=3).json()['bytes'] == []\n"
                                        + "        return {'list':[{'vod_id':'binary','vod_name':'Binary upload verified'}]}\n");
                check(
                        "python_binary_real_http",
                        first(binary.home()).getString("vod_id").equals("binary"));
                server.thread.join(1000);
                check(
                        "python_binary_exact_bytes",
                        server.requests == 2
                                && server.error == null
                                && Arrays.equals(
                                        server.uploaded,
                                        new byte[] {0, (byte) 255, (byte) 128, 65}));
                check("python_empty_binary_body", server.empty != null && server.empty.length == 0);
            }
            exerciseScriptProxy();
            exerciseNativeDeadlines();
            exerciseFullSpiderContract();
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
