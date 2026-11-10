# 开发新功能建议（DEV，3 个）

1. **画中画播放（PiP）**
   实现：复用现有 `PlaybackControls`/`PlaybackController` 与 `playbackLayer`，MainActivity 声明 `supportsPictureInPicture`，进入/退出用 `PictureInPictureParams.Builder`（Android 8+，低版本降级小窗浮层）；播放/暂停沿用现有回调。
   工作量：M

2. **跨源续播（换源不丢进度）**
   实现：复用 `WatchHistory`（已记录 site/line/episode/position）与 `PlaybackController.resolvePlayer`/`savePlaybackHistoryOnPrepared`；换线路/换源时按影片名+集名匹配最近进度并 seek，详情页加「从上次位置继续」入口。
   工作量：S

3. **网盘连续播放队列**
   实现：复用 `QuarkUcFiles` 列目录 + `QuarkUcApi.downloadUrl` + 现有 `Cms.Line/Episode` 模型，把同目录视频按文件名顺序加入临时播放队列，播放器「下一集/自动连播」复用 `PlaybackControls` 的切集逻辑（`changeEpisode`）。
   工作量：M
