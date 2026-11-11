package com.whalefall.movie;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;

/** The source chooser stays above the current screen and never creates a second navigation page. */
final class SourcePicker {
    private static String profile(String origin) {
        return "source-picker:" + (origin == null ? "" : origin);
    }

    static void applyEdits(MainActivity a, Cms.Config config, String origin) {
        String legacyProfile = profile(origin);
        if (!config.origin.isEmpty()) origin = config.origin;
        String currentProfile = profile(origin);
        if (!currentProfile.equals(legacyProfile)
                && !a.prefs.contains(currentProfile)
                && a.prefs.contains(legacyProfile)) {
            String old = a.prefs.getString(legacyProfile, "{}");
            if (!a.prefs.edit().putString(currentProfile, old).remove(legacyProfile).commit()) {
                // Reading the old edit record still honors deletion if migration cannot persist.
                currentProfile = legacyProfile;
            }
        }
        try {
            org.json.JSONObject saved =
                    new org.json.JSONObject(a.prefs.getString(currentProfile, "{}"));
            org.json.JSONArray order = saved.optJSONArray("order"),
                    removed = saved.optJSONArray("removed");
            java.util.Map<String, Cms.Site> byKey = new java.util.LinkedHashMap<>();
            for (Cms.Site site : config.sites) byKey.put(site.key, site);
            if (removed != null)
                for (int i = 0; i < removed.length(); i++) byKey.remove(removed.optString(i));
            config.sites.clear();
            if (order != null)
                for (int i = 0; i < order.length(); i++) {
                    Cms.Site site = byKey.remove(order.optString(i));
                    if (site != null) config.sites.add(site);
                }
            config.sites.addAll(byKey.values());
        } catch (org.json.JSONException ignored) {
            /* Keep the imported order. */
        }
    }

    static void manage(MainActivity a) {
        Cms.Config config = a.config;
        if (config == null || config.sites.isEmpty()) {
            a.notice("暂无可管理的线路");
            return;
        }
        // 1.9.28 P1-C：管理列表展示全部源（禁用源标注，不隐藏），避免重排索引错位
        java.util.List<Cms.Site> all = new java.util.ArrayList<>(config.sites);
        String[] names = new String[all.size()];
        int disabled = 0;
        for (int i = 0; i < names.length; i++) {
            boolean off = SourceCheckScreen.isDisabled(a, all.get(i));
            if (off) disabled++;
            names[i] = (off ? "⛔ " : "") + all.get(i).name + (off ? "（已禁用）" : "");
        }
        AppDialog.Builder builder =
                new AppDialog.Builder(a)
                        .wrapChoices()
                        .setTitle("管理线路" + (disabled > 0 ? "（" + disabled + " 个已禁用）" : ""))
                        .setItems(
                                names,
                                (d, index) -> {
                                    Cms.Site site = all.get(index);
                            new AppDialog.Builder(a)
                                    .wrapChoices()
                                    .setTitle(site.name)
                                    .setItems(
                                            new String[] {"上移", "下移", "移到首位", "删除这条线路"},
                                            (action, choice) -> {
                                                if (choice == 3) {
                                                    new AppDialog.Builder(a)
                                                            .wrapChoices()
                                                            .setTitle("删除「" + site.name + "」？")
                                                            .setNegativeButton("取消", null)
                                                            .setPositiveButton(
                                                                    "删除",
                                                                    (ok, which) ->
                                                                            edit(
                                                                                    a, config, site,
                                                                                    -1))
                                                            .show();
                                                } else {
                                                    int target =
                                                            choice == 2
                                                                    ? 0
                                                                    : Math.max(
                                                                            0,
                                                                            Math.min(
                                                                                    names.length
                                                                                            - 1,
                                                                                    index
                                                                                            + (choice
                                                                                                            == 0
                                                                                                    ? -1
                                                                                                    : 1)));
                                                    edit(a, config, site, target);
                                                }
                                            })
                                    .show();
                        });
        if (disabled > 0) {
            builder.setNeutralButton(
                    "去恢复已禁用源",
                    (d, i) -> {
                        d.dismiss();
                        SourceCheckScreen.show(a);
                    });
        }
        builder.show();
    }

    private static void edit(MainActivity a, Cms.Config config, Cms.Site site, int target) {
        if (a.config != config) return;
        try {
            String key = profile(config.origin.isEmpty() ? a.configOrigin : config.origin);
            org.json.JSONObject saved = new org.json.JSONObject(a.prefs.getString(key, "{}"));
            java.util.List<Cms.Site> sites = new java.util.ArrayList<>(config.sites);
            sites.remove(site);
            if (target >= 0) {
                // 1.9.28 P1-C：管理列表与配置列表一致（含禁用源），target 即真实下标
                sites.add(Math.max(0, Math.min(sites.size(), target)), site);
            } else {
                org.json.JSONArray hidden = saved.optJSONArray("removed");
                if (hidden == null) hidden = new org.json.JSONArray();
                saved.put("removed", hidden.put(site.key));
            }
            org.json.JSONArray order = new org.json.JSONArray();
            for (Cms.Site s : sites) order.put(s.key);
            saved.put("order", order);
            android.content.SharedPreferences.Editor editor =
                    a.prefs.edit().putString(key, saved.toString());
            if (target < 0 && site.key.equals(a.prefs.getString(Prefs.SELECTED_SITE, ""))) {
                editor.remove(Prefs.SELECTED_SITE);
                for (Cms.Site s : sites)
                    if (a.available(s)) {
                        editor.putString(Prefs.SELECTED_SITE, s.key);
                        break;
                    }
            }
            if (!editor.commit()) {
                a.notice("线路变更未能保存，请重试");
                return;
            }
            config.sites.clear();
            config.sites.addAll(sites);
            a.back.clear();
            a.open(new MainActivity.Screen(a::home), false);
            show(a);
        } catch (org.json.JSONException e) {
            a.notice("无法保存线路顺序");
        }
    }

    static void show(MainActivity a) {
        if (a.config == null) {
            a.importDialog();
            return;
        }
        View anchor = a.getCurrentFocus();
        Dialog dialog = new Dialog(a);
        a.sourceDialog = dialog;
        dialog.setOnDismissListener(
                d -> {
                    ModalStack.pop(dialog.getWindow() == null ? null : dialog.getWindow().getDecorView());
                    if (a.sourceDialog == dialog) a.sourceDialog = null;
                    if (anchor != null && anchor.isAttachedToWindow()) anchor.requestFocus();
                    a.ui.postDelayed(() -> TvNav.audit(a, "source-dismissed"), 120);
                });
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = a.vertical();
        panel.setTag("source-picker");
        panel.setPadding(a.dp(24), a.dp(22), a.dp(24), a.dp(18));
        a.glass(panel, 22);
        TextView title = a.text("选择片源", 24, MainActivity.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        panel.addView(title);
        a.gap(panel, 4);
        TextView hint = a.text("选择后返回首页，显示该片源的内容", 13, MainActivity.MUTED);
        panel.addView(hint);
        a.gap(panel, 12);
        LinearLayout topActions = a.row();
        if (a.config.depots.length() > 0) {
            Button depotBtn =
                    a.navButton(
                            "切换子仓库",
                            "warehouse-switch",
                            () -> {
                                dialog.dismiss();
                                showWarehouses(a);
                            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(38), 1);
            lp.rightMargin = a.dp(6);
            topActions.addView(depotBtn, lp);
        }
        Button prefBtn =
                a.navButton(
                        "默认偏好源",
                        "source-preferred",
                        () -> {
                            dialog.dismiss();
                            SourcePreferences.choose(a);
                        });
        LinearLayout.LayoutParams prefLp = new LinearLayout.LayoutParams(0, a.dp(38), 1);
        if (CatPawSettings.available(a)) prefLp.rightMargin = a.dp(6);
        topActions.addView(prefBtn, prefLp);
        if (CatPawSettings.available(a)) {
            Button catBtn =
                    a.navButton(
                            "CatPaw 配置",
                            "catpaw-settings",
                            () -> {
                                dialog.dismiss();
                                CatPawSettings.choose(a);
                            });
            topActions.addView(catBtn, new LinearLayout.LayoutParams(0, a.dp(38), 1));
        }
        panel.addView(topActions, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 10);
        ScrollView scrolling = new TvScrollView(a);
        scrolling.setVerticalScrollBarEnabled(false);
        LinearLayout grid = a.vertical();
        grid.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
        scrolling.addView(grid);
        FrameLayout viewport =
                new FrameLayout(a) {
                    @Override
                    protected void dispatchDraw(android.graphics.Canvas c) {
                        int save = c.save();
                        c.clipRect(0, 0, getWidth(), getHeight());
                        super.dispatchDraw(c);
                        c.restoreToCount(save);
                    }
                };
        viewport.setTag("scroll-viewport-clip");
        viewport.setClipChildren(true);
        viewport.setClipToPadding(true);
        viewport.addView(scrolling, new FrameLayout.LayoutParams(-1, -1));
        panel.addView(viewport, new LinearLayout.LayoutParams(-1, a.dp(286)));
        String selected = a.prefs.getString(Prefs.SELECTED_SITE, "");
        // 1.9.28：隐藏已禁用源，并在默认偏好/当前源被禁用时回退
        java.util.List<Cms.Site> visible = new java.util.ArrayList<>();
        int hidden = 0;
        for (Cms.Site site : a.config.sites) {
            if (SourceCheckScreen.isDisabled(a, site)) hidden++;
            else visible.add(site);
        }
        AppLog.i("SourcePicker", "hidden=" + hidden + " total=" + a.config.sites.size());
        final StringBuilder fallbackMessage = new StringBuilder();
        if (hidden > 0) {
            boolean selectedDisabled = false;
            for (Cms.Site site : a.config.sites)
                if (site.key.equals(selected) && SourceCheckScreen.isDisabled(a, site))
                    selectedDisabled = true;
            boolean preferredDisabled = false;
            for (Cms.Site site : a.config.sites)
                if (SourceCheckScreen.isDisabled(a, site)
                        && SourcePreferences.isPreferred(a, site)) preferredDisabled = true;
            // 1.9.28 P1-B：fallback 必须是可用源；优先默认偏好源
            Cms.Site fallback = null;
            for (Cms.Site site : visible) {
                if (!a.available(site)) continue;
                if (fallback == null) fallback = site;
                if (SourcePreferences.isPreferred(a, site)) {
                    fallback = site;
                    break;
                }
            }
            if (fallback != null && (selectedDisabled || preferredDisabled)) {
                android.content.SharedPreferences.Editor editor = a.prefs.edit();
                if (selectedDisabled) {
                    editor.putString(Prefs.SELECTED_SITE, fallback.key)
                            .putString(Prefs.LOADING_SITE, fallback.key);
                    selected = fallback.key;
                }
                if (preferredDisabled)
                    editor.putString(Prefs.PREFERRED_SITE, fallback.key + "\n" + fallback.api);
                editor.apply();
                fallbackMessage.append(
                        (selectedDisabled ? "当前片源" : "默认偏好源")
                                + "已被禁用，已回退到「"
                                + fallback.name
                                + "」");
            } else if (selectedDisabled || preferredDisabled) {
                fallbackMessage.append("当前/默认偏好源已被禁用，且没有可用片源，请去片源体检恢复");
            }
        }
        if (visible.isEmpty()) {
            if (fallbackMessage.length() > 0) fallbackMessage.append("；");
            fallbackMessage.append("所有片源已禁用，请先去恢复");
        }
        final String fallbackToast = fallbackMessage.toString();
        Button selectedButton = null;
        LinearLayout row = null;
        float fontScale = a.getResources().getConfiguration().fontScale;
        int columns =
                Math.max(
                        1,
                        Math.min(
                                3,
                                (int)
                                        ((Math.min(a.windowWidth(), a.dp(720)) - a.dp(100))
                                                / (a.dp(230) * Math.max(1f, fontScale)))));
        int index = 0;
        for (Cms.Site site : visible) {
            if (index % columns == 0) {
                row = a.row();
                row.setClipChildren(false);
                grid.addView(row);
            }
            boolean current = site.key.equals(selected);
            Button button =
                    a.button(
                            (current ? "✓  " : "")
                                    + (SourcePreferences.isPreferred(a, site) ? "★ " : "")
                                    + site.name,
                            "source-choice:" + site.key,
                            () -> {
                                if (site.api.startsWith("catpaw://")
                                        && android.net.Uri.parse(site.api)
                                                .getPath()
                                                .equals("/api/baseset")) {
                                    dialog.dismiss();
                                    CatPawSettings.open(a, site);
                                    return;
                                }
                                if (!a.available(site)) {
                                    a.notice("这个片源需要启用来源扩展，或仍有接口待适配。可以在设置中查看。");
                                    return;
                                }
                                a.prefs
                                        .edit()
                                        .putString(Prefs.SELECTED_SITE, site.key)
                                        .putString(Prefs.LOADING_SITE, site.key)
                                        .commit();
                                dialog.dismiss();
                                a.back.clear();
                                a.open(new MainActivity.Screen(a::home), false);
                            });
            button.setTextSize(14);
            button.setSingleLine(false);
            button.setMaxLines(Integer.MAX_VALUE);
            button.setEllipsize(null);
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setMinHeight(a.dp(56));
            button.setPadding(a.dp(13), a.dp(10), a.dp(13), a.dp(10));
            boolean enabled = a.available(site);
            button.setSelected(current);
            Runnable paint =
                    () -> {
                        boolean focused = button.hasFocus();
                        FocusMotion.style(
                                a,
                                button,
                                focused,
                                current ? 0xff35353f : 0xff172742,
                                MainActivity.WHITE,
                                12,
                                enabled ? MainActivity.WHITE : MainActivity.MUTED,
                                MainActivity.DARK,
                                false,
                                1.02f);
                    };
            button.setOnFocusChangeListener((v, f) -> paint.run());
            paint.run();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
            row.addView(button, lp);
            if (current) selectedButton = button;
            index++;
        }
        if (row != null)
            while (index++ % columns != 0)
                row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        a.gap(panel, 10);
        TextView count = a.text(visible.size() + " 个片源", 12, MainActivity.MUTED);
        panel.addView(count);
        Button restoreButton = null;
        if (hidden > 0) {
            Button hiddenRow =
                    a.navButton(
                            "已隐藏 " + hidden + " 个已禁用源 · 去恢复",
                            "source-restore-disabled",
                            () -> {
                                dialog.dismiss();
                                SourceCheckScreen.show(a);
                            });
            hiddenRow.setTextColor(0xffffb454);
            hiddenRow.setMinHeight(a.dp(44));
            hiddenRow.setMinimumHeight(a.dp(44));
            panel.addView(hiddenRow, new LinearLayout.LayoutParams(-1, a.dp(44)));
            restoreButton = hiddenRow;
        }
        a.gap(panel, 6);
        LinearLayout footer = a.row();
        a.addButton(
                footer,
                a.navButton(
                        "管理线路",
                        "source-manage",
                        () -> {
                            dialog.dismiss();
                            manage(a);
                        }));
        a.addButton(
                footer,
                a.navButton(
                        "更新配置",
                        "source-update",
                        () -> {
                            dialog.dismiss();
                            a.importDialog();
                        }));
        a.addButton(footer, a.navButton("完成", "source-close", dialog::dismiss));
        for (int i = 0; i < footer.getChildCount(); i++) {
            View control = footer.getChildAt(i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, a.dp(40), 1);
            params.setMargins(a.dp(3), a.dp(2), a.dp(3), a.dp(2));
            control.setLayoutParams(params);
            if (control instanceof TextView) {
                ((TextView) control).setSingleLine(true);
                ((TextView) control).setEllipsize(android.text.TextUtils.TruncateAt.END);
                ((TextView) control).setTextSize(13);
                control.setPadding(a.dp(8), 0, a.dp(8), 0);
            }
        }
        panel.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        int marginH = a.isTvDevice() ? a.dp(48) : a.dp(28);
        int panelWidth = Math.min(a.dp(720), a.windowWidth() - marginH);
        int maxHeight = Math.max(a.dp(180), a.windowHeight() - a.dp(a.isTvDevice() ? 32 : 96));
        panel.measure(
                View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int controlsHeight = panel.getMeasuredHeight() - a.dp(286);
        viewport.setLayoutParams(
                new LinearLayout.LayoutParams(
                        -1, Math.max(a.dp(96), Math.min(a.dp(320), maxHeight - controlsHeight))));
        panel.measure(
                View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        if (panel.getMeasuredHeight() > maxHeight) {
            ScrollView outer = new TvScrollView(a);
            outer.addView(panel);
            dialog.setContentView(outer, new ViewGroup.LayoutParams(panelWidth, maxHeight));
        } else dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(panelWidth, -2);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = .65f;
            window.setAttributes(params);
        }
        Button focus = selectedButton;
        final Button restoreFocus = restoreButton;
        dialog.setOnShowListener(
                d -> {
                    if (focus != null) focus.requestFocus();
                    else if (restoreFocus != null) restoreFocus.requestFocus();
                    else if (grid.getChildCount() > 0)
                        ((LinearLayout) grid.getChildAt(0)).getChildAt(0).requestFocus();
                    if (!fallbackToast.isEmpty()) a.toast(fallbackToast);
                });
        dialog.show();
        if (window != null) window.setLayout(Math.min(a.dp(720), a.root.getWidth() - marginH), -2);
        // 1.9.77：选择片源弹窗注册模态栈，遥控键只作用于弹窗内部
        View pickerDecor = window == null ? null : window.getDecorView();
        ModalStack.push("source-picker", pickerDecor, dialog::dismiss);
        a.ui.post(() -> ModalStack.focusFirst(a, pickerDecor));
    }

    static void showWarehouses(MainActivity a) {
        Cms.Config previous = a.config;
        org.json.JSONArray rows = previous.depots;
        String[] names = new String[rows.length()];
        for (int i = 0; i < names.length; i++) names[i] = rows.optJSONObject(i).optString("name");
        new AppDialog.Builder(a)
                .wrapChoices()
                .setTitle("切换子仓库")
                .setItems(
                        names,
                        (dialog, index) -> {
                            String url = rows.optJSONObject(index).optString("url");
                            a.toast("正在读取子仓库…");
                            a.worker.execute(
                                    () -> {
                                        try {
                                            Cms.Config selected =
                                                    Cms.parseConfig(
                                                            SourceController.fetchImport(a, url),
                                                            url);
                                            String combined =
                                                    com.pilipala.source.Warehouse.retainDirectory(
                                                            selected.raw,
                                                            selected.origin,
                                                            previous.raw,
                                                            raw ->
                                                                    new org.json.JSONObject(
                                                                            Cms.withoutComments(
                                                                                    raw)));
                                            Cms.Config ready =
                                                    Cms.parseConfig(combined, selected.origin);
                                            a.ui.post(
                                                    () -> {
                                                        if (a.isFinishing() || a.config != previous)
                                                            return;
                                                        try {
                                                            SourceController.saveConfig(
                                                                    a, ready, ready.origin);
                                                        } catch (Exception e) {
                                                            a.notice("子仓库保存失败，请重试");
                                                        }
                                                    });
                                        } catch (Exception e) {
                                            a.ui.post(
                                                    () -> {
                                                        if (!a.isFinishing())
                                                            a.notice("子仓库读取失败，已保留原线路");
                                                    });
                                        }
                                    });
                        })
                .show();
    }
}
