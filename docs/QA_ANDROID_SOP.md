# 安卓应用测试流程 SOP（Agent 执行版 v1.0）

> 落地：鲸落 TV/手机版测试按此执行。来源：用户提供的 Android 测试流程 SOP。

## 总则
可执行（adb 命令/操作/断言）、可判定（UI/LOG/API/DB/埋点/性能）、可留证（截图/录屏/logcat/trace/设备信息）、幂等隔离（force-stop/pm clear/重装）、分级阻断（P0 阻断、P1 汇总）、环境一致（机型/系统/ROM/分辨率/网络/版本+commit）。

## 阶段
0 接入准备 → 1 计划用例 → 2 环境矩阵 → 3 冒烟门禁 → 4 功能 → 5 专项（稳定/性能/弱网/功耗/安全/升级）→ 6 回归发布 → 7 线上监控复盘

## 鲸落参数
- 设备：MiBox4C / Android 6.0.1 (API23) / armeabi-v7a / 1920×1080 / WiFi / 遥控器
- 证据目录：/sdcard/Download/DSH-Go/鲸落/verify/<version>-tv/
- 版本核对：adb shell dumpsys package com.whalefall.movie | grep versionName
- 冒烟：logcat -b crash 无 FATAL；dumpsys activity 无 ANR；冷启动≤5s；前后台切换 3 次不崩
- 必测：首页发现/筛选三层焦点、历史清理、播放器（中央三键/底部按钮焦点、隐藏左右±10s、选集小窗）、直播（分类顺序/右侧信息/开源 IPTV/搜索/收藏/二级选择器）、壁纸稳定性、设置（壁纸/内核/解码）、文件管理 APK 安装、更新
- 性能：gfxinfo framestats 60s janky<5%；meminfo 20min 无持续增长；稳定播放 20min 0 崩溃

## 缺陷等级
S1 致命（崩溃/ANR/数据丢失/核心不可用）立即阻断；S2 24h；S3 本迭代；S4 排期。缺陷单：标题/环境/前置/步骤/实际vs预期/复现率/证据/引入版本。

## Agent 执行循环
前置检查 → 打点 → 执行（失败重试1次）→ 断言 → 采证 → 清理 → 上报 JSON（run_id/stage/device/app/summary/cases）。

## 角色
- qa：用例与判定、证据归档（docs/test_plan.md、docs/test_cases.yaml）
- pm：验收标准（docs/PM_ACCEPTANCE.md）
- scrum：版本线/风险/门禁（docs/SPRINT_STATUS.md）
- dev：实现与修复；architect：评审；lead：构建、装机、汇总（平台限制：子代理无 shell 权限）
