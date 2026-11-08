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
