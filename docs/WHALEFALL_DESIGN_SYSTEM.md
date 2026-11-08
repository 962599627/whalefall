# 「鲸落」设计系统 · WhaleFall Design System

> 适用范围：`com.whalefall.movie` 全部界面（手机 / 平板 / TV 三种窗口档位）。
> 代码落地：`UiTokens.java`（token 与 drawable）、`UiIcon.java`（图标工厂）、
> `PlayerToolsSheet.java`（底部工具面板）、`PlayerChrome.java` / `PlaybackControls.java`（播放器）。
> 本文件是后续所有界面改动的唯一视觉依据；与旧文档冲突时以本文件为准。

---

## 0. 设计原则

1. **内容优先**：播放器/详情页的视频与海报是主角，chrome 只做「可读性保障」，不做抢戏的彩色玻璃。
2. **成熟组件优先**：进度条用 `androidx.media3.ui.DefaultTimeBar`（缓冲/拖拽/无障碍自带），
   图标用 vector drawable（官方 Material 图标 + 同风格补齐），底部面板用 MD3 bottom sheet 形态；
   **不再用 Canvas 手绘可被现成组件替代的控件**。
3. **一处定义，处处一致**：颜色/字号/间距/圆角/动效只允许引用 token，禁止在业务文件里散落魔数。
4. **克制**：强调色只有一个（樱花粉）；动效只表达状态变化；阴影只用于浮层。
5. **可访问性不是可选项**：对比度 ≥ 4.5:1、触控 ≥ 48dp、图标必有文案、状态不只靠颜色。

---

## 1. 颜色 token

### 1.1 语义色（`UiTokens` 常量）

| Token | 取值 | 用途 | 在 `#0B1220` 上的对比度 |
| --- | --- | --- | --- |
| `BG` | `#0B1220` | 页面底色 | — |
| `BG_DEEP` | `#070D18` | 页面底部/视频黑底（比纯黑偏蓝） | — |
| `SURFACE_1` | `#141C2A` | 卡片/播放器工具面板 | 文字 `TEXT_PRIMARY` 15.6:1 |
| `SURFACE_2` | `#1B2536` | 悬浮态/输入框 | 同上 |
| `TEXT_PRIMARY` | `#F5F7FC` | 标题、主信息、时间 | **17.3:1** |
| `TEXT_SECONDARY` | `#A9B7D0` | 副标题、说明、集名 | **9.1:1** |
| `TEXT_TERTIARY` | `#8D9CB6` | 弱提示（最低可用等级） | **6.7:1** |
| `ACCENT` | `#FF8FB1` | 樱花粉：选中、直播点、已播进度 | **8.7:1** |
| `ACCENT_SOFT` | `#FFB3C8` | 粉色高亮文字/进度条轨道 | **10.2:1** |
| `ACCENT_DEEP` | `#E96C96` | 粉渐变深端（渐变务必配深色文字） | 6.4:1 |
| `SUCCESS` | `#2AD47A` | 完成/成功 | 9.8:1 |
| `DANGER` | `#FF6B81` | 错误/删除 | 7.9:1 |
| `GLASS_FILL` | `#1AFFFFFF` | 玻璃面填充（仅置于深海蓝底上） | — |
| `STROKE` | `#33FFFFFF` | 面板描边 | — |
| `STROKE_SOFT` | `#1AFFFFFF` | 次要分割线 | — |

**播放器专用（内容优先）**：`PLAYER_ICON #F2F6FF`、`PLAYER_ACTIVE #FF8FB1`、
`PLAYER_SCRIM #E6000000`（底部渐变终点）、`PLAYER_BTN_FILL #66000000`（小圆钮底）、
`PLAYER_BTN_STRONG #CC0B1220`（中央播放钮）、`PLAYER_CHIP #99000000`（信息胶囊）、
`PLAYER_SHEET #F20B1220`（工具面板，近不透明）、`PLAYER_BUTTON_STROKE #40FFFFFF`、
`PLAYER_RIPPLE #33FFFFFF`。

### 1.2 使用规则

- 播放器 chrome 上**只允许**黑 / 白 / `ACCENT` 三色，禁止彩色玻璃叠在视频上（暖色画面会发脏）。
- 页面级玻璃只能铺在 `BG` 渐变或壁纸上，不能铺在视频上。
- 文字只允许三级（primary/secondary/tertiary），第四级不存在；低对比需求用「降字号」而不是降对比。
- 粉底上的文字必须是 `BG_DEEP` 或 `#0B1220`（8.7:1）；**禁止粉底白字**。

---

## 2. 字体 scale

| Token | 字号 (sp) | 字重 | 用途 |
| --- | --- | --- | --- |
| `TYPE_CLOCK` | 24 | medium + `tnum` | 播放器大号时钟 |
| `TYPE_TITLE` | 17 | bold | 播放器标题 / 面板标题 |
| `TYPE_TITLE_COMPACT` | 16 | bold | 手机竖屏标题 |
| `TYPE_BODY` | 15 | regular | 正文、时间码（配 `tnum`） |
| `TYPE_BODY_SM` | 13 | regular | 列表副信息、弹窗正文 |
| `TYPE_CAPTION` | 11.5 | regular | 集名/线路、说明、按钮下标签 |
| `TYPE_MICRO` | 10.5 | regular | 分辨率胶囊、角标 |

- 行高：正文 1.35–1.45；多行说明 1.45。所有数字信息（时间、时钟）启用 `tnum` 等宽数字，避免跳动。
- 大标题禁止用 `letterSpacing` 拉开（中文会被拆散）。
- 系统字号放大到 1.3× 时：标题单行省略、时间与图标重叠区域必须仍可读（播放器时间码用独立 TextView，不叠在轨道上）。

---

## 3. 间距（4/8 网格）

`SP_2=2  SP_4=4  SP_6=6  SP_8=8  SP_10=10  SP_12=12  SP_16=16  SP_20=20  SP_24=24  SP_32=32  SP_40=40  SP_48=48`

| 场景 | 值 |
| --- | --- |
| 页面左右边距（平板） | 24 |
| 页面左右边距（手机） | 16–18 |
| 卡片内边距 | 14–16 |
| 图标行左右边距 | 24 / 16 |
| 播放器控件组间距 | 8–12 |
| 列表行高 | 44–56（触控 ≥48） |
| Bottom sheet 左右内边距 | 12–20 |
| 标题与内容间距 | 8–12 |

---

## 4. 圆角 / 描边 / 阴影

| Token | 值 | 用途 |
| --- | --- | --- |
| `RADIUS_6` | 6dp | 时间码胶囊、小标签 |
| `RADIUS_10` | 10dp | 拖拽气泡 |
| `RADIUS_12` | 12dp | 图标行点击态 |
| `RADIUS_16` | 16dp | 信息胶囊、工具格 |
| `RADIUS_20` | 20dp | 播放器加载胶囊 |
| `RADIUS_24` | 24dp | Bottom sheet 顶角 |
| 999 | 胶囊 | 按钮、芯片、Dock |

- 描边统一 1dp：`STROKE`（面板）/ `STROKE_SOFT`（弱分割）；播放器圆钮 1dp `#40FFFFFF`。
- 阴影（elevation）：工具面板 16dp、中央播放钮 4dp、时钟块 3dp、加载胶囊 10dp；其余不用。

---

## 5. 动效

| Token | 时长 | 用途 |
| --- | --- | --- |
| `MOTION_FAST` | 120ms | 按压/焦点缩放、图标切换 |
| `MOTION_BASE` | 160ms | 控制条淡入 |
| `MOTION_SLOW` | 240ms | Bottom sheet 上滑 |

- 曲线：进入 `DecelerateInterpolator`，退出更快（140ms）。
- 播放器控制条自动隐藏：**3000ms**；暂停、弹层打开、拖拽进度时不隐藏。
- 系统开启「减少动画」时：跳过缩放/位移动画，只做瞬时切换（`MainActivity.reduced`）。

---

## 6. 图标规范

- 网格 24×24，视觉主体 18–20，填充式（Material Rounded 风格），单色白，按状态改 `ACCENT`。
- **必须带 contentDescription**；纯装饰图标不参与无障碍焦点。
- 来源与许可：
  - 官方 media3-ui 图标（play/pause/skip/rewind/ff/speed/subtitles/audio/settings/repeat/fullscreen/chevron）——
    通过 `androidx.media3.ui.R.drawable.exo_*` 直接引用，Apache-2.0。
  - 补齐图标 `res/drawable/ic_whale_*.xml`：back/more/episodes/swap/aspect/danmaku/info/close/replay/
    skip_outro/refresh，路径来自 Google Material Symbols（Apache-2.0）。
- 禁止 emoji 当图标；禁止位图图标；禁止把图标画进 Canvas。

---

## 7. 组件规格

### 7.1 播放器（`PlayerChrome` + `PlaybackControls`）

```
┌ 顶部 120dp 纯黑渐变 ─────────────────────────────────────────┐
│ (48)  标题 17sp bold / 集名·线路 11.5sp + [1080P] 胶囊   (48) │
└──────────────────────────────────────────────────────────────┘
                      中下 11.5sp 提示胶囊
              (48)  ⏪10   (72 ▶/⏸)   10⏩  (48)
┌ 底部 210dp 纯黑渐变 ─────────────────────────────────────────┐
│                                    [ 21:13  影片结束 22:46 ] │
│ 00:14 ───────────●───────────────────────────── 01:33       │
│  上集    下集    选集    倍速    换源    字幕    画面    弹幕    音轨    更多  │
└──────────────────────────────────────────────────────────────┘
右缘垂直居中：48dp 折叠钮（收起后只留进度条）
```

| 区域 | 规格 |
| --- | --- |
| 顶部遮罩 | 纯黑 `#99000000 → 透明`，高 120dp |
| 底部遮罩 | 透明 → `#E6000000`，高 210dp |
| 返回 / 更多 | 44–48dp 圆形，`#66000000` 底 + `#40FFFFFF` 描边 + 白色 24dp 图标 |
| 标题 | 17sp bold（手机 16sp），单行省略，8dp 黑影 |
| 副标题行 | 集名 · 线路 11.5sp `#A9B7D0`；分辨率胶囊 10.5sp 白、`#99000000` 底、6dp 圆角 |
| 中央播放簇 | 48dp 快退/快进圆钮（`#99000000`）+ 72dp 播放/暂停实心圆（`#CC0B1220`、4dp 阴影），间距 28dp；直播隐藏两侧 seek |
| 时钟块 | 24sp medium 白 + 11.5sp `#A9B7D0` 结束时间；`#99000000` 底、16dp 圆角、3dp 阴影；手机不显示 |
| 进度条 | `DefaultTimeBar`：轨道 6dp、触控高 48dp、已播 `#FFB3C8`、缓冲 `#B3FFFFFF`、未播 `#80FFFFFF`、拖拽点 16/22dp 白 |
| 时间码 | 15sp medium `tnum` 白 + `#66000000` 圆角 6dp 底；左当前 / 右总时长，**不压在轨道上** |
| 图标行 | 高 56dp；宽屏 10 格均分（每格 ≥48dp 触控），窄屏 48dp 横向滚动；白色 24dp 图标，无文字；激活项变 `ACCENT` |
| 折叠钮 | 48dp 圆钮，右缘垂直居中，距右 8dp；收起只留进度条 |
| 拖拽气泡 | 白底 `#F2FFFFFF` + 深色文字，10dp 圆角，在轨道上方跟随 |
| 加载态 | 居中 `#E60B1220` 胶囊 + 22dp `ProgressBar`（粉色）+ 13sp「正在加载 …」 |
| 更多面板 | 底部 sheet：`#F20B1220`、顶角 24dp、3 列网格、每格 76dp（24dp 图标 + 11.5sp 标签）、可滚动、点击遮罩关闭 |

**交互规则**：3s 自动隐藏；暂停/弹层/拖拽时不隐藏；点屏唤出；双击暂停、左右滑快进、上下滑亮度音量沿用原逻辑。

### 7.2 全局

| 组件 | 规格 |
| --- | --- |
| 悬浮 Dock | 胶囊 `#1AFFFFFF` + 1dp `STROKE`，图标 24dp + 10.5sp 标签，项高 ≥48dp |
| 列表行 | 高 44–56dp、左右 12–16dp、名称 14–15sp、副信息 11.5sp `TEXT_SECONDARY` |
| 芯片/胶囊 | 高 32–34dp、999 圆角、12sp；选中粉底深字，未选玻璃底浅字 |
| Bottom sheet | 贴底、顶角 24dp、`SURFACE_1` 近不透明、遮罩 `#7305070C`、拖拽把手 36×4dp |
| 主按钮 | 高 46–52dp、999 圆角、`ACCENT → ACCENT_SOFT` 渐变、深色文字、4dp 阴影 |
| 空状态 | 64dp 圆形粉玻璃徽标 + 标题 17sp + 说明 13sp + 主按钮 |

---

## 8. 可访问性（WCAG 2.2 AA）

1. 对比度：正文 ≥ 4.5:1（本表最低 6.7:1）；大字号 ≥ 3:1。
2. 触控：所有可点元素 ≥ 48×48dp（绝对下限 44），相邻目标间距 ≥ 8dp。
3. 图标按钮必须有 `contentDescription`（如「快退 10 秒」「更多工具」）。
4. 状态不只靠颜色：倍速/循环等激活态同时变色与在面板中标注；播放/暂停图标切换。
5. 屏幕阅读器：时间轴用 `DefaultTimeBar` 自带语义；弹层关闭后恢复焦点（播放器为触屏交互，无焦点陷阱）。
6. 字体缩放 ≥ 1.3× 不截断关键操作（标题允许省略）。
7. 动效可关闭（系统 reduced motion / App 内 reduce 设置）。

---

## 9. 落地映射

| 设计资产 | 代码 |
| --- | --- |
| 颜色/字号/间距/圆角/动效 | `UiTokens.java` |
| 图标（vector + 工厂 + 48dp 按钮 + ripple） | `UiIcon.java`、`res/drawable/ic_whale_*.xml`、`androidx.media3.ui.R.drawable.exo_*` |
| 进度条 | `res/layout/whale_time_bar.xml` + `PlaybackControls` |
| 播放器布局 | `PlayerChrome.java` |
| 播放功能/回调 | `PlaybackControls.java`（全部保留，包括选集/换源/倍速/画面/字幕/音轨/解码/片头尾/循环/重播/弹幕/全屏/播放信息） |
| 「更多」工具面板 | `PlayerToolsSheet.java` |
| 壁纸/背景 | `Skin.backdrop` + `MainActivity.installBackdrop` |

---

## 10. 后续（Phase B，未引入）

若后续需要 Material 组件（BottomSheetDialog / Chip / MaterialButton / Slider / SwitchMaterial），
需要新增 AAR：`com.google.android.material:material:1.12.0`、`androidx.appcompat:appcompat:1.7.0`
及其传递依赖（fragment/activity/savedstate/lifecycle/coordinatorlayout/cardview/drawerlayout/
viewpager2/dynamicanimation/appcompat-resources，并把 core 升到 1.13.x）。
同时 `styles.xml` 的 `AppTheme` 必须改为 `Theme.Material3.Dark.NoActionBar` 并补齐
`colorPrimary/colorSurface/colorOnSurface` 等属性，否则 Material 组件构造即崩。
本轮零新依赖：用平台控件 + 已有 AndroidX + vector drawable 达到同等观感。
