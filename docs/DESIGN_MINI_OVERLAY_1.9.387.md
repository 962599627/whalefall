# 小窗叠加层改造（A 方案）设计 · 1.9.387

> 目标：在 MiBox4C（Android 6 / SDK23 / armv7）上做到「播放器 → BACK → 选集页小窗」画面连续，
> 不黑、不冻帧、不崩溃；手机/平板（SDK>=24）行为保持不变。
> 依据：2026-11-14 真机日志（见下「证据」），不是猜测。

## 一、必须同时满足的两条硬约束

1. **播放层（含解码输出 TextureView）从进入播放器到退出播放器，父节点不变、不 detach。**
   真机证据：换 root 后 `onSurfaceTextureDestroyed` → 重挂得到**新的** SurfaceTexture
   （`output available st=... sameBound=false`），重绑后只出最后一帧（冻帧）。
2. **解码输出 View 的布局尺寸全程不变。**
   真机证据：`resize layer=480x270 surface=480x270` / `resize layer=1920x1080 surface=1920x1080`
   之后 Amlogic 硬解 `OMX.amlogic.avc.decoder.awesome` 停止出帧，最严重时
   `ERROR_CODE_DECODING_FAILED`（MediaCodecVideoRenderer error）。音频不受影响 → 「卡住但在播放」。
   TextureView 尺寸变化会 `setDefaultBufferSize`，这颗芯片的硬解不接受运行中改 BufferQueue 尺寸。

结论：小窗/全屏切换 **只能缩放 View（`setScaleX/Y` + pivot），不能改 LayoutParams 尺寸，也不能换父**。

## 二、当前实现为什么必然违反约束

| 位置 | 行为 | 违反 |
| --- | --- | --- |
| `PlaybackController.playerPage` | `a.root = new FrameLayout(a)` + `setContentView` | 播放层从旧 root 被解绑 → 约束 1 |
| `MainActivity.renderPage`（BACK 回详情） | 再换一次 root | 约束 1 |
| `PlaybackControls.resize` | `surface.setLayoutParams(w,h)` | 约束 2 |
| `MiniDetailsLegacy.applyHeroRect` | 改 `playbackLayer` LayoutParams（会连带子 View 尺寸） | 约束 2（间接） |

## 三、改造设计（仅 SDK<24 且「从详情页进播放器」这条路径）

新增 `PlayerOverlay`（`android/src/main/java/com/whalefall/movie/PlayerOverlay.java`）：

```
static boolean attach(MainActivity a)   // 进入播放器：复用现有 root，建立叠加层
static void hidePage(MainActivity a)    // 把 root 里非叠加层子 View 设为 INVISIBLE（不 remove）
static void showPage(MainActivity a)    // 小窗/退出时恢复
static void detach(MainActivity a)      // 退出播放器：移除叠加层，恢复页面
static boolean active(MainActivity a)
```

结构（同一个 root，全程不换）：

```
root (FrameLayout, 唯一，setContentView 只做一次)
├── 页面视图（scroll/body/...）      ← INVISIBLE 隐藏，但保持 attached
└── playerOverlay (FrameLayout, MATCH_PARENT, 黑底)
    ├── playbackLayer (FrameLayout, **固定 window 尺寸**，不随小窗变化)
    │   └── VideoOutput → TextureView（尺寸恒定）
    └── playerChrome 的 dock/progress/overlay（从 PlaybackControls 侧挂到 overlay 而不是 root）
```

小窗/全屏切换：

```
playbackLayer.setPivotX(0); setPivotY(0);
setScaleX(w / layerBaseW); setScaleY(h / layerBaseH);
setTranslationX(x); setTranslationY(y);
```

- `layerBaseW/H` = 进入播放器时的 window 尺寸（固定），小窗 rect 由 `MiniDetails` 给出。
- 不调用 `renderPage`、不改 `playbackLayer` 的 LayoutParams、不 `setContentView`。

BACK 流程：

```
播放页 BACK
└─ minimizePlayback（overlay 模式）
   ├─ 不 renderReturn / 不 renderPage
   ├─ PlayerOverlay.showPage(a)              // 页面视图 VISIBLE，仍 attached
   ├─ 播放层按小窗 rect 做 scale/translate   // 尺寸不变
   └─ 建 MiniDetailsLegacy（操作条 + 可聚焦视频区）
小窗 BACK
└─ releasePlayer + PlayerOverlay.detach(a)   // 页面视图本来就是活的，直接可见
```

## 四、落地顺序（每步都要能编译 + 可回滚）

1. 新增 `PlayerOverlay`（纯新增，不改行为），`grep` 确认无编译冲突。
2. `playerPage`：`legacyMini() && current.detail` 时走 overlay 分支；否则老逻辑（手机/首页直进播放器不受影响）。
3. `PlaybackControls`：overlay 模式下 `resize` 只更新缩放（复用现有 `legacyMini()` 分支，改为读 overlay 的 base 尺寸）。
4. `minimizePlayback`：overlay 模式下跳过 `renderReturn`/`renderPage`，改为 `showPage` + 缩放。
5. `expandPlayback`：overlay 模式下只把 scale 复位 + 隐藏页面视图，不重建 root。
6. `releasePlayer`：`detach` 顺序放在播放器释放之后，避免已释放 Surface 被再次引用。
7. 回归清单：首页→历史首卡→播放→BACK（小窗连续画面）→BACK（回详情）→BACK（回首页释放）；
   小窗 全屏按钮、暂停/关闭按钮、遥控焦点、选集网格、壁纸、上次观看集数。

## 五、已知风险

- `PlayerChrome` 不是 View，`dock/progress` 由 `PlaybackControls` 挂载：overlay 模式下需要把挂载父容器
  从 `a.root` 换成 `playerOverlay`，否则控件会跑到页面视图下面（会被 INVISIBLE 连带隐藏的只有页面视图，不会影响 overlay）。
- 页面视图用 INVISIBLE 而不是 GONE，避免触发重新布局把细节页排版打乱。
- 首页直接进播放器（back 栈里没有详情页）仍走老路径；该路径的小窗画面问题单独评估（不在本次验收路径内）。
- 直播（live）不走此分支。

## 六、证据（真机日志摘录）

```
# 换 root 导致 detach + 新 SurfaceTexture（只出最后一帧）
I/Mini: output destroyed st=224395361 held=false cur=Surface(...@d600061)
I/Mini: output available st=165013812 ... sameBound=false curValid=false
I/Player: lifecycle resume surface=recreated revision=2 player=true

# 解码输出尺寸变化 → Amlogic 硬解停帧
I/Player: resize layer=480x270 video=1077x606 surface=480x270 mode=4
I/Player: decode=hw name=OMX.amlogic.avc.decoder.awesome error=ERROR_CODE_DECODING_FAILED
          msg=MediaCodecVideoRenderer error, index=0, format=… [1080, 606, …]

# 过渡期被误释放（已修）
I/Playback: policy target=home keep=false mini=true · release reason=page page=home
I/Mini: enter reason=player-back … player=false
```
