# 首页海报网格虚拟化（E3-S1）架构评审

| 项 | 内容 |
|---|---|
| 评审对象 | task-1「首页海报网格改 RecyclerView 虚拟化」：`android/src/main/java/com/whalefall/movie/MobileShell.java`（+ 必要的 `MainActivity.smoothHomeScroll`/滚动恢复兼容） |
| 关联需求 | E3-S1（绑定数=可见区+预取）、E3-S2（分页与状态保持）、E3-S3（测量口径），见 `docs/PRODUCT_BACKLOG.md` |
| 评审角色 | architect（技术负责人），复审人 Lead |
| 基线 | 分支 `whalefall-rebrand`，**改造前**工作树（`MobileShell.java` 4610 行） |
| 文档状态 | §1–§3 = 评审口径，先交付给 Lead/开发对齐；§4 = 开发完成后的逐条复核结论（待 Lead 通知后追写） |
| 行号口径 | 本文 §1–§3 行号取自 2026-11-09 21:12–21:20 读取的**改造前基线**。读取期间 task-1 正在改同一文件（4610 → 4807 行），**§4 复核一律以最终 diff 行为准并逐条重钉行号**；§1–§3 以函数名为第一锚点，行号仅作基线定位。 |
| 上游设计 | `docs/ARCH_NOTES_1.2.2.md` §3（实施要点/目标结构/DiffUtil 契约/滚动恢复三条路径/验收清单） |

---

## 1. 现状与目标

### 1.1 现状一句话与问题本质

`MobileShell.renderHomeTab()` 用 **`ScrollView` + `LinearLayout body` 全量构图**：首屏成功后 `fetchCategoryPage` 回调把 `currentVideos` 交给 `renderVideoGrid()`，按 `i % cols` 逐行 `addView` 每张海报卡（每卡含 `FrameLayout`/`ImageView`/2 个 `TextView`，并逐个 `a.poster()` 提交取图任务）；「加载更多」翻页继续 `addView`，数据到 120 条时 **120 张卡全部实例化并常驻视图树**。

问题不是"View 个数"本身，而是四个 O(N) 成本随数据线性放大：

1. **遍历成本**：任何一次 measure/layout/draw（首帧、滚动、筛选浮层开合、Dock 动画）都遍历全部 N 张卡；ScrollView 不做可见性裁剪，卡片全部参与布局。
2. **首帧成本**：首帧就创建 N 套 View 并触发 N 次图片请求 → 解码 → LruCache（10MB）/ImageView 绑定，主线程与 artwork 线程池同时被拉满，低端机首帧卡顿、快滚白块。
3. **内存成本**：N 套 View 常驻 + 每张海报一个 Bitmap 引用，LRU 被 120 张轮换冲刷，返回/再进入时反复解码。
4. **无扩展性**：分页只是"继续堆 View"；数据量或列数（3/4/5 档）变化时成本同比例上升。

**目标（可验收形态）**：首页改为 RecyclerView 虚拟化——prelude（品牌行/历史 shelf/hero/今晚看什么/分类条/状态文案）、影片行、页脚（加载更多）分属不同 item；**只为可见区 + 预取窗口创建并绑定卡片**；滚动位置（切 tab / 详情返回 / 进程重建）、分页状态机、筛选、空/错误态、点击与视觉与现状一致；TV 路径（`HomeScreen`/`TvLive`）零回归。

### 1.2 基线证据索引（改造前，供复核对照 diff）

| # | 行为 | 基线位置 |
|---|---|---|
| 1 | 首页容器创建、`a.scroll` 赋值、滚动恢复 | `MobileShell.renderHomeTab()` 基线 `:810`；`new ScrollView` `:816`；`restoreScroll = pendingHomeScroll>=0 ? pendingHomeScroll : homeScrollY` `:818-820`；`a.scroll = scrollView` `:825` |
| 2 | body 内边距（状态栏 + 底部筛选条/Dock 让位） | `:846-854`：`body.setPadding(0, statusBarHeight()+8, 0, filterBarBottomDp+HEIGHT+18)`，`scrollView.addView(body)` |
| 3 | 历史 shelf（继续观看，横滑卡片） | `:860-962`（`home-history:i` tag、`a.resumeHistory`） |
| 4 | hero「今日首推」容器 | `:965-971`（`heroContainer`，默认 GONE，数据到达后 `removeAllViews`+`addView`） |
| 5 | 「今晚看什么」随机三部 / 换一批 | `:974-1056`（`tonightCard`、`refreshTonight[0]`） |
| 6 | 分类条 + 筛选按钮 | `:1058-1097`；chip 点击与重复点开筛选 `:1726-1754` |
| 7 | 状态文案（加载中/空态/错误/筛选无匹配） | `:1100-1103`；写入点 `:1375-1384`、`:1448-1451` |
| 8 | 网格容器 | `:1105-1108`（`gridContainer`，改造后删除） |
| 9 | 加载更多按钮三态 | `:1110-1125`；文案/可见性 `:1136-1137`、`:1419-1429`；点击 `:1457-1462`；失败「点击重试」`:1448-1451` |
| 10 | 分页状态机 | `currentPage/totalPages/loadingPage/pageFailed/completedPage` `:1127-1129`；成功回调 `:1345-1430`；`currentVideos` 清空/追加/过滤 `action` `:1368-1374` |
| 11 | 自动加载下一页 | `:1464-1480`（`ViewTreeObserver.addOnScrollChangedListener`，`child.getBottom()-(height+scrollY) <= 240dp` 时 `currentPage++`） |
| 12 | 全量卡片构建（问题核心） | `renderVideoGrid()` `:1761-1862`；卡片循环 `:1770-1850`；cell 边距 `:1843-1849`；末行空白补齐 `:1851-1861` |
| 13 | 切 tab 保存滚动像素 | `switchTab()` `:738-790`；`homeScrollY = homeScroll.getScrollY()` `:742-743` |
| 14 | 详情返回复用页面 | `resume()` `:117-139`；`a.scroll = homeScroll` `:135`；`a.body = homeBody` `:136` |
| 15 | 进程重建恢复 | `MainActivity.onCreate` 读 `whale_home_scroll`（基线 `MainActivity:761`）；`onSaveInstanceState` `:907`；`capture()` `:1278`；`restore()` `:1289` |
| 16 | `a.scroll` 字段与全部消费方 | 基线 `MainActivity:94` `ScrollView scroll`；`smoothHomeScroll()` `:1750-1754`；`HomeDock:69`（上键回顶）；`HomeScreen:119/187/326/1408-1434/1514`（TV）；`MobileDetailScreen:781/788`、`DetailScreen:64`、`FilesScreen:50`、`MarkdownScreen:51`、`PlaybackController:919`（=null） |
| 17 | RecyclerView 参考实现 | `TvLive.buildMiddle()` `:737-818`：`LinearLayoutManager` + 匿名 adapter + `setItemAnimator(null)` + `setClipToPadding(false)` + `computeVerticalScrollOffset()` 存位置 + `scrollToPositionWithOffset` 恢复 |
| 18 | 图片加载/缓存/取消 | `ImageLoader.poster()` `:51-75`（**先 `image.setTag(address)`**）；回调按 `address.equals(target.getTag())` 落图 `:165-177`；`MainActivity.images` LruCache 10MB `:80-85`；`trimMemory` `:42-49`；`cancelArtwork` `:33-40` |
| 19 | TV 焦点/tag 兼容 | `HomeDock:72/217` 用 `findViewWithTag("primary")` 兜底；`jumpHomeTop()` 基线 `MainActivity:1756-1765`；`MobileShell` 首卡 tag `primary` `:1779` |

**非目标（与 E3 口径一致，评审不据此扣分）**：不迁 Compose/Kotlin；不改首页模块组成与顺序；不改搜索页 120 条上限；不做"预加载下一页"；不承诺低端机帧率数值；TV 首页/搜索不纳入本次改造。

---

## 2. 风险检查清单（16 条）

> 每条 = **检查点**（复核时看什么/看哪）+ **失败表现**（用户或测试能观察到的坏结果）。R-xx 编号在 §4 复核表中对应引用。

### R-01 滚动位置恢复（三条路径）

**检查点**
- 路径 A「切 tab 离开再回」：`switchTab()` 在 `contentContainer.removeAllViews()` **之前**抓锚点（position+offset，不是像素）；`renderHomeTab()` 读取锚点；**必须等首批数据 `submit()` 之后**才 `scrollToPositionWithOffset`（首帧只有 prelude 时 item 数不足）。
- 路径 B「进详情再返回」：`resume()` 复用 `retainedRoot` 时 **不得重建 RecyclerView/adapter**，位置由存活实例天然保持；`resumeHome` 只恢复分页按钮状态。
- 路径 C「进程被杀重建/旋转」：`onSaveInstanceState` 存锚点、`onCreate` 读入、`renderHomeTab` 在建好 adapter 后恢复一次（数据未到可短重试，但要有上限）。
- 锚点 position 是否**包含 prelude 偏移**且恢复时同样是同一套 item 结构（保存/恢复都在同一版本代码内，跨版本 Bundle 允许退化）。
- 旧 Bundle 兼容：只带 `whale_home_scroll` 像素的旧状态，要么有像素兜底、要么明确接受"回到顶部"，不允许崩溃或空列表死循环重试。

**失败表现**
- 切 tab 回来跳回顶部 / 偏移半行以上；进详情返回位置丢失；「不保留活动」进程重建后停在 header 顶部而不是原位置。
- 恢复时机早于数据到达 → 位置被后续 `submit()` 覆盖，或先滚到旧位置再跳到新位置（肉眼可见闪跳）。
- 旧 Bundle 路径 NPE / 无限 `postDelayed` 重试。

### R-02 `a.scroll` 类型语义（ScrollView → RecyclerView 兼容）

**检查点**
- 基线上 `MainActivity.scroll` 是 **`ScrollView`**，被 12+ 处跨类消费（见 §1.2 #16）。改造若把字段改成 `View`/新增分支，必须逐个核对：`onSaveInstanceState:907`、`capture():1278`、`restore():1289`、`smoothHomeScroll():1750-1754`、`HomeDock:69`、`HomeScreen:119/187/326/1408-1434/1514`（TV）、`PlaybackController:919`。
- **`smoothHomeScroll(int y)` 的语义变化**：旧实现 `y` 是"绝对像素 Y"；若 RecyclerView 分支用 `smoothScrollBy(0,y)`，`y` 变成"相对滚动量"。现有调用方（`HomeDock:69`、`jumpHomeTop`、TV 侧 `HomeScreen:1429`）都传 `0`，但必须在方法注释里写清语义，避免后续调用方误用。
- 移动端 `restore()`/`capture()` 对 RecyclerView 必须走 position+offset，不能用 `getScrollY()`（RecyclerView 该值不反映"第几条"）。
- 非首页页面（详情/文件/Markdown/播放）仍把 `a.scroll` 赋为 ScrollView，不能因类型改动出现 `ClassCastException`。
- `a.scroll` 在切到搜索/直播/片库后仍可能指向已 detach 的首页列表，所有消费方 null/attach 安全。

**失败表现**
- 从详情返回首页后回到顶部（`capture`/`restore` 走了像素分支或字段为 null）。
- `smoothHomeScroll(非0)` 位置跳错（把绝对 Y 当 dy）。
- `ClassCastException: RecyclerView cannot be cast to ScrollView`、或 `a.scroll == null` 时 NPE。
- TV 侧误用移动端分支导致 TV 首页滚动/焦点行为回归。

### R-03 自动加载下一页监听

**检查点**
- 监听**只注册一次**（每个新建 RecyclerView 一次；不随 `submit()` 重复 `addOnScrollListener`，不残留 `ViewTreeObserver` listener）。
- 触发条件与旧一致：`loadingPage || pageFailed || currentPage >= totalPages` 直接 return；触底判定用"最后一个 item 可见 + 距离 ≤240dp"等价旧 `diff <= a.dp(240)`。
- `findViewByPosition(lastItem)` 为 null（footer 未 attach）时不触发、也不误判为触底。
- `onScrolled` 可能以 `dy=0` 被布局触发：确认不会在一次数据更新内连续多次翻页（页码 +2、重复请求）。
- 快速连滚/回滚不会重复请求；请求取消仍走 `cancelPageRequests`。

**失败表现**
- 滚到底不自动加载，只能点按钮；或一次性连翻 2–3 页（流量暴增、页码跳变、列表出现重复/跳跃）。
- 每次 `submit()` 后都重新注册 → N 次滚动触发 N 个并发请求。
- 旧页结果晚到，混进新分类。

### R-04 筛选 / 分类切换重建

**检查点**
- 切分类/改筛选：`selectedCategoryId` 更新、`selectedFilters.clear()`、`currentPage = 1`、`refreshChips`、`loadPage` 顺序与旧一致（基线 `:1726-1750`）。
- 请求 token（`homeRequest`/`generation`/`ticket`）语义不变；500ms 连切 5 次旧结果不得覆盖。
- prelude 内 chip 行重建（`catRow.removeAllViews()`）造成 prelude 高度变化时，RecyclerView 不能出现位置漂移/跳动；筛选摘要条（`filterSummaryBar.refresh`）与筛选按钮文案一致。
- `homeFilterApply`/`filterButton` 闭包指向**最新一次** render 的 `fetchCategoryPage`（不能指向已移除旧视图的旧闭包）。

**失败表现**
- 连切分类后显示旧分类内容/混合内容；页码没重置导致第 2 页请求从旧状态出发。
- 点「筛选」并应用后列表不刷新，或摘要条显示与结果不一致。
- chip 重建后列表跳到顶部/中部（prelude 高度变化未保锚点）。

### R-05 hero「今日首推」/「今晚看什么」/ 历史 shelf

**检查点**
- 三块都在 prelude（单例 item）内：数据更新后 `heroContainer.removeAllViews()+addView`、`refreshTonight` 重新随机 3 条、分类变化时 hero 隐藏规则（`catSnapshot.isEmpty()`）与原一致。
- 历史 shelf 仍是"渲染时构建一次"的语义；切换片源/新增历史后是否与现状一致（现状仅在 render 时构建，不要求本次增强，但不能变差/重复卡）。
- prelude 被 RecyclerView 回收/重新 attach 时，晚到的数据更新是否安全（改的是仍被 prelude 实例持有的 View），attach 后是否补一次绑定。
- 「换一批」按钮、hero 点击、历史卡点击的点击目标仍是当前数据。

**失败表现**
- hero 不出现/显示上一部片；「换一批」点了没反应或换成重复的片。
- 历史 shelf 消失/重复/点开错条目；prelude 晚更新后其高度变化导致滚动锚点漂移。

### R-06 空态 / 错误态

**检查点**
- 空分类：「当前分类暂无影片」；筛选无匹配：「当前筛选暂无匹配，可继续加载更多影片」（基线 `:1375-1384` 文案原样保留）。
- 加载失败：「加载失败：<原因>」+ 加载更多变「点击重试」；错误后**已加载内容保留**（`homeReady = !currentVideos.isEmpty()` 语义）。
- 空态/错误提示在 prelude 内是否仍可见（不被 footer、筛选浮层、Dock 盖住）；空列表时 RecyclerView 结构不崩（item 数 = prelude+footer）。

**失败表现**
- 空分类白屏或只剩空白页脚；错误提示被吞、无法重试。
- 空态下 `getItemCount()` 计算越界（`position == getItemCount()-1` 与 header 冲突）导致崩溃/错位。

### R-07 加载更多按钮三态 + 页脚 item

**检查点**
- 四状态：隐藏（无更多/空）、`加载更多影片 (x/y)`、`正在加载更多…`、`点击重试`；点击逻辑 `currentPage++` 与旧一致（`!pageFailed && currentPage < totalPages`）。
- 页脚作为最后一个 item：按钮隐藏时 item **高度为 0**（等价旧 GONE 无占位）；按钮出现时位置/边距与旧一致（16dp 左右边距、12dp 上边距）。
- `resumeHome`（详情返回复用）恢复按钮状态，不能卡在「正在加载更多…」。
- 点击仍然有效（页脚在可见区、被正确绑定；不能只有 tag 但不可点）。

**失败表现**
- 列表底部多出一块固定空白；页码文案与 `currentPage/totalPages` 不同步。
- 点「加载更多」无反应（页脚 holder 未绑定/被回收）或重试后重复追加。
- 详情返回后按钮停在加载态，用户无法继续。

### R-08 TV 焦点与 `tag("primary")`

**检查点**
- `MobileShell` 是 TV/移动共享类：确认 TV 路径（`HomeScreen`、`TvLive`、`GlobalSearchScreen`）不会被新首页逻辑触碰；`a.body` 的赋值语义若被移动端改动，TV `render()/restore()` 不能受影响。
- 移动首页仍保留首卡 `primary` 与卡片 `video:<id>` tag（TV 遗留兼容）；但**不能依赖** `findViewWithTag` 一定命中——虚拟化后滚出可见区的卡不在视图树里。
- `HomeDock:72/217`、`jumpHomeTop()`、`HomeScreen.restoreFocus()` 等"找 primary/历史卡"的兜底路径，虚拟化后不得抛 NPE、不得把焦点丢到不存在元素。
- `HomeScreen.navigationKey()` 的 `a.scroll` 父链判断：移动端 `a.scroll` 已是列表，不影响 TV（TV 仍 TvScrollView）。

**失败表现**
- TV 首页/详情焦点恢复落到错误元素或丢失焦点；遥控上键"回顶"失效。
- 移动首页在首卡滚出可见区后，`findViewWithTag("primary")` 返回 null 的调用方 NPE。
- TV 侧行为因共享类改动回归（本次无 TV 真机也要做静态核对 + TV 探针）。

### R-09 `resume()` 页面复用

**检查点**
- `resume()` 前置条件（`homeReady/activeTab/retainedRoot/retainedEpoch/retainedWidth/retainedHeight`）不被放宽；复用后重新赋值 `a.scroll/a.body`、补背景层、`resumeHome`。
- `homeList`/`homeAdapter` 与 `retainedRoot` 必须是**同一实例**：不能出现 "复用了旧 root，但 `homeList` 是 null/新实例" 的组合（返回后白屏）。
- 不复用（`resume()` 返回 false）时走重建：锚点由 Bundle/`Screen` 恢复，不能两个恢复源互相覆盖。
- 详情返回路径 `renderReturn()` 在移动端先尝试 `resume()`，失败才 `screen.render.run()`。

**失败表现**
- 从详情返回后首页白屏/空白列表（root 复用但列表未挂上）；或位置/分页状态丢失。
- 返回后出现两个列表叠层/旧列表抢焦点。

### R-10 内存 / 图片回收

**检查点**
- 复用 holder 的绑定必须**先清后设**：无海报时 `setImageDrawable(null)` + `setTag(null)`；`onViewRecycled` 清同两项；`boundPoster`（或等价）避免同图重复请求。
- **不能用 `ImageView.getTag()` 反查业务对象**（DEF-1001 教训）；保留 `a.poster()` 写入的 address tag 作为"防串图"校验（`ImageLoader:165-177`）。
- 行 item 每次 bind `removeAllViews()` 重建 cols 张卡：确认这是有意的权衡（ViewHolder 只复用行容器），且不会导致"回收后仍持有旧 ImageView/海报"的内存滞留。
- `cancelArtwork()` 只在离开首页/切 tab/进入播放时调用；**滚动过程中不取消**（会造成白块）。`IN_FLIGHT` 表与 `artworkRequests` 不会因大量快速绑定无限增长。
- LruCache（10MB）在 120 条数据下不会被单页全量图撑爆；`trimMemory` 路径不变。

**失败表现**
- 快速上下滚出现串图、残留上一张封面、白块久不补齐。
- Java heap / bitmap 内存在滚动后持续上涨；返回首页后旧图未释放。
- 滚动中大量请求被取消 → 停手 1s 内海报仍不补齐。

### R-11 Adapter 数据与 `currentVideos` 一致性

**检查点**
- `submit()` 应传入**新快照**（`new ArrayList<>(currentVideos)`）并在 adapter 内复制，不能与 `currentVideos` 共享可变引用（否则 diff/回收期间数据被并发改）。
- 点击跳转使用的 `site` 快照与卡片数据来源一致（`a.openListing(site, v)`）；`v.action` 过滤仍在数据层（`:1369-1373`），不进 adapter。
- 追加页是否**只追加不重建**（E3-S2 #1）：若用 `notifyDataSetChanged()`，可见行会被重绑（卡 View 重建），与"已显示卡片不重建"口径冲突，应改为 `notifyItemRangeInserted`/DiffUtil 或给出不重建理由与证据。
- `notify*` 调用不能在 layout/scroll 计算过程中发生（`submit` 只在 `ui.post` 回调里）。
- 页码/`currentVideos` 更新完成后再 `submit`，避免"列表先变、页码后变"。

**失败表现**
- 点卡进错详情/旧片源详情；加载更多后可见卡闪烁重绑、首卡 identity 变化；重复条目或漏条目。
- `IndexOutOfBoundsException`（adapter 数据与 position 不同步）。

### R-12 配置变化 / 旋转

**检查点**
- 旋转/分屏：`onConfigurationChanged → refresh() → capture()+render()+restore()`；`retainedWidth/Height` 不匹配时强制重建；重建后列数按新宽度取 3/4/5 档、卡片宽度/海报高度（1.48）重算。
- 状态栏 padding、`filterBarBottomDp`、底部 Dock 让位、平板侧栏（`contentContainer` 宽度）随形态变化。
- 旋转瞬间旧列表已 detach：`capture()` 必须在此之前完成（`refresh()` 内顺序正确）。
- 旋转后恢复锚点、图片按新尺寸重新请求（ImageDecodePolicy 缓存键含尺寸，不应串图）。

**失败表现**
- 旋转后列数/卡片宽度不变（溢出或留大片空白）；卡片高度 0/裁剪。
- 旋转后位置跳变到顶部/中间；横屏底部被 Dock/筛选条遮挡。
- 旋转触发两次 render 导致重复请求。

### R-13 生命周期泄漏

**检查点**
- 新增静态状态（如锚点 position/offset、pending*）在进程重建/退出后不能残留脏值；旧版 `pendingHomeScroll`/`homeScrollY` 若已废弃必须删除或明确兜底，不能"写了没人读"。
- `RecyclerView` 持 Activity context 的生命周期随 `retainedRoot` 销毁；`homeList`/adapter 不被静态字段或长生命周期对象长期引用。
- `ui.post/postDelayed` 的延迟任务（如回顶后补焦点 320ms）在 `isFinishing()/isDestroyed()` 后要早退。
- 滚动监听、`addOnScrollListener`、`ViewTreeObserver` 监听随 RecyclerView detach 自然失效，无重复注册。
- `AppLog` 的虚拟化日志（若保留）只在数据更新时打一条，不能进 `onBindViewHolder`/`onScrolled` 高频路径。

**失败表现**
- 退出/旋转后延迟 runnable 操作已销毁视图（NPE/崩溃）；内存/Activity 泄漏；日志刷屏。
- 静态锚点残留导致下次进入首页位置错误。

### R-14 刘海 / 状态栏 / 底部 padding

**检查点**
- 顶部：`statusBarHeight()`（含刘海机型 fallback 28dp）+ 8dp 不能丢，也不能与旧 `body` padding 重复叠加；`clipToPadding=false` 保证首卡可滚到状态栏下方。
- 底部：`filterBarBottomDp + FilterSummaryBar.HEIGHT_DP + 18dp` 让位，保证最后一个页脚/卡不被筛选条或 Dock 遮挡；`filterSummaryBar` 仍是 `contentContainer` 浮层（在列表之后 `addView`/`bringToFront`）。
- 全屏播放返回后 `applyMobileSystemBars(false)` 与列表 padding 一致（不能出现状态栏覆盖首卡）。
- 平板侧栏布局（`sideRailLayout`）下 `filterBarBottomDp=16` 的分支保留。

**失败表现**
- 首卡被状态栏/刘海压住或顶部多出一截空白；底部最后一行被 Dock/筛选条遮住、点不到。
- 全屏播放退出后首页 padding 错误。

### R-15 既有测试 / 探针兼容

**检查点**
- `android/tests` 没有移动首页专用探针；`HomeProbe`/`PerformanceProbe`/`ResponsiveProbe` 的 `home-*`/`home-video:*`/`home-load-more` 均属 **TV `HomeScreen`**，本次不改 TV 逻辑，不应因共享类改动而红。
- 任何用 `root.findViewWithTag(...)` 的探针只能命中**已 attach** 的视图：若把探针改成移动首页，必须先滚动到目标再点击；`performClick` 对未 attach 视图无效。
- `android/tests/run_checks.py` 只编译非 UI 类（`Cms/RequestScope/SourceEngine/JsonCmsEngine/VideoFilters`），不受影响；`scripts/build_android.py` 用 `rglob('*.java')` 收集源码，**无需改构建脚本**。
- 如 dev 按写窗口约定改了 `android/tests/device/HomeProbe.java`，需说明改动原因并确认 TV 保护用例仍覆盖。

**失败表现**
- 探针 `tag()` 返回 null / `performClick` 无反应 / 断言超时，误报回归。
- 为通过探针而放宽 TV 断言，丢失回归保护。

### R-16 构建与格式

**检查点**
- 编译：`scripts/build_android.py`（Python 主链）能出双包；`RecyclerView 1.3.0` 已在依赖里，**不允许新增依赖/改构建脚本/改 ABI/资源**。
- `javac -source 8` 兼容（lambda/方法引用可用；不要用 var、switch 表达式等更高版本特性）；`minSdk 23` 下 RecyclerView API 可用。
- `python -X utf8 scripts/format_java.py --check`（google-java-format 1.24.0）通过，或明确由 Lead 收口时统一 format。
- Gradle 验证链（`gradle/app`）编译同一份源码不额外破。
- 只改写窗口允许的文件（`MobileShell.java`、必要的 `MainActivity.java`，可选 `tests/device/HomeProbe.java`），不改 TV 文件。

**失败表现**
- 编译失败/包构建不出、R8/Gradle 链报错；格式检查红。
- 越界改文件与 Lead/TvLive 写窗口冲突，包不可复现。

---

## 3. 评审通过标准

### 3.1 判定等级

| 等级 | 含义 | 处置 |
|---|---|---|
| **通过** | 检查点有代码/运行证据支撑，行为与 §1.2 基线一致或经批准变更 | 记录证据位置 |
| **风险** | 不阻断合入，但有可观察的体验/维护性折损或证据缺失 | 登记到 §4 表 + 交付说明；由 Lead 决定是否本轮修 |
| **必须修** | 违反 E3-S1/E3-S2 验收口径，或会导致崩溃/数据错/明显回归 | 阻断合入，给出 `文件:行号` 与修法，修后重审 |

### 3.2 C 类（代码）证据要求 —— 硬门槛

E3-S1 #1 的原话是「120 条数据下首帧不创建全部卡片（评审可见：绑定数为可见区+预取，非全量 addView）」。复核必须拿到下面 4 类证据，缺一不通过：

1. **绑定数 = 可见 + 预取**
   - 静态：`adapter.getItemCount()` 与"行数"公式一致（prelude + ceil(N/cols) + footer）；`onBindViewHolder` 单次只构建 **一行**（≤ cols 张卡）；不存在对 `videos` / `currentVideos` 的**全量** `addView` 循环。
   - 运行：取一条虚拟化日志（`AppLog` 或临时 `AtomicInteger` 计数），证明 120 条数据时首帧/普通滚动后**绑定卡数远小于 120**（数量级判定：手机 3 列时可绑定 ≤ 30 张；给出原始日志文本，不接受"应该没问题"）。
   - 反例即不通过：首帧绑定数 == 视频数；任一 `bind` 路径对全量数据 `addView`；`notifyDataSetChanged` 被当作"虚拟化"证据（它不改变全量 addView 的有无，只证明刷新方式）。
2. **非全量 addView / 无全量重建**
   - 旧 `renderVideoGrid()`（基线 `:1761-1862`）的全量循环已删除或不再被首页调用；数据更新路径是 `adapter.submit(...)`，不是 `gridContainer.removeAllViews()` 后逐卡 `addView`。
   - prelude / footer 各只创建一份（`onCreateViewHolder` 返回单例实例不能导致同一 View 被二次 attach）。
3. **`a.scroll` 全部消费点清单**
   - 复核表中逐点列出：`MainActivity`（保存/捕获/恢复/`smoothHomeScroll`/`jumpHomeTop`）、`HomeDock`、`HomeScreen` 3 处、`MobileDetailScreen`/`DetailScreen`/`FilesScreen`/`MarkdownScreen`/`PlaybackController`、`MobileShell.resume/renderHomeTab`，每点给「通过/风险/必须修」。
4. **分页状态机未改语义**
   - `currentPage/totalPages/loadingPage/pageFailed/completedPage`、`homeRequest` 取消、`action` 过滤、`submit` 时机（更新 `currentVideos` 之后）与基线 `:1345-1455` 一致；只换"渲染方式"，不换"状态机"。

### 3.3 B 类（真机）证据要求

- 真机录屏：进入首页 → 连续快速滚动 10s → 停手，**无 ANR/崩溃、无白块残留、无串图，停手后海报 1s 内补齐**（E3-S1 #2）；注明设备型号/系统/版本/时间。
- 返回路径录屏或截图：进详情→返回，分类/页数/位置恢复（误差 ≤1 行）；切 tab→返回；若有条件做"不保留活动"进程重建。
- 无真机时按 E3-S3 口径明确写 **「未采集」**，不得以静态评审替代 B 类结论，也不得对外宣称"已验证不卡/已加速"。

### 3.4 可直接转给 Lead 的验收清单

**P0（阻断，必须全部满足）**
- [ ] 编译：Python 主链出包成功（双包），无新增依赖/无构建脚本改动；写窗口内文件之外零改动。
- [ ] C 证据 1：绑定数=可见+预取（静态 + 运行日志原文）；全量 `addView` 已消失。
- [ ] R-02：`a.scroll` 12+ 消费点逐点复核通过；`smoothHomeScroll` 语义变更已注明且调用方安全。
- [ ] R-01：三条恢复路径（切 tab / 详情返回 / 进程重建）位置误差 ≤1 行，且有实现级理由。
- [ ] R-03/R-04：分页与筛选状态机语义不变；500ms 连切 5 次分类无旧结果覆盖（C 证据或录屏）。
- [ ] R-06/R-07：空态/错误态/重试/按钮三态正确。
- [ ] R-08/R-15：TV 路径未被改动（静态核对 + TV 探针/静态测试不受影响）；共享类改动无 TV 回归。
- [ ] R-16：`javac -source 8` 编译通过；无越界文件。
- [ ] R-10：holder 复用无串图/白块（代码规则 + 录屏）。

**P1（需证据，缺证据计"风险"）**
- [ ] B 类录屏 10s（或明确"未采集"）。
- [ ] R-05：hero/今晚看什么/历史 shelf 视觉与行为截图对比。
- [ ] R-09：详情返回复用场景录屏（含分页按钮状态）。
- [ ] R-11：追加页"只追加不重建"的证据（首卡 identity 或录屏）；若保留 `notifyDataSetChanged` 必须给出不重建的替代证据与理由。
- [ ] R-12：旋转/平板两种形态截图。
- [ ] R-14：刘海/状态栏/底部遮挡检查（至少截图）。

**P2（建议，不阻断）**
- [ ] R-13：废弃静态态（`pendingHomeScroll`/`homeScrollY` 等）已删除或接兜底；延迟任务有 finish 早退。
- [ ] `scripts/format_java.py --check` 通过（或由 Lead 收口统一 format）。
- [ ] 虚拟化日志降噪（只保留数据更新时一条，或仅 debug）。

### 3.5 回滚标准

满足任一即建议回滚到本 story 前的 `MobileShell.java`/`MainActivity.java`（不连带回滚其他 story）：
1. 出现首屏崩溃/白屏/焦点丢失等 P0 问题且 30 分钟内无法定位；
2. 分页或筛选状态机出现不可控回归（重复/漏页/旧结果覆盖）；
3. 构建链破坏且短期修不好。
滚动位置类问题优先修（R-01），不作为单独回滚理由。

---

## 4. §复核结论（2026-11-09 第二步复核，已填）

> 复核对象：最终工作树（未提交）三个文件：`MobileShell.java`（4803 行）、`MainActivity.java`（2066 行）、`HomeScreen.java`（1519 行，仅类型兼容 2 处）。
> 行号口径：以下行号均为**最终工作树**（`git diff` 未提交状态），可直接核对。
> 证据来源：本次只读代码（read/grep/glob）+ Lead 提供的构建事实；**本轮无真机、无安装/滚动验证**。
> 判定定义见 §3.1；「未真机验证」在 §4.5 单独声明。

### 4.1 逐条结论表

| 条目 | 结论 | 证据（文件:行号） | 说明 / 处置 |
|---|---|---|---|
| R-01 滚动恢复三路径 | **风险** | `MobileShell:754-758`（切 tab 先 `captureHomeAnchor`）、`2002-2013`；恢复 `825-841`、`1809`、`1850-1865`；进程重建 `MainActivity:764-771`、`908-926`、`1313-1316`、`MobileShell:2027-2048` | 三条路径的**实现机制齐备**（切 tab=position+offset 锚点；详情返回=`resume()` 不重建 `117-139`；进程重建=Bundle 锚点+重试）。风险 3 点：(a) `applyHomeRestore` 在 `homeRestorePos >= getItemCount()` 时直接 return 且**不清标记**（`1853`），后续每次 `submit()`（`1809`）都会重试，用户已滚动后数据补齐时可能突然跳回旧锚点；(b) 双恢复源竞争：静态 `homeAnchorPos`（`833-836`）与 `Screen.scrollPos`（`MainActivity:1313-1316`）可能先后生效，`restoreListAnchor` 最多 12 次×80ms 延迟（`2040-2042`），最终位置来源不确定，需真机确认旋转/返回是否 ±1 行；(c) 深位置旋转/进程重建时新实例只拉第 1 页，`pos >= itemCount` 放弃→回顶部（基线是"像素滚到内容底部"），与 ARCH_NOTES §3.9 承诺的"首批数据后恢复一次"有偏离，需文档化。建议：恢复成功或放弃后置 `homeRestorePos=-1`；统一恢复源；必要时恢复加重试上限与"用户首次交互后取消"。 |
| R-02 `a.scroll` 类型语义 | **通过（含 1 项低风险）** | `MainActivity:96`（`View scroll`）、`914-918`、`1294-1298`、`1313-1316`、`1777-1792`；`MobileShell:135`、`856`；`HomeScreen:44/119-120/327/1515`；其余赋值点：`MobileDetailScreen:781/788`、`DetailScreen:64`、`FilesScreen:50`、`MarkdownScreen:51`、`PlaybackController:919` | 12+ 消费点逐个核对：RV 分支按 position+offset（`MainActivity:914-918/1294-1298/1313-1316`），TV 分支 `TvScrollView` 不变（`1779`），非 RV 兜底 `1791` 强转 `ScrollView`——全部赋值点只有 ScrollView 家族（`DetailScreen:53` TvScrollView、FilesScreen/Markdown/MobileDetail 均为 ScrollView），无 CCE 路径；null 已判（`1778/1779`）。低风险：`smoothHomeScroll(y)` 对 RV 分支的 `y` 变成**相对 dy**（`1785-1789`），旧义为绝对像素；当前 3 个调用方都传 0（`HomeDock:69`、`MainActivity:1807`、`HomeScreen:1429`），暂无影响，但方法未注释语义差异，建议补注释。 |
| R-03 自动加载下一页 | **通过（含 1 项低风险）** | `MobileShell:1493-1518` | 条件与旧一致（`1498` loadingPage/pageFailed/currentPage>=totalPages）；末 item 未 attach 即返回（`1507-1508`）；触底公式 `1509-1513` 与旧 `diff<=240dp` 等价（含 bottom padding）；`loadingPage` 在请求入口同步置真（`1202`）防并发重复；监听只注册一次（每次 `renderHomeTab` 新建 RV）。低风险：未判 `dy > 0`（`1497`），任何 `onScrolled`（向上滚/程序滚动）都可能触发；静态未找到"纯数据更新会派发 onScrolled"的路径（AOSP RecyclerView 不会在数据变更时回调），故级联翻页大概率不发生，但建议加 `if (dy <= 0) return;` 或真机日志确认。 |
| R-04 筛选/分类切换 | **通过** | `MobileShell:1771-1790`（chip）、`1672-1692`（筛选面板）、`1374-1385`/`1460-1482`（token）、`1118`（`homeFilterButton`） | `selectedCategoryId/currentPage=1/selectedFilters.clear/refreshChips/loadPage` 顺序与基线一致；请求 token（`homeRequest`/`ticket`/`generation`）未被改；`filterSummaryBar.refresh` 与筛选按钮文案刷新（`1681-1685`）保留；`homeFilterApply` 指向最新 render 的 `fetchCategoryPage`（`1198`）。500ms 连切 5 次分类需真机录屏确认，静态路径无旧结果覆盖漏洞。 |
| R-05 hero/今晚/历史 | **通过（需截图）** | `MobileShell:890-993`（历史）、`995-1002`（hero 容器）、`1004-1087`（今晚）、`1414-1447`（数据后更新） | 三个区块都在 prelude（item0，单例 `homeHeaderBody`）内，结构/顺序与基线一致；hero 在数据后 `removeAllViews+addView`（`1417-1440`）、今晚重新随机 3 条（`1037-1087`）、分类变化隐藏 hero（`1415-1444`）逻辑保留。prelude 滚出可见区时在持有实例上更新、滚回后 attach 会重新布局，静态上安全；真机截图确认「深位置滚回后 hero/今晚显示新数据」。 |
| R-06 空态/错误态 | **通过** | `MobileShell:1131-1135`（statusText）、`1404-1413`（空/筛选无匹配文案）、`1460-1482`（失败+重试） | 文案与基线一致；空列表时 `getItemCount=2`（header+footer），`onBindViewHolder` 对 footer 早退（`1844-1848`），无越界；失败时 `homeReady=!currentVideos.isEmpty()`（`1476`）保留已加载内容。 |
| R-07 加载更多三态 + footer | **通过（风险见 R-11/4.3）** | `MobileShell:1139-1148`（按钮）、`1149-1156`（单例 footer）、`1203-1210`（加载中）、`1448-1458`（三态）、`1479-1480`（点击重试）、`1486-1491`（点击）、`1163-1171`（`resumeHome`） | 四状态与基线一致；footer 隐藏时 `loadMoreBtn` 为 GONE，`FrameLayout` 不测量 GONE 子项→item 高度 0（无占位）；页码只由 `currentPage++`（`1489`）推进。单例 footer 的 holder 复用风险见 4.3-①（需真机确认）。 |
| R-08 TV 焦点与 `tag("primary")` | **通过** | `HomeScreen:44`（`View retainedScroll`）、`119-120`（ViewGroup 判空后 setClipChildren）、`327`、`1502-1518`；`MainActivity:1187/1207`（HomeDock 只在 TV `renderPage` 创建）；`MobileShell:1938`（`primary` tag 保留） | TV 不进入 `renderHomeTab`；HomeScreen 仅类型兼容，行为不变（TV `a.scroll` 仍是 TvScrollView，`instanceof ViewGroup` 恒真）。dev 自述的「HomeDock 回顶部焦点未加延迟」**对移动端不适用**：HomeDock 是 TV home 的右侧栏（`MainActivity:1207`），移动端底部 Dock 是 `MobileShell.buildNavigationBar`，不走 HomeDock；移动端依赖 `primary` 的调用点有 `jumpHomeTop` 320ms 延迟兜底（`MainActivity:1801-1803`、`1810-1820`），`restore()` 移动端提前 return（`1317`）。 |
| R-09 `resume()` 页面复用 | **通过** | `MobileShell:117-139`（条件未放宽；`135` `a.scroll=homeList`、`136` `a.body=homeBody`、`129` `resumeHome`、`133` backdrop） | 复用时不重建列表；`homeList` 在 `renderHomeTab`（`839`）赋值、`retainedRoot` 在 `render()` 末尾赋值，属于同一 shell 最近一次 render，静态成立；不复用时走重建+锚点。无白屏路径证据。 |
| R-10 内存/图片回收 | **通过（含 2 项低风险）** | `MobileShell:1905-1929`（行内 `removeAllViews` 后重建卡片）、`1935-2000`（`buildVideoCard`，`1962` `a.poster`）；`ImageLoader:51-75`、`165-177`（tag 校验） | 卡片每次 bind 重建、不跨行复用 ImageView→无串图；`a.poster` 的 address tag 防线保留；行复用前 `row.removeAllViews()`（`1906`）清空旧卡。低风险：(a) 未实现 `onViewRecycled`，回收行在池中暂存旧卡（短时 bitmap 引用）；(b) 滚动中在飞海报完成时按 tag 写入已 detach 的 ImageView（`ImageLoader:165-177`），浪费解码但无视觉问题。建议在池化时清 `ImageView`（不阻断）。滚动中不取消 artwork（与验收"停手 1s 补齐"一致）。 |
| R-11 Adapter 数据一致性 | **风险** | `MobileShell:1803-1811`（submit 快照）、`1808`（`notifyDataSetChanged`）、`1397-1402`（action 过滤/清空追加）、`1936-1940`（点击用 `homeAdapter.site`） | 通过部分：`submit` 复制快照（adapter 自有 `videos`，不与 `currentVideos` 共享可变引用）；点击用一致 site 快照；`action` 过滤仍在数据层；数据先更新后 submit（`1397-1446`）；submit 只在 `ui.post` 回调。风险：`notifyDataSetChanged()`（`1808`）在**追加页/首屏**会重绑全部可见行（卡片 View 重建、海报重绑），与 E3-S2 #1「已显示卡片不重建」口径冲突（E3-S1 不要求，E3-S2 才要求）；且它是 4.3-① 单例 item 风险的触发条件。建议：追加场景改 `notifyItemRangeInserted(1+oldRows, newRows)`（footer 位置随插入偏移，结构未变更）；首屏空→数据同样可 range-insert；分类切换可 `notifyItemRangeRemoved`+`Insert`（彻底避免 `notifyDataSetChanged`）。 |
| R-12 配置变化/旋转 | **通过（含低风险）** | `MobileShell:122-123`（宽高不匹配不复用）、`842-851`（列数 3/4/5、卡宽/海报高重算）、`877-881`（padding）；`MainActivity:834-853`（`onConfigurationChanged→refresh`）、`1290-1339` | 旋转走 capture→render→restore，列数与尺寸随 `windowWidth()` 重算；`homeCols` 断点与基线 `renderVideoGrid` 同公式。低风险：深位置旋转的恢复见 R-01(c)（只拉第 1 页→回顶）。平板/横屏视觉需截图对比（Phase A 允许 ±5dp）。 |
| R-13 生命周期泄漏 | **风险（P2）** | `MobileShell:58`（`pendingHomeScroll` 已无消费者）、`MainActivity:766`（仍写入）、`916`（RV 分支恒写 `whale_home_scroll=0`）、`1801-1803`/`1810-1820`（320ms 延迟补焦点未判 `isFinishing/isDestroyed`）；`MobileShell:1867-1901`（日志） | 未发现静态字段持有 View/Activity，`homeList/homeAdapter` 均为实例字段随 shell 回收；日志只在数据更新时一条（不在 onBind/onScrolled 高频路径）。风险：`pendingHomeScroll` 为死状态（建议删除或作为旧 Bundle 像素兜底）；`jumpHomeTop` 延迟回调在 finish 后仍可能操作视图（低概率 NPE/无效操作），建议补早退。 |
| R-14 系统栏/padding | **通过** | `MobileShell:877-881`（top=`statusBarHeight()+8`，bottom=`filterBarBottomDp+HEIGHT_DP+18`）、`855`（`clipToPadding=false`）、`857`→`867-875`（RV 先加、filterSummaryBar 后加=浮在上层）；`MainActivity:579-610`（system bars 未改） | padding 与基线 body padding 等价；筛选摘要条仍是 `contentContainer` 浮层；平板侧栏 `filterBarBottomDp=16`（`861-866`）保留。刘海/底部遮挡需截图（真机）。 |
| R-15 测试/探针兼容 | **通过** | `scripts/build_android.py:134`（`rglob('*.java')` 收源码）；`android/tests/run_checks.py:20-24`（只编译非 UI 类）；探针 `HomeProbe/PerformanceProbe/ResponsiveProbe` 均走 TV `HomeScreen`；本次未改探针文件 | `a.scroll` 改 `View` 后探针的 `getScrollY()/scrollTo()/instanceof TvScrollView` 仍编译（均为 View 方法/类型判断）。注意：移动首页**无自动化探针**，虚拟化没有回归保护，建议后续补 mobile home probe（不阻断本轮）。 |
| R-16 构建/格式 | **通过（格式未验证）** | Lead 构建事实：`python3 scripts/build_android.py` 成功、mobile APK 61,996,331B / sha256 `5ed0e00c…f2af`、tv 包 36,223,814B、`build-validation.json` 1.3.0/13000、签名+zipalign 通过、`git_commit=61ebb96`、`git_dirty=true`；classes.dex 含「首页虚拟化」 | 无新增依赖、未改构建脚本、越界文件为零（仅 3 个允许文件）；`javac -source 8` 编译通过（构建事实）。P2：未提供 `format_java.py --check` 结果。 |

### 4.2 C 类证据结论（E3-S1 #1：绑定数=可见区+预取，非全量 addView）

**判定：静态通过（满足 E3-S1 #1 的代码判定）；运行证据「未采集」。**

静态依据（可逐条核对）：
1. `getItemCount() = 2 + ceil(N / homeCols)`（`MobileShell:1814-1816`）——item 数随"行数"增长，不是随卡片数 1:1。
2. `onBindViewHolder` 只对 ROW 调 `bindHomeRow`（`1844-1848`），单次绑定最多创建 `homeCols` 张卡（`1905-1929` 循环 cols 次）。
3. 全量路径已删除：`renderVideoGrid` 在最终代码中不存在；`buildVideoCard` 唯一调用点是 `1915`（当前行位）；没有任何 `for (all videos) addView` 的路径。
4. prelude/footer 为单例 item（`1826-1841`），不随数据量增长。
5. 数据更新走 `submit()`（`1803-1811`）后由 RecyclerView 只绑定可见/预取 holder，不重建容器、不逐卡 `addView` 到列表外容器。

运行证据路径（需真机）：`logVirtualization()`（`1867-1901`）在每次数据更新后打印一条
`首页虚拟化：视频数=N，列表item数=M，可见卡数=K（可见行=R）`。
- 预期：120 条、手机 3 列时 K ≈（可见行+预取 1~2 行）×3 ≈ 9–18，远小于 120；M = 2 + ceil(120/3) = 42。
- 注意：该日志统计的是 **attached 子 view 卡片数**，不是 `onBindViewHolder` 调用次数；作为"非全量"运行证据可用，但严格绑定计数可在真机临时加 `AtomicInteger`（不落库）。
- **本轮无设备，运行日志未采集**；不得据此宣称"已验证不卡/已加速"。

### 4.3 风险清单（按优先级；本轮无「必须修」静态可证项）

1. **【高｜崩溃级设计脆弱点】header/footer 单例 item + `notifyDataSetChanged` 触发整表重排。**
   `onCreateViewHolder` 原样返回 `homeHeaderBody`/`homeFooterWrap`（`1826-1841`），而 `notifyDataSetChanged`（`1808`）在 item 数变化时会让 **footer 的 position 变化**（空态 pos=1 → 数据后 pos=N+1；追加页末位变化）。RecyclerView 结构变更重排时若对同 viewType 重新走 `onCreateViewHolder`，会把同一 View 实例交给第二个 holder（headless 引用），可能表现为 `IllegalStateException/IllegalArgumentException`。静态无法证明 RecyclerView 一定不会二次创建（不同版本/缓存策略行为不同），**必须真机验证两个时刻**：
   - 首屏数据到达（空 itemCount=2 → N+2，footer 从 pos1 移走）；
   - 「加载更多」追加页（footer 末位变化，且 user 在底部时 footer 处于 attached）。
   若真机崩溃/错位 → **升级为必须修**；修法：header/footer 改为"每次 onCreateViewHolder 新建容器、状态用 bind 设置"（loadMore 按钮状态迁移到 adapter），或彻底去掉 `notifyDataSetChanged` 改 targeted notify（见 R-11 建议）。
2. **【中】`applyHomeRestore` 放弃恢复但不清理标记**（`1853`）：后续每次 `submit()` 都会重试，可能在用户已交互后突然跳到旧锚点。
3. **【中】双恢复源竞争**（R-01(b)）：静态 `homeAnchorPos` 与 `Screen.scrollPos` 两条路径 + `restoreListAnchor` 延迟重试，最终位置来源不确定；需真机确认旋转/详情返回误差 ≤1 行。
4. **【中】`notifyDataSetChanged` 与 E3-S2 #1「追加不重建」口径冲突**（`1808`）：E3-S1 不阻断，E3-S2 待开发时需处理；建议本轮顺手改 range-insert。
5. **【低】自动加载未判 `dy>0`**（`1497`）：建议加守卫。
6. **【低】`smoothHomeScroll(y)` 语义变化**（`1780-1789`）：RV 分支 `y` 视为相对 dy，与旧"绝对像素"不同；建议注释。
7. **【低】回收行未清卡片 / 在飞海报写已 detach ImageView**（R-10）：短时内存抬升，建议 `onViewRecycled` 清图。
8. **【P2】死状态与延迟回调**：`pendingHomeScroll`（`MobileShell:58`/`MainActivity:766`）、`whale_home_scroll` 恒为 0（`MainActivity:916`）、`focusHomeTopAfterScroll` 未判 `isFinishing/isDestroyed`（`1810-1820`）。
9. **【P2】格式检查未验证**：`scripts/format_java.py --check` 无结果；建议 Lead 收口 commit 前跑一次。

**基线遗留观察（非本次引入，验收时勿误判）**：切 tab 回到首页时 `currentVideos` 不清空、`currentPage` 不重置（`1397`、`1489` 与 `754-759`），会把当前页再追加一次 → 可能出现重复卡片；这与改造前逻辑相同（基线同代码路径），不属于虚拟化回归，建议另开 issue。

### 4.4 B 类证据状态

**未真机验证。** 本轮只有构建产物（A/C 静态证据），没有安装、没有滚动/加载更多/返回恢复的录屏或截图。B 类验收项（连续快滚 10s 无 ANR/无白块、停手 1s 补齐、详情返回位置误差 ≤1 行）按 E3-S3 口径记为 **「未采集」**，不得对外宣称已验证性能。

### 4.5 总判定

**有条件通过（可合入）。**
- 阻断项（必须修）：**0 项静态可证**。
- 合入前条件（Lead 决定）：
  1. 真机验证 4.3-① 两个时刻（首屏数据到达 / 加载更多）不崩溃、不错位——这是唯一可能升级为「必须修」的点；
  2. 真机采集一条「首页虚拟化：…」原始日志贴 MR（C 运行证据）；
  3. 至少一条滚动录屏（B 证据）或明确标注「未真机验证」。
- 建议本轮顺手修（低成本、不改架构）：4.3-2（清 `homeRestorePos` 标记）、4.3-3（统一恢复源）、4.3-5（`dy` 守卫）、4.3-8（死状态与延迟回调早退）；4.3-1/4 若真机无异常可留到 E3-S2 一并处理。
- 回滚标准仍按 §3.5。

### 4.6 防御性改动快速复核（2026-11-09 21:40，仅 3 处 diff 点）

> 对象：`MobileShell.java`（4828 行）、`MainActivity.java`（2067 行）最终工作树；方式=只读代码；改动目的=解除 4.3-① 单例 item 风险 + 收紧自动加载 + 延迟回调早退。

| # | 复核点 | 结论 | 证据 | 说明 |
|---|---|---|---|---|
| ① | 壳容器搬移是否仍可能 double-parent | **通过** | `MobileShell:1828-1843`（HEADER/FOOTER 每次 `new FrameLayout` 壳）、`1919-1927`（`attachHomeSingleton`：同父早退→摘旧父→`addView`） | 单例内容同一时刻只可能有一个父；换壳时先 `removeView` 再挂，规避 `child already has a parent`。三种 item viewType 分池（HEADER=FrameLayout / FOOTER=FrameLayout 但不同类型 / ROW=LinearLayout），壳与影片行不会混用；同一布局内不会有两个 holder 同时持有内容。残留低风险（非阻断）：若焦点在 header 内且发生 `notifyDataSetChanged` 换壳，内容 detach/re-attach 可能丢焦点（移动端触控为主，TV 不走此路径）；`logVirtualization` 已同步适配壳结构（`1887-1893`，仅日志）。 |
| ② | onBind 是否每 item 只搬一次、不重复 add | **通过** | `MobileShell:1845-1857` + `1923`（`if (content.getParent() == group) return;`） | 同一壳重复 bind 直接返回；不同壳先摘旧父再挂，不存在重复 `addView`；onBind 对 header/footer 只做挂载，不做业务绑定；`onCreateViewHolder` 不再给单例内容设置 LayoutParams（改由 `attachHomeSingleton` 以 `FrameLayout.LayoutParams(-1,-2)` 挂入，尺寸语义与旧一致）。 |
| ③ | `dy` 守卫是否影响首屏/触底自动加载 | **通过** | `MobileShell:1493-1520`（守卫 `1499` `if (dy <= 0) return;`） | 与旧 ScrollView 行为等价：旧实现靠 `onScrollChanged`，**首屏 submit 后无滚动位移时不触发**（新实现同样不触发，故该场景无回归）；到达底部必然伴随向下位移（dy>0），故不丢失"滚到底自动加载"。唯一差异是"从底部向上轻滚不再误加载下一页"（收紧，属改善）。建议真机确认：首屏下滑到底部仍会自动加载且只加载一页。 |
| ④ | 延迟补焦点早退 | **通过（含 P2 注记）** | `MainActivity:1810-1811`（`if (isFinishing()) return;`） | 覆盖 finish 场景；`isDestroyed()` 未判，配置变更重建的 320ms 窗口内仍可能执行一次（低风险，可顺手补 `|| isDestroyed()`）。 |

**快速复核结论：3 处防御改动均通过，无新增必须修。** R-11（追加页 `notifyDataSetChanged` 重建）按既定记录留到 E3-S2；4.3-① 的"崩溃级设计脆弱点"经此改动已从**结构上解除**（不再共享 itemView），最终以真机验证为准：仍建议真机验证首屏数据到达与「加载更多」两个时刻，并将结果回填本节。
