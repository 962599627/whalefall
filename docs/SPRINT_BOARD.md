# 鲸落 Android · Sprint Board

> 维护者：scrum-master ｜ 更新时间：2026-11-09 21:00 +08:00
> 版本基线：`android/version.properties` = **1.2.2 / 12020**（唯一版本源）
> 构建基线：`android/build/build-validation.json` — 1.2.2 双包已出（TV 36.2 MB / mobile 62.0 MB），
> `git_commit=31655ec`、**`git_dirty=true`**、`device_install_tested=false`、`device_playback_tested=false`
> 发布路径：`scripts/build_android.py` → 拷 APK 到 `/sdcard/Download/DSH-Go/鲸落/` → 用户覆盖安装

## 0. 状态图例与完成定义

| 状态 | 含义 |
|---|---|
| todo | 未开始 / 已排期 |
| doing | 有明确负责人正在做 |
| review | 改动已落地，等回归或等真机证据（**本轮主战场**） |
| blocked | 有外部阻塞，需 Lead / 产品拍板或环境支持 |
| done | 有可复核证据（构建产物 / 截图 / 录屏 / 日志） |

优先级：**P0** 崩溃与安全（不做不行）、**P1** 性能与稳定性、**P2** 架构与体验债。
完成定义（DoD）：代码改动 + 自测说明 + 以下之一 — ①构建产物字段 ②真机截图/录屏 ③App 内「运行日志 / 崩溃日志」摘要。**无证据不转 done。**

## 1. 当前 Sprint（S1 · 1.2.2 回归收口，11-09 ~ 11-10）

目标：**把已修的缺陷在真机上验收掉，产出 1.2.2 的干净发布证据**；本轮原则上不开新功能、不动大文件。

| ID | 项目 | 负责人 | 状态 | 依赖 | 风险 | 证据 / 验收 |
|---|---|---|---|---|---|---|
| S1-01 | 1.2.2 双包与校验记录 | lead | done | — | 低 | `build-validation.json`：版本 1.2.2/12020、签名与 zipalign 校验通过 |
| S1-02 | 片库 holder 崩溃（已修待回归） | qa-engineer（回归）/ mobile-dev（待命） | review | S1-01 | 低 | 真机「我的」页 4 条路径无崩溃 + 空态/有数据态切换；代码侧防御见 `MobileShell.java:3237` |
| S1-03 | 直播控制条卡顿（已修待回归） | qa-engineer / tv-ui-designer | review | S1-01 | **中** | 真机直播 60s 录屏：呼出/收起 5 次、切台 3 次；主观帧感描述（哪一步卡） |
| S1-04 | 手机/平板方向与布局回归 | qa-engineer / tv-ui-designer | review | S1-01 | **中** | 手机竖/横、平板竖/横、TV 各 1 张截图；对照 `docs/TV_LIVE_RESPONSIVE.md` §1.2/§2、`docs/LIBRARY_LAYOUT_SPEC.md` §1–2；方向实现见 `TvLive.java:279-287` |
| S1-05 | 频道管理导入导出验证 | qa-engineer | review | S1-01 | 低-中 | 导出后 `文件管理/频道配置/全部频道配置.json` 存在；内置频道改址/隐藏 + 自定义频道导入回显；实现见 `ChannelManagerScreen.java:538,574` |
| S1-06 | 设置页版本号硬编码 1.2.0 | mobile-dev | todo | — | 低 | `SettingsScreen.java:437` 文案硬编码「鲸落 1.2.0」，包为 1.2.2 → 改为读注入版本/`version.properties`；0.2 人日 |
| S1-07 | 首页网格虚拟化 | mobile-dev | todo | S2 排期 | 中 | 现状 `MobileShell.renderHomeTab()`（`:810`）仍是 ScrollView 全量构建；**本轮不动**，见 S2-01 |
| S1-08 | AGP9 + Measured Baseline Profile | android-auditor | **blocked** | 真机/模拟器 + 镜像 | 高 | AGP9 需 Gradle 9.x + 依赖镜像；实测 BP 需 macrobenchmark + 可 profile 设备（本机无 adb）。已交付手写 BP：`docs/BASELINE_PROFILE.md`、`baseline.prof` 已进包 |
| S1-09 | 安全 P0 收口复核 | android-auditor / mobile-dev | review | S1-01 | **中** | P0-1 已落地（AdminConsole token + 403，`:141,218,258`）；P0-3 已清除（全仓无 `String.join`/`Files.*`/`removeIf`）；**P0-2 部分**：FTP 已限局域网 + 一次性 6 位口令（`MiniFtpServer.java:87-117`），但仍明文、固定 2121、RETR/DELE 开放、无 TTL |
| S1-10 | 文档与变更记录同步 | pm-owner | todo | S1-06 | 低 | `CHANGELOG.md` 停在 0.5.3；`docs/STATUS.md` 0.4.16；`docs/BUILD.md:19` 0.4.33-preview；需补 1.2.2 首条变更记录 |
| S1-11 | 真机验证执行（用户） | 用户 / qa-engineer 出清单 | doing | S1-01 | **高** | 见 §2 队列 V1–V5；证据统一收 `/sdcard/Download/DSH-Go/鲸落/verify/1.2.2/` |
| S1-12 | 1.2.2 干净发布（commit + tag） | lead | todo | S1-02~S1-05 有证据 | **高** | 当前包来自 dirty 工作区（GPL 对应源码不可复现）；收口后统一提交并打 tag，再重出包 |

**本轮已完成（供 Lead 汇报引用）**：P0-1/P0-3 安全与 API 崩溃修复落地；片库/直播两处体验修复落地待验；Gradle PoC 跑通（AGP 8.7.3 + R8 + mobile/tv flavor，mobile release 32.6 MB）；手写 baseline profile 进包；TV_LIVE/片库两套响应式规范与设计系统文档就绪。

## 2. 待用户真机验证队列（证据是唯一出口）

本机 proot 无 sandbox、无障碍/ADB 常不可用 → **QA 出用例与静态核对，证据由用户在设备上给**。
每项固定：操作路径 + 期望 + 证据。截图/录屏文件名建议 `V1.png`、`V2.mp4`；崩溃时补「设置 → 查看崩溃日志」截图；异常时补「设置 → 查看运行诊断日志」截图。

| ID | 验证项 | 操作（≤3 步） | 期望 | 证据 |
|---|---|---|---|---|
| V1 | 覆盖安装 1.2.2 | 安装 `/sdcard/Download/DSH-Go/鲸落/` 包 → 设置 → 检查软件更新 | 显示 1.2.2 / 12020，可正常启动 | 安装成功 + 版本页 1 张 |
| V2 | 片库崩溃回归 | 我的 → 观看历史/收藏/下载 往返切换 ≥10 次 → 清空历史 → 空态 → 删单条 | 不闪退、不白屏、列表正确刷新 | 30s 录屏（崩溃则加崩溃日志） |
| V3 | 直播控制条卡顿 | 电视台 → 任一频道 → 点画面呼出/收起 ×5 → 切台 ×3 → 退出重进 | 呼出/收起跟手，掉帧不持续 | 60s 录屏 + 一句「哪一步卡」 |
| V4 | 方向与布局 | 手机竖/横、平板竖/横（如有）、TV 首页/直播/片库 | 无锁错方向、无大空白、无文字重叠 | 截图 4–6 张 |
| V5 | 频道管理导入导出 | 频道管理 → 导出全部 → 文件管理看 `频道配置/全部频道配置.json` → 改址+隐藏 → 导入 | 文件生成；导入后改址/隐藏/自定义均生效 | 3 张截图（toast / 文件 / 导入后列表） |
| V6 | 首页网格滚动（S2 后） | 首页滚动到底、返回再进 | 首屏快、滚动不掉帧、无错位 | 30s 录屏 |
| V7 | 诊断入口可用性 | 设置 → 查看运行诊断日志 / 查看崩溃日志 | 能打开、能复制/另存 | 各 1 张截图 |

> 真机条件不足时的降级：V2/V3/V4 至少给「手机真机 + TV」两组；平板缺失时标「未覆盖」，不允许写「已验证」。

## 3. 风险登记

| ID | 风险 | 等级 | 影响 | 缓解（负责人） |
|---|---|---|---|---|
| R1 | 真机/ADB 不可用，回归证据全靠用户手点 | **高** | review 项无法自动抓 logcat/帧数据，可能在发版后才发现问题 | 验证项压到 5 条以内、每条 ≤3 步；QA 出 checklist + 模拟器静态核对；无证据项保持 review 不转 done（qa-engineer） |
| R2 | `git_dirty=true` + 多角色并行改大文件 | **高** | 包不可复现（GPL 合规）、多人覆盖改动（`MobileShell` 4591 行、`TvLive` 2228 行、`PlaybackControls` 1615 行） | 本轮只回归不改这些文件；**写窗口制**：一个文件一轮只一个负责人；收口后 Lead 统一 commit + tag（lead） |
| R3 | 文档/版本漂移 | 中 | 用户与接手者误判版本；发布说明与包不一致 | S1-06 文案修正；S1-10 补 CHANGELOG 1.2.2；版本号只认 `version.properties`（pm-owner） |
| R4 | P0-2 FTP 残留写/删能力 | 中 | 局域网内凭口令可上传/删除（明文，6 位口令可截屏泄露） | 发布前改「默认关闭」或「只写 + TTL」；或在 1.2.2 说明中明示（mobile-dev / auditor） |
| R5 | 范围膨胀：审计 4×P0 + 9×P1 + 9×P2，设计规范 3 份 | **高** | 本轮 2 天，任何"顺手做"都会挤压回归 | 砍 P2 全部；P1 本轮只留 0 项，S2 起逐项开（scrum-master） |
| R6 | 两条构建链（Python 脚本 / Gradle）产物无一致性门禁 | 中 | 发布用哪条链不明确，包体与校验口径可能不同 | S2-05 定死"发布链"，另一条只做验证（auditor） |

## 4. 待拍板（阻塞项，需 Lead / 产品决策）

| ID | 问题 | 建议 | 决策人 |
|---|---|---|---|
| D1 | 1.2.2 是否直接对外发？当前包来自 dirty 工作区 | 先 commit + tag 1.2.2 再重出包，用重出的包做覆盖安装验证 | lead |
| D2 | MiniFtpServer 默认关闭还是保留（只写 + TTL） | 默认关闭，保留入口；如保留则本轮补 TTL 与禁用 DELE | pm-owner |
| D3 | Measured Baseline Profile 是否本轮硬指标 | 接受手写 BP 作为 1.2.2 交付，实测 BP 挂 S3（需真机环境） | lead |
| D4 | 首页虚拟化是否挤进 S1 | 不挤，S2-01 首项，独占 `MobileShell` 写窗口 | scrum-master 建议 / lead 确认 |
| D5 | targetSdk 33→35 升级窗口 | S4 起做，需产品接受行为回归面 | pm-owner |

## 5. 未来 2 轮排期建议

### S2（11-11 ~ 11-17，5 个工作日 + 真机窗口）主题：性能第一刀 + 构建正式化

| 顺序 | 任务 | 负责人 | 人日 | 说明 |
|---|---|---|---|---|
| 1 | 首页/搜索网格 RecyclerView 虚拟化 | mobile-dev | 2–3 | 独占 `MobileShell`，先冻结其他改动；首屏 24 张其余分批 |
| 2 | 图片磁盘缓存 LRU 配额 + 退出清理 | mobile-dev | 1 | `ImageLoader` 磁盘无上限（审计 P1-8） |
| 3 | P0-2 FTP 收口（默认关闭 / 只写+TTL） | mobile-dev | 0.5 | 发版前必须有一版 |
| 4 | 冷启动埋点（trace）+ 配置异步加载 | mobile-dev | 1–2 | 先埋点再优化（审计 P1-4） |
| 5 | Gradle 正式化：flavor 对齐、R8 keep、16KB 校验进门禁 | android-auditor | 2 | 独立 `gradle/` 目录，与 1–4 不冲突 |
| 6 | 回归门禁：JVM checks 全绿 + 冒烟清单 | qa-engineer | 1 | 现有 15+ 套件 CI 化 |
| 7 | 真机验证 V6/V7 + S1 遗留项 | 用户 | — | 本轮证据回填 board |

出口标准：首页滚动录屏不掉帧；mobile release 包 ≤ 40 MB（或给出明确预算）；构建链唯一；无 P0 遗留；CHANGELOG 1.3.0 首条草稿。

### S3（11-18 ~ 11-24）主题：架构升级启动（需 D5 决策）

1. Media3 1.5.1 → 1.8.x + `DecoderProbe` 解码回归（auditor，2–3 人日，回归面高）。
2. 后台释放播放器（低成本先行）→ 视 D5 再上前台服务/MediaSession（mobile-dev，1–3 人日）。
3. AGP9 评估（仅当 S2-05 完成且镜像可用）（auditor，1 人日）。
4. Compose 试点**只做决策与范围**，不实现（auditor + ui，0.5 人日）。
5. targetSdk 33 分步方案评审（不落地）（auditor + pm，1 人日）。

### 建议砍掉 / 延后（S2–S3 不做）

- 审计 P2 全部（状态承载、Repository、多模块拆分、Compose Multiplatform）——架构收益真实，但在 1.2.x 发版窗口内都是负收益。
- Compose Multiplatform 整体重写、Flutter/Android 双框架统一——与 `flutter/` 已有叙事重叠。
- AdminConsole HTTPS + 证书 pinning、更新 feed 独立签名（P2 安全项，等 P0-2 收口后再排）。
- targetSdk 35 与 16KB 全量门禁——放 S4，先解决"包发得出去、装得上"。

## 6. 变更记录

| 时间 | 变更 | 人 |
|---|---|---|
| 2026-11-09 | 创建 board；录入 1.2.2 收口轮 12 项任务、7 项真机验证、6 项风险、5 项待拍板；给出 S2/S3 排期 | scrum-master |
