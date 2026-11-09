# 电视台直播 · 响应式布局规范（列表页 + 直播播放器）

> 适用范围：`TvLive.screen()`（频道列表/搜索/分组）与 `TvLive.Player`（WebView 直播播放页 + 控制条）。
> 视觉 token 唯一依据：`docs/WHALEFALL_DESIGN_SYSTEM.md`（下称「设计系统」）；本文件**不新增颜色**，
> 所有颜色只能引用设计系统 §1 的 token，字号只能落在 §2 的七档里。
> 本文只定义规范与目标值，不包含代码改动。

---

## 0. 结论先看

1. **设备形态只认 `MainActivity.formFactor`**（`TvLayoutPolicy.FORM_TV / FORM_TABLET / FORM_PHONE`），
   不要在 `TvLive` 里再用 `configuration.smallestScreenWidthDp` 二次判断——该值已被
   `TvLayoutPolicy.deviceDensityDpi()` 的密度归一化（手机短边≈390dp、平板短边≈640dp）改写过，
   首启/旋转时容易判成手机并强制竖屏，这是「平板横屏被锁竖屏、左右大空白」的根因之一。
2. **方向策略按设备形态定，布局按当前窗口宽度定**，两者分离：
   - 手机：列表锁竖屏，播放器锁 `SENSOR_LANDSCAPE`；
   - 平板：**任何页面都不强制方向**（`UNSPECIFIED`），列表竖屏 2 栏、横屏 3 栏自动重排；
   - TV：横屏固定（系统已在 `onCreate` 设置 `LANDSCAPE`）。
3. **断点只看窗口宽度 W（逻辑 dp）**：`<600` 单列 / `600–839` 两栏 / `840–1079` 三栏紧凑 /
   `≥1080` 三栏扩展 / TV 三栏 10-foot。由于密度归一化，平板竖屏 W≈640、横屏 W≈960，
   正好落在 600–839 与 840–1079 两档。
4. 播放器 chrome **不允许彩色玻璃压视频**（设计系统 §1.2）；直播播放页的顶栏/底栏现在用的
   `glassBg(0x730b1220…)` 需要改为纯黑系 token：`PLAYER_CHIP #99000000`、
   `PLAYER_BUTTON_STROKE #40FFFFFF`、`PLAYER_SHEET #F20B1220`。
5. 播放页「点画面切换控制条」目前有**两处重复处理**（`web.setOnTouchListener` 20dp 判定 +
   `TvWebView.dispatchTouchEvent` 12dp/320ms 判定），轻点可能被切换两次而看起来「没反应」。
   规范：只保留 `TvWebView.dispatchTouchEvent` 一处，统一阈值 **≤12dp / ≤320ms**。

---

## 1. 设备分类与方向策略

### 1.1 设备分类（唯一来源）

| 设备类 | 判定 | 逻辑画布（密度归一化后） | 触摸 | 遥控 |
| --- | --- | --- | --- | --- |
| `PHONE` 手机 | `a.formFactor == FORM_PHONE`（真实短边 <600dp） | 短边 390dp，竖屏约 390×844 | 是 | 无 |
| `TABLET` 平板 | `a.formFactor == FORM_TABLET` | 短边固定映射为 640dp；竖屏 640×~960，横屏约 960×640 | 是 | 无 |
| `TV` 电视 | `a.formFactor == FORM_TV`（`isTvDevice()`） | 960×540（`TvLayoutPolicy.densityDpi`） | 否 | 是 |

> 说明：`TvLive.screen()` 当前用 `configuration.smallestScreenWidthDp >= 600` 决定是否锁竖屏，
> 该值与 `a.formFactor` 可能不一致（高 DPI 平板首启被 `detectFormFactor` 判成手机时）。
> 规范：**方向/布局都改用 `a.formFactor`**；`TvLive` 不直接读 `smallestScreenWidthDp`。

### 1.2 方向策略（问题 1 的解法）

| 页面 | 手机 PHONE | 平板 TABLET | TV |
| --- | --- | --- | --- |
| 电视台列表 | `SCREEN_ORIENTATION_PORTRAIT` | **`UNSPECIFIED`（跟随传感器，绝不强制）** | `LANDSCAPE`（系统层已设，页面勿改） |
| 直播播放器 | `SENSOR_LANDSCAPE`（两向横屏） | **`UNSPECIFIED`，不强制转向**；竖屏时 16:9 画面居中、上下留黑 | `LANDSCAPE`（固定） |
| 退出播放器 | 恢复进入前的 `previousOrientation` | 无需恢复（本来未改） | 无需恢复 |

实现约束：

1. 进入直播 tab / 播放器时**只设置一次**方向；渲染（`page.refresh()` / `showChrome()`）里不得再调用
   `setRequestedOrientation`，避免旋转抖动。
2. 手机/平板在 `onConfigurationChanged` 后只重算 `layoutClass` 并重绘，不改方向。
3. 播放器关闭时：仅当本页改过方向（手机）才恢复；恢复值使用进入时保存的**常量**，
   并在 `overlay` 移除后 `post` 下一帧执行，避免与 Activity 重建竞争。
4. 平板横屏进入播放器：不请求方向、不重建 Activity；若系统因网页全屏回调重排，按 1.2 保持不转向。

---

## 2. 断点与布局总表

`W` = 当前窗口可用宽度（逻辑 dp，扣除系统栏之前，页面再扣安全边距）；`H` = 可用高度。
三档列表布局 + TV 档：

| 布局类 | 判定 | 典型场景 | 列表结构 | 侧边距 | 列宽（逻辑 dp） |
| --- | --- | --- | --- | --- | --- |
| `SINGLE` | `W < 600` | 手机竖屏 390 | 单列：分组 chips + 列表 | 16 | 内容 358 |
| `TWO_COL` | `600 ≤ W < 840` | 平板竖屏 640 | 左分类栏 + 列表（顶部当前频道卡） | 20 | 栏 132 · 间距 12 · 列表 456 |
| `THREE_COMPACT` | `840 ≤ W < 1080` | 平板横屏 960 | 分类栏 + 编号列表 + 右侧信息卡 | 20 | 栏 152 · 间距 12 · 列表 504 · 预览 240 |
| `THREE_EXPANDED` | `W ≥ 1080` | 大平板横屏 1080+ | 同上，加宽 | 24 | 栏 168 · 间距 16 · 列表 552 · 预览 280 |
| `TV` | `isTvDevice()` | 电视 960×540 | 三栏 + 遥控焦点 | 安全边 24 | 栏 176 · 间距 16 · 列表 404 · 预览 300 |

> 播放器内的「宽屏 chrome」（右侧信息面板）门槛同步从「W≥720」改为 **`W ≥ 840`**；
> 600–839 的平板竖屏播放器不显示右侧信息面板，画面比例/填充改由顶栏「画面」入口打开底部面板。

---

## 3. 断点规格：频道列表页

### 3.1 公共头部（所有断点）

| 元素 | 值 |
| --- | --- |
| 顶部留白 | `statusBarInset + 8dp`（TV：安全边 18dp） |
| 左右边距 | 手机 16 · 平板 20 · 大平板 24 · TV 24（overscan） |
| 标题 | 24sp bold（`TYPE_CLOCK` 档，全断点统一，不再出现 27/30）；TV 同 24sp + 1.25× 视觉行高 |
| 标题右侧「直播中」胶囊 | 高 26dp、圆点 6dp、文字 11.5sp `TEXT_PRIMARY`/`ACCENT_SOFT` |
| 副标题 | 11.5sp `TEXT_SECONDARY`，单行省略，行距 1.35；TV 13sp（`TYPE_BODY_SM`） |
| 搜索框 | 手机全宽×42dp · 平板 520dp×44dp · TV 480dp×48dp；圆角 16dp；内边距 10/6dp；图标 16dp |
| 搜索框与标题间距 | 8dp |
| 搜索框行下边距 | 6dp（手机 4dp） |
| 手机右上操作胶囊 | 高 34dp、13sp、间距 8dp；平板/大平板收进分类栏底部（现状）或更多菜单 |
| TV | 头部不参与焦点链；首焦点在列表当前行 |

### 3.2 `SINGLE`（手机竖屏，390dp）

| 项 | 值 |
| --- | --- |
| 内容区上边距 | 4dp |
| 内容区下边距 | **96dp**（给全局悬浮 Dock 让位；Dock 隐藏于播放页） |
| 分组 chips 带 | 高 42dp；chip 高 38dp、圆角 999、内边距 13dp、间距 8dp、13sp（`TYPE_BODY_SM`） |
| 列表行高 | **56dp**（触摸 ≥48） |
| 列表内边距 | 左右 5dp、底 10dp |
| 行内：编号 | 宽 30dp、13sp bold、`TEXT_TERTIARY`（选中 `ACCENT_SOFT`） |
| 行内：台标块 | 26×26dp、圆角 6dp、品牌渐变（唯一允许的非 token 色，仅台标） |
| 行内：名称 | 15sp（`TYPE_BODY`）、单行省略、左距 9dp |
| 行内：收藏 ♡ | 24×24dp、13sp；未收藏 `TEXT_TERTIARY` 60%，收藏 `ACCENT` |
| 行内：选中态 | `GLASS_FILL` 粉化：填充 `#2BFF8FB1` 不可用新色 → 用 `ACCENT` 20% 叠 `GLASS_FILL`；圆角 14dp；编号/名称为 `ACCENT_SOFT`/`TEXT_PRIMARY` |
| 行内：长按 | 收藏（Toast：已收藏/已取消收藏） |

### 3.3 `TWO_COL`（平板竖屏，640dp）

| 项 | 值 |
| --- | --- |
| 结构 | 左分类栏 132dp + 间距 12dp + 右列（当前频道卡 + 列表） |
| 分类栏内边距 | 9/12dp，圆角 22dp，`GLASS_FILL` + 1dp `STROKE` |
| 分类栏项 | 高 **48dp**、圆角 14dp、名称 13sp、数量 11.5sp、项间距 6dp；选中 `ACCENT → ACCENT_SOFT` 渐变、深色字 `BG_DEEP` |
| 分类栏底部动作 | 高 34dp 三胶囊（添加/导入/管理），11.5sp |
| 当前频道卡（替代右侧预览栏） | 高 **96dp**；台标 56×56dp 圆角 12dp；名称 17sp bold；meta 11.5sp `TEXT_SECONDARY`；右侧「观看」胶囊 88×36dp（`ACCENT` 渐变，深字） |
| 列表行高 | **52dp** |
| 列表头 | 高 36dp：分组名 15sp bold +「N 个」10.5sp + 提示 10.5sp |
| 列表内边距 | 左右 4dp、底 8dp |
| 行内：编号/台标 | 编号 28dp/13sp；台标 24×24dp 圆角 6dp |
| 行内：名称 | 15sp，左距 8dp |
| 行内：收藏 | 24×24dp |
| 内容区下边距 | 20dp（无 Dock 冲突风险；Dock 仍由全局控制） |

### 3.4 `THREE_COMPACT`（平板横屏，960dp）

| 项 | 值 |
| --- | --- |
| 结构 | 分类栏 152dp + 12dp + 列表 504dp + 12dp + 预览 240dp |
| 内容区 | 左右 20dp、上 8dp、下 20dp |
| 分类栏 | 同 3.3，内边距 9/12dp，项高 46dp |
| 列表行高 | **44dp**（平板触屏下限，不再用 40） |
| 列表头 | 高 36dp；分组名 15sp bold；计数胶囊 10.5sp |
| 行内：编号 | 宽 30dp、13sp |
| 行内：台标 | 26×26dp、圆角 6dp |
| 行内：名称 | 15sp（选中 15sp bold） |
| 行内：收藏 | 24×24dp；右侧留 8dp |
| 预览卡 | 宽 240dp、内边距 16dp、圆角 24dp、`GLASS_FILL` + 1dp `STROKE` |
| 预览卡：台标 hero | 高 **112dp**、圆角 16dp、居中台标字；右上「直播中」胶囊 10.5sp |
| 预览卡：名称 | 17sp bold（`TYPE_TITLE`）2 行省略；上距 12dp |
| 预览卡：meta | 11.5sp `ACCENT_SOFT`；上距 5dp |
| 预览卡：说明 | 11.5sp `TEXT_SECONDARY`，行距 1.45 |
| 预览卡：收藏 | 高 36dp 胶囊；距底 6dp |
| 预览卡：观看 | 高 42dp、`ACCENT → ACCENT_SOFT` 渐变、深色字、圆角 999 |

### 3.5 `THREE_EXPANDED` / `TV`

| 项 | 大平板（≥1080dp） | TV（960×540，10-foot） |
| --- | --- | --- |
| 边距 | 24dp | 安全边 24dp（水平）/ 18dp（垂直） |
| 分类栏 | 168dp，项高 48dp，名称 15sp | 176dp，项高 56dp，名称 17sp（`TYPE_TITLE`） |
| 列表行高 | 46dp | **64dp** |
| 列表名 | 15sp | 17sp bold；编号 15sp |
| 台标块 | 28×28dp 圆角 6dp | 32×32dp 圆角 6dp |
| 收藏 | 26×26dp | **44×44dp**（焦点可点） |
| 预览卡 | 280dp，hero 132dp，名称 17sp（`TYPE_TITLE`） | 300dp，hero 132dp，名称 24sp（`TYPE_CLOCK` bold） |
| 按钮 | 收藏 38dp / 观看 44dp | **收藏 48dp / 观看 52dp** |
| 间距 | 16dp（列/行） | 16dp（列/行） |
| 焦点 | 触屏，无焦点框 | **焦点框 2dp `ACCENT` + 外发光 4dp（`ACCENT` 20%）；缩放 1.02×** |

---

## 4. 断点规格：直播播放器

### 4.1 播放器画布与方向

| 断点 | 画布 | 方向 | 画面 | 布局要点 |
| --- | --- | --- | --- | --- |
| 手机 | 844×390（横屏，锁 `SENSOR_LANDSCAPE`） | 强制横屏 | WebView 全屏 | 顶栏 + 底部频道面板；无右侧信息面板 |
| 平板竖屏 | 640×960 | **不强制** | 16:9 居中，上下各留黑（约 300dp 上下） | 顶栏 + 底部面板；信息面板隐藏 |
| 平板横屏 | 960×640 | 不强制 | WebView 全屏（16:9 上下各 50dp 黑边由网页处理） | 顶栏 + 右侧信息面板 184dp + 底部面板 |
| TV | 960×540 | 固定横屏 | WebView 全屏 | 安全边 24/18；顶栏 56dp；遥控焦点 |

### 4.2 chrome 几何（安全区规则，问题 3）

安全边定义：

```
topInset    = max(statusBarInset, displayCutout.top, TV 18dp) + 8dp
bottomInset = max(navigationBarInset, gestureInset 24dp, 16dp)
sideInset   = 10dp + max(gestureInset.left/right 24dp → 12dp 兜底)
```

| 区域 | 手机 | 平板竖屏 | 平板横屏 | TV |
| --- | --- | --- | --- | --- |
| 顶栏 | 高 48dp，左右 10dp，top = topInset | 同 | 同 | 高 56dp，左右 24dp，top = 18dp |
| 顶栏返回钮 | 44dp 圆钮（`PLAYER_BTN_FILL #66000000` + 1dp `PLAYER_BUTTON_STROKE`） | 同 | 同 | 48dp 圆钮，焦点框 |
| 顶栏标题 | 16sp（`TYPE_TITLE_COMPACT`），单行省略 | 17sp | 17sp | 17sp + 1.25 行高 |
| 顶栏副行 | 11.5sp `TEXT_SECONDARY` | 同 | 同 | 13sp |
| 顶栏按钮（频道/刷新/全屏） | 32dp 胶囊、11.5sp、间距 6dp | 同 | 同 | 40dp 胶囊、13sp |
| 右侧信息面板 | 不显示 | 不显示 | **184×~300dp**，top = topInset + 62dp，右 10dp | 240×~340dp，top 72dp，右 24dp |
| 底部频道面板（收起把手） | 高 44dp，左右 10dp，bottom = bottomInset + 8dp | 同 | 同 | 高 52dp，左右 24dp，bottom = 26dp |
| 底部频道面板（展开） | 最大高 152dp（头 28 + chips 32 + 内边距 20 + 间距） | 最大 168dp | 最大 168dp | 最大 180dp |
| 面板圆角/底色 | 圆角 22dp；`PLAYER_SHEET #F20B1220` | 同 | 同 | 圆角 20dp；同 |
| 面板内边距 | 10/9/10/10dp | 同 | 12/10/12/12dp | 16dp |
| 频道 chips | 高 32dp、11.5sp、间距 7dp、最大名宽 120dp | 同 | 同 | 高 40dp、13sp、间距 10dp、最大名宽 160dp |
| 数字键提示 | 10.5sp `TEXT_TERTIARY`，仅手机/平板 | 同 | 同 | 13sp，另加遥控提示 |
| 加载卡 | 内边距 24/18dp、圆角 22dp、`PLAYER_SHEET`、22dp 粉环 | 同 | 同 | 圆角 20dp、26dp 粉环 |

### 4.3 系统栏 / 网页自带控件 / App Dock 的叠层规则

1. **系统栏**
   - 手机播放器：进入即进入沉浸式（隐藏状态栏+导航栏，`applyMobileSystemBars(true)`），
     边缘手势条区域保留 `bottomInset = 24dp`；上滑唤出系统栏时 chrome 上移避让，恢复后再回。
   - 平板播放器：不隐藏系统栏，edge-to-edge + 透明状态栏；顶栏 `top = statusBar + 8`，底栏避让导航栏。
   - TV：系统无状态栏/导航栏；只保留 5% overscan（≈18dp 垂直 / 24dp 水平）。
2. **网页自带控件**（CCTV/芒果等网页播放器自绘）：我们的底部频道面板**默认收起**为 44dp 把手，
   不长期遮挡；展开时面板用近不透明 `PLAYER_SHEET` 覆盖在网页控件之上（一次性，收起即恢复），
   展开期间暂停 3s 自动淡出；面板收起后恢复计时。
3. **App Dock**：进入播放器前 `MobileShell.setDockVisible(false)`（现状正确），退出恢复；
   列表页 Dock 只在 `W < 1200` 的触屏设备显示（`TvLayoutPolicy.useBottomDock` 现状），
   平板横屏 960dp 时底部留 20dp 即可，不留 96dp。
4. **全屏冲突**：网页触发原生全屏（`onShowCustomView`）时，隐藏我们的全部 chrome（含顶栏、
   信息面板、底部面板、加载卡）；`onHideCustomView` 恢复。TV 遥控 Back 先退网页全屏，再退播放页。

### 4.4 点击 / 显隐规则（问题 3 的核心）

| 事件 | 规则 |
| --- | --- |
| 轻点画面（tap） | 位移 ≤12dp 且时长 ≤320ms：chrome 显示 ↔ 隐藏（只切换一次） |
| 双击 | 直播无暂停语义：不处理，第二次 tap 视为一次 tap 的重复（320ms 窗口内合并） |
| 垂直滑 | `dy > 30dp 且 dy > 1.5×dx`：左半屏亮度、右半屏音量；滑动期间不切换 chrome |
| 水平滑 | 直播不 seek；忽略（交给网页横向滚动） |
| 点 chrome 区域 | 消费事件，不穿透 WebView；`topBar/bottomPanel` 均可点击但只做 `showChrome()` |
| 自动淡出 | 3000ms；展开面板/出现焦点/正在滑动时不淡出；暂停（无直播语义）不参与 |
| 网页原生控件 | 不拦截；我们的 chrome 淡出后网页控件独占操作 |
| 加载中 | chrome 仍可点，但「频道/刷新/全屏」置灰（`TEXT_TERTIARY`），加载完成后恢复 |

> 实现提示（目标态）：删除 `web.setOnTouchListener` 的 tap 判定，只保留
> `TvWebView.dispatchTouchEvent` 中 `tapDx<12dp && tapDy<12dp && tapMs<320ms` 的分支；
> `showChrome()` 里若 `panelOpen` 为 true，则不排 `hideChromeRunnable`。

---

## 5. 交互分配表（问题 4）

| 操作 | 手机触屏 | 平板触屏 | TV 遥控 |
| --- | --- | --- | --- |
| 打开频道 | 轻点行 / 「观看直播」 | 同 | 焦点移到行 + OK；列表首焦点 = 上次频道 |
| 换台（上/下一个） | 底部 chip 条横向滚动后点选；数字键 1–9 | 同 | **CH+ / CH- 或方向键上/下**（列表焦点上下文） |
| 换台（跳转） | 数字键 1–9（≥10 台映射 1–9 前 9 个） | 同 | 数字键 1–9 |
| 分组切换 | 分组 chips 点击 | 左栏点击 | 左栏焦点 + OK |
| 收藏 | 长按行 / 行尾 ♡ | 同 | 长按 OK 收藏（1.2s 长按，Toast 反馈）；或焦点到 ♡ + OK |
| 搜索 | 点搜索框弹软键盘 | 同 | TV 不提供搜索（隐藏搜索框，改「首字母遥控键盘」二期） |
| 唤出/隐藏 chrome | 轻点画面 | 同 | OK / MENU |
| 亮度 | 左半屏上下滑 | 同 | 系统设置（播放器不接管） |
| 音量 | 右半屏上下滑 | 同 | 遥控音量键（系统音量） |
| 频道面板 | 点「频道」或底部把手 | 同 | OK 唤出 chrome 后焦点到「频道」 |
| 全屏（网页） | 顶栏「全屏」 | 同 | 自动全屏，无需按钮；Back 退出 |
| 返回 | 顶栏 ‹ / 系统返回（先退网页 history） | 同 | Back（先网页全屏 → 再 history → 再退出） |
| 焦点移动 | 无 | 无 | 方向键；列表内上下、栏内左右、顶栏左右；焦点框 2dp ACCENT |
| 快速设置 | 顶栏「刷新」 | 同 | 顶栏聚焦后 OK |

TV 额外规则：

1. 进入播放页焦点默认给「频道」按钮（非视频区），避免遥控器无处可去。
2. chrome 可见时方向键在 chrome 内移动；chrome 隐藏时方向键直接换台/唤出。
3. 焦点在 chrome 内时**禁止自动淡出**；焦点离开且 5s 无操作才淡出。
4. 任何焦点态必须同时有 2dp `ACCENT` 边框 + 1.02× 缩放（不能只靠颜色）。

---

## 6. Token 对照表（禁止新增颜色）

### 6.1 颜色映射

| 用途 / 代码现值 | 规范 token | 规范值 | 说明 |
| --- | --- | --- | --- |
| 页面底色 `Skin.backdrop` | `BG` / `BG_DEEP` | `#0B1220` / `#070D18` | 保持现状 |
| 玻璃填充 `0x1affffff` / `0x14ffffff` / `0x10ffffff` | `GLASS_FILL` | `#1AFFFFFF` | 统一 10% 白 |
| 描边 `0x2effffff` / `0x24ffffff` / `0x1fffffff` | `STROKE` / `STROKE_SOFT` | `#33FFFFFF` / `#1AFFFFFF` | 面板用 `STROKE`，分割线用 `STROKE_SOFT` |
| 主文字 `0xfff5f7fc` / `0xfff7fbff` | `TEXT_PRIMARY` | `#F5F7FC` | |
| 次文字 `0xffa9b7d0` / `0xff9aa9c0` / `0xffc9d6ea` / `0xffd6e1f0` | `TEXT_SECONDARY` | `#A9B7D0` | 现值有 4 个近似色，统一 |
| 弱文字 `0xff8d9cb6` / `0xff7f8fa9` / `0xff6f7f99` | `TEXT_TERTIARY` | `#8D9CB6` | `#7F8FA9`、`#6F7F99` 低于最低可用等级，禁用 |
| 强调 `0xffff8fb1` / 直播点 `0xffff6f96` | `ACCENT` | `#FF8FB1` | 直播点也用 ACCENT，不另造粉 |
| 强调浅 `0xffffb3c8` | `ACCENT_SOFT` | `#FFB3C8` | |
| 粉底深字 `0xff31141f` | `BG_DEEP` / `BG` | `#070D18` / `#0B1220` | 设计系统 §1.2：粉底禁止白字，用深蓝黑 |
| 播放器顶栏/底栏 `glassBg(0x730b1220…)`、`0x8c0b1220` | `PLAYER_CHIP` / `PLAYER_SHEET` | `#99000000` / `#F20B1220` | **禁止彩色玻璃压视频**；页面级玻璃不得进播放器 |
| 播放器圆钮底 `0x33ffffff` | `PLAYER_BTN_FILL` | `#66000000` | 小圆钮统一 |
| 播放器描边 `0x4dffffff` / `0x40ffffff` | `PLAYER_BUTTON_STROKE` | `#40FFFFFF` | |
| 台标品牌色 | 频道品牌色（例外） | 各频道 | 仅台标块允许，不进正文/按钮 |

### 6.2 字号 / 间距 / 圆角 / 动效

| 用途 | 规范 token | 值 |
| --- | --- | --- |
| 页面标题（列表页） | `TYPE_CLOCK` | 24sp bold（三端统一） |
| 播放器标题 | `TYPE_TITLE` / 手机 `TYPE_TITLE_COMPACT` | 17sp / 16sp |
| 列表行名称 | `TYPE_BODY`（TV `TYPE_TITLE`） | 15sp（TV 17sp） |
| 正文/说明/搜索 | `TYPE_BODY_SM` | 13sp |
| 副标题/meta/chip | `TYPE_CAPTION` | 11.5sp |
| 计数/小标签 | `TYPE_MICRO` | 10.5sp |
| 页面边距 | `SP_16 / SP_20 / SP_24` | 16/20/24 |
| 列间距 | `SP_12 / SP_16` | 12（≤839）/16（≥840） |
| 列表行高 | — | 56（手机）/52（平板竖）/44（平板横）/46（大平板）/64（TV） |
| 圆角 | `RADIUS_24/20/16/12/6/999` | 面板 24、卡 22→24、行选中 14→12、台标 6、chip 999 |
| 动效 | `MOTION_FAST/BASE/SLOW` | 120/160/240ms；自动淡出 3000ms（TV 5000ms） |
| 阴影 | 设计系统 §4 | 面板 16dp、加载卡 10dp、其余不用 |

---

## 7. 各断点验收清单

### 7.1 手机（PHONE，390×844，竖屏列表 / 844×390 播放）

- [ ] 竖屏进入电视台：单列、标题 24sp、搜索框高 42、列表行 56；横向无裁切，最后一行不被 Dock 遮挡（底部留 96dp）。
- [ ] 点频道进播放器：立即横屏（844×390），顶栏在状态栏下 8dp、底栏在导航/手势区上 8dp，看不到系统栏压字。
- [ ] 退出播放器：列表恢复竖屏且滚动位置/当前频道不变；连续进出 3 次方向不抖动。
- [ ] 轻点画面：chrome 只切换一次（120ms 内不出现二次淡入）；垂直滑左半屏亮度、右半屏音量，滑动过程不误触 chrome。
- [ ] 展开频道面板后 3s 不自动淡出；收起后恢复计时；网页原生全屏时我们的 chrome 全部隐藏。

### 7.2 平板竖屏（TABLET，640×960，两栏）

- [ ] 只有 2 列（分类栏 132 + 列表 456），无第三栏挤压；当前频道卡高 96、行高 52。
- [ ] 旋转到横屏：自动变 3 列（152/504/240），横屏转回竖屏再变 2 列；两个方向都不丢滚动位置与选中频道。
- [ ] 进入直播 tab/播放器**不强制转向**：从横屏切 tab 仍是横屏；从竖屏进播放器仍是竖屏，画面 16:9 居中、上下黑边对称。
- [ ] 预览/当前频道卡在 640dp 下不换行、不截断按钮；长按收藏与 ♡ 收藏状态同步。

### 7.3 平板横屏（TABLET，960×640，三栏紧凑）

- [ ] 三栏宽度 152/504/240，间距 12；预览 hero 112、观看按钮 42、收藏 36，卡片内容不溢出。
- [ ] 列表行高 44（可点区 ≥44），编号 13sp、名称 15sp，长按收藏可用；选中态粉块 + `ACCENT_SOFT` 文字。
- [ ] 播放器右侧信息面板 184dp 出现且不遮网页原生控件；底部面板距底 = 16 + inset；收起后只留 44dp 把手。
- [ ] 顶部/底部 chrome 用纯黑 token（`PLAYER_CHIP`/`PLAYER_SHEET`），亮舞台上无彩色玻璃发脏。

### 7.4 大平板横屏（THREE_EXPANDED，≥1080dp）

- [ ] 三栏 168/552/280、边距 24、间距 16；行高 46，预览卡 hero 132。
- [ ] 内容最大宽度不超过 1200dp（避免超宽空）；列表不被拉伸到 >560dp；预览卡不被压扁。
- [ ] 触控目标 ≥48dp（收藏 26 + 行 46 的可点区域按行整体算）；文字放大到 1.3× 仍不破版。

### 7.5 TV（960×540，10-foot）

- [ ] 三栏 176/404/300，安全边 24/18；行高 64、名称 17sp；首焦点 = 当前频道行，焦点框 2dp ACCENT 始终可见。
- [ ] 遥控：上下移动列表、左右进/出分类栏、OK 播放、长按 OK 收藏、Back 退出（网页全屏时先退全屏）。
- [ ] 播放页 OK/MENU 唤出 chrome，5s 无操作淡出；焦点在 chrome 内时不淡出；频道面板展开时仍可用方向键选台。
- [ ] 数字键 1–9 换台；换台后顶栏名称/台标/列表焦点三者一致。
- [ ] 所有遥控可点元素 ≥48dp；任何焦点态不只靠颜色（边框 + 缩放/描边）。

---

## 8. 建议的 View 层级（伪代码）

### 8.1 列表页

```
FrameLayout root                               // 背景 Skin.backdrop
└─ LinearLayout shell (vertical)
   ├─ LinearLayout header (vertical, wrap)     // 标题 / 副标题 / 搜索
   │  ├─ LinearLayout titleRow
   │  ├─ LinearLayout subRow
   │  └─ LinearLayout searchRow
   └─ FrameLayout contentSlot (height=0, weight=1)   // 每次 refresh 清空重建
      ├─ SINGLE:
      │  └─ LinearLayout column
      │     ├─ HorizontalScrollView chipScroll (h=42)
      │     └─ RecyclerView(weight=1, rowH=56)
      ├─ TWO_COL:
      │  └─ LinearLayout row
      │     ├─ LinearLayout rail            (w=132)
      │     └─ LinearLayout column (weight=1)
      │        ├─ LinearLayout currentCard  (h=96)
      │        └─ RecyclerView(weight=1, rowH=52)
      ├─ THREE_COMPACT / THREE_EXPANDED / TV:
      │  └─ LinearLayout row
      │     ├─ LinearLayout rail            (w=152/168/176)
      │     ├─ View gap (12/16)
      │     ├─ LinearLayout column (weight=1)
      │     │  ├─ LinearLayout listHead     (h=36)
      │     │  └─ RecyclerView(weight=1, rowH=44/46/64)
      │     ├─ View gap (12/16)
      │     └─ LinearLayout preview         (w=240/280/300)
      └─ 空状态 LinearLayout (weight=1)
```

要点：

- 列表一律 `RecyclerView` + `LinearLayoutManager`；不要用 `ScrollView + addView`（TV 焦点/回收）。
- 行 ViewHolder 复用 `channelRow()` 的视觉，但 TV 在 `onBindViewHolder` 里设置
  `setFocusable(true)`、`OnFocusChangeListener`（2dp ACCENT 边框 + 1.02 缩放）。
- 分类栏/当前频道卡/预览卡是辅助区：TV 也参与焦点链（rail 左右、列表上下）。
- 断点切换时只重建 `contentSlot`，保留 `page.group/query/selected` 与回收器滚动位置。

### 8.2 播放器

```
FrameLayout overlay (focusable, black)
├─ TvWebView (-1,-1)                          // 网页播放，不消费 tap/竖滑
├─ LinearLayout loadingCard (center)
└─ FrameLayout chrome
   ├─ LinearLayout topBar            // top = topInset; h=48/56
   │  ├─ back(44/48) + brandMini(40/48) + titleCol(weight)
   │  └─ 频道 / 刷新 / 全屏 胶囊（TV 40dp）
   ├─ LinearLayout infoPanel         // 仅 W≥840：w=184/240, top=topInset+62
   ├─ FrameLayout bottomWrap         // bottom = bottomInset + 8
   │  ├─ LinearLayout channelSheet   // GONE 默认；展开 maxH=152/168/180
   │  │  ├─ head: groupStrip + 提示 + 收起
   │  │  └─ HorizontalScrollView: channelStrip
   │  └─ View handle (40×4dp, radius 999, STROKE_SOFT)  // 收起态提示
   └─ (无独立 tapCatcher：点击由 TvWebView.dispatchTouchEvent 统一判定)
```

状态机：

```java
enum ChromeState { HIDDEN, SHOWN, SHEET_OPEN, WEB_FULLSCREEN }

// 方向（进入/退出各一次）
switch (device) {
  case PHONE:  setRequestedOrientation(player ? SENSOR_LANDSCAPE : PORTRAIT); break;
  case TABLET: setRequestedOrientation(UNSPECIFIED); break;      // 永不强制
  case TV:     /* 系统已 LANDSCAPE，勿改 */ break;
}

// 布局（随 W 变化）
layout = W < 600 ? SINGLE
       : W < 840 ? TWO_COL_A
       : W < 1080 ? THREE_COMPACT
       : THREE_EXPANDED;

// chrome 显隐
onTap() {
  if (sheetOpen) { closeSheet(); return; }       // 面板展开时先收面板
  if (chromeShown) fadeOutChrome(260); else showChrome(240);
}
showChrome() {
  cancelHide();
  if (!sheetOpen && !tvFocusInsideChrome()) postDelayed(hide, 3000);   // TV 5000
}
```

---

## 9. 落地改动清单（供后续实现任务，非本轮）

| 位置 | 现状 | 规范 |
| --- | --- | --- |
| `TvLive.screen()`（方向） | `configuration.smallestScreenWidthDp >= 600 ? UNSPECIFIED : PORTRAIT` | 改用 `a.formFactor`：PHONE→PORTRAIT，TABLET→UNSPECIFIED，TV→不改；只设置一次 |
| `TvLive.screen()`（断点） | `windowDp >= 720` 两值布局 | 四档：`<600 / 600–839 / 840–1079 / ≥1080`（+TV） |
| `renderTvPage()` | 只有 `wide` 布尔 | `layoutClass` 枚举；T1 加当前频道卡，T2 才三栏 |
| `channelRow` 行高 | `wide ? 40 : 56` | 56/52/44/46/64 按断点 |
| `Player.show()`（方向） | 所有设备强制 `SENSOR_LANDSCAPE` | PHONE 才强制；TABLET 不强制；TV 不改 |
| `Player` 信息面板 | `windowDp >= 720` | `W ≥ 840`；宽度 184/240 |
| `Player` 顶/底栏颜色 | `glassBg(0x730b1220…)`、`0x8c0b1220` | `PLAYER_CHIP`/`PLAYER_SHEET` + `PLAYER_BUTTON_STROKE` |
| `Player` tap 判定 | `setOnTouchListener`(20dp) + `dispatchTouchEvent`(12dp/320ms) 双份 | 只保留 `dispatchTouchEvent` 一处 |
| `Player` 面板展开 | 展开后仍会 3s 淡出 | `panelOpen` 时不排 hide，收起后恢复 |
| `Player` 全屏回调 | 只隐藏 topBar | 隐藏全部 chrome，`onHideCustomView` 恢复 |
| `TvLayoutPolicy` | 新增密度归一化目标 | 保持不变；**不要再新增一套 dp 换算** |
