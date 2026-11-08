# 鲸落（Android）2026 架构 / 性能 / 安全审计报告

- 审计对象：`/root/workspace/marcomovie`（Android 标准版，包名 `com.whalefall.movie`，GPL-3.0-only 二开）
- 代码基线：`android/src/main/AndroidManifest.xml` versionName 1.2.0 / versionCode 12000；`android/build/build-validation.json` 记录 git 提交 `897abd4`、`git_dirty=true`
- 构建方式：无 Gradle，`scripts/build_android.py`（aapt2 → javac → D8 → zipalign → apksigner），依赖以 JAR/AAR 固定 SHA-256 放在 `tools/java-dependencies`
- 审计日期：2026-11-08 | 审计者：android-auditor | 方式：只读源码/清单/构建脚本/仓库自带验证文档抽样，**未改动任何代码**

## 0. 结论摘要（先看这里）

1. **最应当先修的不是架构，而是两个"零鉴权局域网服务"**：`AdminConsole` 在 `0.0.0.0:随机端口` 暴露无令牌的 HTTP 管理接口（可读收藏/历史、上传/删除文件、推送播放、改设置），`MiniFtpServer` 在 `2121` 提供**匿名可写可删**的明文 FTP。两者都比"迁移 Compose"重要一个数量级。
2. **`minSdk 23` 的兼容承诺与代码事实冲突**：代码里直接用了 `String.join`（API 26）、`java.nio.file.Files`（API 26）、`Collection.removeIf`（API 24）。这些在 Android 6/7 上抛 `NoSuchMethodError`/`NoClassDefFoundError`，而项目同时在别处特意为 `java.util.Base64`（API 26）写了规避注释——说明这是**系统性缺失"API 级别检查"**，不是个别疏忽。
3. **性能不是主要短板，但结构性天花板明显**：首页/搜索/详情全是 `ScrollView + LinearLayout` 全量构建（全仓 0 处 RecyclerView 使用），无 R8、无 Baseline Profile、无 ABI 拆分，手机包 61.78 MB；`targetSdk=28`、`zipalign -f 4`（非 16KB 页对齐）、JVM `-source 8`，平台适配停在 2019 年的做法上。
4. **不建议现在整体重写 Compose / Compose Multiplatform**。真正的瓶颈是"构建系统 + 列表虚拟化 + 状态承载 + 打包瘦身"，这些用 Gradle 化 + Java 渐进（不必先迁 Kotlin）就能拿到 70% 的收益；UI 重写只应在 Gradle/依赖升级完成后，以"新页面试点"的方式进行。
5. **已有的安全与合规底子其实不差**：更新链路做了同签名+包名+版本+sha256+重定向逐跳校验（`UpdateManager.java:397-427`），回环代理与磁力流都做了随机 token（`LocalProxy.java:19,85-90`、`MagnetStream.java:357-360,395`），局域网配置推送做了 128 位随机路径+5 分钟 TTL+局域网/Host/Origin 校验（`ConfigTransferServer.java:39-135`）——**AdminConsole 与 FTP 恰恰是同仓库里唯一没照这个范式做的两个服务**。这也意味着 P0 修复是"复用现成范式"，不是从零设计。

### 评分（10 分制，主观加权，用于排期而非评级）

| 维度 | 分数 | 主要依据 | 主要短板 |
|---|---|---|---|
| 架构与可维护性 | 4.5 | MainActivity 1825 行、MobileShell 3827 行、MobileDetailScreen 1906 行、TvLive 2228 行；控制器层（SourceController/PlaybackController）拆分方向正确 | 手写页面栈 + 全局可变状态，无生命周期承载；进程死亡即丢栈 |
| 性能与内存 | 5.5 | 仓库自测帧耗时 P95 12–19 ms（`docs/EXPERIENCE_REVIEW.md:76-84,106-108`）；首页磁盘快照、海报二级磁盘缓存 | 无列表虚拟化、无 R8/BP、后台不释放播放器、APK 61.78 MB |
| 安全 | 4.0 | 更新链路强、回环服务 token 化、PIN 用 PBKDF2（`PrivacyPin.java:7-27`） | AdminConsole/FTP 零鉴权；`targetSdk=28`；全局明文 |
| 兼容性与平台适配 | 5.0 | Node 运行时 16KB 对齐验得很细（`docs/android-node-runtime-packaging.md:47-64`） | minSdk 23 ↔ API 26 用法冲突；`zipalign -f 4`；targetSdk 28 |
| 工程质量与可测试性 | 6.0 | 15+ 套纯 JVM 离线检查、格式化脚本、公开内容检查、文档对"未验证"很诚实 | 无 lint / manifest merge / 依赖冲突检查；UI 与播放几乎无自动测试 |
| 发布与合规 | 6.5 | GPL 文本、NOTICE、第三方声明、免责声明、隐私说明、应用内开源许可页齐全 | 构建 `git_dirty=true`、开发签名、无 Play 上架路径 |
| **综合** | **5.2** | 能用、有真实优化记录，但天花板已到 | 安全与平台适配是硬伤 |

## 1. 审计范围、方法与边界

**读了什么**（抽样，非全量）：
- 清单与构建：`AndroidManifest.xml`、`scripts/build_android.py`、4 个依赖清单、`android/build/build-validation.json`、`runtime-capabilities.json`
- 核心 Java（114 个文件中的约 35 个）：`MainActivity`、`MobileShell`、`MobileDetailScreen`、`PlaybackController`、`MoviePlayer`、`VideoOutput`、`PlayerChrome`、`PlaybackControls`、`SystemPlayer`、`TvLive`、`WebResolver`、`SourceController`、`JsEngine`、`CatPawEngine`、`Cms`、`ImageLoader`、`Prefs`、`AccountPanel`、`AdminConsole`、`AdminPanel`、`MiniFtpServer`、`PushFiles`、`ConfigTransferServer`、`LocalProxy`、`MagnetStream`、`UpdateManager`、`UpdatePolicy`、`UpdateApkProvider`、`PrivacyPin`、`PrivacySpace`、`UiCompat`、`UiIcon`、`AppLog`、`DetailCache`、`QueueStore`、`VideoFilterDialog`、`DownloadManager`、`HomeScreen`、`StartupIntro`、`TvLayoutPolicy`、`DecoderRenderersFactory`
- 文档：`README`、`CHANGELOG`、`docs/BUILD.md`、`STATUS`、`COMPATIBILITY`、`EXPERIENCE_REVIEW`、`PRIVACY`、`SECURITY`、`PUBLICATION_CHECKS`、`mobile-regression-2026-11-07`、`android-node-runtime-packaging`、`android-phone-compatibility`、`THIRD_PARTY_NOTICES`、`DISCLAIMER`

**没做**：没跑构建、没跑 lint、没解包 APK 数 dex/method 数、没上真机。因此本文**没有**给出 dex 方法数、真实冷启动耗时、真实内存峰值；凡属推断处标注"推断"。没有 Bash 权限，全部结论来自静态阅读。

## 2. 证据基线（事实表）

| 事实 | 证据 |
|---|---|
| `minSdkVersion=23`，`targetSdkVersion=28` | `AndroidManifest.xml:3` |
| 全局明文 HTTP、`largeHeap=true`、`allowBackup=false`、无 `networkSecurityConfig` | `AndroidManifest.xml:11` |
| 唯一 exported 组件是 MainActivity；更新用私有 ContentProvider | `AndroidManifest.xml:13-20` |
| 无 Gradle 文件；javac `-source 8 -target 8`；D8 `--min-api 23`；无 R8/缩减 | `scripts/build_android.py:104,107` |
| `zipalign -f 4`（4 字节对齐，非 `-P 16` 页对齐） | `scripts/build_android.py:112,149` |
| 未声明 `android:extractNativeLibs`；.so 以 DEFLATED 压缩写入 APK | `scripts/build_android.py:108-111` |
| 手机包同时打包 `armeabi-v7a` + `arm64-v8a` 原生库 | `scripts/build_android.py:74-84` |
| TV APK 36,006,493 B；mobile APK 61,783,106 B | `android/build/build-validation.json:4,7` |
| 该构建 `git_dirty=true`、`device_install_tested=false`、`device_playback_tested=false`、开发签名 | `android/build/build-validation.json:15-20` |
| Media3 1.5.1（含 exoplayer/ui/datasource 等全套）、Kotlin stdlib 1.8.0、Guava 33.3.1、OkHttp 3.14.9（仅 Spider 兼容接口用）、jsoup 1.18.3 | `android/media-dependencies.json:123-208`、`android/dependencies.json:1-51` |
| Node 运行时 16KB 能力文件只覆盖 `libnode.so/libc++_shared.so/libmarco_node.so` | `android/build/catpaw/assets-mobile/catpaw/runtime-capabilities.json` |
| 仓库自认整包 16KB 未完成（`whole_apk_16kb: false` 的文件口径） | `docs/android-node-runtime-packaging.md:103-106` |
| 全仓 0 处 `RecyclerView`/`ListView` 使用，列表均为 `ScrollView+LinearLayout` | `MainActivity.java:942-954`、`MobileShell.java:777,2015,2045`、`DetailScreen.java:53` |
| 全仓 0 处 `startForeground`/`Notification`/`WorkManager` | grep 结果为空 |
| 页面栈是 `ArrayDeque<Screen>` + `Runnable render` | `MainActivity.java:79,167-185,1052-1058` |
| 仅保存 `layout_initial_launch` 一个状态键 | `MainActivity.java:694-697` |
| engines 为无上限 HashMap，按站点 key 长期持有运行时 | `MainActivity.java:121`、`SourceController.java:60-131` |

## 3. 架构审计

### 3.1 现状判断：单 Activity + 自绘 View 栈，本质是"手写了一个没有生命周期的框架"

- `Screen` 只是 POJO（`render` Runnable + focus/scroll/home 标记，`MainActivity.java:167-185`），`open()` 先 `capture()` 再执行 `render.run()`（`1052-1058`）。页面状态保存在 Activity 字段与 `Screen` 字段里，没有 ViewModel/SavedState，进程被杀后只剩磁盘快照可救首页（`HomeScreen.java:197-208`、`413-475`）。
- Activity 承担了路由器、图片加载器、播放宿主、搜索调度、直播宿主、管理后台、更新器、屏保、隐私空间等 8 类职责（`MainActivity.java:20-131`），单文件 1825 行，实例字段约 90 个。**这不是"代码风格问题"，而是禁止并行修改与无法写单测的结构性原因**。
- 手机壳 `MobileShell` 用一个静态类持有 tab、搜索缓存、子页、retained 根视图（`MobileShell.java:50-76,108-130`），并用 `retainedRoot` 手工模拟"视图保留"。这是 Fragment/ViewModel 职责的重复实现：`resume()` 里还要手动 `cancelArtwork/releasePlayer/generation++`（`111-119`），任何一处漏掉就是花屏、黑屏或状态错乱。
- 播放与 UI 强耦合：`PlaybackController`（1637 行）直接读写 `a.root/a.playStatus/a.prepared/a.playing`，`PlaybackControls`（1615 行）直接持有 `a.player` 与时间轴；`PlayerChrome` 与 `MainActivity` 通过一堆 boolean 同步显隐。播放器不是"可替换的 Presenter"，而是一组共享字段。

### 3.2 模块边界评估

| 边界 | 现状 | 判断 |
|---|---|---|
| UI 渲染 | 所有页面在各自 `show()/render()` 内 new 控件；设计 token 集中在 `UiTokens`，图标集中 `UiIcon`，方向对 | 可维持，但需要列表虚拟化 |
| 数据层 | 直接 `SharedPreferences`（`Prefs` 只做 key/默认值/范围白名单），收藏/历史是 JSON 字符串（`WatchHistory`、`Favorites`、`QueueStore`） | 无 Repository；可接受，但迁移 Kotlin 后应换 StateFlow |
| 来源引擎 | `SourceEngine` 接口 + `QueuedSourceEngine` 串行包装 + `SingleFlight` 去重（`SourceController.java:60-131`），设计清晰 | 全仓最好的边界，保留 |
| 播放器 | `MoviePlayer`（Media3 适配）/`SystemPlayer` 双后端 + `VideoOutput`（SurfaceView HDR/TextureView 回退，`VideoOutput.java:15,35-78`） | 抽象可用，缺生命周期与前台服务 |
| 管理后台 / 传输 | `AdminConsole`、`MiniFtpServer`、`ConfigTransferServer` 三个手写 HTTP/FTP 服务，安全实现水平不一致 | 必须收敛到同一范式 |
| 构建 | Python 脚本，无 lint/manifest merge/资源压缩/R8/依赖图 | 长期维护成本最高的一项 |

### 3.3 是否应该拆 feature module / Gradle 多模块

**应该，但不是第一步。** 理由：
- 现在拆 Gradle 多模块的前提是先有 Gradle；而 Gradle 化的收益（R8、Baseline Profile、lint、ABI split、manifest merge、依赖冲突检测、Robolectric）**不需要**同时迁 Kotlin 或 Compose。
- 建议顺序：`单 Gradle 模块（Java）` → `包级分层（ui/player/source/admin/store/core）` → `按 feature 拆 module`。直接拆 module 会在没有 lint 与自动测试的情况下制造大量循环依赖。
- 反例提醒：仓库里还有 `flutter/`（Windows/macOS/iOS）与 `lite/`（API 14）两套并行实现。Android 端若再押 Compose Multiplatform，会出现**三套跨端框架并存**，这是长期成本最高的选项（见 §7）。

### 3.4 可测试性评估

- 优点：大量 Android 无关的纯 Java 逻辑被抽出并离线验证（`android/tests/*Checks.java` 约 30 个套件，`Prefs/UpdatePolicy/Cms/TvLayoutPolicy/ImageDecodePolicy/sample` 等），`docs/publish` 记录 15+ 组套件通过。这是这个项目最值得保留的工程资产。
- 问题：**UI、播放器、后台服务、Activity 生命周期没有自动测试**。设备探针（`android/tests/device/*`）需要真机与 adb，且 `build-validation.json` 明确写着 `device_install_tested=false`。也就是说：改动 `MobileShell`/`PlaybackControls` 这类 3800/1600 行文件时，安全网只有"人肉看屏幕"。
- 建议：Gradle 化后引入 Robolectric + `PlayerView`/`ExoPlayer` fake（Media3 自带 `FakeClock`/`TestExoPlayer` 思路），给页面栈与信息流加 JVM 级测试；这是迁移的第一批收益。

## 4. 性能审计

### 4.1 冷启动与首页首帧

- **主线程同步读配置**：`onCreate` 里同步 `new FileInputStream(files/config.json)` + `Cms.parseConfig`（上限 4 MB，`MainActivity.java:541-552`、`Cms.java:11,416`），之后才 `setContentView`（`611`）。配置越大首帧越晚，且没有任何 trace 记录。
- **布局回调里可能 `recreate()`**：密度重算不一致时在 `onLayoutChange` 内 `recreate()`（`MainActivity.java:629-645`），首次安装/多屏设备上会多一次完整启动。
- 正面：首页有磁盘快照（3 天 TTL）与 5 分钟内存快照（`HomeScreen.java:197-208`），冷启动"先出旧数据"的策略正确。
- **Baseline Profile 缺失**：构建链无 `profileinstaller`、无 baseline profile 生成（`build_android.py` 全流程无相关步骤）。在已有 R8/BP 的成熟 Android 项目里，这两项通常能带来冷启动两位数百分比收益——本仓库连前提都没建立。
- 结论：冷启动**没有被测量过**（构建记录里 `device_install_tested=false`），优化应"先埋点再动手"。

### 4.2 列表与 View 层级（最大的结构性开销）

- 全仓 0 处 `RecyclerView`（grep 证实）；首页是 `ScrollView + LinearLayout`（`MainActivity.java:942-954`），移动端首页/搜索结果/关于页/直播频道列表同样是 `ScrollView` 全量 addView（`MobileShell.java:777,2015,2436,2828,3045`；`TvLive.java:646`）。
- 后果：首页 24→120 张卡片全部实例化 + 全部挂到 View 树 + 全部绑定海报任务；滚动时 `onDraw` 遍历整棵树，低端电视盒上掉帧风险高。仓库自测 P95 看起来很好（12–19 ms，`docs/EXPERIENCE_REVIEW.md:106-108`），但那是 1920×1080 合成数据+预热缓存，且分页只到 40 张量级。
- 修复优先级：**首页/搜索/直播频道三个长列表换 `RecyclerView` + ViewHolder + 预取**，这是 P1 里投入产出比最高的一项（约 3–5 人日，风险中）。

### 4.3 图片加载与内存

- 内存：`LruCache` 按 `memoryClass` 重算（`MainActivity.java:80-85,536-540`），`onTrimMemory` 分档清理（`ImageLoader.java:42-49`），`ImageDecodePolicy` 做采样与按显示尺寸缓存键（`ImageLoader.java:51-75`）——**这部分做得比大多数自研加载器好**。
- 磁盘：`posters_v2`，SHA-256 命名，7 天 TTL（`ImageLoader.java:196-246`），但**没有容量上限与清理策略**：长期使用后 cache 目录无界增长（推断），低存储设备上会被系统整目录清掉，反而造成二次下载。
- 已知未完成项：`docs/EXPERIENCE_REVIEW.md:44` 自己写了"未完成全局图片加载器改造"，与现状一致。
- 建议：给磁盘缓存加 LRU 配额（例如 `min(256MB, cacheDir 可用空间/10)`），退出时增量清理；顺带把 HTTP `Cache-Control`/ETag 纳入键（当前只有 URL+尺寸）。

### 4.4 线程与并发

- Activity 自建 4 个池：`worker(4)`、`details(4)`、`artwork(8)`、`phonetic(1)`（`MainActivity.java:29-36`）；此外还有 `RequestScope`（4→32，`RequestScope.java:13`）、`ImmersivePoster`（8）、`DanmakuView`（1）、`DownloadManager`（分段 4 + 单线程）、`MagnetStream`（1）、`SearchCoordinator`（多池）、`AdminConsole`、`LocalProxy`、`MiniFtpServer` 各自建线程。**峰值线程数可轻易超过 30**，而目标设备是 ARMv7 电视盒子（`android/README.md:3`）。
- 取消语义靠 `Future.cancel(true)` + `interrupt`（`MainActivity.java:1235-1249`），项目文档也承认"第三方 native 插件可能忽略中断"（`docs/EXPERIENCE_REVIEW.md:43`）。
- 建议：统一为两个有界调度器（IO 16 / CPU `min(4,cores)`）+ 一个播放专用单线程；对 `artwork` 池加"可见区优先 + 队列去重"；这一步同时解决低端机卡顿与功耗。

### 4.5 播放器 / Surface / HDR

- `VideoOutput` 默认 SurfaceView（保 HDR），低版本或"兼容模式"回退 TextureView（`VideoOutput.java:15,35-78`）；`DecoderRenderersFactory` 对 Dolby Vision 做了"原生 DV 解码器缺失时回退 H.264/H.265 base layer"（`DecoderRenderersFactory.java:42-83,85-212`）——这在非官方 Media3 里算是相当扎实的工作。
- 但 Media3 停在 **1.5.1**（2025 年初），2026 年已是 1.8+；DV/HDR 相关修复、`DefaultTimeBar`、`PlayerView` 无障碍等都在后续版本。升级收益明确、风险可控（`docs` 已有解码回归样本 `generate_decoder_fixtures.py`）。
- **后台不释放**：`onStop` 只 `player.pause()`，保留 Media3 + Surface + 解码器（`MainActivity.java:1787-1794`）；`onDestroy` 才 release（`1808`）。在小窗/后台挂起场景下，硬解码器与 Surface 长期占用，电视盒子内存压力大。结合"无前台服务"，前台服务化是正解（既支持后台音频，也符合 Android 14+ 前台服务类型要求）。

### 4.6 WebView（TvLive 电视台）

- 每次开台新建 WebView + 桌面 UA（`TvLive.java:1398-1407`，UA 见 `2196-2197`），注入脚本**每 1.8 秒无限重跑**（`1364-1372`），脚本内容还会强制 `video.play()`、取消静音、隐藏页面兄弟节点（`2199-2227`）。后果：持续 JS 执行 + 重排 + 音频外放，是电视端发热与内存的主要来源之一（推断，需实测）。
- 关闭路径处理正确（`2048-2065`：`loadUrl("about:blank")` + `destroy()`）。
- 建议：注入改为幂等 + 最多 N 次（如 5 次后停止）+ `onPause` 停止计时器；WebView 放 `android:process=":web"` 隔离（targetSdk 28 可行，但要处理跨进程 IPC）；更好的方案是优先用 m3u8 直连 ExoPlayer，WebView 仅作为最后回退。**这一项和 §4.8 的体积/内存一起看**。

### 4.7 APK 体积（mobile 61.78 MB）

- 结构推断（基于构建逻辑，未解包）：手机包 = TV 包（36 MB，ARMv7）+ arm64 原生库（`native_files_mobile` 同时塞入 v7a 与 arm64，`build_android.py:74-84`）+ mobile 版 catpaw 资产。差额 25.8 MB 几乎全部是 arm64 Node/QuickJS 运行时。
- 直接可做的三件事：
  1. **手机包只发 arm64**（2026 年在售设备中 ARMv7-only 极少；电视包保留 v7a 即可），预计立减 ~15–20 MB（推断）。
  2. **R8 full mode + 资源收缩**（现在 D8 只做 dex 转换，见 `build_android.py:107`），代码与资源侧还有 20–40% 空间；顺带换来方法数余量。
  3. **App Bundle / ABI split**：需要 Gradle，属于 P2 第一步的收益。
- 顺带清理：`okhttp3` 仅被 `com.github.catvod.crawler.Spider` 的兼容接口引用（`Spider.java:86-90`），OkHttp 3.14.9 已 EOL；`kotlin-stdlib 1.8.0` 是被 androidx 传递引入的 2022 年版本。升级 Media3 会同步刷新这些。

### 4.8 16KB page size

- 做对的部分：Node 运行时三库有 ELF/ZIP 双对齐校验与运行时能力文件（`docs/android-node-runtime-packaging.md:47-64`、`runtime-capabilities.json`），且 `scripts/verify_android_runtime_package.py:49-68` 有 `whole_apk_16kb` 判定与 `--require-whole-apk` 开关。
- 没做的部分：
  - 主构建 `zipalign -f 4`（`build_android.py:112,149`），没有 `-P 16`；
  - manifest 未声明 `android:extractNativeLibs`（当前 targetSdk 28 下系统默认抽取，规避了 zip 对齐问题，但一旦改打包策略就会踩坑）；
  - ffmpeg/gav1/vpx/jlibtorrent/QuickJS 等其余 .so 的页对齐**没有进主构建门禁**（仓库自己也写了 `whole_apk_16kb: false`，`docs/android-node-runtime-packaging.md:103-106`）。
- 影响：Android 15/16 上 16KB 页设备（新出厂机型逐步增多）可能加载 lib 失败；Google 对 targetSdk 35+ 的 Play 应用强制，本项目走侧载可暂缓，但**设备侧风险不受 targetSdk 豁免**。建议在 P1 把 `verify_android_runtime_package.py --require-whole-apk` 接进 `build_android.py` 的末尾，让构建失败而不是发出去才发现。

## 5. 安全审计

### 5.1 P0-1：AdminConsole —— 局域网零鉴权管理后台

**证据**
- 监听 `0.0.0.0` 随机端口，无任何 token/session/PIN（`AdminConsole.java:38`），URL 直接是 `http://<lanIp>:<port>/`（`71-73`）。
- 接口覆盖：读 `state`（收藏、历史、片源列表、设备型号/IP，`246-317`）、改设置（`319-343`）、导入配置（`345-368`）、**上传文件**（`425-433`）、**删除文件**（`214-216`，`PushFiles.delete` 递归删除）、**推送播放任意 URL**（`217-219` → `PushFiles.playUrl`）、队列增删清（`220-234`）。
- 前端 Vue 页面用 `fetch` 直接打 `/api/*`，无 token（`assets/admin/index.html:336,358,367,379,384,395,407-417`）。
- 无 `Origin`/`Host`/`Content-Type` 校验，也不发 CORS 头；而 `Content-Type: text/plain` 的 JSON body 属于"简单请求"，浏览器**不会预检**——即任意网站可以对本机 LAN 地址发起 CSRF（`/api/play`、`/api/files/delete`、`/api/config`）；结合 DNS rebinding，攻击面不限于同网段。
- 暴露窗口：仅在用户打开"管理员后台"对话框期间（`AdminPanel.java:25-38,128-136`）。风险等级因此不是"永远在线"，但**用户很可能开着面板离开**，且 FTP 同时被拉起。
- 同仓库的正确范式：`ConfigTransferServer` 用 128 位随机路径 + 5 分钟 TTL + 仅站点本地地址 + Host 校验 + Origin 校验 + Content-Type 白名单（`ConfigTransferServer.java:39-135`），并在 `docs/PRIVACY.md:38` 写明 5 分钟过期。**AdminConsole 直接复用这套即可。**

**影响**：同一 Wi-Fi 下任何人（含被入侵的 IoT 设备、酒店/公司网络）可读取用户观影历史与收藏、写入/删除用户文件、修改播放设置、把播放器劫持到任意内容源；配置导入可被换成恶意来源链，进而加载第三方 JS/Node 脚本（项目自己声明"扩展无沙箱"，`docs/PRIVACY.md:17`）。

**修复方案（1–1.5 人日，低风险）**
1. 构造时生成 128 位随机 path token（照抄 `ConfigTransferServer:42-46`），`url()` 带 token，UI 二维码/复制链接随之更新。
2. 每个请求校验：来源地址是站点本地/回环；`Host` 等于 `本地地址:端口`；写操作校验 `Origin`；只接受 `application/json`（或表单）并拒绝 `Transfer-Encoding`；加 5–15 分钟 TTL 与"关闭即失效"。
3. 危险操作（`/api/play`、`/api/files/delete`、`/api/config`）要求二次确认 token（页面弹一次即可），并对删除做白名单（只允许 `pushed/` 下文件）。
4. 可选：改用 HTTPS + 自签证书 pinning；或在 UI 上明示"仅在可信网络使用"。

### 5.2 P0-2：MiniFtpServer —— 匿名、明文、可写可删

**证据**
- 固定端口 2121，绑定 `0.0.0.0`（`MiniFtpServer.java:16,26`）。
- `USER`/`PASS` 任意值直接通过（`84-88`），无 TLS（`FEAT` 只报 SIZE/MDTM/PASV，`97`）。
- 支持 `STOR` 上传（`148-165`，60 MB 上限）、`RETR` 下载（`166-179`）、`DELE` 删除（`185-187`）、`LIST`（`130-146`）。
- UI 明确告诉用户"用户名/密码任意"（`AdminPanel.java:104-110`），并和 AdminConsole 一起随面板启停（`35`、`134`）。
- 路径安全本身没问题：`PushFiles.sanitize` 过滤 `..`/分隔符（`PushFiles.java:33-60`），只落在 `files/pushed/`。

**影响**：同网段可直接上传覆盖文件、下载用户"pushed"内容、删除文件；明文传输可被旁路嗅探；2121 端口固定，扫描成本极低。比 AdminConsole 更糟的是**不需要知道随机 token**。

**修复方案（0.5–1 人日）**：默认为关闭；保留时改为"一次性随机用户名+密码（QR 展示）+ 只写（禁用 RETR/DELE）+ 仅局域网 + 会话 TTL + 速率/配额"；或直接删除 FTP，改用已有的配置推送/网页上传（功能重复）。

### 5.3 P0-3：minSdk 23 与 API 24/26 用法冲突（崩溃）

**证据（编译期用 API 35 的 android.jar，运行期 minSdk 23 → 不报错、只崩）**
| 调用 | 最低 API | 位置 | 触发路径 |
|---|---|---|---|
| `String.join(",", current)` | 26 | `VideoFilterDialog.java:153` | 电视端筛选弹层点任一非空条件 → `NoSuchMethodError` |
| `java.nio.file.Files.readAllBytes` | 26 | `PushFiles.java:136`（`readText`） | 打开 pushed 里的 .md/.txt（`MarkdownScreen`、管理后台 `/api/files/text`） |
| `java.nio.file.Files.copy` | 26 | `MiniFtpServer.java:174`（RETR） | FTP 下载文件 |
| `List.removeIf(...)` | 24 | `AdminConsole.java:391`（删频道）、`QueueStore.java:55`（删队列） | 管理后台删电视台 / 删推送队列 |

对照：项目在 `Cms.java:984`、`LiveCatalog.java:647`、`HttpT4Engine.java:59` 都写了"java.util.Base64 需要 API 26，保持 API 23 兼容"的备注，`PlaybackControls.java:973` 还专门避开 `java.util.function.IntConsumer`。**说明团队知道这个坑，只是没有门禁去查。**

**影响**：Android 6/7（API 23–25）设备上四处确定性崩溃；而项目对外承诺"最低 Android 6.0"（`README.md:57`）、电视包还主打老盒子。属于**宣称支持但必崩**的组合，优先级高于任何性能项。

**修复方案（0.5–1 人日）**：逐处替换为兼容实现（`StringBuilder` 拼 join、`FileInputStream` 读、`Iterator.remove`），保留 4 个纯 JVM 用例防回归；同时在构建里加 API 检查（见 P1-7）。若产品决定放弃 API 23–25，则把 `minSdk` 提到 26 并在 README/更新说明中明示——但会丢掉一部分存量电视盒，需产品拍板。

### 5.4 P1：明文 + WebView 混合内容

- `usesCleartextTraffic=true`（`AndroidManifest.xml:11`）；targetSdk 28 下 cleartext 默认本就允许，显式声明意味着**未来升 targetSdk 时必须补 network security config**，否则所有 HTTP 源立刻失效。
- TvLive/WebResolver/CatPawSettings 均开 JS + `MIXED_CONTENT_COMPATIBILITY_MODE`（`TvLive.java:1400-1407`、`WebResolver.java:69-80`、`CatPawSettings.java:55-58`），并使用桌面 UA 伪装（`TvLive.java:2196-2197`）。
- 网盘登录走 WebView（`WebResolver.java:84-92` 提示"可在下方网页完成登录"），随后把 `CookieManager` 的 Cookie 注入到媒体请求头（`128-145`）。在 HTTP 页面或混合内容场景下，**Cookie/Token 可被同网段劫持**。
- 已做对的：`setAllowFileAccess(false)`、`setAllowContentAccess(false)`、无 `addJavascriptInterface`。
- 建议：优先引导 http 站点走 https；对 HTTP 源在 UI 上明确"不安全连接"提示；升级 targetSdk 时用 `networkSecurityConfig` 只放开必要的明文域；不要把"任意明文"作为默认。

### 5.5 P1：更新链路（较强，但有可改进点）

**做对的**（这是全项目安全做得最好的一处）：
- 只接受 HTTPS、无 userinfo/端口/query/fragment、路径不含 `..`（`UpdatePolicy.java:84-102`）；
- 重定向逐跳重新校验（`UpdateManager.java:466-496`）；
- 下载后校验大小 + SHA-256 + 包名 + versionCode 递增 + versionName + minSdk + edition + **同签名**（`UpdateManager.java:397-427`）；安装走私有 ContentProvider 只暴露单个文件（`UpdateApkProvider.java:22-37`）。

**可改进**：
- 更新源走第三方代理 `gh-proxy.org`（`UpdatePolicy.java:12-13`），`ALLOWED_HOSTS` 为空（`16`）——代理可长期拒绝服务或做降级攻击；由于同签名校验兜底，**不能直接替换 APK**，但可让更新长期失效/被投毒元数据干扰。
- Feed 元数据本身无签名（sha256 来自同一 JSON），属于 TOFU：一旦签名密钥泄露，攻击者可直接发新版。建议：feed 增加独立签名（Ed25519/`SigningKey`），或至少 pin 镜像证书、把 `ALLOWED_HOSTS` 收紧到自有域名 + GitHub。
- 建议把 `apksigner verify --print-certs` 的证书 SHA-256 与内置常量比对（构建脚本已有 `MARCOMOVIE_EXPECTED_SIGNER_SHA256` 思路，`build_android.py:127`），运行时也更稳。

### 5.6 P2：凭据与隐私数据存储

- 账号/网盘 Cookie/Token 存在 `SharedPreferences` 明文（`AccountPanel.java:900-1006` 扫描 `shared_prefs`，`EmbyAccounts.java:21` 用 `emby.accounts` 存 token）。App 私有目录 + `allowBackup=false`（`AndroidManifest.xml:11`）挡住了普通读取，但 root/备份提取/调试设备上可直读。
- 备份导出**不含**凭据（`SettingsScreen.java:664-679` 只导出白名单 key），这是正确的取舍；`docs/PRIVACY.md:11-17` 也如实说明。
- PIN 做了 PBKDF2-SHA1 120k 次 + salt + 重试限速（`PrivacyPin.java:7-27`、`PrivacySpace.java:41-47`）。对 4 位 PIN，离线暴力只有 1 万种组合，PBKDF2 只能拖慢不能阻止；**不要把它宣传成"加密"**（`docs/COMPATIBILITY.md` 已说明"不是文件系统加密"，保持这个口径即可）。
- 建议（P2）：用 `EncryptedSharedPreferences`/Android Keystore 包一层存 emby token；敏感操作要求生物识别可选。

### 5.7 动态脚本 / GPL / 合规

- 动态执行：QuickJS、Node（`libnode.so`）、Python（Skulpt）、Android JAR（csp）扩展均在**应用进程内**运行，`SECURITY.md:7` 与 `docs/PRIVACY.md:17` 已明确"不是隔离沙箱"。风险接受是产品决策，但**管理后台的无鉴权会把这个风险放大**（可远程写入配置 → 加载任意远程脚本）。修复 P0-1 后此风险回到"用户自行导入"的可接受范围。
- GPL 合规：`assets/MarcoMovie-NOTICE.txt`、`assets/MarcoMovie-GPL-3.0.txt`、`THIRD_PARTY_NOTICES.md`、`assets/*-notices.txt`、应用内"设置 → 关于 → 开源许可"（`MobileShell.java:3499-3529`）齐全，且保留上游作者与上游项目名；`LICENSE` 为 GPL-3.0-only。
- **但有一个真实合规风险**：`android/build/build-validation.json:15` 记录 `git_dirty=true`——**发布的 APK 来自未提交的工作区，对应源码不可复现**。GPL 要求提供对应源码；当前仓库镜像未必逐字节对应。修复：发布构建强制 clean tree，或把构建脚本改成"dirty 时打 `-dirty` 版本号并在 release notes 标注 commit"。
- 内容合规：`DISCLAIMER.md`、`docs/PRIVACY.md`、应用内声明到位；本项目不走 Play，Play Integrity/隐私沙盒/广告政策不适用（无广告 SDK、无统计 SDK，`docs/PRIVACY.md:3`）。

## 6. 兼容性与 2026 平台适配

| 项目 | 现状 | 2026 风险 | 建议 |
|---|---|---|---|
| targetSdk | 28（`AndroidManifest.xml:3`） | 与 Play 现行要求（API 35/36）差 7–8 代；Android 15/16 的行为变更大多以 target 为开关，固化了 2019 年的默认行为 | 分两步：先 33（predictive back 可 opt-in、通知权限可控）→ 35（edge-to-edge 强制、前台服务类型、16KB 要求）→ 36 |
| Predictive Back | 未适配（`onBackPressed`，`MainActivity.java:1114`） | 系统逐步默认开启后，手势返回动效/回调会不一致 | 升 target 33 时接 `OnBackInvokedCallback`，页面栈已有 `back` 队列可复用 |
| Edge-to-edge | 自绘状态栏高度 + `setSystemUiVisibility(5894)`（`MainActivity.java:443,529`；`UiCompat.java:102-110`） | target 35 强制 edge-to-edge 后，`MobileShell` 的底部 Dock/筛选条（`filterBarBottomDp`）会与手势条冲突 | 统一改用 `WindowInsets`（播放器侧已有正确实现，`PlaybackController.java:928-952`，可复用） |
| 大屏/折叠屏 | `TvLayoutPolicy` 三形态 + 宽度断点（`TvLayoutPolicy.java:16-63`），未用 `WindowSizeClass` | 折叠屏展开/桌面窗口模式下断点靠 width 猜测，易错判平板 | 引入 `androidx.window`/`WindowManager` 依据；`formFactor` 与窗口类解耦 |
| 16KB 页 | 仅 Node 三库有验证 | 新设备加载失败 | 见 §4.8：把 `--require-whole-apk` 接进主构建 |
| 前台服务 | 无（0 处 `startForeground`） | Android 14+ 后台限制下，下载/磁力/后台音频随时被杀；升 target 后必须声明类型 | 增加 `dataSync`/`mediaPlayback` 前台服务 + `POST_NOTIFICATIONS`（target 33+） |
| 存储 | 全用 app 私有目录（`getFilesDir`/`getCacheDir`） | 升 target 后 scoped storage 无影响 | 无需改，保持 |
| 权限 | 只有网络/音频/安装包 | target 33+ 需 `POST_NOTIFICATIONS`；`REQUEST_INSTALL_PACKAGES` 需说明 | 随前台服务一起加 |

## 7. 2026 技术选型建议（是否迁 Gradle / Kotlin / Compose）

### 7.1 决策矩阵

| 选项 | 成本 | 收益 | 建议 |
|---|---|---|---|
| 维持 Python 构建 | 0 | 0，且越拖越贵（无法用 R8/BP/lint/ABI split/Robolectric/依赖解析） | ❌ 不再扩大投入 |
| **Gradle(AGP 9) + 仍用 Java** | 3–5 人日（含依赖重打包、AAR 收敛、签名、保持产物一致校验） | 立刻解锁 R8、Baseline Profile、lint、`--require-whole-apk`、ABI split、manifest merge、Robolectric；是后续一切优化的前提 | ✅ **第一步，强烈推荐** |
| 加 Kotlin（渐进） | 1–2 人日搭骨架 + 每文件迁移成本 | 协程/Flow 才能让"Repository + 状态"真正落地；`PlayerChrome` 这类回调重灾区收益最大 | ✅ 第二步，新代码用 Kotlin，老代码不动 |
| 迁移 Compose（单页试点） | 每页 2–5 人日 | 列表虚拟化、状态、无障碍、Material 3 触控规范一次性解决；但现有 UI 是大量自绘 Canvas/玻璃材质，1:1 复刻成本高 | ⚠️ 第三步，仅新页面/新控件试点（`ComposeView` 内嵌） |
| Compose Multiplatform 整体重写 | 3–6 人月 + 长期双框架维护 | 与 `flutter/`（Windows/macOS/iOS 已用 Flutter）重叠，跨端叙事已有答案 | ❌ 不建议 |
| 迁 Fragment/MVVM 全套 | 2–3 人月 | 收益被 Compose/单 Activity 方案覆盖；手写 `Screen` 栈已等价于导航库 | ❌ 不必单独做 |
| 拆 Gradle 多模块 | 在 Gradle 化之后 5–10 人日 | 编译增量、边界强制、测试隔离 | ✅ 第四步，按 feature 拆 |

### 7.2 推荐渐进路径（不重写 UI）

1. **构建现代化（P2 前置）**：Gradle(AGP 9) + Java 17 toolchain + 依赖走 Maven；保留现有 `dependencies.json` 的 SHA-256 pin 作为校验脚本，或迁 `gradle.lockfile`。产物仍用 `apksigner`/`zipalign` 校验，保证可复现与签名不变。
2. **R8 full mode + 资源收缩 + Baseline Profile**：先解决 keep 规则（反射集中在 `Cms`/`JsEngine`/插件桥），再加 startup profile（冷启动/首页首帧），最后用 Macrobenchmark 量化。
3. **列表虚拟化**：首页、搜索、直播频道、选集先换 `RecyclerView`（或在 Compose 试点里用 `LazyColumn`）。**这是 UI 层收益最大的一步，与是否迁 Compose 无关。**
4. **状态承载**：引入 `ViewModel` + `SavedStateHandle`，把 `MainActivity` 的 90 个字段按职责切成 `HomeState/PlayerState/SearchState`；进程死亡后的页面栈恢复先做"回首页 + 恢复快照"，不要承诺完整恢复。
5. **模块化**：`core`（Cms/Prefs/网络/日志）→ `source`（引擎）→ `player` → `feature:*` → `app`。
6. **Compose 试点**：优先选"设置页"或"关于页"这类纯静态页，验证主题/字体/返回手势/无障碍，再决定是否推进到详情页。

### 7.3 Media3 与播放层

- 升级 Media3 1.5.1 → 1.8.x：DV/HDR、`DefaultTimeBar`、字幕、`PlayerView` 的无障碍与稳定性修复；项目已有解码回归样本，升级后跑一轮 `DecoderProbe`。
- 用 `MediaSession` + `MediaSessionService` 承载播放，天然获得通知/耳机/蓝牙/锁屏控制，替掉现在手写的 `PlayerBubble` 一部分职责；同时满足 Android 14+ 前台服务要求。
- HDR/DV 路径：现有 base-layer 回退逻辑（`DecoderRenderersFactory.java:75-83`）在 1.8 上仍需重测，因为 DoVi 相关 API 迭代过。

## 8. ROI 排序清单

> 工作量按"1 名熟悉该代码库的工程师"估，风险含回归风险。人日不含真机验收。

### P0 —— 必须修（安全/崩溃），合计约 3–4.5 人日

| # | 问题 | 证据 | 影响 | 修复 | 人日 | 风险 |
|---|---|---|---|---|---|---|
| P0-1 | AdminConsole 零鉴权 + CSRF | `AdminConsole.java:38,71-73,135-242,246-317,425-433`；`AdminPanel.java:34`；`assets/admin/index.html:336-417` | 局域网内可读历史/收藏、删文件、推送播放、改配置 → 可加载任意远程脚本 | 复用 `ConfigTransferServer.java:39-135` 的 token/TTL/peer/Host/Origin/content-type 范式；危险操作二次确认 | 1–1.5 | 低 |
| P0-2 | MiniFtpServer 匿名可写可删 | `MiniFtpServer.java:16,26,84-88,148-188`；`AdminPanel.java:104-110` | 同网段任意上传/下载/删除，明文 | 默认关闭；或一次性口令 + 只写 + TTL + 局域网限源；或删功能 | 0.5–1 | 低 |
| P0-3 | minSdk 23 下 API 24/26 调用 | `VideoFilterDialog.java:153`；`PushFiles.java:136`；`MiniFtpServer.java:174`；`AdminConsole.java:391`；`QueueStore.java:55` | Android 6/7 确定性崩溃（筛选/看 MD/FTP 下载/删频道/删队列） | 换兼容实现 + JVM 用例；并加 API 检查门禁 | 0.5–1 | 低 |
| P0-4 | 崩溃守卫主线程 `while(true) Looper.loop()` | `MainActivity.java:243-258`（另有 `260-272` 吞异常） | 插件异常后主线程进入未定义状态的循环，可能 ANR/白屏/状态错乱；永久不清 `LOADING_SITE` | 限定次数（1 次）后走正常崩溃或回首页；非主线程崩溃必须降级到"提示重启"而非静默继续 | 0.5 | 中（涉及崩溃策略，需产品确认） |

### P1 —— 性能/稳定性，合计约 15–22 人日

| # | 问题 | 证据 | 修复 | 人日 | 风险 |
|---|---|---|---|---|---|
| P1-1 | 长列表无虚拟化 | 全仓 0 处 RecyclerView；`MainActivity.java:942-954`、`MobileShell.java:777,2015,2436`、`TvLive.java:646` | 首页/搜索/频道/选集换 RecyclerView + ViewHolder + 预取；首屏 24 张、其余分批 | 3–5 | 中 |
| P1-2 | 无 R8/BP/资源收缩/ABI split | `build_android.py:107,112`；`build-validation.json:7` | Gradle 化后开 R8 full、baseline profile、ABI split；手机包只发 arm64 | 3–5 | 中 |
| P1-3 | 播放器后台不释放 + 无前台服务 | `MainActivity.java:1787-1794,1808`；全仓 0 处 `startForeground` | `MediaSessionService` + 前台服务；`onStop` 明确"小窗继续/释放"策略 | 3–4 | 中 |
| P1-4 | 冷启动主线程读配置 + `recreate()` | `MainActivity.java:536-552,629-645` | 配置异步加载 + 骨架屏；密度重算移到首帧后；埋冷启动 trace | 1–2 | 低 |
| P1-5 | WebView 注入每 1.8s 无限 + 后台不停 | `TvLive.java:1364-1372,2048-2065` | 幂等注入、限次数、`onStop` 停止；条件允许时独立进程 | 1–2 | 中 |
| P1-6 | 线程池碎片化（30+ 线程峰值） | `MainActivity.java:29-36`；`RequestScope.java:13`；`ImmersivePoster.java:48`；`DownloadManager.java:739` 等 | 统一 IO/CPU 调度器 + 可见区优先 + 去重 | 2–3 | 中低 |
| P1-7 | 构建无 API 级别/lint 门禁 | 无 Gradle；`build_android.py` 无 lint/`--require-whole-apk` | Gradle 化后开 `lint`（含 `NewApi`）+ 16KB 全量校验，构建失败即拦 | 1（随 P1-2） | 低 |
| P1-8 | 图片磁盘缓存无上限 | `ImageLoader.java:196-246` | LRU 配额 + 退出清理 + ETag | 1–1.5 | 低 |
| P1-9 | 升级 targetSdk 到 33/35 | `AndroidManifest.xml:3` | 分两步升；补 network security config（明文域白名单）、`POST_NOTIFICATIONS`、edge-to-edge insets、预测返回 | 3–4 | 高（行为回归面广） |

### P2 —— 架构/技术债，合计约 30–60 人日（按里程碑拆）

| # | 问题 | 证据 | 修复 | 人日 | 风险 |
|---|---|---|---|---|---|
| P2-1 | 构建系统自研、无法增量 | `scripts/build_android.py` 全流程 | Gradle(AGP 9)+Java 17；产物校验保持 | 3–5 | 中 |
| P2-2 | 单 Activity 巨型类与全局状态 | `MainActivity.java` 1825 行 / ~90 字段；`MobileShell.java:50-76` 静态状态 | ViewModel+SavedState；按职责切状态类；页面栈抽 `Navigator` | 8–15 | 中高 |
| P2-3 | engines 无生命周期上限 | `MainActivity.java:121`；`SourceController.java:60-131` | LRU + idle 关闭 + 显式 `closeAll()` | 2–3 | 中 |
| P2-4 | 数据层直连 prefs/JSON | `Prefs.java`、`WatchHistory`、`QueueStore` | Repository + 类型化模型；保留 prefs 兼容层 | 5–8 | 中 |
| P2-5 | 缺少 View 层自动测试 | `android/tests/*` 全是纯 JVM；`build-validation.json:18-19` | Robolectric + 播放器 fake；CI 化离线套件 | 3–5 | 低 |
| P2-6 | 依赖陈旧（Media3 1.5.1、OkHttp 3.14.9、Kotlin 1.8.0） | `media-dependencies.json:123-208`；`dependencies.json:1-51` | 随 Gradle 化统一升级并跑解码回归 | 2–4 | 中 |
| P2-7 | 发布不可复现（dirty 构建） | `build-validation.json:15` | 发布门禁 clean tree；签名密钥管理 | 0.5 | 低 |
| P2-8 | 文档漂移 | `docs/BUILD.md:19` 写 0.4.33-preview，manifest 1.2.0 | 版本/产物字段由构建脚本注入文档 | 0.5 | 低 |
| P2-9 | Compose 试点（设置/关于页） | — | `ComposeView` 内嵌验证，决定是否推进 | 5–10 | 中 |

### 投入产出小结

- **P0（3–4.5 人日）**：消除 4 类"对外承诺支持却会崩/会被接管"的问题，是唯一"不做不行"的集合。
- **P1-1/P1-3/P1-2 三项（约 9–14 人日）**：直接作用于"低端电视盒更顺、手机包装得下、后台不被杀"三个用户可感知点。
- **P2-1（3–5 人日）是杠杆点**：它把后续 P1/P2 的成本打七折，且是 R8/BP/ABI split 的唯一入口。

## 9. 路线图（建议）

**第 1 周**：P0-1/P0-2（服务加固，照 `ConfigTransferServer` 范式）→ P0-3（4 处 API 替换 + 4 个 JVM 用例）→ P0-4（崩溃策略收敛）。

**第 2–4 周**：P1-7/P1-2 第一步（Gradle 化，产物校验对齐）→ P1-4（冷启动异步化 + trace）→ P1-8（磁盘缓存配额）。

**第 5–8 周**：P1-1（长列表虚拟化）→ P1-3（前台服务/MediaSession）→ P1-6（调度器收敛）→ P1-5（WebView 限流）。

**第 9–12 周**：R8 full + Baseline Profile + ABI split（P1-2 收尾）→ P1-9（targetSdk 33→35 分两步）→ 解码/16KB 全量回归。

**第 3–6 个月**：P2-2（状态承载）→ P2-3/P2-4 → P2-5（Robolectric/CI）→ P2-9（Compose 试点决策）。

## 10. 验收与度量（建议纳入 CI 的硬指标）

1. 构建产物：`aapt2 dump badging` 断言 `targetSdk`/`minSdk`、`usesCleartextTraffic`、导出组件清单；`zipalign -c -P 16`；`--require-whole-apk` 通过。
2. 静态：`lint`（`NewApi`/`UnsafeIntentLaunch`/`WebView`/`Exported*` 全 error）+ `javac -Xlint`。
3. 体积：APK 预算门禁（如 TV ≤ 40 MB、mobile ≤ 40 MB），超预算失败。
4. 启动：Macrobenchmark cold start `P90` 基线（先在目标电视盒上测出基线，再谈优化目标）。
5. 内存：连续切换 30 次来源、连续播放 60 分钟、TvLive 切台 30 次，记录 `dumpsys meminfo` 峰值与 WebView 进程内存。
6. 安全回归：AdminConsole/FTP 的未授权访问用例（无 token 应 403/410）、CSRF 用例（跨 Origin POST 应拒绝）、明文域白名单用例。
7. 兼容：API 23/26/28/35 四档模拟器跑 P0-3 的 4 条路径 + 现有 15+ 离线套件。

## 附录 A：关键证据索引

- 构建/产物：`scripts/build_android.py:95-158`、`android/build/build-validation.json:1-21`、`android/build/catpaw/assets-mobile/catpaw/runtime-capabilities.json:1-16`
- 依赖：`android/dependencies.json`、`android/media-dependencies.json`、`android/torrent-dependencies.json`（jlibtorrent）、`android/decoder-dependencies.json`
- 安全：`AdminConsole.java:35-242`、`MiniFtpServer.java:16-199`、`ConfigTransferServer.java:35-135`、`LocalProxy.java:19,85-90`、`MagnetStream.java:357-400,546-554`、`UpdateManager.java:300-427,466-496`、`UpdatePolicy.java:12-156`、`UpdateApkProvider.java:13-37`、`AccountPanel.java:900-1006`、`PrivacyPin.java:7-27`
- 性能/架构：`MainActivity.java:20-131,167-185,512-668,694-697,942-954,1040-1105,1235-1249,1638-1647,1759-1820`、`MobileShell.java:50-130`、`SourceController.java:60-159`、`ImageLoader.java:28-75,196-246`、`TvLive.java:1364-1372,1398-1456,2048-2065,2196-2227`、`VideoOutput.java:15-90`、`DecoderRenderersFactory.java:42-212`、`PlaybackController.java:928-952`、`HomeScreen.java:197-208,380-475`
- 文档自述：`docs/EXPERIENCE_REVIEW.md:76-84,106-108`、`docs/android-node-runtime-packaging.md:47-64,103-106`、`docs/mobile-regression-2026-11-07.md:19-22`、`docs/PRIVACY.md:11-27`、`docs/BUILD.md:17-19`、`README.md:57-61`、`THIRD_PARTY_NOTICES.md:1-33`、`DISCLAIMER.md`

## 附录 B：本次未验证、需下一轮补的

1. dex 方法数与真实包内体积构成（需解包 `dist/WhaleFall-1.2.0-android-mobile.apk`）。
2. 冷启动/首页首帧/滚动帧率的真机数据（当前唯一基线是 1920×1080 合成场景，见 `docs/EXPERIENCE_REVIEW.md`）。
3. `MobileShell`/`PlaybackControls`/`TvLive` 只读了关键段落，未逐行审计（各 1600–3800 行）。
4. `.github/workflows` 在当前工作区快照中未见（`docs/BUILD.md:37` 提到 `build-multiplatform.yml`），无法确认 CI 实际覆盖范围；不作为结论。
5. 各预编译 `.so`（ffmpeg/gav1/vpx/jlibtorrent/QuickJS）的 ELF 页对齐与 16KB 实测，需跑 `scripts/verify_android_runtime_package.py --require-whole-apk`。
