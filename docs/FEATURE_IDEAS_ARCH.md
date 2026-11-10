# 架构视角新功能建议（3 条，1.7.10 之后）

| 名称 | 技术方案要点 | 风险 | 工作量 |
|---|---|---|---|
| **画中画 + MediaSession 播放层** | 以现有 `MoviePlayer/SystemPlayer` 为播放源，抽一层 `PlaybackSession`（`MediaSessionCompat` + 通知 + 音频焦点）；`MainActivity` 声明 `supportsPictureInPicture`，`onUserLeaveHint/onPictureInPictureModeChanged` 进入/退出 PiP；手机专属，TV/WebView 直播页走"不支持"降级。 | `targetSdk 28` 下 OEM PiP 行为差异；`TvLive` WebView 播放无法进 PiP；退出 PiP 与现有 `PlayerChrome`/`releasePlayer` 生命周期、旋转、小窗（`MiniDetails`）三态易冲突；需真机矩阵验证。 | M |
| **端到端加密的增量备份与网盘同步** | 复用 `SettingsScreen` 备份 JSON 与 `Prefs.sanitizeSetting` 白名单，只导出 config 摘要 + settings + 历史/收藏增量；本地用 Android Keystore 派生密钥 AES-GCM 加密后写入文件/网盘（复用 `PanLoginGuide` 的网盘登录态与 CSP 存储），恢复时版本化迁移 + 校验和。 | 密钥丢失即不可恢复（需明确"不托管、可导出恢复码"）；插件/网盘存储命名不稳定（`js-source-*` 会随 key 变）；历史含标题等隐私需脱敏开关；恢复冲突策略（合并/覆盖）易做错。 | M |
| **巨型 UI 类拆分 + 构建期质量门禁** | 把 `MobileShell`（4500+ 行）/`HomeScreen`/`TvLive` 按"页面/适配器/工具"拆包（不改行为，先机器可验证的纯搬移）；Python 构建链加门禁：`format_java --check`、单文件行数/方法数阈值、javac lint、`FrameMetrics/JankStats` 基线脚本与崩溃聚合（复用 `AppLog` 脱敏口径）。 | 大文件拆分与 1.7.x 并行开发冲突高（需写窗口+纯搬移 commit）；门禁阈值过严会拖慢发版；性能基线在无设备时只能"未采集"，不能宣称优化。 | M |
