package com.pilipala.marcomovie;

import android.content.Context;
import com.pilipala.catpaw.CatPawHost;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import org.json.*;

/** Imported artifact integrity, request boundaries and exact fallback policy, without Node/JNI. */
public final class GuardChecks {
    static int checks;

    interface Work {
        void run() throws Exception;
    }

    static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }

    static void rejects(Work work, String name) throws Exception {
        try {
            work.run();
        } catch (IOException expected) {
            checks++;
            return;
        }
        throw new AssertionError(name);
    }

    static void rejectsDeclaration(Work work, String name) throws Exception {
        try {
            work.run();
        } catch (IOException expected) {
            check("插件 MD5 声明无效".equals(expected.getMessage()), name);
            return;
        }
        throw new AssertionError(name);
    }

    static String digest(byte[] bytes, String kind) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte value : MessageDigest.getInstance(kind).digest(bytes))
            out.append(String.format(Locale.US, "%02x", value & 255));
        return out.toString();
    }

    static byte[] fixture() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write("synthetic import transport fixture, never executable".getBytes("UTF-8"));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    static String authenticated(String base, String credentials) {
        return "http://" + credentials + "@" + base.substring("http://".length());
    }

    public static void main(String[] args) throws Exception {
        File work = new File(args[0]);
        File files = new File(work, "files");
        files.mkdirs();
        Context context = new Context(files, new File(work, "assets"));
        byte[] artifact = fixture();
        File local = new File(files, "input.jar");
        Files.write(local.toPath(), artifact);
        String md5 = digest(artifact, "MD5");
        String declaration = local.toURI() + ";md5;" + md5;
        check(CspEngine.obtain(context, declaration).equals(local), "local declared MD5 verified");
        check(
                CspEngine.obtain(context, local.toURI() + ";MD5;" + md5.toUpperCase(Locale.ROOT))
                        .equals(local),
                "uppercase marker and digest verified locally");
        String[] invalidDigests = {
            "", " ", "\t", "\n", " " + md5, md5 + " ", md5 + ";md5;" + md5, md5 + ";MD5;"
        };
        for (String marker : new String[] {";md5;", ";Md5;", ";MD5;"}) {
            for (String suffix : invalidDigests) {
                rejectsDeclaration(
                        () -> CspEngine.obtain(context, local.toURI() + marker + suffix),
                        "explicit local digest rejected without normalization");
            }
        }
        rejects(
                () ->
                        CspEngine.obtain(
                                context,
                                local.toURI() + ";md5;" + "00000000000000000000000000000000"),
                "local MD5 mismatch rejected");
        rejects(
                () -> CspEngine.obtain(context, local.toURI() + ";md5;bad"),
                "invalid MD5 declaration rejected");
        File outside = new File(work, "outside.jar");
        Files.write(outside.toPath(), artifact);
        rejects(
                () -> CspEngine.obtain(context, outside.toURI().toString()),
                "local boundary checked");
        File bad = new File(files, "invalid.jar");
        Files.write(bad.toPath(), new byte[] {1, 2, 3});
        rejects(
                () -> CspEngine.obtain(context, bad.toURI().toString()),
                "malformed archive rejected");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String second = "http://127.0.0.1:" + other.getAddress().getPort();
        List<String> authorizations = new ArrayList<>();
        List<String> queries = new ArrayList<>();
        com.sun.net.httpserver.HttpHandler jar =
                exchange -> {
                    authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    queries.add(exchange.getRequestURI().getRawQuery());
                    exchange.sendResponseHeaders(200, artifact.length);
                    exchange.getResponseBody().write(artifact);
                    exchange.close();
                };
        server.createContext("/jar", jar);
        other.createContext("/jar", jar);
        for (String[] route :
                new String[][] {
                    {"/same", "/jar?token=a%2Bb%2F"},
                    {"/cross", second + "/jar"},
                    {"/inject", authenticated(base, "new:login") + "/jar"}
                }) {
            server.createContext(
                    route[0],
                    exchange -> {
                        exchange.getResponseHeaders().set("Location", route[1]);
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                    });
        }
        final String[] result = {
            "{\"version\":1,\"rule\":\"var rule={title:'Fixture',host:'https://media.test'};\",\"origin\":\"https://media.test/\"}"
        };
        final int[] status = {200};
        List<JSONObject> calls = new ArrayList<>();
        server.createContext(
                "/fixture/_port-bootstrap",
                exchange -> {
                    try {
                        calls.add(new JSONObject(Cms.read(exchange.getRequestBody(), 512)));
                        byte[] response = result[0].getBytes("UTF-8");
                        exchange.sendResponseHeaders(status[0], response.length);
                        exchange.getResponseBody().write(response);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        other.start();
        Field endpoint = CatPawHost.class.getDeclaredField("endpoint");
        endpoint.setAccessible(true);
        try {
            CspEngine.obtain(context, authenticated(base, "user:p%2Bss") + "/same;md5;" + md5);
            check(
                    "Basic dXNlcjpwK3Nz".equals(authorizations.get(0))
                            && "token=a%2Bb%2F".equals(queries.get(0)),
                    "Basic and raw query retained on same-origin redirect");
            CspEngine.obtain(context, authenticated(base, "user:pass") + "/cross");
            check(authorizations.get(1) == null, "cross-origin redirect strips Basic");
            rejects(
                    () -> CspEngine.obtain(context, base + "/inject"),
                    "redirect cannot inject new credentials");
            rejects(
                    () ->
                            CspEngine.obtain(
                                    context, base + "/jar;md5;00000000000000000000000000000000"),
                    "download MD5 mismatch rejected");
            String rawAddress = base + "/jar?token=a%2Bb%2F";
            File downloaded =
                    CspEngine.obtain(context, rawAddress + ";MD5;" + md5.toUpperCase(Locale.ROOT));
            check(
                    Arrays.equals(Files.readAllBytes(downloaded.toPath()), artifact),
                    "remote uppercase checksum accepted and verified");
            check(
                    "token=a%2Bb%2F".equals(queries.get(queries.size() - 1)),
                    "raw URL query remains unchanged before checksum marker");
            CspEngine.obtain(context, rawAddress);
            check(
                    "token=a%2Bb%2F".equals(queries.get(queries.size() - 1)),
                    "URL without marker remains unchanged");
            String sha = digest(artifact, "SHA-256");
            int requestCount = queries.size();
            for (String marker : new String[] {";md5;", ";Md5;", ";MD5;"}) {
                for (String suffix : invalidDigests) {
                    String malformed = rawAddress + marker + suffix;
                    File selection =
                            new File(
                                    files,
                                    "csp/selection-"
                                            + CspEngine.hex(malformed.getBytes("UTF-8"))
                                            + ".txt");
                    Files.write(selection.toPath(), sha.getBytes("UTF-8"));
                    rejectsDeclaration(
                            () -> CspEngine.obtain(context, malformed),
                            "invalid remote checksum rejected before populated cache lookup");
                }
            }
            check(
                    queries.size() == requestCount,
                    "invalid declarations never reach the remote server");
            endpoint.set(null, base + "/fixture");
            Cms.Config config = new Cms.Config();
            config.origin = "https://subscription.test/config.php";
            config.spider = declaration;
            Cms.Site site = new Cms.Site();
            site.api = "csp_Dm84Guard";
            site.key = "guard_fixture";
            site.type = 3;
            site.headers.put("Authorization", "fixture-secret");
            site.headers.put("User-Agent", "Fixture");
            AtomicInteger fallbacks = new AtomicInteger();
            GuardEngine.Fallback fallback =
                    () -> {
                        fallbacks.incrementAndGet();
                        return null;
                    };
            SourceEngine engine = GuardEngine.open(context, config, site, fallback);
            check(
                    engine instanceof DrpyEngine && fallbacks.get() == 0,
                    "reviewed result uses real DRPY caller");
            JSONObject request = calls.get(0);
            check(
                    request.length() == 3
                            && request.getInt("version") == 1
                            && request.getString("adapter").equals("dm84-v1")
                            && request.getString("artifactHash")
                                    .equals(digest(artifact, "SHA-256")),
                    "strict capability bootstrap contract");
            File staged = new File(files, "catpaw/imports/" + digest(artifact, "SHA-256") + ".jar");
            check(
                    Arrays.equals(Files.readAllBytes(staged.toPath()), artifact),
                    "exact original bytes staged");
            Cms.Site portable = GuardEngine.portableSite(config, site, new JSONObject(result[0]));
            check(
                    portable.key.equals(site.key)
                            && !portable.headers.containsKey("Authorization")
                            && portable.headers.get("User-Agent").equals("Fixture"),
                    "identity preserved and cross-origin credentials removed");
            config.origin = "https://media.test/config.php";
            check(
                    GuardEngine.portableSite(config, site, new JSONObject(result[0]))
                            .headers
                            .containsKey("Authorization"),
                    "same-origin explicit source credentials retained");
            status[0] = 422;
            result[0] = "{\"error\":\"guard_artifact_unsupported\"}";
            GuardEngine.open(context, config, site, fallback);
            check(
                    fallbacks.get() == 1,
                    "exact unsupported artifact falls back to original Android host");
            for (String error :
                    new String[] {
                        "guard_artifact_integrity", "guard_artifact_invalid", "bootstrap_request"
                    }) {
                result[0] = new JSONObject().put("error", error).toString();
                rejects(
                        () -> GuardEngine.open(context, config, site, fallback),
                        "no fallback for " + error);
            }
            check(fallbacks.get() == 1, "integrity errors never invoke fallback");
            status[0] = 200;
            for (String invalid :
                    new String[] {
                        "{}",
                        "[]",
                        "{\"version\":1,\"rule\":\"var rule={};\",\"origin\":\"https://user:pass@media.test/\"}"
                    }) {
                result[0] = invalid;
                rejects(
                        () -> GuardEngine.open(context, config, site, fallback),
                        "malformed bootstrap rejected");
            }
            config.spider = local.toURI() + ";md5;00000000000000000000000000000000";
            int before = calls.size();
            rejects(
                    () -> GuardEngine.open(context, config, site, fallback),
                    "MD5 failure before bootstrap or fallback");
            check(
                    calls.size() == before && fallbacks.get() == 1,
                    "no fallback and no host call after bad imported digest");
            config.spider = declaration;
            endpoint.set(null, null);
            GuardEngine.open(context, config, site, fallback);
            check(
                    fallbacks.get() == 2,
                    "typed pre-start runtime unavailability keeps original Android host");
        } finally {
            endpoint.set(null, null);
            server.stop(0);
            other.stop(0);
        }
        System.out.println(
                "native Guard import: "
                        + checks
                        + " checks passed (no native Node execution claim)");
    }
}
