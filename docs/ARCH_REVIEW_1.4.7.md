# 1.4.5–1.4.7 架构评审（更新多包体 / 字体颜色 / 网盘登录入口统一 / 登录态三态）

| 项 | 内容 |
|---|---|
| 评审对象（本地已提交 `4165a7a`+`39e8071`，未推送） | `UpdateRelease/UpdatePolicy/UpdateManager/UpdateScreen`、`TextColorDialog/Prefs/MainActivity.userTextColor`、`MainActivity.sourceAction/SettingsScreen.accountAction/sourceAccountAction/PanLoginGuide/AccountPanel`、新 `PanLoginState` |
| 依据 | `docs/DESIGN_REVIEW_1.4.6.md`（字体颜色规范）、需求口径（多包体/登录入口/三态不误报） |
| 方式 | 只读代码（read/grep/glob），未构建/未改代码/未 commit；行号为当前工作树快照 |
| 结论 | **可构建（静态构建面通过）；必须修 1 项：1.4.7 本地凭据判据过宽会误报「已登录」（用户最在意的假状态）；其余 7 项风险见 §5。** |

---

## 1. 1.4.5 更新多包体 —— 通过（含 2 项风险）

- **通过**：
  - feed `variants` 解析（`UpdateRelease:64-99`）：≤4 项、kind 去重、label≤64、每项走 `validateVariant`（size/sha/URL/channel，`UpdatePolicy:92-115`）；旧 feed 无 variants 时数组为空、`variant(index)=null`，`targetSize/targetSha/targetDownloads` 回落基础字段（`UpdateRelease:102-131`）→ 单包行为不变 ✔。
  - edition 过滤：`variantKindAllowed(kind,mobile)` 手机只允许 mobile/arm64/r8、TV 只允许 tv（`UpdatePolicy:68-72`；`UpdateRelease:73-74` 跳过其它 kind）→ **TV 不会列出手机包** ✔；base artifact 也有 `platform`/`editionUrl` 双重校验（`UpdateRelease:36-41`、`UpdatePolicy:57-65`）。
  - 下载/校验/安装都按所选包：`selectedVariant()` 夹取（`UpdateManager:176-193`）；下载取 `targetSize/targetSha/targetDownloads(selected)`（`:344-348`）并按 variant sha 断点续传（`:351-354`）；校验 `verifyApk(...,selected)`（`:377-385`、`:452-465` size+sha+包名+签名）；落盘记录 `download_sha/download_kind`（`:394-398`）；`ready()` 用所选 size/sha 复核（`:197-205`）；安装前再次按 `selectedVariant()` 校验（`UpdateScreen:232-238`）→ 换包后旧 APK 不会被误装 ✔。
  - 自动模式第一项：check 成功即 `variantIndex=0`（`UpdateManager:276-280`），`download` 模式自动下载第一项 ✔。
- **风险 U1**：`variantUrl` 对 `arm64`/`r8` 只要求文件名含 `-arm64`/`-r8` 且版本前缀（`UpdatePolicy:82-90`），未强制 `android-mobile` edition 段；错误/恶意 feed 可让手机下载到 TV 命名的 arm64 包（sha 会校验为 feed 声明的包，但 edition 白名单被绕过）。建议 mobile 变体统一要求 `WhaleFall-<version>-android-mobile` 前缀。
- **风险 U2**：`UpdateScreen` 的多包体选择在「检查/下载进行中」会提示等待（`:134-138`），但 `busy` 期间界面仍显示可点行——可点但无反应；属体验小问题（非阻断）。

## 2. 1.4.5 字体颜色 —— 通过（含 2 项规范偏差风险）

- **通过**：
  - 集中 remap：`MainActivity.userTextColor` 只改 `WHITE`/`MUTED`/`TEXT_TERTIARY` 三种语义色（`:974-982`），`text()/paragraph()/button()` 统一走它（`:984-991,1011-1016,1018-1035`）；`WHITE=Skin.INK=0xfff5f7fc=UiTokens.TEXT_PRIMARY`、`MUTED=0xffa9b7d0=TEXT_SECONDARY`（`MainActivity:22`、`Skin:23-24`、`UiTokens:26-28`）→ 使用 token 的新页面（AccountPanel/PanLoginGuide/PlayerChrome 等）同样被覆盖 ✔。
  - 派生 70%/56%：MUTED `0xb3`（≈70%）、TERTIARY `0x8f`（≈56%）（`MainActivity:979-980`），符合规范 §3；强调色/成功/危险/焦点白描边不在 remap 列表内 → 未被误改 ✔。
  - `TEXT_COLOR` 进备份/重置：`Prefs.KEYS` 含 `TEXT_COLOR_CUSTOM/TEXT_COLOR`（`Prefs:125-126`）；备份 sanitize 只保留 RGB 并强制不透明（`Prefs:210-213`）；`TEXT_COLOR_CUSTOM` 在 BOOLEAN_KEYS（`:153`）；「恢复默认设置」按 KEYS 删除 → 回默认 ✔。
  - `recreate()` 生效：保存/恢复默认后 `a.recreate()`（`TextColorDialog:266,292`）；弹窗回调先 dismiss 再 recreate ✔。
  - TV 无 HEX（`:221,249-252`）；RGB 左右 ±5 用 `OnKeyListener` 消费 DPAD 并更新滑条（`:198-213`），与规范 §2.2 的 ±5 一致（长按靠系统重复事件连发）✔。
- **风险 T1（规范偏差）**：手机「恢复默认」在实现中**立即写 prefs + 关闭弹窗 + recreate**（`TextColorDialog:255-267`），而规范 §2.1 要求"选中默认色并同步滑条/HEX、不关闭、点确定才保存"；且无「确定前二次确认」→ 误触即丢失自定义色。建议改为只重置预览状态，保存仍走"确定"。
- **风险 T2（规范缺失，可读性）**：规范 §2.1 要求对比度 <4.5:1 红字提示 + 确定前二次确认；实现只有一句"亮色在浅色壁纸下可能不易看清"（`TextColorDialog:94-97`），**未拦截深色字配深色底**（用户可把正文设成 #2B313B 导致不可读）。建议补对比度计算/警告。
- **风险 T3（覆盖残差，已由规范 §4 声明"不保证全部"）**：`Skin.INK_MUTED 0xff7f8fa9`、`Color.WHITE 0xffffffff` 与大量字面量（0xffe6edf9/0xffc9d4e6 等）不经 remap；记录不阻断。另：非 `a.text/a.button` 创建后再次 `setTextColor(WHITE)` 的控件（如 `DetailScreen.styleGlassButton:95-117` 的按钮态）会在焦点切换时回到固定白色——属"焦点态不跟随"的边界，建议交付说明写清。

## 3. 1.4.6 网盘登录入口统一 —— 通过（含 1 项低风险）

- **通过**：`MainActivity.sourceAction` 只对**精确等于** `"LoginShow"` 的 action 转 `PanLoginGuide.show`，其余原样交 `SourceController.sourceAction`（`MainActivity:1562-1576`）→ 不误伤非登录 action ✔。
- `SettingsScreen.accountAction` 对 MyDrive 源的 LoginShow 同样先进引导（`SettingsScreen:173-191`）；`sourceAccountAction` **只做直通**（`SettingsScreen:194-209`），全仓仅 `PanLoginGuide:298-300,424-428` 调用 → 不会二次进入引导、无递归 ✔（调用链：引导→sourceAccountAction→SourceController.sourceAction，单向）。
- 从源面板返回刷新：引导把 `this::refreshAfterSource` 作为 finished 传入（`PanLoginGuide:255-259`），`SourceController.sourceAction` 在 UI 线程拿到 action 结果后 finally 调 finished（`SourceController:388-448`）→ 引导/账号面板就地刷新 ✔。
- **风险 L1（低）**：若配置源使用其它登录 action 名（非 `LoginShow`，大小写变体）不会被拦截；返回刷新依赖 source action 的返回时机（若源 action 只是拉起外部页面、立即返回结果，刷新会早于真实登录完成，用户可再点「刷新源状态」）。需真机/具体源验证，非代码错误。

## 4. 1.4.7 登录态三态检测 —— 有必须修（假状态风险）

- **通过**：
  - 三态齐全：LOCAL/SOURCE/UNKNOWN（`PanLoginState:29-31`）；UNKNOWN 文案为「由源管理 · 无法读取」/「有站点 Cookie，但无法确认登录（由源管理）」（`:230-243`），AccountPanel 标签为「由源管理 · 无法读取」「无法确认 · 由源管理 / 本机未检测到可确认信号（不等于未登录）」（`AccountPanel:583-610`），引导页同样（`PanLoginGuide:272,310-314`）→ **UNKNOWN 绝不写成"未登录"** ✔。
  - Cookie 判据收敛：只认登录 Cookie 名（夸克 `__puus`、百度 `BDUSS`、UC `__puus/_uc_s`、阿里 `refresh_token/aliyun`），匿名 `__pus`/普通站点 Cookie 只降级为 UNKNOWN 且要求值非空（`PanLoginState:52-65,106-135`）✔；裸域名不算（`:20-24`）。
  - 日志不含敏感值：只打 drive/state/evidence（prefs 文件名+key 名+token **名**、Cookie 域名），无 token/cookie 值、无账号名、无 URL（`PanLoginState:258-280`）✔。
- **必须修 M1（假状态风险，用户最在意）**：`AccountPanel.driveHasLocalCredential` 的本地凭据判据过宽，**只要 prefs 文件名/键名命中关键字即判已登录（不校验值）**：
  - `text = file + " " + key`，`switch` 里 quark `text.contains("quark")`、baidu `text.contains("baidu")`、ali **`text.contains("ali")`**（`AccountPanel:1080-1114`）——`"ali"` 会命中 `alias`、`quality`、`original` 等常见键名子串；且 `credentialKey` 只要求键含 pan/drive/cookie/token/credential/cred（`:1023-1032`），网盘源的配置类 prefs 只要文件名含站点名且有 `token_*` 配置键，就会显示「已登录（本地凭据）」。
  - 相关同源问题：`PanLoginState.storageEvidence` 用 `text.contains(token)` 子串（`bduss`/`kps=`/`refresh_token`，`:162-166`）——插件缓存/配置里出现这些字面量即 SOURCE。
  - 建议（低成本）：判据必须以**值**为准——去掉裸 `"ali"`/`"bduss"`/`"kps="` 纯子串；要求 `cookie`/`token` 键名 + 值形如 `name=value;` 或长度≥16 的 token 且不含 `<`/`http`；或要求同时出现该网盘域名 + 凭据字段。修后「已登录（本地凭据）」才可信。
- **风险 P1（性能/主线程）**：`detectAll` 无缓存，每次渲染都同步执行：`scanCredentials` 扫全部 shared_prefs（含 XML 解析）+ 对每个网盘 `storageEvidence` 再扫一遍 prefs（4×）+ 每网盘最多 2 次 `CookieManager.getCookie`（`PanLoginState:138-184,199-244,246-281`）；AccountPanel.refresh（`:553`）与 PanLoginGuide.render（`PanLoginGuide:243-247`）各自再调一次 → 打开/刷新面板在主线程做磁盘 IO 与 WebView binder 调用，TV 低端盒可能卡顿。建议：一次扫描复用结果（storage 只扫一遍）、Cookie 探测放 worker/短 TTL 缓存、两面板共享缓存。
- **风险 P2（Cookie 名判据）**：阿里 `aliyun`/`refresh_token` 作为登录 cookie 名无法验证（阿里盘可能同时下发非登录 `aliyun` cookie），存在 false positive 窗口；建议真机对照「已登录/未登录」抓一次 Cookie 或去掉 `aliyun`（保留 `refresh_token`）。
- **风险 P3（日志噪声）**：每次渲染 `AppLog.i` 4 条 drive 明细+1 条汇总（`PanLoginState:258-280`），无敏感值但会在环形日志里刷屏；建议 debug 开关/TTL。
- **风险 P4（口径）**：`AccountPanel:669-675` 在"清除凭据"前提示「本机没有检测到网盘登录凭据…」——是对"无本地凭据"的如实说明，不是状态标签；但与 UNKNOWN 并列时可能被误读，建议文案加"（不等于源内未登录）"与状态页一致。

## 5. 风险清单（汇总）

| # | 风险 | 证据 | 级别 |
|---|---|---|---|
| M1 | 本地凭据判据过宽 → 假「已登录」 | `AccountPanel:1080-1114`；`PanLoginState:162-166` | **必须修** |
| U1 | arm64/r8 变体名未固定 mobile edition | `UpdatePolicy:82-90` | 中低 |
| T1 | 手机「恢复默认」立即保存/关闭，违背规范"不关闭/确定才保存" | `TextColorDialog:255-267`；`DESIGN_REVIEW_1.4.6 §2.1` | 中 |
| T2 | 无对比度<4.5 警告/二次确认，深色字可致不可读 | `TextColorDialog:94-97`；规范 §2.1 | 中 |
| T3 | 字面量/焦点态文本不跟随（规范 §4 已声明"不保证全部"） | `MainActivity:974-982` | 低 |
| L1 | 非精确 `LoginShow`/返回刷新时机的源差异 | `MainActivity:1571`；`SourceController:434-445` | 低 |
| P1 | detectAll 主线程重复 IO（prefs/Cookie）无缓存 | 见 §4-P1 | 中 |
| P2 | 阿里 `aliyun` cookie 名可能误报 | `PanLoginState:61` | 中低 |
| P3/P4 | 日志噪声 / 清除凭据文案边界 | `PanLoginState:258-280`；`AccountPanel:669-675` | 低 |

## 6. 判定

- **构建面：可构建**（新 `PanLoginState` 在 `android/src/main/java`，Python/Gradle 自动包含；无新增依赖；Java 8 语法合规；未发现 API 签名不匹配——`scanCredentials/driveHasLocalCredential/findAccountName`、`UpdatePolicy.validateVariant`、`TextColorDialog.show` 调用点均已核对）。
- **应用代码必须修：M1（1.4.7 假状态判据）**；建议同轮修 T1/T2（字体颜色规范偏差）与 P1（缓存/线程），其余记风险。
- **Top3 见回给 Lead 的消息。**

---

## 7. 修复后快速复核（提交 `aedf7a8`，只核 M1/U1/P1/T1/T2）

> 状态：**可交付（无新增必须修）**；保留 2 条残余风险（低-中），建议记录不阻断。行号为 `aedf7a8` 快照。

| # | 复核点 | 结论 | 证据 |
|---|---|---|---|
| 1 | **M1 判据收紧** | **通过（1 条残余低风险）** | `AccountPanel.credentialConfigKey:1105-1121` 只认 cookie/token/credential/auth/login/session 且显式排除 alias/quality/original/name/title/desc；`credentialValue:1123-1140` 要求 cookie 串（`=`+`;`）或 ≥16 位 token（无空白/`<`/`http`）；`driveHasLocalCredential:1142-1186` 裸 `ali` 已删，ali 只认 `refresh_token/aliyundrive/alipan/aliyun` 特征；`PanLoginState.storageEvidence:368-397` 也加了 `credentialValue` 过滤。残余：`:1152` 的 `|| PanLoginState.sourcePrefsFile(file)` 让来源 prefs 里**任意键**只要值形如 `x=y;` 且键名含网盘名（如 `quark_cookie_mode`、值 `auto=1;`）仍可判「已登录」——建议 value 为 cookie 串时要求含该网盘域名/凭据 token，或去掉 sourcePrefsFile 绕过、统一要求 credentialConfigKey。 |
| 2 | **U1 arm64/r8 前缀** | **通过** | `UpdatePolicy:82-93`：mobile 走 `editionUrl`（`WhaleFall-<ver>-android-mobile.apk` 白名单），tv 走 `editionUrl(...,false)`（`-android-tv`）；arm64/r8 强制 `WhaleFall-<ver>-android-mobile-` 前缀（`:88-89`），arm64 排除 `-r8`（`:90`），r8 要求含 `-r8` 且 `-arm64`（`:91`，与现有 `...-r8-arm64.apk` 命名一致）✔。 |
| 3 | **P1 缓存/worker/合并** | **通过（2 条残余风险）** | `PanLoginState:73-144`：60s 静态缓存（按 siteKey）、同站点并发 waiters 合并、force 失效、扫描在 `a.worker`、回 UI 前判 `dialog.isShowing/version`（`AccountPanel:559-576`、`PanLoginGuide:248-272`）；`detectAll:146-184` 一次 `scanCredentials` + `prefsSnapshot` + `cookieSnapshot`，`storageEvidence` 复用 prefs 快照（`:369-397`），两面板共享同一缓存（`AccountPanel:567`、`PanLoginGuide:257`），主线程无扫描残留 ✔（grep 仅 `detectAll` 调用 scan/snapshot）。残余：①全局单飞按 site，跨站点并发时先完成者会清空/回调另一站点 waiters（`:111-142`；单站点面板场景概率低）；②`force` 在 same-site 已有 in-flight 时只是加入 waiters，可能返回登录前的旧结果（`:111-113`）；③`CookieManager.getCookie` 在 worker 调用（已 try/catch，建议真机确认 OEM WebView 无异常）。 |
| 4 | **T1 恢复默认只重置预览** | **通过** | `TextColorDialog:265-277`：只改 rgb、`refreshAll.run()`（滑条/HEX/预览同步）+ toast「点确定保存」，不写 prefs、不关弹窗；保存仍走「确定」→ `saveOrConfirm:294-297,318-345`。 |
| 5 | **T2 对比度二次确认** | **通过** | `TextColorDialog:317-336`：对 `Skin.BG` 算 WCAG 对比度，`<4.5` 时关闭原弹窗并弹「该颜色可能看不清」；「仍然保存」→ `save:338-345`（写 prefs + toast + recreate）✔；「返回修改」→ `show(a,color):77-85,334` 带回刚选颜色重开 ✔。小注：二次确认默认焦点是 message 而非「返回修改」，建议明确聚焦安全按钮（非阻断）。 |

**修复后结论：M1/U1/P1/T1/T2 全部通过；保留 2 条低-中残余风险（M1 的 sourcePrefsFile 绕过、P1 的跨站点单飞/force 合流），可交付 1.4.7，按 V32 验证。**
