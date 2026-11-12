# 鲸落 TV 测试计划（按 `docs/QA_ANDROID_SOP.md` v1.0）

> 依据：`docs/QA_ANDROID_SOP.md`（提交 `7b4bcfb`，阶段 0–7 / 鲸落参数 / 冒烟门禁 / 性能阈值 / 缺陷等级 / Agent 执行循环）。
> 关联：`docs/test_cases.yaml`（机器可读用例）、`docs/DEFECTS.md`（缺陷台账）、`docs/android-phone-compatibility.md`。
> 角色：qa 出用例与判定（本文+`test_cases.yaml`）；lead 构建/装机/执行汇总；SOP 已注明平台限制：子代理无 shell 权限。

| 项 | 值 |
|---|---|
| 被测版本 | **1.9.164**（电视已装，启动 JNI/FATAL=0） |
| 回退基线 | **1.9.162**（1.9.164 出现 S1 时回退基线） |
| 形态 | TV（遥控器，10-foot UI） |
| 设备参数（SOP） | MiBox4C / Android 6.0.1（API 23）/ armeabi-v7a / 1920×1080 / WiFi / 遥控器 |
| 版本核对 | `adb shell dumpsys package com.whalefall.movie \| grep versionName` |
| 证据根目录 | `/sdcard/Download/DSH-Go/鲸落/verify/1.9.164-tv/` |
| 用例总数 | P0 15 + P1 8 + P2 2 = 25（`docs/test_cases.yaml`） |

---

## 1. SOP 阶段映射（0–7）

| SOP 阶段 | 本计划内容 | 输入 | 输出 | 准出标准 |
|---|---|---|---|---|
| **0 接入准备** | 设备/系统/分辨率/网络/版本/ADB 接入；证据目录建立；1.9.164 安装与 1.9.162 回退包就位 | 1.9.164 TV 包、MiBox4C、ADB、WiFi、1.9.162 回退包 | `env.txt`（getprop 全量）、`adb devices -l`、版本截图、`verify/1.9.164-tv/` 可写 | 版本与 APK 一致、ADB 在线、网络可达片源/直播源、证据目录可写、回退包 sha 已记录 |
| **1 计划用例** | 分模块设计可执行用例（步骤/预期/断言/证据/日志关键字）；P0 ≤15 | SOP、必测模块清单、DEFECTS 历史、1.9.163/164 修复点 | `docs/test_plan.md`、`docs/test_cases.yaml`、覆盖矩阵（§5） | 必测模块全覆盖；每条至少 1 类断言（UI/LOG/PERF）；无口头描述用例 |
| **2 环境矩阵** | 记录设备/ROM/分辨率/网络/输入方式；确认已知限制（API23 无通知权限、系统 WebView 旧、Android 6 未知来源开关） | 阶段 0 的 env.txt | 环境矩阵表（§2） | 设备参数齐、限制已知、异常场景（弱网/断网/存储权限）可构造 |
| **3 冒烟门禁** | SOP 门禁 4 条 + 冒烟用例 `WLTV-P0-001`~`WLTV-P0-004`（启动/首页/筛选/返回） | P0 用例 | `smoke.log`、冷启动计时、前后台切换记录 | ①`logcat -b crash` 无 FATAL；②`dumpsys activity` 无 ANR；③冷启动 ≤5s；④前后台切换 3 次不崩；任一项失败即阻断阶段 4 |
| **4 功能** | 15 条 P0 全量执行（首页发现/筛选/历史/播放器/直播/设置/壁纸） | P0 用例、测试数据 | 每条证据+日志断言+判定 | 15 条全「通过」；失败项按 §4 登记 S1–S4；P0 必测模块无「未验证」 |
| **5 专项** | 稳定/性能/弱网/功耗/安全/升级：`WLTV-P1-006`（20min 稳定+性能）、`WLTV-P1-007`（弱网/断网）、`WLTV-P1-008`（动态壁纸）、`WLTV-P1-003`（升级/更新）、权限与隐私静态核对 | 长播片源、路由器可控、更新源 | `meminfo-*.txt`、`gfxinfo-*.txt`、弱网/断网录屏、更新截图 | 性能阈值见 §3；20min 0 崩溃；弱网有提示可恢复；升级链路可用 |
| **6 回归发布** | P1 8 条 + P2 2 条 + `DEFECTS.md` 已修项回验；对照 1.9.162 回退基线做冒烟复测 | P1/P2 用例、缺陷台账 | 回归记录、缺陷状态更新、发布建议 | 无新增 S1/S2；已修项按 DEF 编号回验；1.9.162 回退基线冒烟通过（可回退） |
| **7 线上监控复盘** | 安装后 24h 看护：崩溃/ANR/性能日志汇总；复盘到 SOP 循环 | 用户侧反馈、`adb logcat`/crash 记录、证据包 | `docs/VERIFY_*.md` 归档、遗留项、复盘结论 | 无未处置 S1；遗留 S2–S4 已登记；证据索引可复核；结论与 `docs/SPRINT_STATUS.md` 一致 |

---

## 2. 环境矩阵（阶段 2 基线）

| 项 | 取值 | 采集/判定 |
|---|---|---|
| 机型 | MiBox4C | `adb shell getprop ro.product.model` |
| 系统 | Android 6.0.1 / API 23 | `getprop ro.build.version.release` / `ro.build.version.sdk` |
| ABI | armeabi-v7a | `getprop ro.product.cpu.abi` |
| 屏幕 | 1920×1080（1080p） | `adb shell wm size` / `wm density` |
| 网络 | WiFi（记录 SSID、2.4G/5G、实测带宽） | 设置→网络 + 速度测试 |
| 输入 | 遥控器（方向/OK/BACK/音量） | 全程不接鼠标 |
| ADB | 网络 ADB 已连接 | `adb devices -l` |
| 版本 | 1.9.164（回退 1.9.162） | `dumpsys package … \| grep versionName` |
| 已知限制 | API23 无通知权限模型；系统 WebView 旧需 X5 回退；Android 6「未知来源」为全局开关（APK 安装前置） | 写进 `env.txt`，判定时引用 |

**日志/证据规范**：

```bash
adb logcat -c
adb logcat -v time > /sdcard/Download/DSH-Go/鲸落/verify/1.9.164-tv/tv.log &
adb shell dumpsys package com.whalefall.movie | grep versionName
adb shell dumpsys activity activities | grep -i "ANR\|whalefall"
```

- 截图：`1.9.164-tv/<case_id>_<序号>.png`；失败项另存「正常对照」。
- 录屏：焦点/动画/卡顿类（`WLTV-P0-006`/`-007`/`-008`/`-009`、`P1-006`、`P2-002`）≥15s。
- 日志断言：把 tag+时间戳上下文摘入 `marks.txt`。
- 性能：`meminfo` 0/5/10/15/20min；`gfxinfo framestats` 60s/段。
- 判定：实际=预期且证据可复查才算通过；偶发（≤3/10）按缺陷记录；未执行写「未验证」，不得写「通过」。

---

## 3. 冒烟门禁与性能阈值（SOP 定义）

**阶段 3 冒烟门禁（4 条）**：
1. `adb logcat -b crash` 无 `FATAL EXCEPTION`；
2. `adb shell dumpsys activity` 无 `ANR in com.whalefall.movie`；
3. 冷启动（force-stop 后首次进入首页）≤ 5s；
4. 前后台切换 3 次不崩、不黑屏。

**阶段 5 性能阈值**：
- `gfxinfo framestats` 60s 内 janky 帧 < 5%；
- `meminfo` 20 分钟无持续增长（5 分钟后平稳，20min PSS 增长 ≤10%）；
- 稳定播放 20 分钟 **0 崩溃**、无 blocking GC 风暴、无温度降频导致的可见卡顿。

---

## 4. 缺陷等级与用例优先级映射（SOP 缺陷单字段）

| SOP 等级 | 含义 | 处置 | 对应用例优先级 |
|---|---|---|---|
| S1 致命 | 崩溃/ANR/数据丢失/核心不可用 | 立即阻断，回退 1.9.162 评估 | P0 |
| S2 | 严重功能受损 | 24h 内修 | P1 |
| S3 | 一般问题 | 本迭代修 | P2 |
| S4 | 建议/优化 | 排期 | P3 |

**缺陷单必填**（SOP）：标题 / 环境（机型·系统·版本·网络）/ 前置 / 步骤 / 实际 vs 预期 / 复现率 / 证据（截图·录屏·日志）/ 引入版本。

---

## 5. 必测模块覆盖矩阵（SOP 必测清单）

| SOP 必测模块 | 覆盖用例 |
|---|---|
| 首页发现 / 筛选三层焦点（tab↓→芯片→选项→卡片） | `WLTV-P0-002`、`WLTV-P0-003`、`WLTV-P0-004` |
| 历史清理 | `WLTV-P0-005` |
| 播放器·中央三键 / 底部按钮焦点 | `WLTV-P0-006`、`WLTV-P0-007` |
| 播放器·隐藏左右 ±10s | `WLTV-P0-008` |
| 播放器·选集小窗 | `WLTV-P0-009` |
| 直播·分类顺序/右侧信息/开源 IPTV/搜索/收藏/二级选择器 | `WLTV-P0-010`~`WLTV-P0-013` |
| 壁纸稳定性 | `WLTV-P0-015`、`WLTV-P1-008` |
| 设置（壁纸/内核/解码） | `WLTV-P0-014`、`WLTV-P1-004` |
| 文件管理 APK 安装 | `WLTV-P1-001`、`WLTV-P1-002` |
| 更新 | `WLTV-P1-003` |
| 启动冒烟/前后台切换 | `WLTV-P0-001` |
| 性能/稳定性/弱网 | `WLTV-P1-006`、`WLTV-P1-007` |

---

## 6. 日志关键字总表（断言用）

| Tag | 关键字（示例） | 用途 |
|---|---|---|
| `FilterLayer` | `tab-down to=`, `chip-down dim=… to=option:first`, `level=2 dim=… source=`, `clear category=` | 筛选三层焦点链 |
| `Filter` | `tap state=select/expand/collapse category=`, `capability … icons=true/false` | 分类切换/能力判定 |
| `FilterOk`/`FilterOption`/`FilterFocus` | `chip=… options=`, `focus=first dim=`, `clear category=` | 选项应用/焦点回位 |
| `PlayerFocus` | `focus=… zone=center`, `ok=play-pause action=toggle`, `text-button focus=… zone=buttons`, `default=play-pause` | 播放器焦点链 |
| `Player` | `key=left|right hidden=true action=seek-relative`, `repeat=N action=hint-only` | 隐藏控制条 ±10s / 长按不风暴 |
| `Mini`/`MiniFocus` | `enter size=`, `zone=mini focus=`, `ok=expand` | 选集小窗 |
| `IptvOrg` | `source=cache|asset channels=`, `try=… result=ok`, `filter=… selected=true`, `favorite channel=… on=`, `play-from-favorite`, `picker open current=`, `picker level=1 group=` | 直播列表/搜索/收藏/二级选择器 |
| `Wallpaper` | `page=… bg=transparent`, `video engine=… firstFrame=true`, `video page-change keep=true` | 壁纸稳定性 |
| `History` | `clear scope=home+live removed=` | 历史清理 |
| `LiveKernel`/`X5` | `mode=auto|x5|system used=`, `canUseX5=`, `x5 create failed mode=`, `init failed fallback=system-webview` | 直播内核选择/回退 |
| `Update` | `page=enter`, `try=… result=ok`, `page=fallback shown=true` | 更新 |
| `FileMgr`/`FileManager` | `scan root=`, `play local=`, `download … state=` | 文件管理 |
| `Memory` | `buffer=15s cache=64MB wallpaper=sample` | 长播内存基线 |
| 异常 | `FATAL EXCEPTION`, `ANR in com.whalefall.movie`, `GC_FOR_ALLOC`, `Skipped N frames` | 崩溃/卡顿判据 |

---

## 7. 执行顺序（本版）

阶段 3→4：`WLTV-P0-001`（冒烟 **\***）→ `P0-002`~`P0-004`（筛选焦点）→ `P0-006`~`P0-009`（播放器）→ `P0-010`~`P0-013`（直播）→ `P0-014`（设置/内核）→ `P0-015`（壁纸）。
\* P0-001 同时充当阶段 3 冒烟门禁，失败即停并按 S1/S2 处置。

阶段 5~6：P0 全过后执行 P1（文件管理→更新→设置持久化→换源/倍速/字幕→20min 性能→弱网→动态壁纸）与 P2 记录项；最后用 1.9.162 回退基线跑冒烟确认可回退。

**Agent 执行循环（SOP）**：前置检查 → 打点（`marks.txt` 时间戳）→ 执行（失败重试 1 次）→ 断言（UI/LOG/PERF）→ 采证 → 清理（force-stop/截图归档）→ 上报 JSON：

```json
{
  "run_id": "wl-tv-1.9.164-<yyyymmdd-hhmm>",
  "stage": "3-smoke|4-functional|5-special|6-regression",
  "device": "MiBox4C/Android 6.0.1/API23/1080p/WiFi",
  "app": {"versionName": "1.9.164", "versionCode": 19164, "apk_sha256": "<sha>"},
  "summary": {"total": 15, "pass": 15, "fail": 0, "blocked": 0, "untested": 0},
  "cases": [
    {"case_id": "WLTV-P0-001", "verdict": "pass|fail|blocked|untested", "asserts": ["UI_ASSERT", "LOG_ASSERT"], "evidence": ["1.9.164-tv/WLTV-P0-001_01.png"], "defect": null, "duration_s": 62}
  ]
}
```

---

## 8. 出口判定模板（阶段 7）

```
版本：1.9.164（versionCode 以 dumpsys package 为准；回退基线 1.9.162）
设备：MiBox4C / Android 6.0.1 / API 23 / armeabi-v7a / 1920×1080 / WiFi <SSID>
执行：P0 15/15，P1 8/8，P2 2/2（未执行项列原因）
冒烟：crash FATAL=0；ANR=0；冷启动=__s（≤5s）；前后台切换 3 次=通过
性能：gfxinfo janky=__%（<5%）；20min PSS 增长 __%（≤10%）；20min 崩溃=0
缺陷：S1=__，S2=__（DEF-xxxx），S3=__，S4=__
证据：/sdcard/Download/DSH-Go/鲸落/verify/1.9.164-tv/（marks.txt 为索引）
回退：1.9.164 不通过时回退 1.9.162（回退基线冒烟=通过/未验证）
结论：可发布 / 不可发布（条件：…）
未验证项与原因：
```
