# 设计走查报告 · 1.2.2

> 版本：whalefall 1.2.2 · 片库响应式 + 电视台直播响应式首轮实现走查
> 审核人：UI/UX 设计；审核方式：只读代码，对照
> `docs/LIBRARY_LAYOUT_SPEC.md`、`docs/TV_LIVE_RESPONSIVE.md`、`docs/WHALEFALL_DESIGN_SYSTEM.md`。
> 本轮不改代码；所有「建议修正值」均为可直接落地的 dp / sp / token。
> 优先级定义：**P0** 只列会导致明显丑或误操作的；**P1** 违反规范/一致性、应该在下一轮修；
> **P2** 细节/技术债，可顺手处理。

---

## 0. 审核结论

| 模块 | P0 | P1 | P2 | 符合 |
| --- | --- | --- | --- | --- |
| 片库（`MobileShell.renderMyTab/buildLibraryList/buildRow/buildCard/buildEmpty`） | 3 | 20 | 6 | 4 |
| 电视台直播列表 + 播放器（`TvLive`） | 0 | 10 | 9 | 9 |

> **范围澄清（重要）**：`MobileShell`（含片库）只在**非 TV** 路径创建（`MainActivity.home()/contentHome()` 对
> `isTvDevice()` 分支走 `HomeScreen` / `CatalogScreen.historyPage` / `FavoritesScreen`）。
> `TvLive.screen()` 也只被 `MobileShell.renderTvTab()` 调用，所以 **TV 端的「三栏片库」「电视台直播」当前不是
> 本套实现**，而是 TV 专属的 `HomeScreen/FavoritesScreen/historyPage`（它们已有 `setFocusable` / 焦点监听）。
> 因此 `LIBRARY_LAYOUT_SPEC` / `TV_LIVE_RESPONSIVE` 的 **TV 章节是「规范已写、实现未接入」**，
> 本报告记为 P1 产品缺口（第 2.4 节），不把 TV 当成本轮代码回归的 P0。

**结论**：片库的「顶部页签 + 网格」方向正确，但**宽度计算把全局导航栏扣了两次**，导致平板横屏只出 3 列、
右边空 88dp；空状态在网格断点只占第一列且用 emoji；删除/取消收藏角标过小且无确认。播放器颜色/显隐/
全屏等本轮已按规范落地（符合项多），剩余主要是尺寸、字号与安全区；TV 差异按 P1 产品缺口记录。

**三条 P0（只列明显丑 / 误操作）：**

| # | 问题 | 场景 | 修正目标 |
| --- | --- | --- | --- |
| P0-1 | 片库网格可用宽度重复扣 88dp | 平板横屏 3 列 + 右侧 88dp 空白；平板竖屏退化成单列；TV 若复用会 3 列 | 直接使用 `host.getWidth()`（已扣全局 rail），列数按 600/840/1080/TV 判定；T2 必须 4 列 |
| P0-2 | 片库空状态：emoji「🎬」+ 网格里只占第一列 + 顶部对齐 | 平板空列表时文字挤在左上角第一格 | 用 64dp 玻璃圆 + `UiIcon` 20dp `ACCENT` + 17sp/13sp + 36dp 按钮；`SpanSizeLookup` 空状态占满 `cols`；垂直居中 |
| P0-3 | 片库 24dp 角标「✕ / ♥」单击直接删除/取消收藏，无确认、无撤销、热区仅 24dp | 误触即永久丢历史/收藏 | 视觉 24dp 但热区 44×44；删除/取消收藏弹确认或 Toast+撤销；TV 分支改长按 OK |

---

## 1. 片库走查明细

代码位置：`MobileShell.renderMyTab()`（2901–3005）、`buildLibraryList()`（3009–3035）、
`MyListAdapter.buildRow()`（3092–3128）、`buildCard()`（3132–3191）、`buildEmpty()`（3195–3230）、
`styleLibraryPill()`（3320–3329）。

### 1.1 断点 / 列数 / 宽度

| # | 检查项 | 规范值 | 实际值 | 判定 | 建议修正值 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| L1 | 可用宽度来源 | 片库在 `contentContainer` 内，`Wc` 已扣右侧全局 rail（≈92dp） | `avail = host.getWidth()` 后再 `- a.dp(88)` → **双重扣减** | **偏差** | 删除 `if (!phone) avail = ... - a.dp(88)`；直接用 `host.getWidth()`；`phone` 判断也改用 `widthDp < 600` | **P0-1** |
| L2 | 列数判定 | `<600` 1 列 / `600–839` 3 / `840–1079` 4 / `≥1080` 5 / TV 4 | 用被扣减后的 `widthDp`：平板横屏 960 → 780 → **3 列**；平板竖屏 640 → 520 → **1 列**；TV 960 → 780 → **3 列** | **偏差** | `widthDp = host.getWidth()/density`；`≥1080→5`、`≥840→4`、`≥600→3`；TV 固定 4；grid 用 `GridLayoutManager(cols)` | **P0-1** |
| L3 | 平板横屏使用宽度 | content 868dp（960−92），4 列 ×196dp + gap14 | 780dp 可用、3×237dp，右侧 88dp 空白 | **偏差** | 由 P0-1 修复；修后卡片 196dp | **P0-1** |
| L4 | 页面左右边距 | 16 / 20 / 20 / 24 / 24（TV） | 16（phone）/ 20（其余） | 偏差 | T3 与 TV 改 24 | P1 |
| L5 | 列间距 `gapH` | 12 / 14 / 16 / 18 | 非手机一律 14 | 偏差 | T1 12、T2 14、T3 16、TV 18 | P2 |
| L6 | 手机行/网格切换 | `<600` 列表行，其余网格 | 以 `formFactor==PHONE` 判断，非手机 1 列时仍走 `buildRow`（平板竖屏会出现「手机行」） | 偏差 | 改由 `cols` 决定：`cols==1 → buildRow`，`cols>1 → buildCard` | P1 |

### 1.2 卡片尺寸 / 封面 / 标题 / 进度

| # | 检查项 | 规范值 | 实际值 | 判定 | 建议修正值 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| L7 | 封面 2:3 | 高 = 宽×1.5 | `coverH = round(cardW*1.5)`，但 `cardW` 是被扣减后的值，与真实 item 宽不一致 | 偏差 | 用真实 item 宽（修完 P0-1 后 `cardW` 即等于列宽），并在 `onSizeChanged` 里按实际宽度重算封面高 | P1 |
| L8 | 网格卡片容器 | `GLASS_FILL #1AFFFFFF` + 1dp `STROKE` + `RADIUS_16` + 左右 10 / 底 10 内边距 | `buildCard` **无卡片背景、无圆角、无内边距**；文字直接贴在页面背景上 | 偏差 | `card.setBackground(round(16dp, GLASS_FILL, STROKE))`；`setPadding(10,0,10,10)`；`clipToOutline` | P1 |
| L9 | 标题 | 15sp bold（TV 17sp），固定 2 行高 42dp（TV 48dp），`maxLines=2`、`ellipsize=END` | 15sp、`maxLines=2`、高度写死 **46dp**、`topMargin 8`；无 TV 字号 | 偏差 | 高度 42dp（TV 48）、`topMargin 10`、TV `setTextSize(17)`；`setIncludeFontPadding(false)` | P1 |
| L10 | 标题区与 meta 间距 | 标题后 4dp、meta 固定 18dp | meta 紧跟标题（无 topMargin），高度 18dp | 偏差 | meta `topMargin 4dp`；标题与 meta 均 `setSingleLine`/固定高 | P2 |
| L11 | 进度条 | 3dp（TV 4dp）、圆角 999、轨道 `#4DFFFFFF`、已播 `ACCENT`、宽度=封面宽 | 3dp **直角**、轨道/已播颜色正确、宽度 `cardW*frac`（与真实封面宽有偏差）；`duration=0` 时 `frac` 会被钳到 1 → 满条 | 偏差 | `GradientDrawable` 圆角 1.5dp；宽度=真实封面宽；`duration<=0` 时整条隐藏；TV 4dp | P1 |
| L12 | 网格 item 尺寸 | 单元高 = cover + 10 + title 42 + 4 + meta 18 + 10 | 实际 `cover + 8 + 46 + 18`，**底部无内边距**，卡片高度比规范少 12–14dp | 偏差 | 由 L8/L9 修复后按规范公式：`cellH = coverH + 10 + titleH + 4 + metaH + 10` | P1 |
| L13 | 右/底间距 | 右列不重复加 gap（或首尾等距） | item `rightMargin = gap` 且 `rv.setPadding(sidePad…)` → 右缘总留白 = sidePad+gap（左缘只有 sidePad），左右不对称 | 偏差 | 用 `ItemDecoration` 统一 gap，或 `paddingRight = sidePad - gap`；最后列不设 rightMargin | P2 |

### 1.3 手机列表行

| # | 检查项 | 规范值 | 实际值 | 判定 | 建议修正值 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| L14 | 行高 | 100dp | `padding 8+8 + thumb 80` = **96dp** | 偏差 | 上下 padding 10 → 100dp | P2 |
| L15 | 缩略图 | 56×80、圆角 10、CENTER_CROP | 56×80 ✓ | 符合 | — | — |
| L16 | 行卡片背景 | `GLASS_FILL` + 1dp `STROKE` + `RADIUS_16` | `#CC1E3557 → #CC17294A` 蓝玻璃，非 token | 偏差 | `glassBg(GLASS_FILL, STROKE…)` 或 `UiTokens.round(16, GLASS_FILL, STROKE)` | P1 |
| L17 | 行内进度条 | 3dp、meta 下 5dp | **没有进度条**（`holder.progress` 只在卡片里创建） | 偏差 | 行内加 3dp 进度条（轨道 `#4DFFFFFF`、已播 `ACCENT`） | P1 |
| L18 | 标题/meta | 标题 15sp 单行 22dp、meta 11.5sp 16dp | 标题 15sp ✓；meta **12sp**；无固定高 | 偏差 | meta 改 11.5sp `TEXT_SECONDARY`、高 16dp | P1 |
| L19 | 删除/详情按钮 | 56×44 热区、13sp `DANGER`（收藏为 `TEXT_TERTIARY`） | 12sp、`#EF4444`，热区 ≈30dp 高 | 偏差 | 文字 13sp、颜色 `DANGER`/`TEXT_TERTIARY`、`setMinimumHeight(44)`、宽 ≥56 | P1 |
| L20 | 长按 | 历史=删除确认、收藏=取消收藏 | 无长按处理 | 偏差 | `setOnLongClickListener` 弹确认 | P1 |

### 1.4 角标 / 删除 / 收藏

| # | 检查项 | 规范值 | 实际值 | 判定 | 建议修正值 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| L21 | 角标尺寸/热区 | 视觉 24dp、热区 44×44、图标 14dp | 24×24 文本「♥」/「✕」12sp，热区 24dp | 偏差 | `PlayerUiKit`/`UiIcon` 14dp 图标；外层 44×44 透明可点容器 | **P0-3** |
| L22 | 删除/取消确认 | 删除/取消收藏需确认或 Toast+撤销 | 单击立即写 prefs 并刷新，**无确认无撤销** | 偏差（误操作） | 删除弹「删除该条？」；收藏取消给 3s 撤销 Toast | **P0-3** |
| L23 | 角标配色 | `PLAYER_CHIP #99000000` + 1dp `PLAYER_BTN_STROKE #40FFFFFF` | `0x99000000` 字面量（值对、未用 token 常量）；无描边 | 偏差 | `UiTokens.PLAYER_CHIP` + `PLAYER_BTN_STROKE`、`round(12dp…)` | P2 |
| L24 | TV 角标 | TV 不显示角标（走长按 OK） | 网格卡片显示角标，但不可聚焦 | 偏差 | TV 分支不添加角标 View | P1 |

### 1.5 页头 / 页签 / 空状态 / 下载

| # | 检查项 | 规范值 | 实际值 | 判定 | 建议修正值 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| L25 | 页标题 | 24sp bold `TYPE_CLOCK`；上 10/12dp | **22sp**；`paddingTop = statusBar+10` 固定 | 偏差 | 24sp；平板 top = statusBar+12 | P1 |
| L26 | 页签 | 13sp；高 34（热区≥44）；激活 `ACCENT→ACCENT_SOFT` 深字；未选 `GLASS_FILL+STROKE` | **13.5sp**；激活**白底黑字**；未选 `#CC172842` | 偏差 | 13sp；激活 `TvUiKit.accentFill`、文字 `BG_DEEP`；未选 `GLASS_FILL+STROKE`；`setMinimumHeight(44)` | P1 |
| L27 | 清空历史 | 13sp `DANGER`、热区 56×44 | **12sp** `#EF4444`，无热区 | 偏差 | 13sp `UiTokens.DANGER`、`setMinHeight(44)` | P1 |
| L28 | 统计文案 | 「共 N 部 · 本周新增 M」11.5sp `TEXT_SECONDARY` | 无统计行 | 偏差 | 标题右侧/下方加统计；数据来自 history/favorites size | P1 |
| L29 | 空状态视觉 | 64dp 玻璃圆 + `UiIcon` 20dp `ACCENT` + 17sp 标题 + 13sp 说明 + 36dp 粉按钮，最大宽 420、居中 | **emoji「🎬」40sp**、无圆底；按钮 37dp；`paddingTop 72` 顶对齐 | 偏差（明显丑 + 违反 §6 禁止 emoji） | 见 P0-2：`FrameLayout` 64dp 圆 + `UiIcon.FAVORITE/PLAY/LIVE`；标题 17sp、说明 13sp；按钮 36dp；`Gravity.CENTER`；`box.setMaxWidth(420)` | **P0-2** |
| L30 | 空状态在网格断点 | 占满整行（`spanSize = cols`） | 无 `SpanSizeLookup`，空 item 只占**第一列** | 偏差（明显丑） | `GridLayoutManager.setSpanSizeLookup`：`getItemViewType==2 ? cols : 1` | **P0-2** |
| L31 | 下载页签 | 最大宽 760、左对齐 `sidePad`、底部 24（平板） | body 宽 760 但**无左 padding**（贴 0），底部写死 **106**；用 `a.windowWidth()` 而非 content 宽 | 偏差 | body `paddingStart=sidePad`，宽度 `min(Wc-2*sidePad, 760dp)`；底部 phone 106 / 平板 24 | P1 |
| L32 | 底部留白 | phone 106、平板/TV 24 | `rv.setPadding(..., a.dp(106))` 所有断点 | 偏差 | phone 106 / tablet 24 / TV 24（扣 nav inset） | P1 |

### 1.6 对齐 / 安全区 / TV

| # | 检查项 | 规范值 | 实际值 | 判定 | 建议修正值 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| L33 | 长标题不撑宽 | 卡片宽只来自网格；标题固定 2 行 | 网格 item `MATCH_PARENT` ✓；标题 `maxLines=2` ✓ | 符合（修复 P0-1 后等比） | — | — |
| L34 | 横向 rail 对齐 | 若加横向 rail，复用同一卡片宽度并 `paddingStart=sidePad` | 当前无横向 rail | 符合/N.A. | — | — |
| L35 | 全局右导航栏避让 | 内容已由 `contentContainer` 让出 | 逻辑上已让出，但被再扣 88dp（P0-1） | 偏差 | 见 P0-1 | **P0-1** |
| L36 | 状态栏/刘海 | 顶部 `max(statusBar, cutout)` | page 顶 `statusBar+10` 固定 | 偏差 | 用 insets 分支；平板 +12 | P2 |
| L37 | 手机底部 Dock | 内容底 106 | ✓ 106 | 符合 | — | — |
| L38 | TV 焦点（若 TV 复用本页） | 页签→网格首卡；焦点框 3dp `ACCENT`+1.02；行高 379/pitch 397 | `MobileShell` 仅非 TV 运行，TV 走 `HomeScreen/FavoritesScreen/historyPage`（已有焦点）；本页无 `setFocusable` → 规范 TV 章节未接入 | 未实现（P1 产品缺口） | 若要 TV 复用：卡片/页签 `setFocusable(true)`、焦点 foreground、首焦点页签、长按 OK；否则保持现状并删掉规范中的 TV 验收项 | P1 |

---

## 2. 电视台直播走查明细

代码位置：`TvLive.screen()`（278–305）、`renderTvPage()`（472–577）、`buildCategoryRail()`（580–680）、
`channelRow()`（822–903）、`buildPreview()`（905–1078）；`Player`（`buildTopBar` 1738、`buildInfoPanel` 1820、
`buildBottomPanel` 1919、`showChrome` 2123、`toggleChrome` 2151、`dispatchTouchEvent` 2258、`close` 2200）。

### 2.1 方向策略

| # | 检查项 | 规范值 | 实际值 | 判定 | 建议修正值 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| T1 | 列表方向 | PHONE 锁竖屏、TABLET `UNSPECIFIED`、TV 不改 | `screen()` 已按 `formFactor` 分支：PHONE→PORTRAIT、TABLET→UNSPECIFIED、TV 不改 | 符合 | — | — |
| T2 | 播放器方向 | PHONE `SENSOR_LANDSCAPE`；TABLET 不强制 | 仅 `FORM_PHONE` 设置 `SENSOR_LANDSCAPE`；平板不转向 | 符合 | — | — |
| T3 | 关闭恢复 | 恢复进入前方向 | `close()` 固定写回 PORTRAIT/UNSPECIFIED；`previousOrientation` 已声明但**未使用**（死代码） | 偏差 | 用 `previousOrientation`（仅 PHONE 改过才恢复）；删除死字段或接上 | P2 |
| T4 | 方向设置时机 | 只设置一次 | `screen()` 每次渲染都调用 `setRequestedOrientation` | 偏差 | 放进 `switchTab(5)` 的首次进入或只在方向变化时调用 | P2 |

### 2.2 列表断点 / 三栏尺寸

| # | 检查项 | 规范值 | 实际值 | 判定 | 建议修正值 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| T5 | 断点分类 | `<600` 单列 / `600–839` 两栏 / `840–1079` 三栏 / `≥1080` 三栏扩展 / TV 三栏 | 仍是二值：`wide = windowDp >= 720`（296 行） | 偏差 | 改为 `layoutClass` 四档 + TV：`<600 / 600–839 / 840–1079 / ≥1080 / TV` | P1 |
| T6 | 平板竖屏 640dp | 两栏（rail 132 + 列表） | `640 < 720` → 单列，rail/preview 都不显示 | 偏差 | `600≤W<840` 两栏：rail 132 + 列表，预览改为列表顶部当前频道卡 96dp | P1 |
| T7 | 三栏列宽 | T2 152/504/240；T3 168/552/280；TV 176/404/300 | 150 / 列表自适应 / 252（所有 wide 共用） | 偏差 | 按断点取值：T2 152/240，T3 168/280，TV 176/300 | P1 |
| T8 | 侧边距 | 16 / 20 / 24（T3、TV 24） | `wide ? 20 : 16`（TV 也是 20） | 偏差 | T3/TV 24 | P2 |
| T9 | 列表行高 | 56 / 52 / 44 / 46 / 64 | `wide ? 40 : 56`（40 < 44 触控下限；TV 也 40） | 偏差 | T1 52、T2 44、T3 46、TV 64；手机 56 | P1 |
| T10 | 列表标题字号 | 24sp（`TYPE_CLOCK`） | `wide ? 27 : 24` | 偏差 | 统一 24sp | P1 |
| T11 | 行名称/编号字号 | 15sp / 13sp（TV 17/15） | `wide ? 15.5 : 15`、编号 13.5 | 偏差 | 15sp、编号 13；TV 17/15 | P2 |
| T12 | 预览卡 | T2 hero 112 / 名称 17 / 按钮 36·42；TV hero 132 / 名称 24 / 48·52 | hero 96、名称 19、按钮 36·42（所有 wide） | 偏差 | T2 112/17/42；TV 132/24/52；T3 132/17/44 | P2 |

### 2.3 播放器 chrome

| # | 检查项 | 规范值 | 实际值 | 判定 | 建议修正值 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| T13 | 顶栏底色 | `PLAYER_CHIP #99000000` + 1dp `PLAYER_BUTTON_STROKE` | `glassBg(0x99000000, 0x40ffffff, 20)` | 符合 | 用 `UiTokens.PLAYER_CHIP` / `PLAYER_BTN_STROKE` 常量替换字面量 | — |
| T14 | 底部面板底色 | `PLAYER_SHEET #F20B1220`、圆角 22 | `glassBg(0xf20b1220, 0x40ffffff, 22)` | 符合 | 同上（token 常量） | — |
| T15 | 信息面板 | 仅 `W≥840`；宽 184（TV 240） | 门槛 840 ✓；宽 **172**（TV 也 172） | 偏差 | 184 / TV 240 | P2 |
| T16 | 普通控制条尺寸 | 返回 44–48、顶栏按钮 32→TV 40、chips 32→TV 40 | 返回 38、顶栏胶囊 32、频道 chips 32（TV 同） | 偏差（触控 <44） | 返回 44（TV 48）；顶栏按钮 36/40；chips 36（TV 40），并 `setMinimumHeight(44)` | P1 |
| T17 | 标题/副行字号 | 手机 16sp / 平板 17sp；副行 11.5sp | 14.5sp；副行 10sp | 偏差 | 16/17sp；副行 11.5sp `TEXT_SECONDARY` | P1 |
| T18 | 安全区 | 顶部 `max(statusBar,cutout)+8`；底部 `inset+8`；手机沉浸式 | 顶部 statusBar+8 ✓；底部 **16**（未含 nav/gesture inset）；**未调用 `applyMobileSystemBars(true)`**，系统栏常驻 | 偏差 | 进入播放器调用沉浸式；底部 = `max(navBar/gesture,16)+8`；平板上 22 | P1 |
| T19 | 显隐规则 | tap ≤12dp/320ms、只一处；3s 自动淡出；`panelOpen` 不淡出 | 单处 `dispatchTouchEvent`（12dp/320ms）✓；350ms 防抖 ✓；`showChrome` 判断 `panelOpen` ✓ | 符合 | — | — |
| T20 | 全屏回调 | 隐藏全部 chrome；`onHideCustomView` 恢复 | `onShowCustomView` 里 `chrome.setVisibility(GONE)` ✓；`onHideCustomView` → `showChrome()` ✓ | 符合 | — | — |
| T21 | Dock / 网页控件叠层 | 进入播放器隐藏全局 Dock；底部频道面板默认收起 | `MobileShell.setDockVisible(false)` ✓；`bottomPanel` 默认 `GONE` ✓ | 符合 | — | — |
| T22 | 手势 | 垂直 30dp、左亮度/右音量；不误触 chrome | `dy>30dp && dy>1.5dx`；左右半屏 ✓ | 符合 | — | — |
| T23 | 淡出动效 | `MOTION_SLOW` 240ms | `fadeOutChrome()` 直接 `setVisibility(INVISIBLE)`，无动画 | 偏差 | `animate().alpha(0).setDuration(240)` | P2 |
| T24 | TV 自动隐藏 | 5000ms | 3000ms（所有设备） | 偏差 | TV 5000ms | P2 |

### 2.4 TV 尺寸 / 范围说明

> **范围**：`MobileShell`、`TvLive` 只在非 TV 路径运行；TV 的片库/直播使用 `HomeScreen`、`FavoritesScreen`、
> `CatalogScreen.historyPage`（这些页面已有焦点实现）。因此 T25/T26 的「TV 焦点」不是本套代码的回归缺陷，
> 而是 **TV_LIVE_RESPONSIVE 的 TV 章节尚未接入**；若要把电视台直播接进 TV，按下表数值实现时再验。

| # | 检查项 | 规范值 | 实际值 | 判定 | 建议修正值 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| T25 | 列表页焦点（TV 接入后） | 分类栏/行/预览可聚焦；首焦点当前频道；焦点框 2dp `ACCENT`+1.02 | 列表行/栏/预览未设可聚焦；仅非 TV 使用 | 未实现（P1 产品缺口） | 若 TV 复用：行 `setFocusable(true)`、焦点 foreground、`nextFocus` 链、`recycler.scrollToPosition`；行高 64 | P1 |
| T26 | 播放器 chrome 焦点（TV 接入后） | 顶栏按钮/频道 chips 可聚焦，OK 触发；数字键换台 | chrome 全为 TextView 不可聚焦；仅数字键可用；D-pad 无法展开频道面板 | 未实现（P1 产品缺口） | 按钮 `setFocusable(true)` + 焦点框；chips 可聚焦；首焦点「频道」；TV 打开时焦点进入 chrome | P1 |
| T27 | TV 10-foot 字号（接入后） | 名称 17、编号 15、行高 64、按钮 ≥48 | 名称 15.5、行高 40、按钮 32 | 偏差（跟着非 TV 值） | 见 T9/T11/T16 | P2 |
| T28 | 数字键换台 | 1–9 换台 | `overlay.setOnKeyListener` 支持 1–9 ✓ | 符合 | — | — |

---

## 3. 符合项（无需改）

| 模块 | 符合项 |
| --- | --- |
| 片库 | 网格方向正确、封面 2:3 计算、标题 `maxLines=2`、手机缩略图 56×80、Dock 底部留白 106、下载列表最大宽 760、页签常显 |
| 直播 | 列表/播放器方向策略（PHONE 锁竖屏、TABLET 不强制、TV 由系统横屏；TV 走独立页面）、播放器仅手机强制横屏、顶/底栏纯黑 token、信息面板门槛 840、tap 单处 12dp/320ms、3s 自动淡出 + panelOpen 暂停、全屏隐藏 chrome、Dock 隐藏、垂直手势 30dp、数字键换台 |

---

## 4. P1 / P2 汇总（便于排期）

### P1（下一轮修）

**片库**
- L4 T3/TV 边距 20→24；L6 平板竖屏应走网格；L7 封面高用真实 item 宽；L8 网格卡片补 `GLASS_FILL+STROKE+RADIUS_16+内边距`；
- L9 标题高 46→42（TV 48、字号 17）；L11 进度条圆角/真实宽度/`duration=0` 隐藏；L12 卡片高公式；
- L16 行背景 token；L17 手机行补进度条；L18 meta 12→11.5sp；L19 删除按钮 13sp/热区 44；L20 长按确认；
- L25 页标题 22→24；L26 页签 13sp + ACCENT 渐变 + 非白底；L27 清空 13sp DANGER + 44 热区；L28 补统计文案；
- L31 下载页左对齐 sidePad + 底部断点；L32 底部留白断点化。

**直播**
- T5/T6 四档断点（640dp 两栏）；T7 三栏列宽；T9 行高 40→44；T10 标题 27→24；
- T16 播放器按钮 38→44（TV 48）、chips 36；T17 标题 16/17sp、副行 11.5sp；T18 沉浸式 + 底部 inset；
- T25/T26（TV 接入后）：若要把 `TvLive` 接进 TV，先补焦点链与 10-foot 尺寸；否则保持 TV 走现有 `HomeScreen` 并删掉规范 TV 验收项。

### P2（顺手）

L5 gap 断点值；L10 meta 间距；L13 列间距左右对称；L23 token 常量；L36 insets；
T3 恢复 `previousOrientation`；T4 方向只设一次；T8 侧边距；T11 字号 15/13；T12 预览卡尺寸；T15 信息面板 184/240；T23 淡出 240ms；T24 TV 5000ms；T27 TV 10-foot 字号（接入后）；T28 数字键已符合（保留）。

---

## 5. 复测清单（修完 P0 后）

- [ ] 平板横屏（960dp）片库：4 列、卡片 196dp、右侧无 88dp 空白；页头与首列左缘对齐。
- [ ] 平板竖屏（640dp）片库：3 列网格，不出现「手机行」样式。
- [ ] 空数据三页签：平板空状态横跨整行、居中、无 emoji；手机空状态 64dp 图标 + 双行文案 + 36dp 按钮。
- [ ] 片库删除/取消收藏：44dp 热区、二次确认或撤销 Toast，误触不丢数据。
- [ ] 播放器（非 TV）：手机沉浸式、底部控制条在导航手势区上方；信息面板宽 184（若 TV 接入则 240）。
- [ ] TV 接入验证（若本轮把 `TvLive`/片库接进 TV）：D-pad 可完成「切页签 → 进网格 → 选中 → 打开」与「直播列表 → 选频道 → 播放页 → 打开 chrome」；焦点框 2dp `ACCENT` 可见；chrome 5s 淡出。

---

## 6. 代码位置索引

| 文件 | 区域 |
| --- | --- |
| `MobileShell.java` | `renderMyTab` 2901、`buildLibraryList` 3009、`buildRow` 3092、`buildCard` 3132、`buildEmpty` 3195、`styleLibraryPill` 3320 |
| `TvLive.java` | `screen` 278、`renderTvPage` 472、`buildCategoryRail` 580、`buildMiddle` 683、`channelRow` 822、`buildPreview` 905、`buildTopBar` 1738、`buildInfoPanel` 1820、`buildBottomPanel` 1919、`showChrome` 2123、`toggleChrome` 2151、`dispatchTouchEvent` 2258、`close` 2200 |
| `MobileDownloads.java` | `render` 45、行构建 152 |
| 规范 | `docs/LIBRARY_LAYOUT_SPEC.md`、`docs/TV_LIVE_RESPONSIVE.md`、`docs/WHALEFALL_DESIGN_SYSTEM.md` |
