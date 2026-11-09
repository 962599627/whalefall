# 鲸落 1.4.9 发布说明草稿（手机 / 平板 / TV）

> 状态：**用于定位网盘登录数据存储位置（V34）**。1.4.9 = 1.4.8 全部内容 + 诊断 v2。
> 维护：Lead ｜ 日期：2026-11-10 01:56

## 版本信息

| 项 | 值 |
|---|---|
| versionName / versionCode | **1.4.9 / 14009** |
| 构建 | `scripts/build_android.py`（干净提交 `5520416`，`git_dirty=false`）；R8 Gradle/AGP 8.7.3；arm64 单 ABI 同源码 |

| 文件 | 大小 | sha256 |
|---|---|---|
| `WhaleFall-1.4.9-android-mobile.apk` | 62,037,291 B | `cd2a9e66424ef3da23804e08545a0ac4b49a9452bf41921ff82f5acc36648f01` |
| `WhaleFall-1.4.9-android-tv.apk` | 36,260,678 B | `658b51e004619e12f5a0291be069718eac028b500f9f6061cf550b1ae865345c` |
| `WhaleFall-1.4.9-android-mobile-arm64.apk` | 35,600,982 B | `4002dfab02599a51523f0bcccb68ea71e64630fae934cb65ba2e05dd78380bca` |
| `WhaleFall-1.4.9-android-mobile-r8-arm64.apk` | 33,118,742 B | `f982c78f0a239a8d388bcb7e92562a87379ccfedb36cc652485a52de673287b8` |

## 本版内容

1. **诊断 v2**（`PanLoginState.diagnose`）：「诊断登录数据」一键输出
   - prefs 文件名清单；`files/`、`no_backup/`、`databases/`、`cache/`、`external_files/`、`app_webview/` 各目录文件数与总大小；
   - 最近修改 Top15 文件（路径/大小/mtime，登录刚写入的文件最可能在这里）；
   - DB 文件与 `app_webview/**/Cookies` 大小；
   - CookieManager 每域名长度（`diagnose cookie dom=… len=…`）；
   - 扩大字节搜索：≤800 文件/单文件≤8MB/总读取≤64MB，强 token 或 cookie 形态串命中记路径；
   - 全部不含 cookie/token 值。
2. **无法读取时文案**：明确「本机未发现可读取的登录数据（插件可能加密存储/仅内存）→ 可到源面板确认或手动标记」。
3. 保留 1.4.8 的取证扩大/手动标记/缓存去重，以及 1.4.7/1.4.6/1.4.5/1.4.4 全部内容。

## 验证与证据

- `docs/VERIFY_1.4.9.md` V34：点「诊断登录数据」后把 `I/PanLogin diagnose …` 整段回传；能定位到存储位置即通过（不要求自动读取成功）。
- 1.4.8 V33 实测未过（`prefs=8 prefHits=0 files=300 fileHits=0 cookies=[]`），DEF-2029 保持未解决，等本次日志。

## 发布前检查清单

- [ ] 收到 `PanLogin diagnose` 全量日志并定位存储位置。
- [ ] 据此实现精确解析后，再随下一版发布。
