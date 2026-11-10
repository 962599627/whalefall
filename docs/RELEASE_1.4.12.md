# 鲸落 1.4.12 发布说明草稿（手机 / 平板 / TV）

> 状态：**待用户 V37 验证**。1.4.12 = 1.4.11 全部内容 + Base64 源存储凭据读取（DEF-2029 收口）。
> 维护：Lead ｜ 日期：2026-11-10 14:01

## 版本信息

| 项 | 值 |
|---|---|
| versionName / versionCode | **1.4.12 / 14012** |
| 构建 | `scripts/build_android.py`（干净提交 `f1028f6`，`git_dirty=false`）；R8 Gradle/AGP 8.7.3；arm64 单 ABI 同源码 |

| 文件 | 大小 | sha256 |
|---|---|---|
| `WhaleFall-1.4.12-android-mobile.apk` | 62,041,387 B | `c7973213be3be96973d9fc796f88676a28a39455fae7e78e3e3c8cbc85962502` |
| `WhaleFall-1.4.12-android-tv.apk` | 36,268,870 B | `adc09b2cd7b293a726cc5cd75d461e99c78a262ef39fc39112f23a1e521103f2` |
| `WhaleFall-1.4.12-android-mobile-arm64.apk` | 35,609,174 B | `fc57844f3a779990c58c79ad771dc614c93096603256adacbeb9a9af129be721` |
| `WhaleFall-1.4.12-android-mobile-r8-arm64.apk` | 33,122,024 B | `1c5a49c4e89c440d0b9960e193ea9b932a724792cf88e778907cc340af055415` |

## 本版内容

1. **定位结论**（1.4.11 V36）：源把登录 Cookie 以 **Base64** 存在 `spUtils` prefs：`quark_ck`（168B Base64 夸克 Cookie）、`quarkSt`（32B Base64）等；Cookie 库 8 行是 CCTV、files/databases 均无关。
2. **Base64 解码识别**：扫描所有 prefs 值，Base64 且解码后可打印 → 用「cookie 形态 + 强 token（kps=/__puus/__pus/bduss/uc_st/refresh_token/…）」判定网盘；命中即 `STATE_SOURCE`，面板显示「**源已登录（源存储登录凭据 · spUtils · quark_ck）**」。
3. **防误报**：`ff_sp.jpDeviceId` 等设备 ID 因无 cookie 形态/强 token 不判登录；键名本身不作判据。
4. 诊断新增 `diagnose decode spUtils.quark_ck b64=yes drive=quark matched=yes`（不含值）。
5. 含 1.4.11/1.4.10/1.4.9 的 Cookie 库、DB、诊断 v2/v3 等全部内容。

## 验证与证据

- `docs/VERIFY_1.4.12.md` V37：装 1.4.12 → 我的夸克应显示「源已登录（源存储登录凭据 · spUtils · quark_ck）」；未登录网盘不误绿；异常补 `diagnose decode` 日志。
- 技术边界：若源改用加密/压缩后 Base64，仍可能读不出（显示无法读取+手动标记）。

## 发布前检查清单

- [ ] V37 通过。
- [ ] 确认后准备 GitHub Release（4 包 + 含 variants 的 feed + 源码归档）。
