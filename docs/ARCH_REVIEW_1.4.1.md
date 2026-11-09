# 1.4.1 TV 详情页（TvDetailLayout）+ 直播页壁纸 · 架构评审（C 类证据）

| 项 | 内容 |
|---|---|
| 评审对象（工作区未提交） | `TvDetailLayout.java`（新，821 行）、`DetailScreen.java`（768，`show` 单行委托 + 旧组件方法保留）、`TvLive.java`（2575，`screen` 去不透明底）；对照 `MainActivity`/`PlaybackController`/`MobileDetailScreen`（未改）与既有 device 探针 |
| 需求依据 | 用户要求：TV 详情页对齐平板风格；电视台直播页看不到壁纸（1.4.1） |
| 方式/证据 | 只读代码（read/grep/glob），**未构建、未改代码、未 commit、无真机**；行号为最终工作树快照 |
| 结论 | **可构建（静态构建面通过）；应用代码 0 项必须修；验收侧 1 项必须处理（DetailProbe 与新详情契约不兼容）**；风险 6 项见 §4 |

---

## 1. 通过项（逐条给证据）

### 1.1 焦点链（重点 1）——通过（含 1 条低风险注记）
- **首焦点**：加载中先聚焦「返回」（`TvDetailLayout:184`）；数据到达后恢复 `a.current.focus` tag → 无则 episodeFocus → 否则主播放（`:294-314`）；无线路时 `backBtn.requestFocus()`（`:208-214`）✔ 符合"主播放/无线路退返回"。
- **顶部返回/收藏可达**：固定在 `a.root` 顶部行（`:42-64`，margin 18/24），不在滚动区，DPAD 几何可达 ✔；返回=全局 `a.onBackPressed()`（`:48`）→ `MainActivity:1385-1454` 统一 pop ✔。
- **焦点恢复 tag**：`detail-play`（`:269`）、`episode:<line>:<originalIndex>`（`:591`）、`detail-line:<i>`（`:437`）、`detail-act-order/copy/synopsis/favorite`（`:657/666/671/676`）、`episodes-all/previous/next`（`:499/533/553`）、`detail-season:<n>`（`DetailScreen:718`）、`detail-back/favorite/title/cover/scroll/loading/history` 均齐备；恢复逻辑 `:294-314` 对线路名/集数原索引匹配，倒序/分页/换线路后仍能命中（episode tag 用 originalIndex，`:585-591`）。
- **播放页回详情恢复原焦点**：`PlaybackController.openPlayer` 走 `a.open(...)`（`:634`）→ `capture()` 存详情焦点（`MainActivity:1320-1334`）；`leavePlayback`/`showEpisodeDetails` 回详情后 `renderReturn` 或直接重绘（`PlaybackController:779-807`），`TvDetailLayout` 按 `a.current.focus` 恢复 ✔。
- **低风险**：无显式 `nextFocus`，跨栏（左线路↔右选集）/顶部行完全依赖默认 FocusFinder；从右栏深处到顶部返回需多次按键（无 nextFocus 捷径），建议真机确认手感；左侧线路列表是普通 `ScrollView`（`:105-109`）而非 `TvScrollView`，焦点 reveal 为直接跳转、无平滑/可重定向动效（与右栏手感不一致）。

### 1.2 布局（重点 2）——通过
- 安全边：左右 24dp、上 18dp、下 18dp（`:38,44-45,68-69`）；顶部行 48dp、内容顶距 78=18+48+12 ✔。
- 两栏：`contentW=windowWidth-48`，`leftW=round((contentW-20)*0.52)`、`rightW=余量`（`:71-74`），20dp gap（`:72`）→ 52/48 ✔；左栏固定（`row` 内 `-1` 高，`:76-82`），左内线路区独立 `ScrollView`（`:103-109`）。
- 右栏独立滚动：`TvScrollView` tag `detail-scroll`（`:85-93`），`a.scroll=rightScroll`、`a.body=rightCol`（`:94-95`）。`FocusMotion.unclipAncestors` 遇 `ScrollView` 即 break（`FocusMotion:35-61`），不会破坏滚动裁剪 ✔；错误回退 `SourceController:263-279` 把「重试」追加到 `state.getParent()`=rightCol，`a.body=rightCol` 使 `a.paragraph` 错误文案也落在右栏 ✔。
- `a.scroll` 像素恢复（`MainActivity:1339-1369`）对 `TvScrollView` 走 `scrollTo` ✔；数据未到时的像素恢复随后由焦点恢复覆盖，行为可接受。
- 无整页不透明层：`TvDetailLayout` 只有卡片/按钮背景（`:351,361,383,456,608,707`），根容器沿用 `renderPage` 安装的壁纸底（`MainActivity:1118-1149`）✔（壁纸可见需求成立）。

### 1.3 数据/空态（重点 3）——通过
- `redirectListing` 首行早退（`:31`），在 `renderPage` 之前，不产生半渲染页 ✔（与 `MobileDetailScreen:466` 同约定）。
- 无线路：过滤空线路（`:206-207`）→ 状态文案 + 操作区空 + 返回焦点（`:208-214`）✔。
- 无选集/详情拉取失败：`loadDetail` 失败把错误写 `detail-loading` 并追加「重试」到右栏（`SourceController:263-279`），不崩溃 ✔。
- 书签续播：`remembered` 匹配 site+id（`:217-229`），标签/主播放文案/`playBookmark`（`:261-288`）✔。
- 换线路：清 page、重建线路列表与选集并回焦该线路（`:438-446`）✔；倒序：切换后重建选集并回焦操作按钮（`:653-663`）；分页：切页后回焦页按钮（`:535-566`）✔；`EpisodeSelection` 记录 line/page（`TvDetailLayout:487-488`），播放返回按 selection 还原线路/页（`:230-239`）✔。

### 1.4 不碰手机（重点 4）——通过
- 分流只在 TV：`MainActivity.detail`（`:1634-1640`）`!isTvDevice()` → `MobileDetailScreen.show`；`isTvDevice()`=`formFactor==FORM_TV`（`:567-569`），手机/平板（mobile 包）不进 TV 详情 ✔；`MobileDetailScreen` 本轮未改。
- `DetailScreen.show` 只剩单行委托（`DetailScreen:37-40`）；旧组件方法全部保留且仍被使用：`renderEpisodes`（`:264`）被 `MainActivity.renderEpisodes`（`:1642-1645`）→ `MiniDetails:247` 使用；`glassNavButton/bindFavoriteAction/collectMetaPills/makeGlassMetaPill/heroPlayButton/buildRatingAndCastRow/buildFullSynopsisMessage/populateTvRelatedSeasons` 均被 `MiniDetails`/`TvDetailLayout` 使用，未见悬空引用 ✔。
- 注：`DetailScreen.renderEpisodes` 与 `TvDetailLayout.renderEpisodes` 现在是**两套选集渲染**（相同 tag 约定），行为可能日后漂移——低风险维护项。

### 1.5 壁纸层（重点 5）——通过（含对比度风险）
- `TvLive.screen` 已去掉不透明 `Skin.backdrop` 背景（`TvLive:320-322` 仅注释，无 `setBackground`），让 `renderPage`/`MobileShell` 安装的壁纸与主题渐变透出 ✔。
- 手机版 live 页：`MobileShell:821` 直接 add `TvLive.screen(a)`，根透明后透出 `MobileShell.render()` 的统一 backdrop；**壁纸关闭时父层底与旧 `Skin.backdrop` 是同一渐变，视觉无差异；仅「壁纸启用」时手机 live 页也显示壁纸**（若需求仅 TV，需按 `isTvDevice()` 保留旧底）。
- TV 详情：`renderPage("", "", false)` 安装 backdrop（`MainActivity:1185-1232` + `1118-1149`），`TvDetailLayout` 无整页不透明层 ✔。
- **风险 R1（可读性）**：live 页头部标题/副标题、详情右栏正文直接叠在壁纸上，无 scrim；`navigation=false` 时 `renderPage` 也不加 `homeGradient`。壁纸默认 60% 调暗（`ImageLoader.Atmosphere`）可缓解，但亮色壁纸仍有对比度风险——建议 live header / 详情右栏加轻量 scrim 或玻璃底，或真机截图评审确认。

---

## 2. 必须处理项（验收侧，不阻断构建）

**M1：既有 TV 详情探针与新布局契约不兼容，会 CCE/NPE/断言失败，导致"TV 详情回归通过"无法成立。**
- `DetailProbe:257,263-264` 把 `tag("detail-cover")` 强转 `ImmersivePoster`；新页用的是普通 `ImageView`（`TvDetailLayout:353-358`）→ **ClassCastException**（带 poster 参数时）。
- `DetailProbe:274-303` 断言存在 `episode-full-title`、且"title 与 cover 同父、title 在 cover 上方"（旧 hero 固定海报列）；新 52/48 两栏无此结构 → **NPE/断言失败**。
- `DetailProbe:315-319` 断言 `detail-line:1`.isSelected()；新线路按钮用背景色表达选中、未 `setSelected`（`TvDetailLayout:456`）→ 失败。
- `DetailProbe:353,374-375,383` 依赖 `episodes-order` tag 与倒序后焦点；新操作按钮 tag 为 `detail-act-order`（`TvDetailLayout:657`）→ **NPE**。
- 需 dev/QA 同步更新 `tests/device/DetailProbe.java`（按新两栏/新 tag 重写断言），或在 V 阶段显式豁免该探针并改用新版手工/新探针；否则不能宣称 TV 详情回归通过。`ExperienceProbe:509-527`、`InteractionProbe:722/746/816-821/856-859` 使用的 tag（detail-play/episode:/detail-favorite）新页仍存在，预计可过，但需随 M1 一起跑确认。

---

## 3. 风险清单（含行号）

| # | 风险 | 证据 | 建议 |
|---|---|---|---|
| R1 | 壁纸下可读性（live 头部 / 详情右栏无 scrim） | `TvLive:320-322`；`TvDetailLayout:112-143`（title/演员/简介直接叠底）；`MainActivity:1185-1232`（navigation=false 无 homeGradient） | 加轻 scrim/玻璃底或真机截图定 |
| R2 | 换线路后主播放仍优先书签线路，与注释"按当前选中线路"矛盾 | `TvDetailLayout:261-271`（remembered≠null 即 `playBookmark`）；`CatalogScreen:216-243`（按书签 line 播放） | 若产品期望"续播优先"，改注释/交付说明；否则按 lineIndex 是否变化决定 |
| R3 | 多季切换是 in-place `show()`，不压 Screen | `DetailScreen:715-728`（season 按钮直接 `show(...)`） | 从第 2 季按 BACK 回列表而非上一季；确认是否符合预期，必要时改为 `a.open(new Screen(...), true)` |
| R4 | 首次进入/异步数据到达会无条件 `.requestFocus()` 到主播放，可能抢走用户已移动的焦点 | `TvDetailLayout:309-314`（无 `focused.isAttachedToWindow()` 守卫，对比 `TvLive:638-640` 有守卫） | 加"用户已移动焦点则不抢"守卫 |
| R5 | 多季区异步插在选集之前，焦点已落在集数时布局下移、滚动位置不跟随 | `TvDetailLayout:168-177` 顺序；`DetailScreen:653-761` 异步插入 | 低概率；真机检查多季详情 |
| R6 | 右栏初次可能与异步冲突的 present 执行两次（listing 有线路且无简介时先 `present(listing)`，再 `loadDetail` 回调 `present(video)`） | `TvDetailLayout:317-334` | 目前幂等（各 wrap 先 removeAllViews），多季异步区有轻微竞态；建议加 once 标记 |

---

## 4. 判定

- **构建面：可构建。** 新文件在 `android/src/main/java` 下，自动进入 Python `rglob`（`scripts/build_android.py:134`）与 Gradle `srcDirs`（`gradle/app/build.gradle:26`）；无新增依赖；Java 8 语法（lambda/try-with-resources）无违规。编译未在本会话执行（由 Lead 跑），未发现明显 API 签名不匹配（`glassNavButton/heroPlayButton/populateTvRelatedSeasons/EpisodeSelection.boundPage/SourcePreferences.lineIndex` 均已核对存在）。
- **应用代码：0 项必须修**；建议本轮顺手处理 R2（注释/行为一致）与 R4（焦点守卫），其余记风险。
- **验收侧：M1 必须处理**（更新 DetailProbe 或明确豁免）——否则 TV 详情回归无法用现有探针证明。
