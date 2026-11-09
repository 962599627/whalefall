# 1.4.0 TV 补齐 4 项功能 · 架构评审（C 类证据）

| 项 | 内容 |
|---|---|
| 评审对象（工作区未提交） | `ExtraPages.java`（新，682 行）、`MobileShell.java`（4528）、`SettingsScreen.java`（887）、`HomeDock.java`（322）、`FilesScreen.java`（472）、`TvLive.java`（2554）、`AdminPanel.java`（191） |
| 关联需求 | `docs/PRODUCT_BACKLOG.md` E6-S1~S4；`docs/SPRINT_BOARD.md` S2-09~S2-13；`docs/DESIGN_REVIEW_1.4.0.md`（Lead 拍板口径）；`docs/VERIFY_1.4.0.md`（V14–V16） |
| 评审角色 | architect（技术负责人） |
| 方式/证据 | 只读代码（read/grep/glob）+ 设计文档对照；**未构建、未改代码、未 commit、无真机** |
| 行号口径 | 均为**复核时最终工作树**快照（2026-11-09 23:0x；复核期间工作树仍在变动，已按最终快照重钉全部行号）。 |
| 重要限制 | 子会话无 bash/git，**无法执行 `git diff`**：`ExtraPages` 与旧 `MobileShell` 5 页的"逐行等价"只能做**函数级不变量核对**（见 §1.5）；建议 Lead 落盘前抽看 diff 或把 diff 回传，我可补逐行确认。 |

---

## 0. 总判定

**必须修（4 项）→ 修后重审。构建面本身可通过（可出包），但不建议按当前工作树构建 1.4.0 交付包（TV 功能缺口 + 手机回归各 1 处）。**

- 构建面（重点 5）：通过。新文件自动进入 Python/Gradle 源集合；无 Java 8 违规语法；无新增依赖/未改构建脚本（§5）。
- 必须修：**M1 TV 崩溃日志页按钮不可达**、**M2 TV 频道管理未适配**、**M3 播放返回焦点未回原频道（E6-S1 #3）**、**M4 手机遥控器说明页重复 7 行（手机零变化被破坏）**（§6）。
- 风险 8 项（§7）：HomeDock rail 306dp 几何不足、SettingsScreen 新分组手机可见、TV 剪贴板/文件选择器未降级、文件管理返回箭头回环、文件页 TV 未隐藏小按钮、管理员后台内容可能被裁切无法滚动、旧 confirm 默认焦点非取消、TvLive 文案/尺寸偏差。

---

## 1. 评审重点 1：手机零变化

### 1.1 抽取方式与调用点（结构核对）

| 页面 | 手机入口（MobileShell） | 手机渲染 | TV 入口（SettingsScreen） |
|---|---|---|---|
| 关于鲸落 | 主菜单第 6 项 → `settingsSubPage=6`（`MobileShell:4034-4052`），分发 `MobileShell:3962-3963` | `ExtraPages.about(a,body)` | `settings-about`（`SettingsScreen:106-110`）→ `extra(EXTRA_ABOUT)`（`SettingsScreen:122-125`） |
| 观影统计 | 主菜单第 7 项 → `=7`，分发 `MobileShell:3964-3965` | `ExtraPages.stats` | `settings-stats`（`97-100`）→ `EXTRA_STATS`（`126-128`） |
| 遥控器说明 | 子页 2 内卡片 → `=8`（`MobileShell:3798-3806`），分发 `3966-3967` | `ExtraPages.keymap` | `settings-keymap`（`101-105`）→ `EXTRA_KEYMAP`（`130-132`） |
| 存储清理 | 子页 4 内卡片 → `=10`（`MobileShell:4001-4009`），分发 `3970-3971` | `ExtraPages.storage` | `settings-storage`（`91-95`）→ `EXTRA_STORAGE`（`134-136`） |
| 崩溃日志 | **手机仍走旧弹窗** `MainActivity.showCrashLog()`（`MainActivity:340`，卡片 `MobileShell:3995-4000`） | 未迁移（✔ 手机零变化） | `settings-crash-log`（`86-90`）→ `EXTRA_CRASH`（`138-140`） |

结论：5 页入口编号/标题映射与入口卡片文案仍在 `MobileShell`；抽取后 `MobileShell` 只保留调用（`3963/3965/3967/3971`）与旧崩溃弹窗入口，**结构上符合 S2-09 口径**。

### 1.2 helper 等价核对（手机分支逐项参数）

- `ExtraPages.sectionHeader`（`:485-490`）与 `MobileShell.addSectionHeader`（`:4253-4258`）：12.5sp / `0xff5ac8fa` / bold / padding(4,6,4,6) **完全一致**。
- `ExtraPages.settingCard`（`:492-541`）与 `MobileShell.addSettingCard`（`:4260-4296`）：手机分支 `minHeight=0`（默认同）、icons 36dp、title 14.5sp、subtitle 12sp、`glassBg(0xcc1e3557,0xcc17294a,RADIUS_20,0.7,0x33ffffff)`、`›` 20sp MUTED、bottomMargin 10dp —— **逐项一致**；`tvFocus` 在手机是 no-op（`ExtraPages:287` 先判 `!isTvDevice()` 直接 return），不改变手机焦点/监听。
- 手机专用尺寸：about 76/26/11.5/12.5；keymap 12.5/12.5/列宽 120；storage 12.5/11.5/12；metric 18/11 —— 均为手机值，TV 才走放大分支。

### 1.3 必须修 M4：手机「遥控器按键说明」页重复 7 行

`ExtraPages.keymap()`（`ExtraPages.java:182-189`）：

```java
int tvRows = a.isTvDevice() ? 8 : rows.length;      // 手机 = 15
for (int i = 0; i < tvRows; i++) keymapRow(...);    // 手机：0..14 全渲染（含键盘/手势行）
if (!a.isTvDevice()) {                              // 手机：再来一遍
    sectionHeader("键盘 / 手机手势");
    for (int i = 8; i < rows.length; i++) keymapRow(...);   // 8..14 重复
}
```

- 手机效果：第一段「电视遥控器」下已出现全部 15 行，第二段「键盘 / 手机手势」再把第 8–14 行（空格/回车、Esc、双击、滑动、长按 2 倍速等 7 行）**重复显示一遍**。
- 影响：手机页视觉/信息重复（E6「不改手机版布局/行为」、S2-09「手机版零变化」不满足）；TV 路径正确（`tvRows=8` 且不进第二段）。
- 修法：第一段固定 8 行（`int tvRows = 8;`），手机第二段渲染 8..14；或者按 `isTvDevice()` 分两套列表。改后手机恢复「电视遥控器 8 行 + 键盘/手机手势 7 行」。

### 1.4 其他手机面核对

- 手机设置页二级标题映射未变（`MobileShell:3622-3641`：6 关于/7 统计/8 遥控器/10 存储；9 标题仍在但无入口，见 §7-R9）。
- 手机崩溃日志仍为弹窗（`MainActivity:340` 起），没有切到 TV 整页实现 ✔。
- **风险 R2（手机非零变化）**：`SettingsScreen.show()`（`SettingsScreen:67-111`）新增「本机与工具」分组**没有 isTvDevice 门控**；而手机可从 `MobileShell:4010-4018`「完整高级设置与备份恢复」进入同一个 `SettingsScreen`。即手机用户也会看到这 7 个新入口 → 与 E6「不改手机版布局/行为」边界冲突。二选一：加 TV 门控，或确认"手机也补入口"并同步文档/验收。

### 1.5 等价性限制（必须写清的未验证项）

无法执行 `git diff`，故**不能宣称"逐行等价"**；已核对的部分：函数入口/helper 参数/入口编号/尺寸分支（§1.2–1.4）。仍需 Lead 或 diff 补齐确认：旧 `MobileShell` 5 页的**字符串/尺寸/顺序**是否被改写（尤其 about 卡片集合、storage 行文案、stats 指标口径）。建议命令：`git diff -w -- android/src/main/java/com/whalefall/movie/MobileShell.java`，抽看删除段是否为纯搬移。

---

## 2. 评审重点 2：TV 焦点 / BACK 栈

### 2.1 HomeDock 7 项 —— 通过项与 R1

通过：
- 顺序 `首页→搜索→直播→电视→收藏→网盘→设置`（`HomeDock:13-16`）；新键 `nav-tv`（`:15`）；图标映射 `3=TV/4=FAVORITE/5=CLOUD/6=TUNE`（`HomeDock:304-321`）。
- 上/下 nextFocus 链 0..6（`HomeDock:137-146`）；右=原地；左回内容区（`HomeDock:209-223`）；内容右缘进首页（`rightEdge` `:205-207`）。
- 动作绑定：3=电视 `TvLive.show(a)`（`HomeDock:78`），其余顺序正确。
- 焦点标签 `showLabel(index)` 索引 0..6 安全（`HomeDock:164-203`）。

**风险 R1（几何计算不足）**：`mainDp = 306*navScale`（`HomeDock:28-30`），但每个 icon 同时设置上下各 `cellGap≈4dp`（`HomeDock:95-104`）。实际所需高度 = padding(8+8) + 7×38 + 7×(4+4) = **338dp > 306dp**。
- 100%：内容超出 32dp，垂直居中后首/末图标各**溢出玻璃框约 4dp**（`rail.setClipChildren(false)` 可见）。
- 140%：cellDp 53、gap 6、padding 11 → 需 ≈477dp，rail 428dp → 溢出 ≈49dp，**首/末图标出框约 7–8dp**（设计文档按"6 个间隙"算的 306；与代码"每项上下 margin"模型不一致；溢出量随 navScale 放大）。
- 修法：`mainDp` 306→338（140% ≈473dp < 504 安全高），或把 `cellGap` 改为只加单侧。
- 证据：`HomeDock:26-30,95-104,137-146`；`DESIGN_REVIEW_1.4.0.md §1.2`。

### 2.2 SettingsScreen「本机与工具」分组

- 顺序/标题：文件管理→管理员后台→崩溃日志→存储→统计→遥控器→关于（`SettingsScreen:75-111`）；分组标题 13sp 不参与焦点（`:148-153`）。
- 行可聚焦、`link()` 有 tag（`settings-files`…`settings-about`，`:26-61`），OK 触发 `open()`（`:63-65`）。
- 首焦点：`renderPage` 后 `MainActivity.restore()`（`:1339-1369`）先找 `s.focus`/`primary`/home tag，都无则 `body.addFocusables` 取第一个 → 第一行「首页与壁纸」符合设计"首焦点=首页与壁纸"。
- BACK 焦点回原行：进子页前 `capture()`（`MainActivity:1320-1334`）存当前 tag，返回 `restore()` 按 tag 命中 ✔。
- 结论：通过（R2 见 §1.4：该分组手机也能看到）。

### 2.3 TvLive（TV 分支）

通过：
- TV 入口 `TvLive.show()`（`TvLive:279-282`）由 HomeDock「电视」打开；BACK 由 Activity 回栈回首页（`MainActivity:1385-1454`）并恢复 `nav-tv` 焦点。
- TV 隐藏搜索框且 input 不可聚焦（`TvLive:469-473`）。
- 三栏：rail 176 / preview 300（`TvLive:309-318`）；首焦点=当前分组胶囊（`TvLive:629-652`，tag 在 `:691`）；频道行 64dp（`TvLive:703`）、行 tag `tv-focus-channel`（`TvLive:944`）；预览按钮 48/52dp（`TvLive:1168,1180`）。
- TV 下「添加/导入」降级（`TvLive:733-737,754-758`）；分组/数字键等原有能力未动。
- 手机侧 `wide` 增加"手机竖屏一律单列"守卫（`TvLive:302-307`），属 1.3.1 DEF-2012 修复延续，意图正确。

问题：
- **必须修 M3（E6-S1 #3 焦点回原频道）**：`open(..., () -> page.refresh())`（`TvLive:1177-1178`）关闭播放器后整页重绘；`focusFirstTvItem` 在 wide 时固定聚焦 `tv-focus-cat:<group>` 胶囊（`:641-644`），非 wide 时聚焦首个频道行 —— **不按播放频道恢复**，焦点落在左侧分类栏/首行，不满足"返回列表后焦点回到原频道"。修法：频道行 tag 带频道名（当前所有行同 tag `tv-focus-channel`，`:944`），播放返回时按 `page.selected` 找行聚焦。
- **必须修 M2（频道管理未适配）**：`managePill` 在 TV 直接 `ChannelManagerScreen.show(a)`（`TvLive:763-774`），而 `ChannelManagerScreen` **无任何 isTvDevice 分支**：19sp 标题（`:72`）、可见搜索 EditText（`:109-118`）、行/批量按钮不可聚焦（adapter `:350-380`、action `:338-348`）、手机间距（`:60`）→ TV 遥控无法勾选/删除/隐藏/编辑，且搜索框会抢首焦点。设计 §2.2 与 S2-10（"含频道管理/自定义/导入"）要求 TV 版式。二选一：本轮适配；或 TV 隐藏「管理」入口并在交付说明登记"未覆盖"。
- **风险 R8（文案/尺寸偏差，低）**：TV 副标题/预览说明仍写手机手势（`TvLive:535-537`、`1109-1112` "上下滑调亮度/音量"）；页面标题 27sp（设计 28sp，`:337`）；三栏间隙 12dp（设计 16dp，`:610-623`）；预览台标高 96dp（设计 132dp，`:1090`）。
- **风险 R8b（BACK 语义）**：`Player.back()` 先 `web.goBack()` 否则直接 `close()`（`TvLive:2349-2357`），没有"先收 chrome"；与设计 §2.2"先收 chrome→再退网页全屏→回列表"不一致（E6-S1 #2 只要求回列表不退出，故不阻断）。

### 2.4 FilesScreen

通过：
- TV 上传/新建/重命名走统一降级（`FilesScreen:87-95,321-325,295-299`），不唤起系统选择器/软键盘。
- 删除二次确认默认「取消」：`fileMenu` 删除 → `ExtraPages.confirmDanger`（`FilesScreen:271-293` + `ExtraPages:437-447`）。
- 空态首焦点=「管理员后台」（`FilesScreen:146-165`：`adminBtn.setTag("primary")` + `tvFirstFocus`）；非空首焦点=首行（`:168-182,258`）。
- 文件行可聚焦、OK 打开、长按菜单（`:177-182,232-252`）；预览页 BACK/OK 关闭遮罩（`:423-439`）。
- 根目录返回走 Activity 回栈回设置（`:69-73`）；子目录 BACK 因每级 `show()` 都压 `Screen` 而逐级返回（`:25-31`）。

风险：
- **R4 返回箭头与 BACK 语义不一致**：左上「‹」在子目录执行 `show(a, parentOf(path))`，又压入一个新 Screen（`FilesScreen:69-73`+`25-31`）；若用户先用箭头返回再按 BACK，会回到刚才的子目录（回环）。修法：箭头与 BACK 统一（替换当前 Screen 或用 `onBackPressed()`）。
- **R5 与设计口径不一致**：设计 §2.2/§4 要求 TV 隐藏「上传/新建/行尾 ⋯」；当前仍显示（上传/新建已降级，属功能安全），但行尾「⋯」未 `tvFocus`（`FilesScreen:223-228`）→ TV 不可聚焦，重命名/删除只能依赖长按 OK；若长按在具体盒子上不触发，则功能不可达。

### 2.5 管理员后台（AdminPanel）

通过：TV 隐藏「复制链接 / 在浏览器打开」（`AdminPanel:89-123`）；QR 200dp（`:57`）；令牌 13/地址 17（`:74-87`）；唯一按钮「关闭后台」且默认焦点（`:147,160-169`）；dismiss 停止 Console+FTP（`:149-157`）→"隐藏按钮后仍可关闭" ✔；启动失败不崩（toast，`:25-32`）。

**风险 R6**：内容放进普通 ScrollView（`:143-147,183-189`），而 `AppDialog` 会把内容裁剪到 `maxHeight = windowHeight-200dp`（`AppDialog:97,117-120`）；960×540 下标题+QR200+地址+令牌+FTP+说明 ≈400dp，底部 FTP/说明可能被裁切，且**没有可聚焦滚动区**（关闭按钮是唯一焦点）→ 遥控无法滚动查看。修法：把 ScrollView 设为可聚焦并接 DPAD 滚动，或压缩 QR/说明、或分成两屏。

### 2.6 二次确认默认焦点

- 新页面：`ExtraPages.confirmDanger` 展示后 post 聚焦 `dialog-action:-2`=取消 ✔（`ExtraPages:437-447,449-456`；`AppDialog` 按钮 tag 见 `:126-153`）。
- **风险 R7**：旧路径未统一 —— `SettingsScreen.confirm()`（`SettingsScreen:530-536`）与 ChannelManager 删除确认（`ChannelManagerScreen:224-253`）未显式给「取消」焦点；`AppDialog` 默认首焦点是 message（`AppDialog:64-70,171-174`），按 OK 无动作，与"破坏性操作默认取消"不一致。

---

## 3. 评审重点 3：崩溃 / 空态 / 降级

### 3.1 必须修 M1：TV 崩溃日志页按钮不可达

`ExtraPages.crashLog()`：日志卡 `logView` 可聚焦（`:347-376`），其 `OnKeyListener` 对 DPAD_UP/DOWN **无条件 `return true`** 并滚动页面（`:365-375`）；「另存到文件管理 / 清空记录」在日志卡下方（`:379-421`），页面内**没有其它焦点路径**。结果：TV 遥控无法把焦点移到两个按钮 → 清空/导出功能不可用（E6-S3 #1/#3、设计 §2.2 崩溃日志行"上/下滚动文本；滚到底→按钮"未满足）。
修法：滚动到顶部/底部后 `return false` 让焦点继续下移（或 DPAD_DOWN 到底后放行）；或把按钮并入同一滚动容器的焦点序列。

### 3.2 TV 无剪贴板

**风险 R3**：`ExtraPages.about` 仍显示并可点「复制项目源码地址」（`ExtraPages:75-89`，调用 `ClipboardManager`）。设计 §2.2 关于页明确"TV 无剪贴板→隐藏或降级"，拍板 4"复制/粘贴类统一降级"；当前不崩但口径不符（点后 toast "已复制"，实际无法取用）。修法：TV 隐藏该卡或改走 `ExtraPages.degrade`。

### 3.3 TV 无 IME / 选择器

- 新增 TV 页无 EditText ✔（about/storage/stats/keymap/crashLog 均无输入）。
- TvLive「添加/导入」、FilesScreen「上传/新建/重命名」均降级 ✔（§2.3/§2.4）。
- 配置导入 TV 走 `PhoneConfigDialog`（`MainActivity.importDialog:1574-1586`，TV 分支 QR）；`AddressInput` 自带遥控键盘（既有能力）✔。
- **风险 R7b**：`SettingsScreen.pick()`（`:597-613`）在 TV 仍 `startActivityForResult(ACTION_OPEN_DOCUMENT/CREATE_DOCUMENT)`，仅失败时提示"电视没有文件选择器"。首页与壁纸（选图/选视频）、搜索与数据（备份导出/还原）在 TV 会先拉系统选择器 → 与设计 §3.2"不弹系统选择器，直接统一降级"不一致（不崩）。

### 3.4 空态 / 错误态

- 文件空态：「还没有文件 / 这个文件夹是空的」+ 引导，首焦点管理员后台（`FilesScreen:145-165`）✔。
- 崩溃日志空态：无文件时显示"暂无崩溃记录"与说明、不建按钮（`ExtraPages:306-332,379`）✔。
- 统计空态：「本周还没有观看记录」（`ExtraPages:252-255`）✔，不显示未定义数值。
- 登录/服务失败：AdminPanel 启动失败 toast（`:25-32`，设计期望弹窗，低风险）；FTP 失败时隐藏 FTP 行不崩（`:36-40,125-131`）✔。
- 单弹窗约束：`AppDialog` 以 `a.appDialog` 串行（`AppDialog:160`），degrade 次按钮先 dismiss 再开 AdminPanel（`ExtraPages:432` + `AppDialog:132-137`）✔。

---

## 4. 评审重点 4：抽取类 API 风险

- **无 MobileShell 实例/私有状态依赖**：`ExtraPages` 仅静态方法 + `MainActivity` 公开/包内 API；对 `MobileShell` 的依赖只有两个 static 工具（`MobileShell.glassBg`、`MobileShell.SettingIconView`，见 `ExtraPages:508,518`），不使用任何字段/`currentVideos` 等运行态。
- **无 static 泄漏**：`ExtraPages` 无静态可变字段；每次页面重建即新建 View。
- **耦合可接受**：同包工具类引用；后续可把 `glassBg/SettingIconView` 迁到 UiKit（不阻断）。
- **PushFiles 写路径 TV 可写**：写 app 私有 `files` 目录，`PushFiles.writeText`（`PushFiles:84-94`）自动建父目录；crashLog 另存失败有 `a.notice`（`ExtraPages:397-399`）✔。路径穿越防护由 `PushFiles.file/resolve` 既有逻辑承担（未改）。
- **未越界**：手机崩溃日志弹窗 `MainActivity.showCrashLog:340` 保留；`MobileShell` 仅删 5 页、留调用；TV 设置页只在 `SettingsScreen` 加分组。

---

## 5. 评审重点 5：构建面

| 检查点 | 结论 | 证据 |
|---|---|---|
| 新文件进入构建 | **通过** | Python：`scripts/build_android.py:134` `rglob('*.java')`；Gradle：`gradle/app/build.gradle:26` `java.srcDirs=['../../android/src/main/java',...]` |
| Java 8 语法 | **通过（静态 grep）** | 无 `var`/`List.of`/`record`/switch 表达式/文本块；lambda、try-with-resources 合法（`ExtraPages` 全文 682 行核对） |
| 无新增依赖/构建脚本改动 | **通过（按文件清单）** | 仅 7 个源文件；无 import 新库（`com.google.zxing` 等为既有） |
| minSdk 23 | **通过** | `StandardCharsets`(API19+)、`Formatter.formatFileSize`、`PopupWindow/AppDialog` 既有 API |
| 格式检查 | **未验证（P2）** | 未提供 `python -X utf8 scripts/format_java.py --check` 结果；`ExtraPages:401` 等长行可能需 format |

**构建面结论：可编译/可出包；但能否"发布"取决于 §6 四项。**

---

## 6. 必须修清单（4 项，阻断交付）

| # | 问题 | 证据（文件:行号） | 修法 |
|---|---|---|---|
| **M1** | TV 崩溃日志页「另存到文件管理 / 清空记录」遥控不可达 | `ExtraPages.java:347-376`（KeyListener 无条件 consume）+ `:379-421`（按钮） | 滚到边界 `return false` 让焦点离开；或日志区与按钮同一焦点序列 |
| **M2** | TV 频道管理未适配（手机布局、搜索 EditText 可见、行/按钮不可聚焦），但 TV 从 TvLive「管理」可直接进入 | `TvLive.java:763-774` 打开；`ChannelManagerScreen.java:50-60,72,109-118,149-153,190-204,338-348,350-380` 无 TV 分支 | 二选一：①按设计 §2.2 适配（隐藏搜索/行可聚焦/按钮 48/首焦点首行/BACK 焦点）；②本版 TV 隐藏「管理」入口并登记"未覆盖" |
| **M3** | E6-S1 #3：播放返回后焦点未回原频道（落到分类胶囊/首行） | `TvLive.java:629-652`（fixed 目标）、`:1177-1178`（返回 refresh）、`:944`（所有行同 tag） | 行 tag 带频道名；返回时按 `page.selected` 聚焦；`focusFirstTvItem` 增加"优先 selected 行"分支 |
| **M4** | 手机「遥控器按键说明」重复 7 行（手机零变化被破坏） | `ExtraPages.java:182-189`（`tvRows = tv?8:15` 后又渲染 8..14） | 第一段固定 8 行；手机第二段渲染 8..14；TV 只渲染 8 行 |

---

## 7. 风险清单（按优先级；不阻断构建，但需 Lead 决定本轮修/登记）

| # | 风险 | 证据 | 建议 |
|---|---|---|---|
| R1 | **HomeDock rail 306dp 几何不足**：7×38+7×(4+4)+2×8=338dp；100% 溢出 32dp（首/末图标出框 ~4dp），140% 需 ≈477dp（rail 428）出框 ~18dp | `HomeDock:26-30,95-104,137-146`；DESIGN §1.2 | `mainDp` 306→338，或 gap 只加单侧；140% 下 473<504 安全 |
| R2 | **「本机与工具」分组未 TV 门控**：手机「完整高级设置」也会出现 7 个新入口，与 E6「手机零变化」冲突 | `SettingsScreen:67-111`；手机入口 `MobileShell:4010-4018` | 加 `if (a.isTvDevice())` 门控，或确认"手机也补"并同步文档 |
| R3 | TV 关于页「复制项目源码地址」仍可点（TV 无剪贴板） | `ExtraPages:75-89` | TV 隐藏或改走 degrade |
| R4 | 文件管理返回箭头会再压栈 → 与 BACK 一起用出现回环 | `FilesScreen:69-73,25-31` | 箭头统一走 `onBackPressed()`/替换当前屏 |
| R5 | 文件管理 TV 仍显示上传/新建/⋯；⋯ 不可聚焦，重命名删除依赖长按 OK | `FilesScreen:82-116,223-228,248-252` | 按设计隐藏或给 ⋯ 加 tvFocus + 短按菜单 |
| R6 | 管理员后台内容可能超出对话框可视区且无可聚焦滚动区 | `AdminPanel:143-147,183-189`；`AppDialog:97,117-120` | 滚动区可聚焦接 DPAD，或压缩布局 |
| R7 | 旧路径未按拍板降级/默认焦点：`SettingsScreen.pick()` TV 仍拉文件选择器；`SettingsScreen.confirm`/ChannelManager 删除确认默认焦点在 message 非「取消」 | `SettingsScreen:597-613,530-536`；`ChannelManagerScreen:224-253`；`AppDialog:64-70,171-174` | TV 加 pick 降级；confirm 显式聚焦取消按钮 |
| R8 | TvLive TV 文案/尺寸偏差：仍写手机手势（`:535-537,1109-1112`）、标题 27 vs 28（`:337`）、栏间隙 12 vs 16（`:610-623`）、预览台标 96 vs 132（`:1090`）；`Player.back()` 未先收 chrome（`:2349-2357`） | 同左；DESIGN §2.2 | 文案删除手势、尺寸对齐设计（低优先） |
| R9 | 死代码/注释：`MobileShell:4055` 残留「设置 → 关于鲸落」javadoc；子页 9（片源教程）`addSourceGuidePage` 仅 dispatch 无入口（`settingsSubPage=9` 无赋值） | `MobileShell:3968-3969,4091` | 清理注释；确认 page9 是否既有死路（不阻断） |
| R10 | **等价性未逐行验证**（无 git diff）：手机 5 页文案/尺寸是否原样搬移未证明 | §1.5 | Lead 落盘前抽 diff；或把 diff 发我补审 |
| R11 | 格式检查未验证 | `scripts/format_java.py --check` | Lead 收口前跑一次 |
| R12 | 口径冲突需文档同步：E6-S2 #1 写"重命名可操作"，DESIGN 拍板 2 要求 TV 重命名走降级；代码按 DESIGN 执行 | `PRODUCT_BACKLOG:339` vs `DESIGN_REVIEW_1.4.0 §5 拍板2` | 同步验收文本（注明 TV 重命名=降级提示） |

---

## 8. 对 V14–V16 的补充验证建议（无 TV 设备则统一"未覆盖"）

1. **V15 增补**：崩溃日志页——DPAD 下能否把焦点移到「另存到文件管理 / 清空记录」并按 OK（覆盖 M1）。文件管理——空目录首焦点、删除二次确认默认「取消」、箭头返回后再 BACK 是否回环（R4）。
2. **V14 增补**：进「管理」检查频道管理是否能遥控操作（覆盖 M2）；播放 CCTV-1 → BACK 回列表后焦点是否在原频道行（覆盖 M3）；Dock 7 项在 100% 与 **140% navScale** 下截图（覆盖 R1）。
3. **V15 增补**：管理员后台滚动到 FTP/说明（R6）；关于页确认「复制源码地址」在 TV 的处理（R3）。
4. **手机回归**：设置→播放、解码与字幕→遥控器按键说明，手机应只出现「电视遥控器 8 行 + 键盘/手机手势 7 行」、无重复（覆盖 M4）；「完整高级设置」是否出现新分组（R2，按拍板预期判断）。

---

## 9. 结论 Top3（给 Lead 的摘要）

1. **M4 手机回归（手机零变化被破坏）**：`ExtraPages.java:182-189` 手机「遥控器说明」把键盘/手势 7 行渲染两遍——这是本任务对手机版唯一明确的行为破坏，优先修。
2. **M1 TV 崩溃日志按钮不可达**：`ExtraPages.java:365-375` 上下键被日志卡全部吞掉，`另存/清空` 遥控无法聚焦——E6-S3 核心操作不可用。
3. **M2/M3 TV 直播链路缺口**：`ChannelManager` 未 TV 适配却能从 TV「管理」进入（`TvLive:763-774`）；播放返回焦点不回原频道（`TvLive:629-652` 固定挑分类胶囊）——E6-S1 #3 不满足。

**判定：必须修（M1–M4）后重审；构建面可通过，暂不建议按当前工作树出 1.4.0 交付包。**
