# Baseline Profile（启动 profile）现状

## 已做
- `gradle/app/src/main/baseline-prof.txt`：手工整理的**启动热路径** profile
  - 覆盖：MainActivity 创建/onCreate/背景安装、MobileShell.render/renderHome/首页顶栏/海报网格、
    ImageLoader.poster、Skin.backdrop、CspEngine 初始化
- AGP 构建时自动编译进 APK：`assets/dexopt/baseline.prof`（855B 左右）+ `baseline.profm`
- 验证方式：`:app:assembleMobileRelease` → 解包 APK 确认 `assets/dexopt/baseline.prof` 存在

## 限制（诚实说明）
- 这份 profile 是**按代码结构手写的**，不是用 Macrobenchmark 在真实设备上跑出来的
- 真正测量式生成需要：
  1. `androidx.benchmark:benchmark-macro-junit4` + `androidx.baselineprofile` Gradle 插件
  2. 可调试/可 profile 的设备或模拟器，跑 `BaselineProfileRule` 采集
  3. 本机（aarch64 + proot、无 adb 连接）目前不具备该工具链，所以只做到「手写 + 打包 + 可被 ART 使用」
- 手动 profile 的收益通常小于实测 profile，但比没有好；后续有真机 benchmark 条件时应替换

## 后续
- 接入 macrobenchmark 后重新生成并对比冷启动时间
- 把 `baseline.prof` 同步进 `scripts/build_android.py` 的自定义构建（当前仅 Gradle 构建包含）
