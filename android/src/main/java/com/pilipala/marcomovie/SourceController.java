package com.pilipala.marcomovie;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Manages source engine lifecycles, detail loading, phonetic title indexing, and configuration /
 * subtitle / danmaku document imports.
 */
final class SourceController {
    private SourceController() {}

    static boolean available(MainActivity a, Cms.Site s) {
        return s.supported()
                || (a.prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, false)
                        && (CspEngine.eligible(s) || JsEngine.eligible(s))
                        && a.config != null
                        && !a.config.spider.isEmpty());
    }

    static SourceEngine engine(MainActivity a, Cms.Site s) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        final Cms.Config snapshot;
        final int epoch;
        synchronized (a) {
            SourceEngine existing = a.engines.get(s.key);
            if (existing != null) return existing;
            snapshot = a.config;
            epoch = a.engineEpoch;
        }
        return a.engineInitializations.get(
                epoch + ":" + s.key,
                () -> {
                    synchronized (a) {
                        if (epoch != a.engineEpoch) throw new IOException("配置已更改，请重新选择来源");
                        SourceEngine existing = a.engines.get(s.key);
                        if (existing != null) return existing;
                    }
                    SourceEngine raw;
                    if (s.supported()) raw = new JsonCmsEngine(s);
                    else if (a.prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, false)
                            && CspEngine.eligible(s)
                            && snapshot != null) {
                        ensureProxy(a, snapshot);
                        raw = new CspEngine(a.getApplication(), snapshot, s);
                    } else if (a.prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, false)
                            && JsEngine.eligible(s)
                            && snapshot != null) {
                        ensureProxy(a, snapshot);
                        raw = new JsEngine(a.getApplication(), snapshot, s);
                    } else throw new IOException("来源需要尚未启用的扩展引擎");
                    SourceEngine created = new QueuedSourceEngine(raw);
                    synchronized (a) {
                        if (epoch == a.engineEpoch) {
                            if (raw instanceof JsEngine) ((JsEngine) raw).activate();
                            a.engines.put(s.key, created);
                            return created;
                        }
                    }
                    created.close();
                    throw new IOException("配置已更改，请重新选择来源");
                });
    }

    static void ensureProxy(MainActivity a, Cms.Config snapshot) throws IOException {
        synchronized (a) {
            if (a.localProxy == null) a.localProxy = new LocalProxy(a.getApplication(), snapshot);
        }
    }

    static void closeEngines(MainActivity a) {
        a.liveSession = null;
        a.detailCache.clear();
        a.homeData.clear();
        if (a.localProxy != null) {
            a.localProxy.close();
            a.localProxy = null;
        }
        final List<SourceEngine> old;
        synchronized (a) {
            a.engineEpoch++;
            old = new ArrayList<>(a.engines.values());
            a.engines.clear();
        }
        if (!old.isEmpty())
            new Thread(
                            () -> {
                                for (SourceEngine e : old) e.close();
                            },
                            "close-source-engines")
                    .start();
    }

    static void loadDetail(
            MainActivity a, Cms.Site site, String id, MainActivity.Done<Cms.Video> done) {
        final int ticket = a.generation, epoch = a.engineEpoch;
        final String key = epoch + ":" + site.key.length() + ":" + site.key + id;
        final DetailRequest timing = new DetailRequest();
        Future<?> request =
                RequestScope.submit(
                        a.details,
                        () -> {
                            try {
                                timing.dispatched = SystemClock.elapsedRealtime();
                                String raw = a.detailCache.get(key);
                                timing.cached = raw != null;
                                if (raw == null) {
                                    SourceEngine source = a.engine(site);
                                    timing.engineReady = SystemClock.elapsedRealtime();
                                    long[] phases = new long[2];
                                    raw = SourceGate.measured(phases, () -> source.detail(id));
                                    timing.gateWaitNanos = phases[0];
                                    timing.delegateNanos = phases[1];
                                    timing.returned = SystemClock.elapsedRealtime();
                                } else
                                    timing.engineReady =
                                            timing.returned = SystemClock.elapsedRealtime();
                                if (Thread.currentThread().isInterrupted()) return;
                                Cms.Listing listing = Cms.listing(raw);
                                if (listing.videos.isEmpty()) throw new IOException("来源没有返回影片详情");
                                timing.parsed = SystemClock.elapsedRealtime();
                                if (!timing.cached && epoch == a.engineEpoch)
                                    a.detailCache.put(key, raw);
                                Cms.Video video = listing.videos.get(0);
                                a.ui.post(
                                        () -> {
                                            if (a.isFinishing() || ticket != a.generation) return;
                                            try {
                                                done.accept(video);
                                                a.root.postOnAnimation(timing::ready);
                                            } catch (Exception error) {
                                                a.paragraph("详情数据无法显示，请返回后重试。");
                                            }
                                        });
                            } catch (Exception error) {
                                if (Thread.currentThread().isInterrupted()) return;
                                a.ui.post(
                                        () -> {
                                            if (a.isFinishing() || ticket != a.generation) return;
                                            View state = a.root.findViewWithTag("detail-loading");
                                            if (state instanceof TextView)
                                                ((TextView) state).setText(a.message(error));
                                            LinearLayout target =
                                                    state != null
                                                                    && state.getParent()
                                                                            instanceof LinearLayout
                                                            ? (LinearLayout) state.getParent()
                                                            : a.body;
                                            a.addButton(
                                                    target, a.button("重试", "primary", a::refresh));
                                        });
                            }
                        });
        a.pageRequests.removeIf(Future::isDone);
        a.pageRequests.add(request);
    }

    static <T> void load(
            MainActivity a, String label, MainActivity.Work<T> task, MainActivity.Done<T> done) {
        final int ticket = a.generation;
        TextView loading = a.text(label, 17, MainActivity.MUTED);
        a.body.addView(loading);
        a.submitPage(
                () -> {
                    try {
                        T value = task.run();
                        a.ui.post(
                                () -> {
                                    if (a.isFinishing() || ticket != a.generation) return;
                                    a.body.removeView(loading);
                                    try {
                                        done.accept(value);
                                    } catch (Exception e) {
                                        a.notice("数据格式不符合此版本支持的格式。");
                                    }
                                });
                    } catch (Exception e) {
                        a.ui.post(
                                () -> {
                                    if (a.isFinishing() || ticket != a.generation) return;
                                    a.body.removeView(loading);
                                    a.paragraph(a.message(e));
                                    a.addButton(a.body, a.button("重试", "primary", a::refresh));
                                });
                    }
                });
    }

    static String message(Exception e) {
        String s = e.getMessage();
        if (e instanceof CspEngine.EngineException) return s;
        if (s != null
                && (s.startsWith("配置")
                        || s.startsWith("来源返回 HTTP")
                        || s.startsWith("来源没有")
                        || s.startsWith("来源重定向")
                        || s.startsWith("单页超过")
                        || s.startsWith("响应超过")
                        || s.startsWith("剧集返回")
                        || s.startsWith("媒体地址返回")
                        || s.startsWith("此剧集"))) return s;
        return "连接超时、来源不可达或数据格式不受支持。请检查配置与网络后重试。";
    }

    static void learnTitles(MainActivity a, List<Cms.Video> videos) {
        if (a.phonetic == null || videos == null || videos.isEmpty()) return;
        for (Cms.Video video : videos)
            if (video.title != null && !video.title.isEmpty()) a.pendingTitles.add(video.title);
        while (a.pendingTitles.size() > 400)
            a.pendingTitles.remove(a.pendingTitles.iterator().next());
        a.ui.removeCallbacks(a.learnPendingTitles);
        a.ui.postDelayed(a.learnPendingTitles, 500);
    }

    static void learnPendingTitles(MainActivity a) {
        List<String> titles = new ArrayList<>(a.pendingTitles);
        a.pendingTitles.clear();
        final PhoneticSearchIndex index = a.phonetic;
        final android.content.SharedPreferences target = a.prefs;
        if (index == null || titles.isEmpty()) return;
        a.phoneticWork.execute(
                () -> {
                    LinkedHashSet<String> values = new LinkedHashSet<>();
                    try {
                        org.json.JSONArray previous =
                                new org.json.JSONArray(
                                        target.getString(Prefs.PHONETIC_TITLES, "[]"));
                        for (int i = 0; i < previous.length(); i++)
                            values.add(previous.optString(i));
                    } catch (Exception ignored) {
                    }
                    for (String title : titles) {
                        index.learn(title);
                        values.add(title);
                    }
                    while (values.size() > 400) values.remove(values.iterator().next());
                    target.edit()
                            .putString(
                                    Prefs.PHONETIC_TITLES,
                                    new org.json.JSONArray(values).toString())
                            .apply();
                });
    }

    static void sourceAction(MainActivity a, Cms.Site site, String action) {
        a.detailCache.clear();
        final int ticket = a.generation;
        a.worker.execute(
                () -> {
                    try {
                        String response = a.engine(site).action(action);
                        a.ui.post(
                                () -> {
                                    if (ticket != a.generation
                                            || a.isFinishing()
                                            || response == null
                                            || response.trim().isEmpty()) return;
                                    try {
                                        org.json.JSONObject result =
                                                new org.json.JSONObject(response);
                                        if (result.has("list")) {
                                            Cms.Listing list = Cms.listing(response);
                                            new AppDialog.Builder(a)
                                                    .setTitle(site.name)
                                                    .setItems(
                                                            list.videos.stream()
                                                                    .map(v -> v.title)
                                                                    .toArray(String[]::new),
                                                            (dialog, index) ->
                                                                    a.detail(
                                                                            site,
                                                                            list.videos.get(index)))
                                                    .setNegativeButton("关闭", null)
                                                    .show();
                                        } else
                                            a.notice(
                                                    result.optString(
                                                            "msg",
                                                            result.optString("message", response)));
                                    } catch (Exception ignored) {
                                        a.notice(response);
                                    }
                                });
                    } catch (Exception error) {
                        a.ui.post(
                                () -> {
                                    if (ticket == a.generation) a.notice(a.message(error));
                                });
                    }
                });
    }

    static void pickDocument(MainActivity a, int requestCode, String missingPickerNotice) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            a.startActivityForResult(intent, requestCode);
        } catch (Exception error) {
            a.notice(missingPickerNotice);
        }
    }

    static void addressImportDialog(MainActivity a) {
        AddressInput input =
                new AddressInput(a, "https://…/config.json", a.prefs.getString(Prefs.ORIGIN, ""));
        AppDialog d =
                new AppDialog.Builder(a)
                        .setTitle("导入配置")
                        .setView(input)
                        .setNegativeButton("取消", null)
                        .setNeutralButton("选择本地文件", (x, y) -> a.pickConfig())
                        .setPositiveButton("导入地址", null)
                        .create();
        d.setOnShowListener(
                x ->
                        d.getButton(-1)
                                .setOnClickListener(
                                        view -> {
                                            String address = input.value();
                                            if (!Cms.isHttp(address)) {
                                                input.invalid("请输入 HTTP(S) 配置地址");
                                                return;
                                            }
                                            d.dismiss();
                                            a.open(
                                                    new MainActivity.Screen(
                                                            () -> {
                                                                a.renderPage(
                                                                        "正在导入",
                                                                        "IMPORT CONFIGURATION");
                                                                a.load(
                                                                        "连接配置并读取来源…",
                                                                        () -> {
                                                                            String raw =
                                                                                    Cms.fetch(
                                                                                            address,
                                                                                            null);
                                                                            return Cms.parseConfig(
                                                                                    raw, address);
                                                                        },
                                                                        c ->
                                                                                a.saveConfig(
                                                                                        c,
                                                                                        address));
                                                            }),
                                                    true);
                                        }));
        d.show();
    }

    static void onActivityResult(MainActivity a, int request, int result, Intent data) {
        if (request == UpdateScreen.INSTALL) {
            UpdateScreen.result(a);
            return;
        }
        if (request >= 44 && request <= 46) {
            if (result == Activity.RESULT_OK && data != null && data.getData() != null)
                SettingsScreen.result(a, request, data.getData());
            return;
        }
        if (request == 43
                && result == Activity.RESULT_OK
                && data != null
                && data.getData() != null) {
            final PlaybackControls controls = a.playbackControls;
            if (controls != null) {
                Uri file = data.getData();
                a.worker.execute(
                        () -> {
                            try (InputStream in = a.getContentResolver().openInputStream(file)) {
                                String text = Cms.read(in, 8 * 1024 * 1024);
                                a.ui.post(
                                        () -> {
                                            if (controls == a.playbackControls) {
                                                controls.danmaku.enabled(true);
                                                controls.danmaku.loadText(
                                                        text,
                                                        new DanmakuView.Listener() {
                                                            public void loaded(int count) {
                                                                a.toast("已加载 " + count + " 条弹幕");
                                                            }

                                                            public void error(String message) {
                                                                a.notice(message);
                                                            }
                                                        });
                                            }
                                        });
                            } catch (Exception error) {
                                AppLog.w("SourceController", "load danmaku file failed", error);
                                a.ui.post(() -> a.notice("弹幕文件无法读取。"));
                            }
                        });
            }
            return;
        }
        if (request == 42
                && result == Activity.RESULT_OK
                && data != null
                && data.getData() != null) {
            Uri subtitle = data.getData();
            if (a.player != null && a.prepared) {
                String name = "外挂字幕";
                try (android.database.Cursor cursor =
                        a.getContentResolver()
                                .query(
                                        subtitle,
                                        new String[] {
                                            android.provider.OpenableColumns.DISPLAY_NAME
                                        },
                                        null,
                                        null,
                                        null)) {
                    if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                } catch (Exception ignored) {
                }
                try {
                    a.player.addExternalSubtitle(
                            subtitle, name, MediaOptions.subtitleMime(name, ""));
                    a.toast("已导入字幕");
                } catch (Exception error) {
                    AppLog.w("SourceController", "load subtitle file failed", error);
                    a.notice("字幕无法读取，请选择 SRT、VTT、ASS 或 TTML 文件。");
                }
            }
            return;
        }
        if (request == 41
                && result == Activity.RESULT_OK
                && data != null
                && data.getData() != null) {
            Uri uri = data.getData();
            a.open(
                    new MainActivity.Screen(
                            () -> {
                                a.renderPage("正在读取文件", "IMPORT CONFIGURATION");
                                a.load(
                                        "读取本地配置…",
                                        () -> {
                                            try (InputStream i =
                                                    a.getContentResolver().openInputStream(uri)) {
                                                return Cms.parseConfig(
                                                        Cms.read(i, Cms.MAX_JSON), null);
                                            }
                                        },
                                        c -> a.saveConfig(c, ""));
                            }),
                    true);
        }
    }

    static void saveConfig(MainActivity a, Cms.Config c, String origin) throws Exception {
        a.configStore.write(c.raw);
        a.closeEngines();
        a.config = c;
        a.configOrigin = origin;
        a.prefs.edit().putString(Prefs.ORIGIN, origin).apply();
        a.back.clear();
        a.current = new MainActivity.Screen(a::home);
        a.current.render.run();
        a.restore(a.current);
        a.toast("已导入 " + c.sites.size() + " 个来源");
    }
}
