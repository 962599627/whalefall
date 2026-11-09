# ARCH_NOTES 1.2.2 · 架构评审、实施规格与硬约束

> 角色：架构师 / 技术负责人 ｜ 版本基线：`android/version.properties` = **1.2.2 / 12020** ｜ 日期：2026-11-09
> 输入：`docs/ANDROID_AUDIT_2026.md`、`docs/GRADLE_MIGRATION.md`、`docs/BASELINE_PROFILE.md`、`docs/TV_LIVE_RESPONSIVE.md`、`docs/LIBRARY_LAYOUT_SPEC.md`、`docs/HANDOFF_2026-11-09.md`、`docs/SPRINT_BOARD.md`、`docs/DEFECTS.md`、`docs/PRODUCT_BACKLOG.md`（E3）
> 性质：只写文档，**本轮不改任何代码**。§3 是交给 `mobile-dev` 的直接实施规格；§5 是 code review 的硬性 checklist。
> 与冲刺板对齐：本文对应 **S2-01（首页网格虚拟化）**、**S2-05（Gradle 正式化）**、**S3 Measured BP 评估**；S1 收口轮不改大文件（见 SPRINT_BOARD §3 R2）。

---

## 0. TL;DR（10 条，给 Lead 拍板用）

1. **当前最高风险不是性能，是播放页/直播页的"层叠状态"**：已确认 3 个代码级缺陷——`TvLive` 播放器不随 Activity/页面重建关闭、`releasePlayer()` 不清 `playbackLayer/playerOverlay/playerChrome` 字段、`MobileShell.dockView` 静态残留。都属于"看不见的存活对象"，是真机黑屏/鬼声/内存增长的高发区。**建议提到 S2 第一项（0.5–1 人日）**，排在首页虚拟化之前。
2. **首页网格虚拟化不依赖 Gradle**（E3 已确认），可在现有 Python 构建链上完成；按 §3 规格实施预计 **2–3 人日**，经一次 code review 可合入。风险集中在 holder 复用、图片 tag、滚动恢复三处，§5 已把它们变成硬规则。
3. **静态 View 引用只有一处**（`MobileShell.dockView`），可以当天修；真正的泄漏面是 **Activity 字段跨页残留**（`playbackLayer/playerOverlay`），不在静态变量里，因而更容易被忽略。
4. **体量债已过安全线**：`MobileShell` 4591 行 / `MainActivity` 2011 行 / `MobileDetailScreen` 1906 行 / `TvLive` 2228 行，且 11-08→11-09 一天内 `MobileShell` 从 3827 涨到 4591（+764 行）。**S2 起冻结 `MobileShell` 单人写窗口，先"加文件不加行"**（新代码放 `MobileHomeGrid.java` 等新文件，见 §3.11）。
5. **R8 结论：体积已达标（-47%），功能未过门**。R8 arm64 release 已出包（32.6→33.05 MB），但片源扩展引擎初始化失败（`GRADLE_MIGRATION.md:61`），根因未定位。**1.2.2 不发布 R8 包**；先做 ABI 过滤拿安全收益，R8 留在 S2-05 定位。
6. **AGP 9 结论：可构建，收尾未完成**。AGP 9.5.1 + Gradle 9.6 已验证可出包（`HANDOFF:60`），但缺 gradle wrapper、aapt2 走机器绝对路径、**tv flavor 的 manifest 没有用 `${whaleEdition}` 占位符**（见 §4.2 第 3 条，选错更新通道的隐患）。收尾清单 7 项、约 2 人日。
7. **Measured Baseline Profile：本机不可做**（无 adb / 无 macrobenchmark 工具链），按 D3 口径 1.2.2 接受手写 BP；但 `baseline-prof.txt:5` 引用了**不存在的方法** `MobileShell.renderHome()`，需要修或删（§4.3）。
8. **构建工具链的 `/tmp` 依赖是发布风险**，不是"习惯问题"：HANDOFF 已写明 /tmp 每次会话可能被清空、SDK 来自 `/tmp/aosp`、aapt2/zipalign 是 qemu wrapper（`HANDOFF:24-32`）。修法不是"记住重建"，而是 **doctor 脚本 + wrapper + 只认环境变量**（§2.5）。
9. **5 条硬约束**（§5）作为评审门禁：holder 复用、图片 tag、播放页合成层、方向/formFactor、构建脚本可移植性。**违反即打回，不写"下次改"。**
10. **建议 S2 执行顺序**：① 层叠状态 P0 修复 ② 首页网格虚拟化（独占 `MobileShell`）③ 图片磁盘缓存配额 ④ Gradle/R8 收尾 ⑤ 其余列表（全部剧集弹窗、TV 搜索）逐项开。

---

## 1. 现状快照（1.2.2，含对旧文档的勘误）

| 事实 | 证据 / 备注 |
|---|---|
| 版本唯一源 `version.properties` = 1.2.2 / 12020 | `android/version.properties:1-2`；Gradle `app/build.gradle:3-5,16-17` 已读取 |
| 双构建链并存：Python 脚本（发布用）+ `gradle/`（验证中） | `scripts/build_android.py`（202 行，含 `--assets-only`、ABI 过滤、baseline assets）；`gradle/app/build.gradle`（111 行） |
| 发布包：TV 36,219,718 B / mobile 61,996,331 B | `android/build/build-validation.json:4,7`（1.2.2，`git_dirty=true`） |
| R8 包已产出但未发布 | `dist/WhaleFall-1.2.2-android-mobile-r8-arm64.apk`；问题见 `GRADLE_MIGRATION.md:61` |
| RecyclerView **已落地 4 处**：搜索、片库、频道管理、直播频道列表 | `MobileShell.java:2064,2357,3025,3038`；`ChannelManagerScreen.java:149,350`；`TvLive.java:737,745` |
| 首页海报网格仍是 `ScrollView + LinearLayout` 全量构建 | `MobileShell.renderHomeTab()` `:810-1484`、`renderVideoGrid()` `:1761-1862`（每次加载更多 `container.removeAllViews()` 重建全部卡片） |
| 手写 Baseline Profile 已进包 | `gradle/app/src/main/baseline-prof.txt`（10 行，见 `docs/BASELINE_PROFILE.md`）；AGP 打进 `assets/dexopt/baseline.prof` |
| 行数 Top：MobileShell 4591 / TvLive 2228 / MainActivity 2011 / MobileDetailScreen 1906 / PlaybackController 1637 / PlaybackControls 1615 / HomeScreen 1518 | 按 `read` 文件总行数；本会话前一次读数为 3827 / 2228 / 1825 / 1906 / 1637 / 1615 / 1518（`MobileShell` 近期 +764 行） |
| 安全 P0 已收敛（代码确认、待真机） | AdminConsole token+TTL+LAN+Host（`AdminConsole.java:19-21,80,186-205,258-273`）；FTP 改 `wf`+一次性 6 位口令+LAN（`MiniFtpServer.java:86-121`）；API 24/26 调用已清（DEF-1012） |

**文档勘误（本文不重复错误结论，后续由 pm-owner 统一修）**
- `docs/ANDROID_AUDIT_2026.md` §4.2「全仓 0 处 RecyclerView」已过时；P1-1 应改为"首页网格尚未虚拟化"。
- `docs/PRODUCT_BACKLOG.md:170,327` 同样引用了"0 处 RecyclerView"。
- `docs/ANDROID_AUDIT_2026.md` P0-1/P0-2 状态应更新为"已收敛，残余风险见 DEF-2004"。
- `MiniFtpServer.java:11` 的 javadoc 仍写"匿名登录（用户名/密码任意）"，与实现（`wf`+口令）不一致——注释债，顺手修。
- `gradle/app/build.gradle` 里 release `buildTypes` 被重复定义两次（`:58-63` 与 `:66-74`），后者生效；属可读性债。

---

## 2. 最高风险的 5 项技术债（按风险排序）

### 2.1 播放页 / 直播页的层叠状态管理 —— P0，0.5–1 人日

**层清单（谁加、加到哪、谁移除）**

| 层 | 加在哪 | 谁移除 | 现状风险 |
|---|---|---|---|
| `playbackLayer`（视频/字幕/弹幕/触屏 HUD） | `PlaybackController.playerPage` 新建 root 后 `addView`（`:914-925`） | 无显式移除；靠下一次替换 root | 字段不清空 → 旧 View 树被字段持有 |
| `playerOverlay`（PlayerChrome 控件层） | `PlayerChrome` `addView` 到 `playbackLayer`（`:336,358`），字段赋值 `:361` | 无 | 同上；`PlayerChrome` 没有 `close()` |
| `playbackLoading`（解析中遮罩） | `PlaybackController:576-577` 加到 root | `clearPlaybackLoading` `:584-591` | 正确 |
| `TvLive.Player.overlay`（WebView 直播） | `TvLive:1669` 加到 root | 只在 `TvLive.Player.close()`（`:2205`） | **不在 Activity 生命周期内关闭** |
| 屏保 / 壁纸层 | `MainActivity.showScreensaver` 等 | 各自 dismiss | 正确 |
| `VideoWallpaper` / `HomeAmbient` | root index 0/1 | `releaseWallpaper` | 正确 |

**缺陷 A（P0）：TvLive 播放器不随页面/生命周期关闭**
- 证据链：`TvLive:1669-1676` 把 overlay 加到 `a.root` 并 `a.tvPlayer = this`；`MainActivity.stopWeb()`（`:1663-1665`）只转给 `PlaybackController.stopWeb()`，后者只处理 `webResolver`（`PlaybackController.java:44-48`）；`MainActivity.onStop()`（`:1952-1981`）与 `onDestroy()`（`:1984-2006`）**都没有 `tvPlayer.close()`**；`MobileShell.render()`（`:390-403`）也只调 `stopWeb/releasePlayer`。
- 触发路径：开直播后按 Home 键退后台（音频/网络继续）、旋转或改窗口尺寸触发 `render()` 重建 root（旧 WebView 脱离窗口但未 destroy，`tvPlayer` 字段仍持有）、切换配置/导入来源触发 `render()`。
- 影响：后台耗电/耗流量、鬼声、WebView 内存不释放；`MobileShell.setDockVisible(false)` 之后若重建，Dock 显隐状态与新 root 不同步。

**缺陷 B（P0）：`releasePlayer()` 不清理层字段**
- 证据：`PlaybackController.releasePlayer`（`:1538-1558`）只做 seek/history/controls/player 的收尾，未触及 `a.playbackLayer`、`a.playerOverlay`、`a.playerChrome`（声明见 `MainActivity.java:133,137,140`；赋值见 `PlaybackController:921-925`、`PlayerChrome:361`）。旧 root 被下一次 `setContentView` 丢弃，但 Activity 字段仍引用旧 `playbackLayer` → 旧 `VideoOutput/SurfaceView`、控件位图、字符缓存全部被拖住，直到下一次进播放页。
- 影响：进出播放页/切集循环时内存台阶式上涨（真机可用 `dumpsys meminfo` 观察），并与缺陷 A 叠加。

**缺陷 C（P1）：`MobileShell.dockView` 静态残留**
- 证据：`private static View dockView`（`:731`），只在手机底部 Dock 分支赋值（`:466`）；平板右栏分支（`:419-434`）不复位。手机→平板（窗口跨 640dp）触发 `render()` 后，静态字段仍持有旧 root 的 Dock，`setDockVisible`（`:734-736`）操作的是已 detach 的视图。
- 影响：旧 root 泄漏（整棵树，含图片/焦点动效）；直播隐藏 Dock 的语义在平板模式下变成空操作。

**修复契约（代码级，供 mobile-dev 直接照做）**
```java
// MainActivity：唯一层清理入口
void clearPlaybackLayers() {
    if (playbackLayer != null) {
        playbackLayer.removeAllViews();
        if (playbackLayer.getParent() instanceof ViewGroup)
            ((ViewGroup) playbackLayer.getParent()).removeView(playbackLayer);
    }
    playbackLayer = null;
    playerOverlay = null;
    playerChrome = null;      // PlayerChrome 无 close，先断引用；后续补 close()
    if (miniDetails != null) { miniDetails.remove(); miniDetails = null; }
    miniMode = false;
}
// PlaybackController.releasePlayer() 末尾调用 a.clearPlaybackLayers()；
// TvLive.Player.close() 已存在，新增 static void closePlayer(MainActivity)
//   并在 onStop/onDestroy/MobileShell.render() 开头调用；关闭后 a.tvPlayer = null。
// 同时删除 MobileShell.setDockVisible 的静态字段：dockView 改为 MobileShell 实例字段，
//   setDockVisible 改实例方法；若跨类调用需静态入口，用 WeakReference<View>。
```
**验收**：① 开直播→Home 键→30 s 无声音、`dumpsys meminfo` 不涨；② 进出点播播放页 10 次，Java heap 稳定；③ 手机↔平板改窗口尺寸后 Dock 状态正确；④ 正常返回路径无黑屏/双 overlay。

### 2.2 静态 View 引用与内存泄漏面 —— P1，0.5 人日

- `git grep "static .*(View|Activity|Context|Dialog|WebView)"` 结果：只有 `MobileShell.dockView`（`:731`）与 `MainActivity.activeActivity`（`:188`，onDestroy 已清）。**静态不是主战场**。
- 真正的泄漏面是"Activity 字段跨页残留 + 静态集合持对象"：
  | 位置 | 持有物 | 现状 | 规则 |
  |---|---|---|---|
  | `MainActivity.playbackLayer/playerOverlay` | 旧播放页 View 树 | 见 §2.1-B | 必须由 `clearPlaybackLayers` 清 |
  | `ImageLoader.IN_FLIGHT`（`:28-30`，静态 map） | `List<ImageView>` | 只在 `cancel()` 时整体清（`:33-40`）；请求完成/异常时按 key 移除 | 页面切换必须 `cancelArtwork()`；RecyclerView 滚动不得调用整体 cancel |
  | `MainActivity.sharedPhonetic`（静态） | 拼音索引（数 MB） | 跨 Activity 复用，设计如此 | 保留；注释说明 |
  | `MobileShell.cachedSearchHits`（静态 List） | 搜索结果模型 | 新搜索/清空时 `clear()`（`:2097,2208,3683`） | 保留，但加"上限 200 条"防御 |
  | `MobileShell.homeScrollY/pendingHomeScroll` | int | 已被 `pendingHomeScroll` + 新列表状态替代（§3.9） | 迁移后删旧字段 |
- **规则**：不得新增 `static View/Activity/Context/Dialog`；跨页面对象只允许"模型/字符串/Parcelable 状态"。

### 2.3 MainActivity / MobileShell 体量 —— P1，3–5 人日（分两步）

- 现状：`MobileShell` 4591 行（含 5 个 tab + 设置 10 页 + 片库 + 2 个 Adapter + 图标绘制内部类），`MainActivity` 2011 行（路由/播放/壁纸/崩溃/更新/权限/屏保），单人无法在 1 天内安全改动。
- **硬阈值（S2 起执行）**：单文件新增代码不得突破当前行数；`MobileShell` 只允许"替换/迁移"，新增功能一律新建文件。评审看 diff 行数与新增文件。
- **拆分方案（机械、低风险，按顺序做）**：
  1. `MobileHomeTab.java`：`renderHomeTab` + 网格 Adapter（顺路完成 §3，净减 `MobileShell` 约 900 行）。
  2. `MobileLibraryTab.java`：`renderMyTab`/`buildLibraryList`/`MyListAdapter`/`RowHolder`（约 500 行）。
  3. `MobileSettingsTab.java`：`renderSettingsTab`/`populateSettingsBody`/设置卡片工具（约 900 行）。
  4. `MobileNavChrome.java`：Dock/右栏/图标绘制内部类（约 400 行）。
  5. `MobileSearchTab.java`：搜索页 + `SearchResultsAdapter`（约 600 行）。
  6. `MainActivity` 只保留：生命周期、`Screen` 栈、`open/refresh/renderReturn`、字段与工具方法；播放/壁纸/崩溃/更新各自抽 helper（`CrashReporter`、`WallpaperHost`）。
- **不做**：不在 1.2.x 发布窗口内做"状态承载/ViewModel/Repository"重构（PRODUCT_BACKLOG 已明确砍掉）；拆文件只为降低并行冲突与单文件复杂度，不改行为。
- **风险控制**：每拆一个文件单独提交；拆完跑 `android/tests/run_*.py` 全绿；`MobileShell` 写窗口保持串行（SPRINT_BOARD R2）。

### 2.4 RecyclerView 虚拟化范围（剩余清单）—— P1

| 列表 | 现状 | 数据规模 | 优先级 | 建议 |
|---|---|---|---|---|
| 手机首页海报网格 | `ScrollView + LinearLayout`（`MobileShell:810-1484,1761-1862`） | 24/页，最多 120+ | **P1 / S2-01** | 本文 §3 |
| 「全部剧集」弹窗 | 全量 `LinearLayout`（`MobileDetailScreen:1632-1693`） | 单线路最多数百集 | P2 | 换 `RecyclerView`（3 列），或限制最多 300 集 + 分组 |
| 详情页剧集分组 | 每组 50 集全量（`MobileDetailScreen:1470-1546`） | ≤50/组 | P2（可接受） | 保持；分组本身已限流 |
| 移动搜索 | 已 RecyclerView（`MobileShell:2064,2357`） | 120 上限 | 已完成 | — |
| 片库历史/收藏 | 已 RecyclerView（`:3025,3038`） | 300/60 上限 | 已完成 | 修 DEF-2005/2006 |
| 频道管理 | 已 RecyclerView（`ChannelManagerScreen:149`） | 数百 | 已完成 | — |
| 直播频道列表 | 已 RecyclerView（`TvLive:737`） | 数百 | 已完成 | — |
| TV 首页 | `HomeScreen` 窗口化（每次最多 120 张） | 120 | P2（独立 story） | TV 焦点逻辑复杂，需单独设计，不塞进 S2 |
| TV 搜索 | `GlobalSearchScreen` ScrollView 列表 | 120 上限 | P2 | 等 TV 首页方案确定后复用 |
| 设置/关于/文件管理 | ScrollView | 静态/短列表 | 不做 | — |

### 2.5 构建工具链的 `/tmp` 与外部路径依赖 —— P0（发布可复现性），1–1.5 人日

**证据链**
- `HANDOFF:24-32`：`/tmp/aosp/android-35/android.jar`、`/tmp/aosp/bt/android-15/*`、qemu wrapper、`/usr/x86_64-linux-gnu` 运行库——**/tmp 每次会话可能被清空**，且重建步骤有 4 步手工操作。
- `scripts/build_local_phone.sh:7-11`：JDK/`BUILD_TOOLS`/`ANDROID_JAR`/预编译目录全部是机器绝对路径，其中 `ANDROID_JAR` 默认 `/tmp/aosp/...`。
- `gradle/gradle.properties:2`：`android.aapt2FromMavenOverride=/root/workspace/toolchain/sdk/aapt2`（机器专属、qemu wrapper）。
- `gradle/` 无 wrapper（无 `gradlew` / `gradle/wrapper`），Gradle 版本取决于本机 `/root/workspace/toolchain/gradle-9.6.0`。
- 双构建链无一致性门禁（SPRINT_BOARD R6）：Python 包与 Gradle 包谁发布未定。

**修复契约**
1. 新增 `scripts/doctor.py`（~120 行）：检查 JDK/SDK/aapt2/android.jar/预编译 `.so`/签名 keystore/环境变量，逐项打印"OK / 缺失 + 修复命令"；`build_android.py` 与 Gradle 的 `prepareRuntimeAssets` 先调 doctor，失败即给出可复制命令。
2. 平台包优先用仓库内 `tools/android-35/android.jar`（`prepare_android_platform.py` 已支持下载到 `tools/`），`build_local_phone.sh` 默认值改为**仓库相对路径**并做存在性检查；`/tmp` 只作为最后回退并打印警告。
3. Gradle 侧：补 `gradle/wrapper/gradle-wrapper.properties`（pin `distributionUrl` + sha256），`aapt2FromMavenOverride` 改为读环境变量（`MARCOMOVIE_AAPT2`），缺省时用 AGP 自带 aapt2（x86_64 环境）或报错说明。
4. 定死"发布链"：1.2.x 仍用 Python 链发布，Gradle 链只做验证；两条链的产物摘要写入 `build-validation.json`，由 Lead 在 S2-05 拍板切换时机。
5. 机器相关路径集中到 `scripts/local_env.example.sh`（被 git 忽略的 `local_env.sh` 覆盖），文档只写环境变量名。

---

## 3. 「首页海报网格 RecyclerView 化」落地方案（S2-01 / E3-S1 + E3-S2）

### 3.0 给 mobile-dev 的 10 条实施要点（先看这个）

1. **只改移动端首页**：TV 首页（`HomeScreen`）与 TV 搜索（`GlobalSearchScreen`）不在本次范围。
2. 新建 `MobileHomeGrid.java`（`com.whalefall.movie` 包），**不要继续往 `MobileShell` 里加行**；`MobileShell.renderHomeTab()` 只负责组装 prelude 与创建 `MobileHomeGrid`。
3. 结构：`contentContainer` 里放 **一个 RecyclerView + 现有 `filterSummaryBar` 浮层**；prelude（品牌行/历史/今日首推/今晚看什么/分类条/状态文案）作为 **item 0（span=满列）**，卡片是 item 1..N（span=1），页脚（加载更多）是最后一个满列 item。
4. `GridLayoutManager(span)` + `SpanSizeLookup`（prelude/footer 满列，卡片 1 列）；span 用 §3.5 公式按 **RecyclerView 实际可用宽度**算，不用 `windowWidth()`（后者含右侧导航栏）。
5. 数据源仍用 `MobileShell.currentVideos`；Adapter 内部持有副本，`submit(List)` 用 **DiffUtil**（按 `id` 判同、按可见字段判内容），**禁止 `notifyDataSetChanged()`**。
6. 图片绑定必须遵守 §3.7：无海报时 `setTag(null)+setImageDrawable(null)`，`onViewRecycled` 同样清理；**任何 holder 不得用 `getTag()` 找子 View**（DEF-1001 的教训，`HANDOFF:52`）。
7. 点击直接 `a.openListing(site, v)`（`MainActivity:309` 已有的重定向/文件夹逻辑）；保留 `tag = "video:" + id` 与首卡 `"primary"`（TV 焦点恢复兼容），但不得依赖它们。
8. 滚动恢复：切 tab 前存 `LayoutManager.onSaveInstanceState()` 到 `MobileShell` 静态字段；重建后恢复一次；`MainActivity.onSaveInstanceState` 增加 `whale_home_list` 的 Parcelable 存取（§3.9）。
9. 分页/自动加载/取消语义沿用现有 `currentPage/totalPages/loadingPage/pageFailed/homeRequest`，不要另造状态机；「加载更多」体验不变（E3-S2 #1）。
10. 验收按 §3.12，至少给"快滚 10 s 无白块/无串图"录屏 + 绑定计数证据（可见区 + 预取，非全量）。

### 3.1 目标与非目标

**目标**：首页 120 条数据下首帧只创建可见区 + 预取卡片；快滚无 ANR/白块；加载更多只追加；返回/切 tab 位置误差 ≤1 行；点击/筛选/分页不回归。

**非目标**（对齐 PRODUCT_BACKLOG E3-S1/S2 "不做什么"）：不迁 Compose/Kotlin；不改首页模块组成（继续观看/今日首推/今晚看什么顺序不动）；不改搜索页 120 上限；不做预加载下一页；不承诺低端机帧率数值。

### 3.2 现状行为（必须原样保留的 8 件事）

| # | 行为 | 证据 |
|---|---|---|
| 1 | `body` 子项顺序：品牌行 → 历史头+横滑 → heroContainer → tonightCard → 分类条（含筛选按钮）→ 状态文案 → 网格 → 加载更多 | `MobileShell:856-1125` |
| 2 | 筛选摘要条是 `contentContainer` 的浮层（不进滚动区），底距 `filterBarBottomDp` | `:829-844`、`:110-111` |
| 3 | 首页准备期：`homeReady=false`；`resume()` 复用 retained root 时恢复滚动/`resumeHome` | `:117-139` |
| 4 | 分页：`currentPage/totalPages`、page==1 时 `currentVideos.clear()` 后累加，过滤掉 `action` 项 | `:1368-1374` |
| 5 | 加载更多按钮三态：隐藏 / "加载更多影片 (x/y)" / "正在加载更多…" / "点击重试" | `:1110-1125,1419-1429,1457-1462` |
| 6 | 自动加载：距底 ≤240dp 时 `currentPage++` 并取下一页 | `:1464-1480` |
| 7 | 翻页请求带 `homeRequest` token，旧请求结果不得覆盖新分类（500ms 连切 5 次分类） | `:1166-1184`、E3-S2 #3 |
| 8 | hero/今日首推在列表更新后刷新；`refreshTonight` 每次数据变化重新随机 3 条 | `:1386-1418` |

### 3.3 目标结构

```
contentContainer (FrameLayout)
├─ RecyclerView rv                       // LayoutManager = GridLayoutManager(span)
│    ├─ item 0  PRELUDE  (span=span)     // 原 body 去掉 grid/loadMore/status 后的 LinearLayout
│    │    ├─ buildHomeTopHeaderRow()
│    │    ├─ 历史头 + HorizontalScrollView
│    │    ├─ heroContainer / tonightCard
│    │    └─ 分类条（catRow + filterButton）+ statusText
│    ├─ item 1..N CARD (span=1)          // 原 renderVideoGrid 单卡结构，逐字段一致
│    └─ item N+1 FOOTER (span=span)      // loadMoreBtn 三态
└─ FilterSummaryBar（现有浮层，不动）
```

`a.body`（TV 侧 render/restore 依赖）仍指向 prelude 的 LinearLayout，避免 TV 逻辑受影响（该字段在移动端仅被 `render()` 复用；保持赋值即可）。

### 3.4 视图类型与 span

| viewType | 内容 | span | 备注 |
|---|---|---|---|
| `TYPE_PRELUDE = 0` | 预建的 prelude `LinearLayout` | `span` | 只创建一次；历史/hero/分类变化时**原地更新**，不 notify |
| `TYPE_CARD = 1` | 单张海报卡（封面+角标+标题+副标题） | 1 | 复用 `CardHolder` |
| `TYPE_FOOTER = 2` | `loadMoreBtn` | `span` | 三态文案 + 点击重试 |

`SpanSizeLookup`：`getSpanSize(pos) = viewType == CARD ? 1 : span`。**空状态**（无片）不是独立 item，沿用 prelude 里的 `statusText`；不要学片库 DEF-2005 那样让空状态只占 1 列。

### 3.5 尺寸与断点（Phase A：视觉不变；Phase B：与 `LIBRARY_LAYOUT_SPEC` 统一）

**Phase A（本次必做）**——保持与现状近似的观感，只换容器（允许 ±5dp 的边缘差，以截图对比验收）：
```
// 在 rv.post / rv.addOnLayoutChangeListener 里取 rv 实际内容宽（此时 padding 已生效）
int contentW = rv.getWidth() - rv.getPaddingLeft() - rv.getPaddingRight();
float dp = contentW / density;
int span    = dp >= 720 ? 5 : (dp >= 520 ? 4 : 3);   // 与 renderVideoGrid:1763-1764 的三档等价
int sidePad = dp >= 600 ? 20 : 16;                   // 现状 body/gridContainer 为 16dp
int gap     = 10;                                    // 现状相邻卡之间 5+5=10dp
int cardW   = (contentW - gap * (span - 1)) / span;
int posterH = Math.round(cardW * 1.48f);             // 现有 1.48 比例，不改
```
- `rv.setPadding(sidePad, 0, sidePad, bottomPad)`，`clipToPadding=false`；`bottomPad = filterBarBottomDp + FilterSummaryBar.HEIGHT_DP + 18`（等价现状 body 底部 padding `:849-853`）。
- item 宽 `MATCH_PARENT`；`rightMargin = (最后一列 ? 0 : gap)`、`topMargin = 4dp`、`bottomMargin = 14dp`——与现状 `cellLp`（`:1843-1849`）等价；"最后一列"用 `(pos - preludeOffset) % span == span-1` 计算，prelude/footer 不参与。
- 边缘差说明：现状海报左缘 = 16(容器) + 5(卡边距) = 21dp；新方案 = 16dp，差 5dp。若截图评审不能接受，把 `sidePad` 设为 21、`gap` 保持 10 即可完全对齐。
- `rv.setHasFixedSize(true)`；`rv.setItemAnimator(null)`（避免翻页闪烁）；`rv.setItemViewCacheSize(6)`。

**Phase B（S3 评估，不在本 story）**：按 `LIBRARY_LAYOUT_SPEC` §8.2 统一（`Wc = W - railW`、`sidePad` 16/20/24、`minCard` 公式、2:3 封面）。Phase A 完成后用截图对比 390/640/960/1080 四档，若与片库网格观感不一致再排 Phase B。

### 3.6 数据与 DiffUtil 契约

```java
// MobileHomeGrid.java
final class HomeGridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    interface FooterActions { void onRetry(); void onLoadMore(); }
    private static final int TYPE_PRELUDE = 0, TYPE_CARD = 1, TYPE_FOOTER = 2;

    private final MainActivity a;
    private Cms.Site site;                 // 当前片源快照，点击用
    private final View prelude;            // item 0（外部构建后传入）
    private final View footer;             // item N+1（loadMoreBtn）
    private final List<Cms.Video> items = new ArrayList<>();
    private int span, cardW, posterH, gap;

    void submit(List<Cms.Video> next) {    // next 必须是新 List 快照
        DiffUtil.DiffResult d = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            public int getOldListSize() { return items.size(); }
            public int getNewListSize() { return next.size(); }
            public boolean areItemsTheSame(int o, int n) { return id(items.get(o)).equals(id(next.get(n))); }
            public boolean areContentsTheSame(int o, int n) { return sameCard(items.get(o), next.get(n)); }
        });
        items.clear(); items.addAll(next);
        d.dispatchUpdatesTo(this);         // 只刷新变化项，不重绑已有卡片
        // footer 状态由外部 setFooterState() 控制，不参与 diff
    }
    private static String id(Cms.Video v) { return v.id == null ? "" : v.id; }
    private static boolean sameCard(Cms.Video x, Cms.Video y) {
        return eq(x.title, y.title) && eq(x.poster, y.poster) && eq(x.remarks, y.remarks)
            && eq(x.year, y.year) && eq(x.area, y.area);
    }
}
```
- **列表来源**：`fetchCategoryPage` 成功回调里 `currentVideos` 更新后调用 `grid.submit(new ArrayList<>(currentVideos))`；**不要**在 adapter 里做网络。
- **分类/筛选切换**：先 `currentVideos.clear()`（现有 `:1368`），再 `submit(empty)`，取到第 1 页后 `submit(list)`；旧结果由 `homeRequest` token 丢弃。
- **页脚状态**：`grid.setFooter(LOADING / RETRY / HIDDEN / LOAD_MORE, currentPage, totalPages)`，与现有按钮文案一致。
- 注意 `v.action` 过滤逻辑（`:1369-1373`）保留在数据层，不进 adapter。
- `filterSummaryBar` 仍由 `contentContainer` 持有并浮在 RecyclerView 之上：先 `addView(rv)`，再 `addView(filterSummaryBar.view())` 或最后 `bringToFront()`（沿用 `:827` 的现有做法）。

### 3.7 图片加载与 holder 复用（硬规则，见 §5.2）

```java
static final class CardHolder extends RecyclerView.ViewHolder {
    final FrameLayout art;      // 封面容器（现有 art）
    final ImageView img;        // CENTER_CROP
    final TextView placeholder, title, sub, badge;
    String boundPoster = "";    // 本 holder 当前绑定的海报地址
}

void onBindViewHolder(ViewHolder h, int pos) {
    CardHolder c = (CardHolder) h;
    Cms.Video v = items.get(pos - 1);                 // prelude 偏移
    c.title.setText(v.title);
    c.sub.setText(formatCardSubtitle(v, site));
    c.placeholder.setText(firstChar(v.title));
    String poster = v.poster == null ? "" : v.poster.trim();
    if (poster.isEmpty()) {                           // DEF-2006 的修复口径
        c.boundPoster = "";
        c.img.setTag(null);
        c.img.setImageDrawable(null);
    } else if (!poster.equals(c.boundPoster)) {
        c.boundPoster = poster;
        c.img.setImageDrawable(null);                 // 先清旧图，避免串图
        a.poster(poster, c.img);                      // ImageLoader 内部 setTag(address)
    }
    c.itemView.setOnClickListener(x -> a.openListing(site, v));
}

void onViewRecycled(ViewHolder h) {                   // 每次回收必清
    CardHolder c = (CardHolder) h;
    c.img.setImageDrawable(null);
    c.img.setTag(null);
    c.boundPoster = "";
}
```
- `a.poster()` 会 `image.setTag(address)`（`ImageLoader.poster:52`），完成后按 `address.equals(target.getTag())` 判定是否落图（`ImageLoader:165-177`）——**保留 tag 就是串图防线，不能拿 tag 查子 View**。
- 不要复用 `renderVideoGrid` 里的 `card.setTag("primary")` 做任何查询；首卡 `primary` 只为 TV 遗留兼容。

### 3.8 点击与现有逻辑的兼容

- 点击 → `a.openListing(site, video)`：`MainActivity.openListing`（`:309-324`）已处理 `action`/folder/`msearch:`/detail 四类跳转，移动端行为不变。
- 长按：现状首页卡片无长按，**不新增**。
- 焦点：移动端 `restore()` 提前返回（`MainActivity:1290`），不要为 RecyclerView 加焦点逻辑；TV 不进来。
- 触摸反馈：沿用 `MobileDetailScreen.bindTouchFeedback(card)`（`:51-65`）——注意它设置了 `setOnTouchListener`，不影响 `setOnClickListener`。

### 3.9 滚动恢复（三条路径）

| 路径 | 现状 | 改造后 |
|---|---|---|
| 切 tab 后回来 | `switchTab` 记录 `homeScrollY`（`:742-743`）→ `renderHomeTab` 用 `pendingHomeScroll`/`homeScrollY` 恢复（`:818-821`） | `switchTab` 离开首页前 `grid.saveState()`（`lm.onSaveInstanceState()` → 静态 `Parcelable homeListState`）；`renderHomeTab` 建好 adapter 后 `grid.restoreState(homeListState)` 一次 |
| 进详情再返回 | `resume()` 复用 `retainedRoot`（`:117-139`）→ RecyclerView 实例存活，位置天然保持 | 不变；`resume()` 里不要重建 grid；仅 `resumeHome.run()` 恢复分页按钮状态 |
| 进程被杀重建 | `MainActivity.onSaveInstanceState:901-911` 保存 `whale_tab/whale_home_scroll/whale_search` | 增加 `out.putParcelable("whale_home_list", MobileShell.savedListState())`；`onCreate` 读入 `MobileShell.pendingListState`；`renderHomeTab` 在**首批数据提交后**恢复一次（标志位，避免空列表恢复无效） |
| 窗口尺寸/旋转 | `retainedWidth/Height` 不匹配 → `render()` 重建 | 重建后用已保存的 `homeListState` 恢复；若数据未回来，恢复延迟到首次 `submit` 后 |

**回退兼容**：保留 `pendingHomeScroll` 的像素兜底（旧 Bundle 也能用）；`MainActivity.capture()/restore()` 的 `a.scroll` 分支在移动端变成 null 安全（现有代码已判空，`:1278,1289`）。

### 3.10 分页 / 自动加载 / 请求取消

- 自动加载：`rv.addOnScrollListener(...)`，用 `GridLayoutManager.findLastVisibleItemPosition() >= adapter.getItemCount() - 1 - span` 触发；**替换** `ViewTreeObserver.addOnScrollChangedListener`（`:1464-1480`），避免重复注册。
- 触底阈值等价现状：`diff <= a.dp(240)` → 用"最后可见项位置 + span*2"近似，测试时以"滚到页脚前自动触发一次"为准。
- 取消：仍在 `switchTab`/`cancelPageRequests` 里取消；RecyclerView 滚动**不取消** artwork 请求（滚动中取消会造成白块），只在离开首页/tab 切换时 `cancelArtwork()`（现状 `:745`）。
- 旧请求覆盖：不动 `homeRequest` 机制（E3-S2 #3 已由它保证）。

### 3.11 变更文件清单 / 写窗口

| 文件 | 动作 | 行数变化（估） |
|---|---|---|
| `MobileShell.java` | `renderHomeTab` 改造：body 去掉 grid/status/loadMore → 作为 prelude；`renderVideoGrid` 删除；`switchTab` 加 `grid.saveState()` | **-250 ~ -350** |
| `MobileHomeGrid.java`（新） | RecyclerView + Adapter + Holder + DiffUtil + span/尺寸 + 滚动状态 | +420 ~ +520 |
| `MainActivity.java` | `onSaveInstanceState`/`onCreate` 加 `whale_home_list`；无其他改动 | +6 |
| 文档 | 更新 `ANDROID_AUDIT_2026.md` P1-1 状态、`PRODUCT_BACKLOG` E3 状态 | 文档 |

**写窗口**：本 story 独占 `MobileShell.java`（SPRINT_BOARD R2）；同期其他改动不得碰该文件与该类；`MainActivity` 只允许 Lead 在本 story 合并前改 (如需) 其它区域，避免冲突。

### 3.12 验收清单（对齐 E3-S1/S2 + 新增度量）

**功能（必过）**
1. 首页首屏 24 条正常；滚动到底触发自动加载；「加载更多」点击/重试/隐藏三态正确。
2. 分类/筛选切换：内容替换正确，`currentPage` 重置为 1，旧请求不覆盖（500 ms 连切 5 次）。
3. 点卡片进详情 → 返回：分类、已加载页数、滚动位置误差 ≤1 行。
4. 切 tab 离开再回：位置恢复；进程重建（开发者选项「不保留活动」）后也能回到原 tab 并恢复位置。
5. 无海报/海报加载失败：占位显示正确，不串图；快速上下滚 10 s 后观察：无错图、无白块残留。
6. 历史卡片/今日首推/今晚看什么/筛选浮层/底部 Dock 视觉与现状一致（Phase A 允许 ±5dp，截图对比验收）。
7. 空分类/加载失败：状态文案正确，错误后「重试」可用。
8. 平板竖/横、手机竖/横四种形态：列数 3/4/5 与现状一致，无遮挡、无溢出。
9. 切换片源（`source-switcher`）后首页数据与网格同步刷新，无残留旧卡。
10. 连续快速滚动 10 s 无 ANR、无崩溃；停手后海报 1 s 内补齐（E3-S1 #2）。

**绑定计数证据（E3-S1 #1，评审必看）**
- 在 `onBindViewHolder` 加临时 `static AtomicInteger bindCount`（仅 debug 分支或测完删除），验证：120 条数据首帧 `bindCount ≤ 可见数 + 4*span`，而非 120；把日志/截图贴到 MR。

**性能与内存（有条件则测，无条件写"未采集"）**
- 同设备改造前后：进入首页首帧、滚到底耗时、`dumpsys meminfo` Java heap；每项 ≥5 次采样，只报原始数据（E3-S3 口径）。
- 快滚期间无"白块"（录屏 30 s）。

**回归**
- TV 首页/详情/播放不回归（本次不碰 TV 代码，但 `MobileShell` 是共享类，需 TV 侧冒烟）。
- `android/tests/run_checks.py` 等 JVM 套件全绿。
- 点击/返回/退出路径与现状一致（`openListing`/`redirectListing` 未改）。

### 3.13 风险与回滚

| 风险 | 概率 | 处置 |
|---|---|---|
| 串图/白块（holder 复用） | 中 | §5.2 硬规则 + 快滚录屏 + `onViewRecycled` 清理 |
| 滚动恢复不准（切 tab/进程重建） | 中 | 三条路径分别验收；保留像素兜底 |
| 分页重复/漏页 | 中 | 不改 `homeRequest/currentPage` 语义；`submit` 只做展示 |
| prelude 内嵌横向滚动与 RecyclerView 滚动冲突 | 低 | prelude 单 item，横向控件方向判定交给现有 HorizontalScrollView |
| 视觉偏差（间距/圆角） | 中 | Phase A 严格照搬现有参数；截图对比 |
| 回滚 | — | 单文件改动（`MobileShell` 还原 + 删除 `MobileHomeGrid`）；不回滚任何其他 story |

---

## 4. R8 / AGP 9 / Measured Baseline Profile 的可行性结论与下一步

### 4.1 R8：体积可行，功能未过门（1.2.2 不发布 R8 包）

**已验证**
- Gradle + R8 arm64 release 可出包：`-47%`（32.6 MB，`GRADLE_MIGRATION.md:60`）；第四轮自动生成 assets 后 `assembleRelease` 33.05 MB（`:68`）。
- keep 规则已从"最小"扩到覆盖插件宿主：`gradle/app/proguard-rules.pro` 保留 `com.whalefall.movie.**`、`com.pilipala.**`、`com.github.catvod.**`（CSP 父接口）、`okhttp3/okio/gson/jsoup`（插件直调宿主类）、`org.json`、`androidx.media3.**`、native 方法。
- `mapping/usage/seeds` 已产出（`gradle/app/build/outputs/mapping/mobileRelease|tvRelease`），具备排查基础。

**未验证 / 阻塞**
- 片源扩展引擎初始化失败：`扩展引擎初始化失败：IOException`（`GRADLE_MIGRATION.md:61`）。**该报错是 `CspEngine` 构造的包装异常**（`CspEngine.java:69-79`），真实 cause 类型/堆栈当前只进 `diagnostic()`，没进用户可见日志 → 先补可观测性再定位，不要猜。

**定位步骤（按顺序，S2-05 执行）**
1. **拿真实 cause**：临时把 `CspEngine.diagnostic("load_failed", ...)` 的 cause 堆栈写入 `AppLog` 与 `crash-last.txt`（或设置页"运行诊断日志"），在 R8 包上复现一次，拿到具体 `IOException` message（是"插件下载失败 HTTP"、"无法创建插件私有目录"、"插件不含 classes.dex"，还是"插件父接口不兼容"）。
2. **二分 shrink / optimize**：`minifyEnabled true` + 临时 `-dontshrink`（保留 optimize）与 `-dontoptimize`（保留 shrink）各出一包，判断是裁剪还是优化阶段。若与插件无关，则转向第 3 步。
3. **对照 `usage.txt`**：把 R8 包被删的类与 `CspEngine/CatPawHost/JsEngine/CatPaw` 的反射/动态加载入口对照；重点看 `com.github.catvod.crawler.*`、`okhttp3`/`gson`/`jsoup`、`org.json`、`com.pilipala.marcomovie.QuickJsDeadline`（JNI）、`assets/catpaw` 读取相关类。
4. **最小复现**：在 Gradle debug 包上手动开 R8（`minifyEnabled true`/debuggable），排除 assets/签名/ABI 变量；若 debug+R8 也失败 → 纯 shrink 问题；若只 release 失败 → 查 `useLegacyPackaging`/signing/资源差异。

**替代方案（若 R8 短期不能过门）**
- **先做 ABI 过滤**：mobile 只发 `arm64-v8a`（TV 保留 v7a），预计减 15–20 MB（推断，需实测）；这是纯打包收益、零功能风险，可立即进 1.2.x。
- **资源收缩**单独试（`shrinkResources` 依赖 R8，但可先做 `android.enableResourceOptimizations`/手动剔除未用资产）。
- **R8 延后**到 S3，以"功能回归全绿 + DecoderProbe/CatPaw 探针通过"为门槛；在此之前 Release notes 不得写"R8 已启用"。

**验收门槛（何时可发布 R8 包）**：① 引擎初始化成功（CSP/JS/CatPaw 各至少 1 条真实源）；② 点播/直播/解码冒烟通过；③ `dist` 包签名/zipalign 校验通过；④ 体积与未混淆包对比记录在 `build-validation.json`。

### 4.2 AGP 9：路径可用，工程化未完成（约 2 人日）

**已验证**：AGP 9.5.1 需 Gradle 9.6，移除 manifest `uses-sdk`、排除本地旧 `kotlin-stdlib/annotations` 重复类后可构建（`HANDOFF:60`；`gradle/build.gradle:2`、`gradle/app/build.gradle:37`、`gradle/app/src/main/AndroidManifest.xml` 无 uses-sdk）；mobile/tv flavor 与 debug/release 产物均已产出。

**收尾清单**

| # | 问题 | 现状证据 | 动作 | 人日 |
|---|---|---|---|---|
| 1 | 无 gradle wrapper | 仓库无 `gradlew`/`gradle/wrapper` | 生成 wrapper（pin 9.6.0 + sha256），文档改为 `./gradlew` | 0.3 |
| 2 | aapt2 覆盖是机器绝对路径 | `gradle/gradle.properties:2` | 改读 `MARCOMOVIE_AAPT2` 环境变量，缺省用 AGP 自带；qemu wrapper 文档化 | 0.3 |
| 3 | **tv flavor 的 edition 错误** | `build.gradle:88-108` 设置 `manifestPlaceholders=[whaleEdition:…]`，但 `gradle/app/src/main/AndroidManifest.xml:11` 写死 `android:value="mobile"`，未用 `${whaleEdition}` | manifest 改占位符；tv flavor 出包后用 `aapt dump badging`/反编译确认 `EDITION=tv`；否则 **tv 包会走 mobile 更新通道** | 0.5 |
| 4 | Gradle manifest 版本号与 `version.properties` 双源 | manifest `:2` 仍写 12000/1.2.0；`defaultConfig` 用 version.properties 覆盖 | 删除 manifest 中 versionCode/versionName，或改占位符；文档注明唯一源 | 0.2 |
| 5 | release `buildTypes` 重复定义 | `app/build.gradle:58-74` | 合并为一块；`lint { abortOnError false }` → 分阶段改 `true`（先只对新增文件） | 0.3 |
| 6 | `prepareRuntimeAssets` 用 `bash -lc` | `app/build.gradle:79-84` | 改为直接调 `python3`（Windows 可用 `py`），失败信息可读；或包成跨平台 Gradle task | 0.3 |
| 7 | 双链无一致性门禁 | SPRINT_BOARD R6 | 两条链产物都写 `build-validation.json`（含 ABI、assets 摘要、版本），Lead 拍板发布链 | 0.3 |

**结论**：AGP 9 可以在 S2 完成收尾，但**不切换发布链**；切换条件：mobile/tv 两个 flavor 的 `EDITION` 正确、覆盖安装不丢数据、更新通道校验通过、签名与现有 dev 证书一致（`GRADLE_MIGRATION.md:58-60` 已验证过覆盖安装）。

### 4.3 Measured Baseline Profile：本机不可做，按 D3 接受手写 BP

**现状**
- 手写 `gradle/app/src/main/baseline-prof.txt`（10 条）由 AGP 打进 `assets/dexopt/baseline.prof`；Python 链也会带上 `android/assets-baseline`。
- 内容问题：`:5` 引用了 `MobileShell;->renderHome()V`，但当前代码只有 `renderHomeTab()`（`MobileShell.java:810`）——**无效行**，不会让构建失败，但会让人误以为覆盖了首页渲染。`renderVideoGrid` 行在 §3 改造后会失效，需同步改为新类。
- 实测不可做的硬条件：本机 aarch64 proot、无 adb/macrobenchmark 设备（`HANDOFF:26,71`）；`androidx.benchmark` 未在离线依赖清单；首帧/冷启动基线未采集（E3-S3 状态）。

**处置（对齐 SPRINT_BOARD D3）**
1. 1.2.2 交付手写 BP；**Release notes 不得宣称"Baseline Profile 已优化启动"**（E3-S3 #3 口径）。
2. S2 内做两件低成本事：① 删除/修正失效 profile 行；② 在 `docs/BASELINE_PROFILE.md` 标注"手写、未实测、覆盖范围仅列出的方法"。
3. S3 实测路径（等有 adb 的真机/模拟器）：接入 `androidx.benchmark:benchmark-macro-junit4` + `androidx.baselineprofile` 插件 → `BaselineProfileRule` 采集 → 用 `StartupProbe`（`android/tests/device/StartupProbe.java`）/`PerformanceProbe` 对照 → 只给原始数据与结论。
4. **替代证据**（无设备时）：现有 `PerformanceProbe`/`ExperienceProbe` 的帧耗时采集可复用，但只能报告"代码路径与耗时字段"，不能替代冷启动实测。

---

## 5. 五条硬约束（评审 checklist，违反即打回）

### 5.1 RecyclerView holder 规范

- **规则**
  1. `onCreateViewHolder` 只创建空壳与固定子 View；**禁止**在 `onBindViewHolder` 里 `new View()/addView()/removeAllViews()`。
  2. item 根宽必须 `MATCH_PARENT`；卡片宽只由 `GridLayoutManager` span 决定，禁止 `wrap_content` 撑宽。
  3. `onBindViewHolder` 必须**全量设置**所有可变状态（文案、可见性、颜色、图片），不得依赖上一次绑定的残留。
  4. 必须实现 `onViewRecycled` 清理图片/tag/监听中不随绑定更新的部分。
  5. **禁止 `notifyDataSetChanged()`**；新增/更新用 `DiffUtil` 或精确 `notifyItem*`。
  6. 禁止在 holder 里持有 `Activity`/`Context` 字段（渲染时用 `itemView.getContext()` 临时取即可）；item 点击必须用**位置无关**的数据快照（用 `items.get(pos)`，不要缓存在 Adapter 外层）。
- **反例（现存）**：`MobileShell.MyListAdapter.onBindViewHolder` 无海报时未清图 → DEF-2006（条目复用残留上一条封面）；`notifyDataSetChanged()` 全量重绑（`:3060`）。
- **检查方式**：MR 里贴 `onCreateViewHolder/onBind/onViewRecycled` 三段代码 + 快滚录屏；评审逐条对照。

### 5.2 图片加载 tag 规范

- **规则**
  1. 只通过 `MainActivity.poster(url, image)` 加载海报；它内部会 `image.setTag(address)`（`ImageLoader:52`），**不得**再往 ImageView 上写其它 tag。
  2. 无海报/加载失败时：`image.setTag(null); image.setImageDrawable(null)`（否则残留旧图/旧 tag → 串图）。
  3. `onViewRecycled` 必清 `setTag(null)+setImageDrawable(null)`，并把 holder 的 `boundPoster` 置空。
  4. **任何代码不得用 `ImageView.getTag()` 反查业务对象或子 View**（DEF-1001 类崩溃的根因）。
  5. 不在 `onBind` 里为同一地址重复发起请求（holder 自缓存 `boundPoster`）；页面切换仍由 `cancelArtwork()` 统一取消。
- **依据**：`ImageLoader:165-177` 的落图判定依赖 tag 相等；`HANDOFF:52` 已把这条写成团队共识。
- **检查方式**：grep `setTag(` 在 `*Adapter*`/holder 内出现即人工复核；快滚录屏无串图。

### 5.3 播放页合成层规范

- **规则**
  1. 播放页的 root 与 `playbackLayer` 只能由 `PlaybackController.playerPage` 创建；其它模块**不得**往 root 里加全屏层。
  2. 新增 overlay 必须有明确 owner 与移除点；`releasePlayer()` 必须调用统一清理入口（§2.1 契约），把 `playbackLayer/playerOverlay/playerChrome/miniDetails` 全部置空。
  3. 播放期间禁止 `a.setContentView(root)`/`MobileShell.render()` 重建 root；需要重建时必须先 `clearPlaybackLayers()` + `releasePlayer()`。
  4. `TvLive.Player.close()` 必须在 `onStop`、`onDestroy`、`render()`、切 tab 四条路径被调用；禁止只依赖"root 被替换"来释放 WebView。
  5. 播放层不得持有 Activity 之外的 Context；Surface/WebView 必须在离开页面后 `release()/destroy()`。
- **反例（现存）**：§2.1 的 A/B/C 三个缺陷。
- **检查方式**：`grep "addView(.*root"` 与 `grep "playbackLayer"` 的调用点逐个人工过；MR 附"进出播放页 10 次 + 后台 30 s"的 meminfo 与录屏。

### 5.4 方向 / formFactor 规范

- **规则**
  1. 设备形态唯一来源：`a.formFactor`（`TvLayoutPolicy.FORM_TV/FORM_TABLET/FORM_PHONE`）；方向页面禁止再读 `smallestScreenWidthDp`/`configuration.orientation` 自行判定形态（`TV_LIVE_RESPONSIVE.md §0/§1.1` 已明确）。
  2. 布局断点**只看当前窗口宽度**（`W`/`Wc`），形态与方向分离；旋转后只重算断点，不改方向。
  3. `setRequestedOrientation` 只允许在"进入/退出特定页面"时调用一次；渲染函数（`render/refresh/showChrome`）里禁止调用，避免旋转抖动。
  4. 新增页面先读 `docs/TV_LIVE_RESPONSIVE.md §1.2` 的方向表，再写布局。
- **反例（现存/已修）**：`TvLive` 历史上用 `smallestScreenWidthDp>=600` 判定导致平板锁竖屏（DEF-1004 已修，规范留档）。
- **检查方式**：grep `smallestScreenWidth`、`setRequestedOrientation` 的调用点；四形态截图（手机竖/横、平板竖/横）。

### 5.5 构建脚本可移植性规范

- **规则**
  1. 仓库内路径必须相对仓库根解析（`Path(__file__).resolve().parents[1]`）；**禁止** `/tmp/...`、`/root/workspace/...` 等机器绝对路径出现在提交的脚本默认值里。
  2. 所有外部工具路径必须来自环境变量，且缺省值指向仓库内或给出"缺失 + 修复命令"；新增外部依赖必须进 `*dependencies.json`（SHA-256）或 Gradle wrapper（distribution sha256）。
  3. `gradle.properties` 不得含机器专属路径；Gradle 必须用 wrapper，不依赖系统 Gradle。
  4. 构建脚本必须能在"无网 + 无线设备"环境给出明确错误，而不是静默产出错误包（例如缺 assets 时直接失败）。
  5. 两条构建链（Python/Gradle）产物必须写同一份 `build-validation.json` 字段，Lead 据此决定发布链。
- **反例（现存）**：`scripts/build_local_phone.sh:9` 的 `/tmp/aosp/...`；`gradle/gradle.properties:2`；无 wrapper；`GRADLE_MIGRATION.md:37` 承认首次需联网。
- **检查方式**：`grep -rn "/tmp/\|/root/workspace" scripts gradle --include=*.py --include=*.sh --include=*.gradle --include=*.properties`（白名单：文档/示例）；`scripts/doctor.py` 全绿。

---

## 6. 评审门禁与排期

### 6.1 MR 评审清单（每次提交照抄）

1. 是否触碰 §5 任一条硬约束？触碰必须给出理由并在 MR 里标注。
2. 是否引入 `static View/Activity/Context`？是否新增了未登记的 overlay/root 子 View？
3. 是否给 `MobileShell/MainActivity` **增加**行数（S2 起不允许，除本 story 的迁移）？
4. 是否新增 `notifyDataSetChanged()`/`getTag()` 反查/`/tmp` 路径？
5. 是否有验收证据（截图/录屏/绑定计数/meminfo），或明确写"未采集，原因"？

### 6.2 S2 建议顺序（与 SPRINT_BOARD §5 对齐并补依赖）

| 顺序 | 任务 | 负责人 | 人日 | 依赖/说明 |
|---|---|---|---|---|
| 1 | 层叠状态 P0 修复（§2.1 A/B/C） | mobile-dev | 0.5–1 | 先做，避免首页改造与播放层问题混淆 |
| 2 | 首页网格虚拟化（§3） | mobile-dev | 2–3 | 独占 `MobileShell`；入口是 §3.0 十条 |
| 3 | 图片磁盘缓存 LRU + 退出清理 | mobile-dev | 1 | 审计 P1-8；与 §3 同一 owner，顺路 |
| 4 | Gradle 收尾 + R8 定位（§4.1/4.2） | android-auditor | 2 | 与 1–3 不同文件域，可并行 |
| 5 | 其余列表（全部剧集弹窗 / TV 搜索） | mobile-dev / tv | 2 | 等 §3 落地复用 holder 规范 |
| 6 | 手写 BP 修正 + 文档口径 | android-auditor | 0.5 | §4.3；不改构建 |

### 6.3 需 Lead 拍板（3 项）

1. **D-N1**：首页网格 Phase A 是否接受"视觉参数照搬现状"（不顺手做 Phase B 的 2:3/统一间距）？建议接受，Phase B 挂 S3。
2. **D-N2**：R8 若 S2 内定位不出来，是否接受"ABI 过滤先行、R8 延后 S3"？建议接受（零功能风险）。
3. **D-N3**：`MobileShell` 拆分文件是否与本 story 同批做（拆 1/5 步）还是单独 story？建议同批只做 `MobileHomeGrid.java` 抽出，其余拆分挂 S3。

---

## 附录 A：证据索引（本文引用）

- 架构/首页：`MobileShell.java:117-139,390-403,419-466,728-790,810-1484,1761-1862,2935-3035,3234-3271,3309-3318`；`MainActivity.java:133,137,140,188,309-324,562-624,901-911,1266-1325,1659-1665,1750-1764,1945-2006`；`PlaybackController.java:44-48,576-591,796-838,840-925,1538-1558`；`PlayerChrome.java:336-361`；`TvLive.java:737-800,1663-1676,2205,2219`
- 构建/体积：`scripts/build_android.py`（`--assets-only`、ABI 过滤、baseline assets）；`android/build/build-validation.json`；`docs/GRADLE_MIGRATION.md:56-69`；`gradle/app/build.gradle:1-111`；`gradle/build.gradle:2`；`gradle/gradle.properties:2`；`scripts/build_local_phone.sh:7-11`；`docs/HANDOFF_2026-11-09.md:24-44`
- Baseline Profile：`gradle/app/src/main/baseline-prof.txt`；`docs/BASELINE_PROFILE.md`；`android/tests/device/StartupProbe.java`、`PerformanceProbe.java`
- 规范/验收：`docs/TV_LIVE_RESPONSIVE.md §0-2`；`docs/LIBRARY_LAYOUT_SPEC.md §1-2,§8`；`docs/PRODUCT_BACKLOG.md` E3；`docs/SPRINT_BOARD.md` S2-01/S2-05/D3；`docs/DEFECTS.md` DEF-1001/1002/2002/2004/2005/2006/1012/1013
- 安全现状：`AdminConsole.java:19-21,80,186-205,258-273`；`MiniFtpServer.java:86-121`；`ImageLoader.java:28-40,51-75,128-187,196-246`

## 附录 B：本文未覆盖 / 下一轮补

1. 真机帧率与冷启动数据（E3-S3；无设备时保持"未采集"）。
2. `MobileDetailScreen`（1906 行）与 `TvLive`（2228 行）未逐行评审，只看了与本任务相关的段落。
3. R8 真实 cause 未取到（需运行 R8 包复现，§4.1 步骤 1）。
4. AGP 9 下 16KB/`zipalign -P 16` 与 `extractNativeLibs` 策略未验证（GRADLE_MIGRATION 未覆盖；建议随 S2-05 一起评估）。
5. `Media3 1.5.1 → 1.8.x` 升级评估按 S3 排期，本文只标注版本落后，不做兼容结论。
