# Gradle 迁移验证记录（P1）

日期：2026-11-09 · 结论：**可行性已通过**

## 背景
审计建议把自写 aapt2→javac→d8 脚本迁移到 Gradle(AGP)，以解锁 R8 full、lint、Baseline Profile、ABI split 与依赖管理。本轮在设备（aarch64 + proot Ubuntu）上完成了 PoC 构建。

## 环境
- Gradle 8.13（腾讯镜像下载，解压于 /root/workspace/toolchain/gradle-8.13）
- AGP 8.7.3（依赖走 aliyun google/public 镜像）
- 本地 SDK：/root/workspace/gradle-sdk（platforms/android-35 来自 /tmp/aosp/platform，build-tools/35.0.0 来自 /root/workspace/toolchain/sdk）
- aapt2 覆盖：`android.aapt2FromMavenOverride=/root/workspace/toolchain/sdk/aapt2`
  （设备是 aarch64，AGP 自带 aapt2 是 x86_64 二进制，必须用本地 qemu 包装脚本，否则 Aapt2 Daemon startup failed）

## 工程骨架（gradle/ 目录）
- settings.gradle：pluginManagement + dependencyResolutionManagement 指向 aliyun 镜像
- build.gradle：`com.android.application` 8.7.3
- app/build.gradle：namespace com.whalefall.movie、compileSdk 35、minSdk 23、targetSdk 28、version 1.2.0/12000；
  sourceSets 直接指向 `../../android/src/main/java`、`../../shared/java`、`../../android/src/main/res`、`../../android/src/main/assets`；
  dependencies 使用 `fileTree('../../tools/java-dependencies', jar/aar)`（即现有离线依赖清单，无需联网拉依赖）
- app/src/main/AndroidManifest.xml：现有清单副本（去掉 package 属性，改用 namespace）
- gradle.properties：aapt2 override、nonTransitiveRClass=false

## 结果
`gradle --no-daemon :app:assembleDebug` → **BUILD SUCCESSFUL in 43s**，产出 `gradle/app/build/outputs/apk/debug/app-debug.apk`。
（debug 构建用于可行性验证，未包含脚本生成的 catpaw/python 运行时 assets、jniLibs 与 release 签名。）

## 落地到正式构建还需要（按顺序）
1. 把 `scripts/build_android.py` 里生成 catpaw assets / python host assets / jniLibs 的步骤移植为 Gradle task（或生成到 assets/jniLibs 目录后由 AGP 打包）
2. signingConfig 指向 `android/private/development.p12`（release）
3. flavor：mobile / tv（两套 assets 与 manifest 差异）
4. R8 full + proguard 规则（当前 tools/r8.jar 实验可用但 keep 规则不足）
5. Baseline Profile（需真机/模拟器 macrobenchmark）
6. CI：Gradle 构建 + 现有纯 JVM 检查套件

## 已知限制
- 无网络时首次同步会失败（依赖已在 tools/java-dependencies 时仍会拉 AGP/插件，首次需联网）
- AGP 9 需要 Gradle 9.x；本轮用 AGP 8.7.3 与 Gradle 8.13 验证路径，升级是配置项而非重写

## 第二轮：运行时打包验证（同轮完成）
在 `gradle/app/build.gradle` 追加：
- `jniLibs.srcDirs = ['../../../native-prebuilt/catpaw', '../../../native-prebuilt/deadline']`（注意相对工程目录是三级 `../../../`）
- `assets.srcDirs += ['../../android/build/catpaw/assets-mobile']`（复用脚本已生成的 catpaw 运行时资源；更新时先跑一次 `python3 scripts/build_android.py` 或后续拆独立 task）
- `signingConfigs.release` 读取 `MARCOMOVIE_KEY_PASSWORD` 环境变量 + 现有 `android/private/development.p12`

结果：`:app:assembleDebug` 再次 **BUILD SUCCESSFUL in 16s**，APK 180.6MB（debug、双 ABI、未做 R8/ABI 过滤），已确认包含：
- `lib/arm64-v8a/libnode.so`（63.8MB）、`libmarco_node.so`、`libmovie-quickjs-deadline.so`、`libquickjs-android-wrapper.so`、`libjlibtorrent-*.so`
- `assets/python-host/*`、`assets/catpaw/*` 等运行时资源

说明：debug APK 使用 AGP 自动生成的 debug 签名，与现有 release 签名不同，不能覆盖安装；本轮只做内容与流程验证。

## 结论
Gradle 化路径已经跑通「源码 + 资源 + 运行时 assets + jniLibs + 签名配置」的完整打包链路；
剩余工作是把 `scripts/build_android.py` 中的资产生成/ABI 过滤/版本注入抽成 Gradle task，并补 R8/Baseline Profile 与 mobile/tv flavor。

## 第三轮：R8 release 与手机 manifest
- 从 `android/build/AndroidManifest.mobile.xml` 取手机版 manifest（base manifest 是 TV 版，直接打包会进 TV 界面），去掉 package 属性后固定给 Gradle 用
- `MARCOMOVIE_KEY_PASSWORD` 已确认可用值（见构建环境），Gradle release 签名与现有 dev 证书一致，可直接覆盖安装
- R8 规则见 `gradle/app/proguard-rules.pro`（-dontobfuscate，keep com.pilipala/com.whalefall/org.json/kotlin/androidx.media3/原生方法）
- 产物：`app-release.apk` arm64 + useLegacyPackaging → **32.6MB**（-47%），可正常启动、手机 UI 正常
- **问题**：该包能运行但片源扩展引擎初始化失败（`扩展引擎初始化失败：IOException`），首页内容加载不了；判定为 R8 对 CSP/CatPaw 运行时反射加载路径的裁剪或 native 资源定位差异，暂不发布
- 已回退安装稳定自定义构建（61.8MB）。后续需：给 CatPaw/CSP 引擎补 keep（含 assets 读取相关类）、对比 native 库加载路径，再验证
