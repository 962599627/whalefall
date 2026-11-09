# 鲸落 1.4.10 发布说明草稿（手机 / 平板 / TV）

> 状态：**待用户 V35 验证**。1.4.10 = 1.4.9 全部内容 + 登录态精确读取（Cookie 库 / databases / 原始字节）。
> 维护：Lead ｜ 日期：2026-11-10 02:04

## 版本信息

| 项 | 值 |
|---|---|
| versionName / versionCode | **1.4.10 / 14010** |
| 构建 | `scripts/build_android.py`（干净提交 `5b84a74`，`git_dirty=false`）；R8 Gradle/AGP 8.7.3；arm64 单 ABI 同源码 |

| 文件 | 大小 | sha256 |
|---|---|---|
| `WhaleFall-1.4.10-android-mobile.apk` | 62,041,387 B | `a60f81b18ec9d997d4e080604c6e241fc05ae356c5b44265e78207902c78ce87` |
| `WhaleFall-1.4.10-android-tv.apk` | 36,264,774 B | `92a80c3ceb8539a3900b5611f81891ec08d3937c382e409a3b3c9bd766ffcdae` |
| `WhaleFall-1.4.10-android-mobile-arm64.apk` | 35,605,078 B | `211583a77d4ae26098b2da42f906fb076757387f3927a1f8e74c97b16f3a828f` |
| `WhaleFall-1.4.10-android-mobile-r8-arm64.apk` | 33,120,998 B | `e4153e43a30f150a1426ee4703a6b020434d59ec4349b320d3389c1b52fbcf59` |

## 本版内容

1. **直接读 WebView Cookies SQLite 库**：CookieManager 返回空时，复制 `app_webview/Default/Cookies` 到 cache 后只读打开，按 host 后缀找网盘登录 Cookie 名（`__puus`/`_uc_s`/`bduss`/`refresh_token`/`aliyun`）；命中显示「源已登录（WebView Cookie 库 host=… name=…）」。
2. **解析 databases/*.db**：sqlite_master 列表 + 每表前 20 行字符串值走同一套凭据判定，命中记 `db:<文件>.<表>`（不含值）。
3. **原始字节扫描**：不再跳过含 NUL 的文件（SQLite/DB 可命中），大文件前后各 2MB 采样。
4. **诊断精简**：各目录 Top5 最近文件、`diagnose cookie-db rows/loginHits/anyHits`、`dbhit`、`bytescan` 日志。
5. 含 1.4.9 诊断 v2、1.4.8 取证扩大/手动标记、1.4.7 三态检测/防误报、1.4.6 登录入口统一、1.4.5 播放页壁纸/更新多包体/字体颜色等全部内容。

## 验证与证据

- `docs/VERIFY_1.4.10.md` V35：源内已登录夸克 → 面板刷新应显示可确认凭据证据（Cookie 库/DB/文件）；仍 unknown 则回传 `diagnose cookie-db …` 日志。
- 已知限制：若 Cookie 库加密/未落盘、DB 加密或插件仅存内存，仍可能读不到（会明确写「由源管理·无法读取」）；手动标记可兜底。

## 发布前检查清单

- [ ] V35 通过或回传诊断日志定位。
- [ ] feed 已含 variants；Release v1.4.10 上传 4 包 + sha + 4 feed + 源码归档。
