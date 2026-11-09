# 鲸落 1.4.6 发布说明草稿（手机 / 平板 / TV）

> 状态：**待用户真机验证（V31 + V27–V30）后发布**。1.4.6 = 1.4.5 全部内容 + 网盘登录 UI 统一（DEF-2027）。
> 维护：Lead ｜ 日期：2026-11-10 01:12

## 版本信息

| 项 | 值 |
|---|---|
| versionName / versionCode | **1.4.6 / 14006** |
| 构建 | `scripts/build_android.py`（干净提交 `02342b4`，`git_dirty=false`）；R8 Gradle/AGP 8.7.3；arm64 单 ABI 同源码 |

| 文件 | 大小 | sha256 |
|---|---|---|
| `WhaleFall-1.4.6-android-mobile.apk` | 62,020,907 B | `8852c4f65193e7c2e63ea014f5624fb60588ff89ed8d0f66c7fd1092eb555815` |
| `WhaleFall-1.4.6-android-tv.apk` | 36,248,390 B | `3c4340f276ac91d76cc462c13dbddeed165b19e908f327b907df9ad52894a603` |
| `WhaleFall-1.4.6-android-mobile-arm64.apk` | 35,584,598 B | `8cf37c7a1c6a194891b929295e9bef596cc2410d5e8494960cb26a5d3be2b1ef` |
| `WhaleFall-1.4.6-android-mobile-r8-arm64.apk` | 33,104,432 B | `3b3596697ad65e80957101734cdc11df667dedd47b6748a2a2e8722ee26165a3` |

## 本版内容

1. **网盘登录统一走 App 自己的玻璃面板**（DEF-2027）：
   - `MainActivity.sourceAction` / `SettingsScreen.accountAction` 对 `LoginShow` 统一拦截 → 先进入我们的「网盘登录」引导面板（4 个网盘、状态、步骤）；
   - 面板内「打开源登录界面」/「源账号管理（启用/停用）」前先弹我们的玻璃确认，确认后才打开来源方面板（源面板内部样式由配置源绘制，无法改）；
   - 返回后「我已登录，刷新状态」；`AccountPanel` 的「登录凭据配置」改名「源凭据配置」避免误入口。
2. 含 1.4.5 全部内容：播放页壁纸遮挡修复、检查更新多包体选择（标准/arm64/R8；TV 只显示 TV 包）、字体颜色自定义（预设/RGB/HEX、恢复默认、备份恢复）；以及 1.4.4 壁纸生命周期修复、1.4.3 播放页点按/铺满等。

## 验证与证据

- `docs/VERIFY_1.4.6.md`：V31（手机/TV：点登录先出玻璃面板 → 打开源面板 → 返回刷新；源面板内部样式不属验收失败项）。
- `docs/VERIFY_1.4.5.md`：V27 壁纸跨页面不丢、V28 播放页不被壁纸遮挡、V29 更新包体选择、V30 字体颜色。
- 已知限制：来源方面板内部（白底/红绿按钮/二维码）样式由配置源决定；TV 频道管理未适配等既有项不变。

## 发布前检查清单（用户执行）

- [ ] 手机/TV V31 通过；V27–V30 复验通过。
- [ ] 发布：feed 已含 variants；Release v1.4.6 上传 4 包 + sha + 4 feed + 源码归档（流程同 v1.4.3）。
