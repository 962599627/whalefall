# 鲸落统一配色系统 · 樱花粉主题（1.9.312）

> 目标：把当前「深蓝底 + 多处蓝/青/绿/白杂色 + 绿色默认字体 + 粉色强调」收回成**暖夜梅 + 樱花粉**一套色。粉色只做强调，卡片回到低饱和暖梅色；所有页面同一套 token。
> 范围：手机/TV 全部界面；**只改颜色 token 与组件引用，不改布局、不改交互**。
> 只出设计规格，不碰代码、不构建。文件行号基于当前代码，若后续改动以邻近的旧 ARGB 字符串定位。

---

## 1. 设计方向

- **底色**：深海蓝改为**暖夜梅黑**（低饱和紫红黑），去掉蓝色渐变与青蓝光晕，解决“底和粉打架”。
- **卡片**：统一为**暖梅玻璃底**：`#3A1F2B` + 默认 40% 不透明度，1dp 淡粉描边；不再出现蓝玻璃 `#1E3557`、蓝卡 `#1A2C45`、绿玻璃、白卡混用。
- **强调**：樱花粉只在四类地方出现：选中、焦点、主按钮、进度/当前态。其余用暖白/灰粉文字。
- **文字**：默认暖白偏粉 `#FFE3EC`，不再默认亮绿 `#4CFF80`；用户可改字体颜色，但底色/描边/焦点等不开放乱改。
- **比例**：背景 70%、中性卡片/面板 20%、文字 7%、樱花粉 3%；语义成功/警告/危险合计 ≤1%。

---

## 2. 设计 Token 表（ARGB）

### 2.1 背景/表面

| Token | ARGB | 用途 |
| --- | --- | --- |
| `BG_DEEP` | `#FF0C070C` | 页面最底层、播放器外框、弹窗最深底 |
| `BG` | `#FF140C12` | 默认页面背景 |
| `BG_TOP` | `#FF1B1118` | 页面顶部渐变起点 |
| `SURFACE_1` | `#FF211621` | 内嵌面板、次级区域 |
| `SURFACE_2` | `#FF2B1C27` | 输入框底、工具格底、浮层底 |
| `GLASS_FILL` | `#1AFFF4F7` | 薄玻璃面板（筛选面板、dock、胶囊） |
| `GLASS_STROKE` | `#33FFF4F7` | 薄玻璃 1dp 描边 |
| `SCRIM` | `#B3000000` | 弹窗遮罩、图片底部压暗 |
| `SCRIM_SOFT` | `#66000000` | 轻遮罩、播放器工具层 |
| `SHADOW` | `#66000000` | 仅浮层阴影，不用于卡片描边 |

### 2.2 卡片（默认套色）

| Token | ARGB | 用途 |
| --- | --- | --- |
| `CARD_BASE` | `#FF3A1F2B` | 默认卡片暖梅底色 |
| `CARD_FILL` | `#663A1F2B` | 默认 40% 卡片填充（首页/详情/设置/片库/dock） |
| `CARD_FILL_STRONG` | `#B33A1F2B` | 浅色壁纸/需要更强的卡片（70%） |
| `CARD_STROKE` | `#33FFD6E3` | 1dp 卡片描边（20% 淡粉） |
| `CARD_STROKE_STRONG` | `#66FFD6E3` | 焦点/浮层卡片 1dp 描边（40%） |

### 2.3 强调/选中

| Token | ARGB | 用途 |
| --- | --- | --- |
| `ACCENT` | `#FFFF8FB1` | 选中、主按钮、当前进度、关键数字 |
| `ACCENT_SOFT` | `#FFFFB3C8` | 选中文字、聚焦文字、浅色按钮字 |
| `ACCENT_PALE` | `#FFFFD6E3` | 大按钮渐变浅端、极弱的粉装饰 |
| `ACCENT_DEEP` | `#FFE96C96` | 按下/强调深端，少用 |
| `FOCUS_FILL` | `#1AFF8FB1` | 焦点底（10% 粉） |
| `FOCUS_STROKE` | `#FFFFB3C8` | 焦点 2dp 内描边 |

### 2.4 文字

| Token | ARGB | 用途 |
| --- | --- | --- |
| `TEXT_PRIMARY` | `#FFFFE3EC` | 标题、正文、默认字体颜色 |
| `TEXT_TITLE` | `#FFF7F1F4` | 大标题/需要更亮的主标题（少用） |
| `TEXT_SECONDARY` | `#FFD9BCC7` | 说明、次级信息、未选标签 |
| `TEXT_TERTIARY` | `#FFA98693` | 弱提示、占位、计数 |
| `TEXT_ON_ACCENT` | `#FF2B0F1B` | 粉底上的深色字/图标 |
| `TEXT_DISABLED` | `#66D9BCC7` | 禁用文字（40% 次文字） |

### 2.5 状态/播放器

| Token | ARGB | 用途 |
| --- | --- | --- |
| `SUCCESS` | `#FF7FD8A5` | 成功/已完成，低饱和暖薄荷 |
| `WARNING` | `#FFFFC98B` | 警告，暖杏 |
| `DANGER` | `#FFFF7A8A` | 危险/删除，暖珊瑚红 |
| `PLAYER_SCRIM` | `#E6000000` | 播放器底部/顶部遮罩 |
| `PLAYER_CHIP` | `#99000000` | 播放器中央胶囊/顶部按钮玻璃 |
| `PLAYER_SHEET` | `#F20C070C` | 播放器工具面板底 |
| `PLAYER_ICON` | `#FFF7F1F4` | 播放器图标、白字 |
| `PLAYER_TRACK` | `#33FFF4F7` | 进度条轨道/未选轨道 |

> 所有 token 只保留一个主粉、一个浅粉、一个深粉；**不再保留蓝色/青色强调 token**。`Skin.PINK_*` 与 `UiTokens.*` 合并为同一套，避免双份颜色定义。

---

## 3. 可配置项与限制

**只允许用户改这 4 组**：

| 配置 | 选项 | 默认 |
| --- | --- | --- |
| 卡片底色（预设套色） | 樱夜 `#3A1F2B`、墨夜 `#211A1F`、雾梅 `#332433`、咖啡 `#3A2A24` | 樱夜 |
| 卡片透明度 | 20 / 40 / 60 / 80% | 40% |
| 选中色（预设） | 樱花粉 `#FF8FB1`、珊瑚 `#FF7A8A`、杏桃 `#FFC98B` | 樱花粉 |
| 字体颜色 | 预设：樱白 `#FFE3EC`、暖白 `#FFF7F1F4`、柔灰 `#D9BCC7`、墨 `#2B0F1B`；可 HEX 自定义 | 樱白 |

- **不再允许**：任意卡片 HEX（当前 `glass_custom_color` 入口去掉）、任意选中 HEX（当前 `card_accent_color` 自定义去掉）、白色/蓝色/绿色预设（`white/black/accent/custom` 旧值迁移）。
- 旧配置迁移：
  - `glass_color=black` → 墨夜；`white` → 雾梅；`accent` → 樱夜；`custom` → 最接近的预设（默认樱夜）。
  - 旧 `card_accent_color` 任意值 → 最接近的 3 个选中色预设。
  - 旧默认字体 `#4CFF80` → 樱白 `#FFE3EC`；`Prefs.DEFAULT_TEXT_COLOR` 同步改。
- 其余颜色一律由 Token 决定：dock、筛选面板、播放器、焦点、按钮、状态色都不开放配置。

### 3.1 深色/浅色壁纸适配
- **深色壁纸（默认）**：卡片 `CARD_FILL` 40%；页面无 scrim；玻璃 `GLASS_FILL`；文字 `TEXT_PRIMARY/SECONDARY`。
- **浅色壁纸**：
  - 页面在内容层下加 `#990C070C` scrim（避免白壁纸冲淡文字）；
  - 卡片自动使用 `CARD_FILL_STRONG`（70%，`#B33A1F2B`）或用户透明度取 `max(用户值,70%)`；
  - 描边用 `CARD_STROKE_STRONG`；焦点粉底可提高到 `#26FF8FB1`；
  - 不把卡片改成白色/浅色，避免文字和粉色同时失真。
- 玩家/播放器始终黑底，不受壁纸明暗影响。

---

## 4. 组件映射与写死颜色替换清单

> “当前”只列代表性旧值；同一文件内相同旧值按组替换。行号是当前代码位置。

| 组件 | 位置 | 当前值 | 替换为 |
| --- | --- | --- | --- |
| 页面背景渐变 | `Skin.java:54-60` | `#0E1B31/#0B1220/#070D18` 蓝渐变 | `BG_TOP/BG/BG_DEEP` 暖梅渐变 |
| 右上光晕 | `Skin.java:99` | `#5AFF9DBB`（粉） | `ACCENT` 低透明 `#33FF8FB1` |
| 左下青蓝光 | `Skin.java:108` | `#338FC5FF`（蓝） | 删除；如保留改 `#1AFFD6E3` 极弱粉 |
| 通用卡片底 | `Skin.java:73-79` | `#551E3A5F` 描边/蓝底 | `CARD_FILL/CARD_STROKE` |
| 全局卡片底色 | `MainActivity.java:1490-1504` | `#000000/#FFFFFF/#2EE6A0/#1E3A5F` + alpha50 + 白描边 | 预设 4 色 + alpha 20/40/60/80 + `CARD_STROKE` |
| 全局主题注入 | `MainActivity.java:1470-1486` | `Skin.cardFill` + `GLOBAL_TINT` | 同上；启动/onResume 必须初始化 |
| 右侧 dock | `HomeDock.java:50` | `a.glass`（GlassworkSurface，易不跟色） | `a.cardBg(25*navScale)` 或启动注入 `GLOBAL_TINT` |
| 手机 dock | `MobileShell.java:904` | `a.cardBg(26)`（已跟卡片） | 保留；统一 `CARD_FILL` |
| 历史小卡 | `MobileShell.java:1232` | `a.cardBg(16)` | 保留 token |
| 今晚卡 | `MobileShell.java:1299` | `a.cardBg(16)` | 保留 token |
| 设置行 | `SettingsScreen.java:51` | `a.cardBg(12)` | 保留 token |
| 筛选面板 | `MobileShell.java:1477-1479` | `#1AFFFFFF` 底/描边 | `GLASS_FILL/GLASS_STROKE` |
| 筛选面板 TV | `HomeScreen.java:257` | `a.cardBg(16)` | 保留 |
| L2 未选文字 | `MobileShell.java:2586` | `#A9B7D0` | `TEXT_SECONDARY` |
| L2 粉下划线 | `MobileShell.java:2601` | `#FF8FB1` | `ACCENT` |
| L3 选中底 | `MobileShell.java:2713,2721-2722` | `#070D18/#FF8FB1/#66FFB3C8` | `TEXT_ON_ACCENT/ACCENT/ACCENT_SOFT` |
| 分类 chip 选中字 | `MobileShell.java:2883` | `#000000`/`#F3F4F6` | `TEXT_ON_ACCENT/TEXT_PRIMARY` |
| 分类 chip 渐变 | `MobileShell.java:2892` | `ACCENT→#FFB3C8` | `ACCENT→ACCENT_PALE` |
| 分类 chip 未选玻璃 | `MobileShell.java:2896` | `#CC172842/#CC182B4C` | `CARD_FILL` 或 `GLASS_FILL` |
| 分类漏斗 | `MobileShell.java:2902` | `ACCENT/#8D9CB6` | `ACCENT/TEXT_TERTIARY` |
| 详情 hero 底 | `MobileDetailScreen.java:724,726` | `#0D1A2B` + `#22FFFFFF` | `SURFACE_1 + CARD_STROKE` |
| 详情线路选中底 | `MobileDetailScreen.java:1236` | `(0x14<<24)|Skin.cardAccent` | `FOCUS_FILL + ACCENT` 规则；即 `0x1A`/`0x14` 可用 |
| 详情线路下划线 | `MobileDetailScreen.java:1256` | `Skin.cardAccent` | `ACCENT`（跟随选中色） |
| 详情集数选中 | `MobileDetailScreen.java:1518-1524` | `Skin.cardAccent` 底 + `#66FFB3C8` 描边 | 选中 `ACCENT` 底（或 40% tint）+ `TEXT_ON_ACCENT` + `ACCENT_SOFT` 描边 |
| 详情集数未选 | `MobileDetailScreen.java:1523-1524` | `#1AFFFFFF`/`#26FFFFFF` | `GLASS_FILL/GLASS_STROKE` |
| 详情季/集弹窗选中 | `MobileDetailScreen.java:1605,1710,1759` | 白色底/白字 | 选中 `ACCENT` 底 + `TEXT_ON_ACCENT`；未选 `GLASS_FILL` |
| 详情收藏粉字 | `MobileDetailScreen.java:559,888` | `#FF8FB1` | `ACCENT` |
| 详情主播放渐变 | `MobileDetailScreen.java:1014` | `#FFC7D6→#FF9AB5` | `ACCENT_PALE→ACCENT_SOFT`，字 `TEXT_ON_ACCENT` |
| TV 预览底 | `TvDetailLayout.java:451,453` | `#0D1A2B/#22FFFFFF` | `SURFACE_1/CARD_STROKE` |
| TV 线路选中 | `TvDetailLayout.java:559,566` | `Skin.cardAccent`/`#8D121F33` | `FOCUS_FILL`/`CARD_FILL` |
| TV 集数选中 | `TvDetailLayout.java:711,725` | `Skin.cardAccent`/`#FF8FB1` | `ACCENT/ACCENT_SOFT`，字 `TEXT_ON_ACCENT` |
| 播放器顶部/底部遮罩 | `PlayerChrome.java:85,91` | `#99000000→0`/`0→PLAYER_SCRIM` | `PLAYER_SCRIM/SCRIM` |
| 播放器手势胶囊 | `PlayerChrome.java:176` | `#B3000000` | `PLAYER_CHIP` 或 `SCRIM` |
| 播放器文字阴影 | `PlayerChrome.java:377,383` | `#CC000000` | `SHADOW` |
| 播放器 seek 气泡 | `PlayerChrome.java:438` | `#F2FFFFFF` + `#33000000` | `TEXT_TITLE` + `CARD_STROKE`（保持白底黑字可保留） |
| 播放器进度轨道/填充 | `PlayerChrome.java:1512,1513` | `#33FFFFFF`/`#FF8FB1` | `PLAYER_TRACK/ACCENT` |
| 播放器双色光晕 | `PlayerChrome.java:1486,1496` | 蓝 `#8FA8FF` + 粉 `#FFB3C8` | 只用粉：`ACCENT` + `ACCENT_PALE`；删蓝 |
| 字体颜色预设 | `TextColorDialog.java:24-26` | 绿/蓝/青/黄/紫 9 色 | 樱白/暖白/柔灰/墨 + 3 浅粉 + 2 暖梅（8–9 格） |
| 字体推荐组合 | `TextColorDialog.java:34` | `#4CFF80/#2B313B/#FFB3C8` | `#FFE3EC/#FFF7F1F4/#D9BCC7` |
| 字体预览底 | `TextColorDialog.java:100` | `#33000000` | `GLASS_FILL` |
| 字体色块焦点 | `TextColorDialog.java:145` | `#172742`（蓝） | `FOCUS_FILL/FOCUS_STROKE` |
| 卡片调色盘预设 | `CardColorDialog.java:24-26` | 绿/蓝/青/黄/紫 9 色 | 仅 4 个暖梅卡片套色 + 3 个选中色，不再有多余色 |
| 卡片推荐组合 | `CardColorDialog.java:34` | `#4CFF80/#2B313B/#FFB3C8` | `#3A1F2B/#211A1F/#FF8FB1` |
| 选中色按钮 | `CardColorDialog.java:195` | `#1AFFFFFF` 半透明底 | `GLASS_FILL` + 1dp `GLASS_STROKE` + `TEXT_PRIMARY` |
| 危险文字 | `MobileShell.java:4589,4620,4705,5138` | `#EF4444` 多个红 | `DANGER` 单色 |
| 直播分类标签色 | `MobileShell.java:4537-4555` | 蓝/红/琥珀/绿/紫多色 | **中性** `CARD_FILL + TEXT_SECONDARY`；只保留台标品牌色 |
| 成功/警告/错误 | `UiTokens.java:32-33` | `#2AD47A/#FF6B81` | `SUCCESS/WARNING/DANGER` 新值 |
| 旧 `Skin.PINK_SOFT` | `Skin.java:28` | `#33FFB3C8` | `ACCENT_TINT`（或 `FOCUS_FILL`） |

### 4.1 关键文件统一原则
- `Skin.java` 与 `UiTokens.java` 只能有一套颜色；`Skin` 的常量全部改为 `UiTokens` 别名，禁止再各定义一份。
- `MainActivity.cardBg` 只从“卡片预设 + 透明度”计算，不再识别 `black/white/accent/custom` 之外的旧分支；`#2EE6A0` 绿、`#1E3A5F` 蓝全部删除。
- `Skin.backdrop` 只保留暖梅渐变 + 单个粉光晕；去掉青蓝光。
- `GlassworkSurface` 只用于薄玻璃；dock 若要跟卡片色，改为 `cardBg` 或确保启动注入 `GLOBAL_TINT`。
- `MobileShell` 中所有 `glassBg(0xCC172842, 0xCC182B4C…)` 蓝玻璃批量替换成 `CARD_FILL` / `GLASS_FILL`；所有 `#FF8FB1` 改 `ACCENT`。
- 播放器是内容优先场景：只允许黑/白/粉三种，不允许蓝紫光晕、青蓝进度、绿色状态。

---

## 5. 樱花粉默认主题完整预设

### 5.1 深色壁纸（默认）
| 项 | 值 |
| --- | --- |
| 页面底 | `BG #140C12`，顶部 `BG_TOP #1B1118`，最深 `BG_DEEP #0C070C` |
| 卡片 | 樱夜 `#3A1F2B`，α **40%** → `#663A1F2B`；描边 `#33FFD6E3` |
| 选中/强调 | `#FF8FB1`；软粉 `#FFB3C8`；淡粉 `#FFD6E3` |
| 文字 | 主 `#FFE3EC`；次 `#D9BCC7`；弱 `#A98693`；粉底字 `#2B0F1B` |
| 焦点 | 底 `#1AFF8FB1` + 2dp `#FFB3C8` |
| 光晕 | 右上 `#33FF8FB1`；左下 `#1AFFD6E3` |
| 语义 | 成功 `#7FD8A5`；警告 `#FFC98B`；危险 `#FF7A8A` |

### 5.2 浅色壁纸适配
| 项 | 值 |
| --- | --- |
| 页面 scrim | 内容层下 `#990C070C` |
| 卡片 | 自动 `#B33A1F2B`（70%）；用户 alpha 低于 70 时实际不低于 70 |
| 描边 | `#66FFD6E3` |
| 文字/强调/焦点 | 同深色壁纸（保持暖白 + 粉），不改成黑字 |
| 播放器 | 不变（黑底） |

### 5.3 配置组合示例
- 默认：樱夜 + 40% + 樱花粉 + 樱白。
- 轻玻璃：樱夜 + 20% + 樱花粉 + 暖白（只适合深色壁纸）。
- 极深：墨夜 + 60% + 珊瑚 + 暖白。
- 浅壁纸：樱夜 + 70% + 杏桃 + 柔灰。

---

## 6. 验收清单（每页一张截图）

1. **首页**：背景暖梅黑，无蓝/青光；dock/历史/今晚卡同一暖梅玻璃底；粉色只出现在选中 tab、进度、重点按钮。
2. **详情**：hero 卡与页面底同族；标题暖白、说明灰粉；主播放键粉底深字；线路/集数选中为设定 accent，不再固定粉。
3. **选集弹窗**：当前集 = accent 底 + `TEXT_ON_ACCENT`；非当前 = `GLASS_FILL`；无白底、无绿色。
4. **播放器**：黑底、白图标、只有粉进度/粉激活；无蓝紫光晕、青蓝轨、绿色状态；控制胶囊一致。
5. **设置**：行卡同一暖梅底/描边；开关/选中粉；没有蓝玻璃、白卡、绿字混入。
6. **片库**：海报卡统一 `CARD_FILL`，角标中性，焦点框粉；筛选为薄玻璃。
7. **直播**：分类/频道/收藏只使用 accent 与中性色；台标品牌色仅限台标本身；不再有多色标签抢粉。
8. **调色盘**：只剩卡片 4 套色、选中色 3 套色、字体预设；预览为暖色系，无绿/蓝/青预设；主按钮与 Tab 对应。

**全局验收**：任意页面截图取色统计——粉色面积 ≤8%，蓝/青/绿不再作为强调色出现；卡片底色只有一套暖梅族；文字只有暖白/灰粉族；同一屏只有一个强调粉。

---

## 7. 实施顺序（供 dev）

1. 合并 `Skin`/`UiTokens` token，加入本表；改 `Prefs.DEFAULT_TEXT_COLOR` 为 `#FFE3EC`。
2. 改 `MainActivity.cardBg/applyGlassTint`、`Skin.backdrop/card`、`HomeDock`/`MobileShell` dock。
3. 按 §4 映射批量替换组件颜色（先详情/选集/播放器，再首页/设置/直播）。
4. 精简 `TextColorDialog`/`CardColorDialog` 的预设和入口，只留 4 项可配置。
5. 迁移旧 prefs 值到新 4 预设，旧任意 HEX 映射到最近色。
6. 按 §6 七页截图验收；用取色器检查主色、背景、卡片、焦点、文字。
