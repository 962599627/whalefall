# 鲸落 1.3.1 发布说明草稿（手机 / 平板）

> 状态：**待用户真机验证后发布**。修复内容为 P1（DEF-2010 直播加载浮层不消失、DEF-2012 点频道布局错乱/播放器不出现）与 P2（DEF-2011 电视 tab 顶部按钮被裁切）；B 类真机证据未采集，Release notes 必须保留「未真机验证」标注。
> 维护：Lead ｜ 日期：2026-11-09 ｜ 上一版：1.3.0（未发布，已被本版取代）

## 版本信息

| 项 | 值 |
|---|---|
| versionName / versionCode | **1.3.1 / 13001**（`android/version.properties`） |
| 包名 | `com.whalefall.movie`，minSdk 23 / targetSdk 28 |
| 产物 | `dist/WhaleFall-1.3.1-android-mobile.apk`、`dist/WhaleFall-1.3.1-android-tv.apk` + `.sha256` |
| 交付路径 | `/sdcard/Download/DSH-Go/鲸落/` |
| 构建 | `scripts/build_android.py`（clean 树构建，`git_dirty=false`） |

## 本版内容

1. **修复：电视台直播画面已出但「正在连接/正在加载官方页面」浮层不消失**（DEF-2010，P1）。官方直播页主文档进度可能长期停在 <100%，原实现只在 `onPageFinished` 收起浮层。现在三条独立出口：页面内 `<video>` 真实播放探针（每 1.8s）、页面进度到 100%、网页原生全屏；并顺带把注入脚本单例化（DEF-2002「1.8s 无限重跑」的多循环叠加消除）。
2. 首页海报网格 RecyclerView 虚拟化（同 1.3.0，E3-S1）。
3. 陕西卫视改为官方直播页 `http://live.snrtv.com/star`（可能先弹「确定前往」）。
4. 设置页「关于」版本号改为运行时读取（不再硬编码 1.2.0）。
5. **修复：电视 tab 顶部按钮在窄屏被右缘裁切**（DEF-2011，P2）。窄屏（手机竖屏 390dp）标题行只保留标题，「＋自定义 / 导入列表 / 管理」三个操作按钮移到标题下方独立一行、右对齐，不再被裁切。
6. **修复：点频道后布局错乱、播放器不出现**（DEF-2012，P1）。直播播放中旋转不再重建整页（`onConfigurationChanged` 增加播放器分支只做 relayout）；退出播放器后按竖屏重建、底部 Dock 恢复；手机竖屏不再出现右侧竖栏。**此项未真机验证。**

## 验证与证据

- C 类（代码评审）：修复仅 `TvLive.java`（+32/-5），三条浮层出口 + inject 单例；DEF-2010/DEF-2002 已登记 `docs/DEFECTS.md`「已修待验证」。
- C 类（代码评审，`1b8f45b`）：DEF-2011/DEF-2012 —— `TvLive` 标题行窄屏换行（操作 pill 换行右对齐）+ 转屏时序（`show` 先登记 `tvPlayer` 再转屏、`close` 先摘再恢复竖屏、`onConfigurationChanged` 只 relayout）、`MobileShell` 侧栏 `!isPortrait()` 守卫；已登记 DEFECTS「已修待验证」。
- 首页虚拟化：`docs/ARCH_REVIEW_HOME_VIRTUALIZATION.md` §4 静态通过；设计 `docs/DESIGN_REVIEW_1.3.0.md` 17 条不变量无 P0。
- **B 类（真机）：未采集。** 清单：`docs/VERIFY_1.3.1.md`（V9 浮层必验 / V10 不回归 / V11 inject 稳定性可选 / V12 顶部按钮 / V13 点频道与退出）+ `docs/VERIFY_1.3.0.md`（全量）；证据目录 `/sdcard/Download/DSH-Go/鲸落/verify/1.3.1/`。
- **未真机验证项**：探针在 CCTV-2 等页面能否 1.8s 内返回 true；纯 iframe 播放器（主文档无 `<video>`）探针不穿透，仍靠 onPageFinished 兜底；首页虚拟化滚动流畅度与两个高风险时刻（首屏数据到达 / 加载更多）；**电视 tab 顶部按钮在窄屏是否不再被裁切（V12）**；**点频道后播放器是否立即出现、退出后竖屏布局正常且无右侧竖栏（V13）**。

## 已知限制（不在本版修复）

- 纯 iframe 播放器的频道：浮层可能仍停（探针不穿透，属已知子集）。
- 页面存在自动播放预览/广告 video 时，浮层可能提前收起。
- 15 条卫视仍为官网首页（待用户提供官方直播页）；首页列数断点与全站规范不一致（D-01，既有）；切 tab 回首页重复追加当前页（既有）。

## 发布前检查清单（用户执行）

- [ ] 包与 sha 一致：mobile/tv 两包 + sha256 与本文件一致。
- [ ] `updates/android-mobile.json` / `android-tv.json` 已更新为 1.3.1/13001、size/sha256 本版值。
- [ ] Release notes 用本文件内容，保留「未真机验证」标注；Release tag `v1.3.1`。
- [ ] 覆盖安装后设置底部显示「鲸落 1.3.1」。
