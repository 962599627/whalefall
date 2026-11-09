# 鲸落 1.4.8 发布说明草稿（手机 / 平板 / TV）

> 状态：**待用户 V33 验证 / 诊断日志回传**。1.4.8 = 1.4.7 全部内容 + 登录态取证扩大 + 诊断 + 手动兜底（DEF-2029）。
> 维护：Lead ｜ 日期：2026-11-10 01:48

## 版本信息

| 项 | 值 |
|---|---|
| versionName / versionCode | **1.4.8 / 14008** |
| 构建 | `scripts/build_android.py`（干净提交 `4d7bcbc`，`git_dirty=false`）；R8 Gradle/AGP 8.7.3；arm64 单 ABI 同源码 |

| 文件 | 大小 | sha256 |
|---|---|---|
| `WhaleFall-1.4.8-android-mobile.apk` | 62,033,195 B | `f52dc773f16c5c24d44fe1010251bcde706bc9543665bb4e94b4b2bcb2656b82` |
| `WhaleFall-1.4.8-android-tv.apk` | 36,260,678 B | `2f60ac5adfdf6e8dcae3d3c65cbc54724980ba60d28df1406e50879286c81f2a` |
| `WhaleFall-1.4.8-android-mobile-arm64.apk` | 35,596,886 B | `ea9875694cabed15c3431cc67c6077bd3985c46315c16765d290ea7fcdc25428` |
| `WhaleFall-1.4.8-android-mobile-r8-arm64.apk` | 33,116,817 B | `67017421fe4495644bda47bb455d7c3410c499310b888c8bd15d1f6501543ec7` |

## 本版内容

1. **登录态取证扩大**（DEF-2029）：全部 shared_prefs（除 movie 系列）按「值形态 + 强凭据 token（`kps=`/`bduss`/`uc_st`/`refresh_token`/`stoken=`/`__puus=`/`__pus=`）」判定；递归扫描 App 私有目录小文件（≤512KB、最多 300 个，跳过二进制/媒体）；命中显示「源存储登录数据（文件/键名）」。
2. **诊断**：面板新增「诊断登录数据」按钮，扫描摘要写 `I/PanLogin scan prefs=… prefHits=… files=… fileHits=… cookies=[…]` 与 `diagnose …`（只写文件名/键名/域名，不含 cookie/token 值），用于定位插件存储位置。
3. **手动兜底**：自动检测仍 unknown 时，可在网盘行「我已在源里登录（手动标记）」，显示「已登录（用户确认，非自动检测）」；不会伪装成自动检测。
4. **去重**：60s 缓存 + 1.5s 强制突发守卫，避免重复扫描刷日志。
5. 含 1.4.7（三态检测/防误报/架构复核 5 项）、1.4.6（登录入口统一）、1.4.5（播放页壁纸遮挡/更新多包体/字体颜色）、1.4.4（壁纸生命周期）全部内容。

## 验证与证据

- `docs/VERIFY_1.4.8.md` V33：源内已登录夸克 → 刷新应显示凭据证据或手动标记；点「诊断登录数据」后日志有 `PanLogin scan` 行；手机/TV 各一张截图。
- 已知限制：若插件把会话加密存 DB 或只存内存，仍显示「由源管理 · 无法读取」+ 手动确认；检测为启发式，最终以源面板为准。

## 发布前检查清单（用户执行）

- [ ] V33 通过或提供 `PanLogin scan` 日志定位。
- [ ] 发布：feed 已含 variants；Release v1.4.8 上传 4 包 + sha + 4 feed + 源码归档。
