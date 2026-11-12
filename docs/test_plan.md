# 鲸落 TV 测试计划（SOP 阶段 0–7）

> 依据：`docs/QA_ANDROID_SOP.md`（用户 Android 测试 SOP · Agent 执行版）阶段 1 输出要求。
> 若 SOP 文件暂未落地，本计划按 SOP 的「阶段 0–7 + 用例字段」结构执行；字段/阶段名与 SOP 有出入时以 SOP 为准。
> 关联：`docs/test_cases.yaml`（机器可读用例）、`docs/DEFECTS.md`（缺陷台账）、`docs/android-phone-compatibility.md`。

| 项 | 值 |
|---|---|
| 适用版本 | 1.9.158 TV 包（`android/version.properties`，以实际安装包为准，证据目录名随版本） |
| 被测形态 | TV（遥控器操作，10-foot UI） |
| 目标设备 | MiBox4C / Android 6.0.1 / API 23 / 1080p / WiFi |
| 执行方式 | Lead 在电视真机执行；QA 产出计划/用例/判定标准；证据由执行者按本文规范归档 |
| 证据根目录 | `/sdcard/Download/DSH-Go/鲸落/verify/<version>-tv/`（例：`1.9.158-tv/`） |
| 用例总数 | P0 15 条 + P1 8 条 + P2 2 条 = 25 条（`docs/test_cases.yaml`） |

---

## 1. 环境与准入参数（阶段 0 基线）

| 参数 | 取值 | 采集方式 / 判定 |
|---|---|---|
| 设备型号 | MiBox4C | `adb shell getprop ro.product.model`，记录到 `env.txt` |
| Android 版本 | 6.0.1（API 23） | `adb shell getprop ro.build.version.release` / `ro.build.version.sdk` |
| 分辨率/密度 | 1080p（1920×1080，density 通常 320） | `adb shell wm size` / `wm density` |
| 网络 | WiFi，2.4G/5G 各记录 SSID 与实测带宽 | 设置→网络 + 速度测试截图 |
| ADB | 网络 ADB 已连接，`adb devices` 为 device | `adb devices -l` 输出入 `env.txt` |
| 输入方式 | 遥控器（方向/OK/BACK/音量） | 全程以遥控操作，不使用鼠标 |
| 时间同步 | 设备时间与北京时间一致（影响更新/日志） | 设置→日期时间截图 |
| 安装包 | sha256 与 `version.properties` 版本一致 | `sha256sum` + 设置→关于鲸落截图 |

**阶段 0 准出**：`env.txt`（设备/版本/网络/ADB 输出）+ 版本截图 + 证据目录可写，缺一不放行。

**日志采集（全流程）**：

```bash
adb logcat -c
adb logcat -v time > /sdcard/Download/DSH-Go/鲸落/verify/<version>-tv/tv.log &
# 关键时刻打点
adb shell 'echo "=== TC-xxx $(date) ===" >> /sdcard/Download/DSH-Go/鲸落/verify/<version>-tv/marks.txt'
```

**证据规范**：
- 截图：`<version>-tv/<case_id>_<序号>.png`；失败项额外存「正常对照」各 1 张。
- 录屏/动画/焦点类：`<case_id>.mp4`（≥15s）。
- 日志断言：从 `tv.log` 摘取对应关键字上下文（tag + 时间戳），粘入 `marks.txt`。
- 性能：`meminfo` 每 5 分钟一次（0/5/10/15/20min），`gfxinfo framestats` 60s 一段。
- **判定口径**：实际=预期且证据可复查才算通过；偶发（≤3/10）也按缺陷记录，不写「通过」；未执行写「未验证」，不得写「已通过」。

---

## 2. 阶段 0–7：输入 / 输出 / 准出标准

| 阶段 | 目标 | 输入 | 执行内容 | 输出 | 准出标准 |
|---|---|---|---|---|---|
| 0 环境与准入 | 确认可测 | APK、电视、ADB、WiFi | 设备/系统/分辨率/网络/版本/ADB 采集；证据目录建立 | `env.txt`、版本截图、`tv.log` 采集脚本 | 设备参数完整、版本与 APK 一致、ADB 在线、网络可达片源与直播源 |
| 1 计划与用例设计 | 可执行的用例集 | SOP 阶段 1、必测模块清单、DEFECTS 历史 | 分模块设计步骤/预期/断言/日志关键字；P0 限 15 条；定义证据路径 | `docs/test_plan.md`、`docs/test_cases.yaml`、覆盖矩阵（§6） | 必测模块全覆盖；每条用例有可判定断言（UI/LOG/PERF 至少一类）；P0 ≤15；无「口头描述」用例 |
| 2 冒烟/准入 | 确认包可用 | P0 中的启动/首页/播放/直播/返回 5 条 | 安装覆盖升级→冷启动→首页首帧→起播点播→起播直播→逐级返回 | 冒烟截图/录屏、`smoke.log` | 无启动崩溃/黑屏；点播与直播均能出画面；返回不退出/不卡死；否则停止后续并报 Lead |
| 3 P0 功能验收 | 核心链路通过 | 15 条 P0；测试数据（点播源 ≥1、直播源 ≥1、历史 ≥3、收藏 ≥3） | 按 `test_cases.yaml` 顺序执行，逐条落证据与日志断言 | 每条 P0 的截图/录屏 + 日志摘录 + 判定 | 15 条全部「通过」；或失败项已登记 P0/P1 且 Lead 书面接受；P0 必测模块无「未验证」 |
| 4 P1/P2 回归与边界 | 次要功能与历史缺陷不回归 | 8 条 P1 + 2 条 P2；`DEFECTS.md` 已修项 | 文件管理/APK 安装、更新、设置持久化、换源、倍速、搜索、长播、断网、动态壁纸 | 回归记录 + 证据 | 无新增 P1；已知 P2 现状与台账一致；修复项按 DEF 编号回验 |
| 5 性能与稳定性 | 无越播越卡 | 20 分钟点播、壁纸开/关、`meminfo`/`gfxinfo` | `WLTV-P1-006` 长播；`WLTV-P1-007` 断网恢复（顺带看 ANR）；壁纸稳定性并入 `WLTV-P0-013` | `meminfo-*.txt`、`gfxinfo-*.txt`、温度/帧率记录 | PSS 5 分钟后平稳（20min 增长 ≤10%）；janky 帧 <5%；无 `blocking GC` 风暴/ANR；无温度降频导致卡顿 |
| 6 兼容与异常 | 异常可回退 | 弱网/断网、存储权限、系统安装器、更新源、X5 内核 | APK 安装、更新检查/下载、断网、内核缺失回退、文件管理权限 | 异常场景截图 + 日志 | 均有明确提示且可恢复；不白屏/不崩溃/不静默失败；失败可重试 |
| 7 出口评审与归档 | 可复核的结论 | 全部证据、缺陷台账、用例执行记录 | 汇总通过率、未验证项、P0/P1 清单、证据索引 | QA 结论（可打包/不可）、证据清单、遗留风险 | 证据齐全可复查；P0=0 且 P1 已处置或 Lead 接受；未验证项如实标注；结论与 `docs/VERIFY_*.md` 一致 |

---

## 3. 优先级定义（与 SOP 对齐）

- **P0**：核心链路，失败即阻塞发布（启动/首页发现/筛选焦点、播放器控制、直播起播、壁纸显示、设置内核生效）。
- **P1**：重要功能或体验显著受损，可带风险发布但须登记并给出规避说明。
- **P2**：一般功能/视觉/文案问题，记录进 DEFECTS，按版本排期。
- **P3**：建议/优化项。

**失败处理**：P0 失败 → 立即报 Lead，暂停同模块后续用例；P1 失败 → 登记 DEF 并继续；连续 2 次同现象失败视为稳定复现，附 `tv.log` 上下文。

---

## 4. 日志关键字总表（断言用）

| Tag | 关键字（示例） | 用途 |
|---|---|---|
| `FilterLayer` | `tab-down to=`, `chip-down dim=… to=option:first`, `level=2 dim=… source=`, `clear category=` | 首页筛选三层焦点链 |
| `Filter` | `tap state=select/expand/collapse category=`, `capability … icons=true/false` | 分类切换/能力判定 |
| `FilterOk` / `FilterOption` / `FilterFocus` | `chip=… options=`, `focus=first dim=`, `focus=retry`, `clear category=` | 选项应用、焦点回位 |
| `PlayerFocus` | `focus=… zone=center`, `ok=play-pause action=toggle`, `text-button focus=… zone=buttons`, `default=play-pause` | 播放器焦点链 |
| `Player` | `key=left\|right hidden=true action=seek-relative`, `repeat=N action=hint-only` | 控制条隐藏 ±10s / 长按不风暴 |
| `Mini` / `MiniFocus` | `enter size=`, `zone=mini focus=`, `ok=expand` | 选集/小窗 |
| `IptvOrg` | `source=cache\|asset channels=`, `try=… result=ok`, `filter=… selected=true`, `favorite channel=… on=`, `play-from-favorite`, `picker open current=`, `picker level=1 group=`, `picker group=… switched=true` | 直播列表/搜索/收藏/二级选择器 |
| `Wallpaper` | `page=… bg=transparent`, `video engine=… firstFrame=true`, `video page-change keep=true`, `set-from-file type=image` | 壁纸稳定性 |
| `History` | `clear scope=home+live removed=` | 历史清理 |
| `LiveKernel` / `X5` | `mode=auto\|x5\|system used=`, `canUseX5=`, `x5 create failed mode=`, `init failed fallback=system-webview` | 直播内核选择与回退 |
| `Update` | `page=enter`, `try=… result=ok`, `page=fallback shown=true` | 更新检查 |
| `FileMgr` / `FileManager` | `scan root=`, `play local=`, `download … state=` | 文件管理 |
| `Memory` | `buffer=15s cache=64MB wallpaper=sample` | 长播内存基线 |
| 异常 | `FATAL EXCEPTION`, `ANR in com.whalefall.movie`, `GC_FOR_ALLOC`, `Skipped N frames` | 崩溃/卡顿判据 |

---

## 5. 设备与已知限制（影响判定）

1. Android 6.0.1 / API 23：无通知权限模型、系统 WebView 较旧；网页直播/播放需验证 X5 回退（`WLTV-P0-014`）。
2. 遥控器为唯一输入：所有用例以 DPAD/OK/BACK 执行，触摸类预期不适用。
3. Android 6 上系统「未知来源」为全局开关：APK 安装用例需先允许未知来源（属环境配置，不算应用缺陷）。
4. 若某阶段设备/账号/网络缺失，对应用例写「未验证（原因）」并计入 §7 遗留项，不得写通过。
5. 长播与 jank 判定依赖 adb 采样；无法采集时只能标「未验证」，不得凭主观描述下结论。

---

## 6. 必测模块覆盖矩阵

| 必测模块 | 覆盖用例 |
|---|---|
| 首页发现·筛选三层焦点（tab↓→芯片→选项→卡片） | `WLTV-P0-002`、`WLTV-P0-003`、`WLTV-P0-004` |
| 历史清理 | `WLTV-P0-005` |
| 播放器·中央三键/底部按钮焦点 | `WLTV-P0-006`、`WLTV-P0-007` |
| 播放器·隐藏时左右 ±10s | `WLTV-P0-008` |
| 播放器·选集小窗 | `WLTV-P0-009` |
| 直播·分类顺序/收藏置顶/右侧信息/开源 IPTV/搜索/收藏/二级选择器 | `WLTV-P0-010`~`WLTV-P0-013` |
| 壁纸稳定性 | `WLTV-P0-013`、`WLTV-P1-008` |
| 设置 | `WLTV-P0-014`、`WLTV-P1-004` |
| 文件管理·APK 安装 | `WLTV-P1-001` |
| 更新 | `WLTV-P0-015` |
| 首页·启动与基础可用 | `WLTV-P0-001` |
| 性能/稳定性 | `WLTV-P1-006`、`WLTV-P1-007` |

---

## 7. 出口判定模板（阶段 7 填写）

```
版本：<version>（versionCode=<code>）
设备：MiBox4C / Android 6.0.1 / API 23 / 1080p / WiFi <SSID>
执行：P0 15/15，P1 8/8，P2 2/2（未执行项列原因）
结果：P0 通过 X，失败 Y（DEF-xxxx）；P1 通过 X，失败 Y（DEF-xxxx）；P2 记录 Z
性能：20min PSS 增长 __%，janky 帧 __%，无/有 blocking GC 风暴
证据：/sdcard/Download/DSH-Go/鲸落/verify/<version>-tv/（清单见 marks.txt）
结论：可打包 / 不可打包（条件：…）
未验证项与原因：
```

**放行条件**：P0 全通过（或失败项有 Lead 书面接受）+ 无新增 P0 崩溃/白屏 + 无 20min 越播越卡证据 + 更新与安装可用。
