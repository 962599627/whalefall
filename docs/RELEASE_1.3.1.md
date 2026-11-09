# 鲸落 1.3.1 发布说明草稿（手机 / 平板 / TV）

> 状态：**待用户真机验证后发布**。修复 DEF-2010（直播加载浮层不消失）、DEF-2011（电视 tab 顶部按钮裁切）、DEF-2012（点频道布局错乱/播放器不出现）；B 类真机证据未采集，Release notes 必须保留「未真机验证」标注。
> 维护：Lead ｜ 日期：2026-11-09 22:35 ｜ 上一版：1.3.0（未发布，已被本版取代）

## 版本信息

| 项 | 值 |
|---|---|
| versionName / versionCode | **1.3.1 / 13001**（`android/version.properties` 单一事实源） |
| 包名 | `com.whalefall.movie`，minSdk 23 / targetSdk 28 |
| 构建链 | `scripts/build_android.py`（Python 主链，干净提交 `404fb92`，`git_dirty=false`）；R8 包用 Gradle/AGP 8.7.3 另出 |

### 本次构建的四个包（均已拷到 `/sdcard/Download/DSH-Go/鲸落/`）

| 文件 | 大小 | sha256 | 说明 |
|---|---|---|---|
| `WhaleFall-1.3.1-android-mobile.apk` | 62,000,427 B | `ef5a8576ba644e4611c4921e4472b918d5030c6248cd39af3b1eb7ef6a0f636e` | **主测包**，双 ABI |
| `WhaleFall-1.3.1-android-tv.apk` | 36,223,814 B | `5ae3073b5ef24f5a0be25581fd31743a625aa5bb77ac58c3244cc2f847881fdb` | TV/盒子 |
| `WhaleFall-1.3.1-android-mobile-arm64.apk` | 35,564,118 B | `4e17a77451c56460575d77bed25d97ee54ac1ddaa1e0c2ef1e1ddbd4b48c5878` | arm64 单 ABI（去掉 32 位库，其余同主包） |
| `WhaleFall-1.3.1-android-mobile-r8-arm64.apk` | 32,540,184 B | `19a9801e2c1d4c0c61600a6a2191c2deb65b6311f5de5ad75abbd25fc2922a28` | R8 缩减 + arm64（**本版未真机验证**；R8 真机验证记录在 `3446eb9`，1.2.x 的首页/详情/播放通过） |

- 更新 feed 用主包（mobile）与 TV 包；arm64/R8 为附加安装包。
- 注意：R8 包用 AGP 8.7.3 + Gradle 8.13 构建（AGP 9.5.1 出的包缺 resources/manifest，见 `docs/GRADLE_MIGRATION.md` 已知限制），签名与主包同一开发证书，可覆盖安装。

## 本版内容

1. **修复：电视台直播画面已出但「正在连接/正在加载官方页面」浮层不消失**（DEF-2010，P1）。官方直播页主文档进度可能长期停在 <100%，原实现只在 `onPageFinished` 收起浮层。现在三条独立出口：页面内 `<video>` 真实播放探针（每 1.8s）、页面进度到 100%、网页原生全屏；并顺带把注入脚本单例化（DEF-2002「1.8s 无限重跑」的多循环叠加消除）。
2. **修复：手机电视 tab 顶部「管理」按钮被右缘裁切**（DEF-2011，P2）。窄屏下标题行只放标题，「＋自定义 / 导入列表 / 管理」移到标题下方独立一行（右对齐）。
3. **修复：点频道后布局错乱、播放器不出现**（DEF-2012，P1）。直播播放中请求横屏会触发整页重建：挂在 root 上的播放器 overlay 被新 root 丢掉，并按横屏宽度重建出「右侧栏 + 三段式」错乱布局。修复：旋转只 relayout 不重建整页；播放中先登记 tvPlayer 再转屏、退出时先摘除再恢复竖屏；手机竖屏一律底部 Dock（侧栏不再在竖屏出现）。
4. 首页海报网格 RecyclerView 虚拟化（同 1.3.0，E3-S1）。
5. 陕西卫视改为官方直播页 `http://live.snrtv.com/star`（可能先弹「确定前往」）。
6. 设置页「关于」版本号改为运行时读取（不再硬编码 1.2.0）。

## 验证与证据

- C 类（代码评审）：DEF-2010/DEF-2002 在 `TvLive.java`（探针 + inject 单例）；DEF-2011/2012 在 `TvLive.java` / `MainActivity.java` / `MobileShell.java`（提交 `1b8f45b`），已在 `docs/DEFECTS.md` 登记「已修待验证」。
- 首页虚拟化：`docs/ARCH_REVIEW_HOME_VIRTUALIZATION.md` §4 静态通过；设计 `docs/DESIGN_REVIEW_1.3.0.md` 17 条不变量无 P0。
- **B 类（真机）：未采集。** 清单：`docs/VERIFY_1.3.1.md`（V9 浮层 1s 内消失 / V12 顶部按钮不裁切 / V13 点频道直接出播放器、退出竖屏正常 / V11 inject 稳定性可选）+ `docs/VERIFY_1.3.0.md`（全量）；证据目录 `/sdcard/Download/DSH-Go/鲸落/verify/1.3.1/`。
- **未真机验证项**：上述 V9/V12/V13；R8 包功能（片源扩展引擎/首页/详情/播放）；首页虚拟化滚动流畅度与两个高风险时刻。

## 已知限制（不在本版修复）

- 纯 iframe 播放器的频道：浮层可能仍停（探针不穿透，属已知子集）。
- TV 版（-android-tv）是独立界面（HomeScreen + HomeDock），**未接手机版的电视台直播（TvLive）、文件管理、离线下载、隐私空间、崩溃日志、观影统计等**；是否补进 TV 版待用户拍板（详见会话内清单）。
- 15 条卫视仍为官网首页（待用户提供官方直播页）；首页列数断点与全站规范不一致（D-01，既有）；切 tab 回首页重复追加当前页（既有）。

## 发布前检查清单（用户执行）

- [ ] 主测包通过 V9/V12/V13 后再考虑发布；R8 包单独抽测。
- [ ] `updates/android-mobile.json` / `android-tv.json` 已更新为 1.3.1/13001、size/sha256 本版值。
- [ ] Release notes 用本文件内容，保留「未真机验证」标注；Release tag `v1.3.1`。
- [ ] 覆盖安装后设置底部显示「鲸落 1.3.1」。
