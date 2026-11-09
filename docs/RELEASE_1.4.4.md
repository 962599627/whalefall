# 鲸落 1.4.4 发布说明草稿（手机 / 平板 / TV）

> 状态：**待用户真机验证（V27）后发布**。1.4.4 = 1.4.3（已发布 v1.4.3）+ 壁纸生命周期统一修复（DEF-2023）。
> 维护：Lead ｜ 日期：2026-11-10 00:42

## 版本信息

| 项 | 值 |
|---|---|
| versionName / versionCode | **1.4.4 / 14004** |
| 构建 | `scripts/build_android.py`（干净提交 `3721e2f`，`git_dirty=false`）；R8 Gradle/AGP 8.7.3；arm64 单 ABI 同源码 |

| 文件 | 大小 | sha256 |
|---|---|---|
| `WhaleFall-1.4.4-android-mobile.apk` | 62,016,811 B | `b14033f39788a5cdb80499279a6d449aa8a823bdc824d7e23afe74261aea7fa1` |
| `WhaleFall-1.4.4-android-tv.apk` | 36,240,198 B | `f972d529df2f0c649cb6655b608f576d3672449ba635509d2acf136304b02751` |
| `WhaleFall-1.4.4-android-mobile-arm64.apk` | 35,580,502 B | `472126b667ffae33d0e7da46640036d213cd0796de25c1092b753e1bdfa192f4` |
| `WhaleFall-1.4.4-android-mobile-r8-arm64.apk` | 33,097,622 B | `a0e1dbd0e022f28d4736022c2ac12f96c6cacce0c211c75459e924b23d06c579` |

## 本版内容

1. **修复页面切换/返回/旋转后壁纸丢失或变成纯色**（DEF-2023）：
   - 统一幂等 `applyWallpaper`：base 渐变必在；静态壁纸层（`WALLPAPER_TAG`）保证唯一、缺则补；动态壁纸单例从旧页面迁移到新页面（先摘旧父、插到内容之下）。
   - 所有页面入口/返回栈重建点（`open/refresh/renderReturn/installBackdrop/ensureBackdrop` 及自定义 root 页面）都会补齐壁纸，不再出现「详情有、首页纯色」。
   - 页面切换不再调用 `releaseWallpaper()`（只在退出 App/关闭视频壁纸时释放）。
   - 静态壁纸文件缺失/解码失败写 `W/Wallpaper` 运行日志，不再静默退纯色；新增 `I/Wallpaper` 状态日志（状态变化才打）。
2. 含 1.4.3 全部内容（播放页点按/铺满、运行日志复制、TV 功能对齐与详情页重排、直播页壁纸等）。

## 验证与证据

- **B 类未采集**：`docs/VERIFY_1.4.4.md` V27——静态壁纸、动态壁纸各走一遍：首页→详情→选集→返回→播放→退出→切 5 tab→设置子页→文件管理→阅读器→返回→旋转一次；全程壁纸都在、始终是用户设置的那张/那段、不出现纯色；动态壁纸要求确实在动。
- 范围：只针对 **App 内设置的壁纸**（静态图片/动态视频），不含手机系统桌面壁纸。
- 已知限制：壁纸文件被用户删除/损坏时只能显示 base 渐变（会写 W/Wallpaper 日志）；TV 频道管理未适配等既有项不变。

## 发布前检查清单（用户执行）

- [ ] 手机 V27 通过（静态+动态各一遍）。
- [ ] 发布时：更新 `updates/*.json`、上传 Release v1.4.4（流程同 v1.4.3，含源码归档）。
