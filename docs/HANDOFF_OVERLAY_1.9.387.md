# 交接：小窗叠加层改造进度（1.9.387）

> 仓库 `/root/workspace/marcomovie`，分支 `tv-logic-fixes`，工作区未提交。
> 设备 MiBox4C（Android 6 / SDK23 / armv7），adb `192.168.10.2:5555`。
> 交付包：`/sdcard/Download/DSH-Go/鲸落/WhaleFall-1.9.387-android-tv.apk`

## 一、已经确定的事实（真机日志，不要再推翻）

1. Android 6 + Amlogic 硬解（`OMX.amlogic.avc.decoder.awesome`）：
   - **解码输出 View 尺寸一变就停帧**，严重时 `ERROR_CODE_DECODING_FAILED`。音频继续 → 「卡住但在播放」。
   - 触发点：`PlaybackControls.resize()` → `surface.setLayoutParams(480x270 / 1920x1080)`。
2. 播放层被换父/换 root 会 detach 解码输出 TextureView；重挂拿到**新的** SurfaceTexture
   （`output available ... sameBound=false`），只出最后一帧。
3. 正确方向 = **播放层全程不 detach + 解码输出尺寸全程不变**，小窗只做 View 缩放。
   设计文档：`docs/DESIGN_MINI_OVERLAY_1.9.387.md`。

## 二、1.9.387 已实现

- 新增 `PlayerOverlay.java`：在**现有 root** 上叠全屏容器，播放层固定尺寸挂进去；
  页面子 View 用 INVISIBLE 隐藏（保持 attached）；小窗用 `scaleTo(x,y,w,h)`。
- `PlaybackController.playerPage`：`legacyMini() && current.detail` → 走 overlay 分支，不再 `setContentView`。
- `minimizePlayback/expandPlayback`：overlay 模式走 `minimizeOverlay/expandOverlay`，不调用 `renderReturn/renderPage`。
- `releasePlayer`：`PlayerOverlay.detach()`。
- `PlaybackControls.resize`：overlay 模式只 `PlayerOverlay.reset()`，不改尺寸。
- `MiniDetailsLegacy`：overlay 模式 `placeInPosterSlot` 返回 false，公式分支改调 `scaleTo`。

## 三、1.9.387 真机结果（未通过）

- 小窗**布局正确**（画面在选区内、操作条/壁纸/选集/上次观看都正常），但**画面仍冻帧**。
- 日志显示 overlay 生命周期没稳住：

```
14:55:26 resize layer=480x270 ... surface=480x270 mode=4   # 仍走了老 resize（说明 overlay 非 active）
14:55:28 Mini: overlay detach
14:55:31 Mini: overlay detach
14:55:31 Mini: output destroyed ...                       # 仍发生 detach
14:55:31 Mini: output available ... sameBound=false
14:55:38 resize layer=1920x1080 ... surface=1920x1080
```

## 四、下一步（按顺序做，不要跳）

1. 在 `playerPage` 开头打印 `overlayPlayer`、`PlayerOverlay.active`、`releasePlayer` 调用栈来源，
   确认 **`releasePlayer(a)`（playerPage 里第一段）是不是把刚 attach 的 overlay 拆掉了**：
   现在顺序是 `releasePlayer → PlayerOverlay.detach → attach`，但 `resolvePlayer` 可能在播放中重入，
   导致 overlay 被 detach 后没有重建。
2. `PlaybackControls.resize` 的 overlay 守卫没生效，说明进小窗那一刻 `PlayerOverlay.active(a)==false`。
   需要在 `minimizeOverlay` 里校验并打印；若为 false，说明 overlay 分支根本没走到。
3. 修掉以上两点后，复测顺序：
   首页→历史首卡→播放→BACK（小窗应连续出画，比对间隔 3 秒两张截图不同）→
   小窗「全屏」（不冻）→ BACK（回详情）→ BACK（回首页释放、遥控可用）。
4. 手机/平板（SDK>=24）不受影响，改完必须回归：播放→BACK 小窗→放大→返回释放。

## 五、其它已验证可用的修复（别回退）

- 历史集数：`activeEpisodeIndex()`，`indexOf` 失效按集名回退，不再写 -1。
- 回主页释放：`releasePlaybackOnPageChange` 拦截条件收紧为「目标页是详情页才拦」。
- 小窗过渡保护：`miniTransitionGuard`（过渡一帧内禁释放）。
- 旧机小窗中央键不再用 `▶`（缺字形豆腐块），改文字。
- `VideoOutput.holdSurface()`（旧机小窗期不解绑/不 release，兜底）。
