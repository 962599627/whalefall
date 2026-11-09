# 鲸落 1.4.2 发布说明草稿（手机 / 平板 / TV）

> 状态：**待用户真机验证（V24）后发布**。1.4.2 = 1.4.1 全部内容 + 手机播放页控件内缩/不贴边修复（DEF-2020）。
> 维护：Lead ｜ 日期：2026-11-09 23:55 ｜ 上一版：1.4.1（TV 修复+详情页重排，未发布）

## 版本信息

| 项 | 值 |
|---|---|
| versionName / versionCode | **1.4.2 / 14002** |
| 构建 | `scripts/build_android.py`（干净提交 `5029551`，`git_dirty=false`）；R8 用 Gradle/AGP 8.7.3；arm64 单 ABI 同源码 |

| 文件 | 大小 | sha256 | 说明 |
|---|---|---|---|
| `WhaleFall-1.4.2-android-mobile.apk` | 62,012,715 B | `f13064c807f91ec0b7adbb3f084121cff5cfba29799c477abf39f9989a3bfd95` | **手机主测包** |
| `WhaleFall-1.4.2-android-tv.apk` | 36,240,198 B | `e76081697e5c243db245a502cf6610548b39682197128106f883c951c279d2a4` | TV/盒子 |
| `WhaleFall-1.4.2-android-mobile-arm64.apk` | 35,576,406 B | `8b0b407543c9a184eb444a678ab532b1be67b80b68de12a4226c9782b7fc0ed6` | arm64 单 ABI |
| `WhaleFall-1.4.2-android-mobile-r8-arm64.apk` | 33,096,750 B | `e87ce8e4679b0e31c5e22427626ea674cf4e0646cecddc47fdcf4ff153d74ef1` | R8+arm64（未真机验证） |

## 本版内容

1. **修复手机播放页控件整体内缩/不贴边**（DEF-2020）：全屏播放页的 chrome 不再避让状态栏/导航栏/手势区（只避让屏幕切孔），内缩统一交给 `PlayerChrome` 的 16/24dp 设计边距 → 顶部返回/标题/清晰度/更多、右侧时钟/影片结束、底部进度条与图标行回到屏幕边缘。
2. **修复视频 surface 可能停在旧尺寸/小卡**：窗口每次布局完成都重排 chrome 并重算 surface；surface 未测量时下一帧重试（最多 3 次），尺寸相同不重复设置。
3. **新增一次性诊断日志**：`设置 → 运行日志` 可见 `I/Player: layout layer=… surface=… overlay=… safe=[…] fill=…`（全进程一条）；若仍异常，把该行截图给支持方即可定位。
4. 含 1.4.1 全部内容：TV 文件管理返回修复、TV 详情页平板式两栏（52/48、选集 2 列、遥控焦点）、直播页壁纸透出；1.4.0 全部修复与 TV 功能对齐；首页虚拟化等。

## 验证与证据

- C 类：本轮修复仅 `PlaybackController/PlaybackControls/PlayerChrome`（+ 日志），代码量小；架构复核未完成（热修直接交付），风险已列在下。
- **B 类未采集**：`docs/VERIFY_1.4.2.md` V24（进播放页看视频/控件铺满 → 切画面比例 → 返回再进 → 旋转一次）；异常补 `Player` layout 日志截图。
- 已知风险：个别 ROM 全屏下系统导航条仍占位且切孔为 0 时，底部控件可能被导航条压住（旧逻辑不会）；如 V24 遇到，用日志确认后对 navigationBars bottom 做最小避让即可。
- **不得写成已验证**：V24、V21–V23、R8 包功能、首页虚拟化滚动数据。

## 发布前检查清单（用户执行）

- [ ] 手机包通过 V24；TV 包通过 V21–V23。
- [ ] `updates/android-*.json` 已更新 1.4.2/14002；Release tag `v1.4.2`。
- [ ] 覆盖安装后设置底部显示「鲸落 1.4.2」。
