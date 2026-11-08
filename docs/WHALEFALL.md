# 鲸落 / WhaleFall —— Marco Movie 二次开发说明

本仓库是 [Marco Movie](https://github.com/Pilipala9919/MarcoMovie)（作者 Pilipala，GPL-3.0-only）
的二次开发分支，品牌改为「鲸落 / WhaleFall」，功能保持上游实现。

- 应用包名：`com.whalefall.movie`（可与官方 Marco Movie 共存安装）
- 版本：`1.0.0`（versionCode `10000`）
- 最低系统：Android 6.0（API 23），targetSdk 28
- 产物：`dist/WhaleFall-1.0.0-android-tv.apk`（ARM 32 位，电视/盒子）、
  `dist/WhaleFall-1.0.0-android-mobile.apk`（arm64-v8a + armeabi-v7a，手机/平板）
- 仓库与下载：https://github.com/962599627/whalefall
  （Release 里附两个 APK，应用内更新检查也读这个仓库的 `updates/`）

## 相对上游改了什么

| 类别 | 改动 |
| --- | --- |
| 包名 | `com.pilipala.marcomovie` → `com.whalefall.movie`（Java 包、Manifest、provider authority、EDITION meta-data 全部同步） |
| 应用名/界面 | 应用名「鲸落」；启动页字标、手机端品牌位、设置页关于文案、局域网配置页标题 |
| 品牌素材 | 新的图标 / 启动横幅 / mipmap 全套（`android/src/main/res/`，素材源在 `brand-jingluo/`） |
| 签名身份 | 新构建使用自签名证书（`CN=WhaleFall, OU=WhaleFall Preview`），与官方签名不同，不能覆盖安装官方包 |
| 更新检查 | 默认关闭（`UpdatePolicy.FEED`/`MIRROR` 为空时显示「未配置更新源」）；改成自己的下载页即可启用，见下 |
| 备份格式 | 内部格式 `MarcoMovie-1` / `marco-movie-backup` 保持不变，可读取官方备份文件 |
| 原生库 | 为在 ARM Linux 上构建，`scripts/build_catpaw_android.py`、`scripts/build_quickjs_deadline.py` 增加「复用官方发行版预编译原生库」分支（`MARCOMOVIE_PREBUILT_*`）；不设置环境变量时行为与上游一致 |
| JNI 兼容 | `QuickJsDeadline` 保留在上游包 `com.pilipala.marcomovie`，因为官方 `libmovie-quickjs-deadline.so` 的符号名固化在旧包名下 |

## 更新源（已配置为本仓库）

应用内更新检查已指向本公开仓库：

```java
// android/src/main/java/com/whalefall/movie/UpdatePolicy.java
static final String FEED = "https://cdn.jsdelivr.net/gh/962599627/whalefall@main/updates/";
static final String MIRROR = "https://raw.githubusercontent.com/962599627/whalefall/main/";
static final String[] ALLOWED_HOSTS = {};   // 留空 = 接受任意 HTTPS 主机
```

- 发布新版本：构建 → 生成新的 `updates/android-tv.json` / `updates/android-mobile.json`
  （更新 versionName/versionCode/size/sha256/url）→ 上传 APK 到新的 GitHub Release → 提交推送。
- APK 文件名需满足 `WhaleFall-<版本>-android-tv.apk` / `WhaleFall-<版本>-android-mobile.apk`。
- 两个地址都依赖本仓库公开可访问；raw.githubusercontent 在部分网络需代理。

## 在 Linux / ARM 机器上构建（本次实际采用的流程）

上游构建脚本面向 Windows（JDK 17 + Android SDK build-tools）。在 ARM Linux（含本机
Android proot 环境）上构建需要：

1. **JDK 17**（`MARCOMOVIE_JDK` 指向其目录）。
2. **Android 编译平台包**：`platform-35_r02.zip` 中的 `android.jar`（`MARCOMOVIE_ANDROID_JAR`）。
3. **build-tools 35**：`aapt2`、`zipalign`、`aapt`、`lib/d8.jar`、`lib/apksigner.jar`。
   `d8`/`apksigner` 是 Java 程序可直接跑；`aapt2`/`zipalign`/`aapt` 是 x86_64 原生程序，
   在 ARM 上用 `qemu-x86_64-static -L /usr/x86_64-linux-gnu` 包装后调用
   （需要 `apt install libc6-amd64-cross libgcc-s1-amd64-cross libstdc++6-amd64-cross`）。
   所有 Android 产物都输出到 `MARCOMOVIE_BUILD_TOOLS` 目录，脚本按 `aapt2`/`aapt`/`zipalign`
   文件名查找，因此用同名的 shell 包装脚本即可。
4. **依赖 jar**：`android/*dependencies.json` 里的地址走 Maven Central，国内需镜像
   （如 `maven.aliyun.com` 或 FrostWire 的 GitHub release），并预置到 `tools/java-dependencies/`
   且校验 SHA-256；`marco-decoders-armv7.zip`、`marco-node-android.zip` 从 GitHub Release 下载。
5. **原生库**（免 NDK 的关键）：从官方 `0.5.3` 安装包里按需取出
   `libnode.so`、`libc++_shared.so`、`libmarco_node.so`、`libmovie-quickjs-deadline.so`
   （arm64 取自手机包、armv7 取自电视包）和 `assets/catpaw/runtime-capabilities.json`，
   按 ABI 放好，然后：

```bash
export MARCOMOVIE_JDK=/usr/lib/jvm/java-17-openjdk-arm64
export MARCOMOVIE_BUILD_TOOLS=/path/to/toolchain/sdk   # 放 aapt2/aapt/zipalign/d8 包装脚本
export MARCOMOVIE_ANDROID_JAR=/path/to/android-35/android.jar
export MARCOMOVIE_PREBUILT_CATPAW_DIR=/path/to/native-prebuilt/catpaw
export MARCOMOVIE_PREBUILT_DEADLINE_DIR=/path/to/native-prebuilt/deadline
python3 scripts/build_android.py
```

`native-prebuilt/catpaw/<abi>/` 放三个 `.so`，`native-prebuilt/deadline/<abi>/` 放
`libmovie-quickjs-deadline.so`；`runtime-capabilities.json` 由脚本按实际文件哈希重新生成。

也可以直接在 Windows 上按上游 `docs/BUILD.md` 构建（需要 JDK 17 + NDK r28c），
不设置 `MARCOMOVIE_PREBUILT_*` 时脚本走上游原生编译路径。

## 验证状态与已知限制

已验证：资源编译（aapt2）、javac 编译、dex、zipalign 校验、APK 签名与摘要校验、
Manifest 包名/版本/应用名、APK 内 ABI 与原生库清单。

未验证：真机安装与播放。签名是本地开发签名，与官方包互不兼容；更新检查默认关闭；
真机安装请先卸载/避开与官方包同包名冲突（本分支已改成独立包名，可共存）。

## 许可证与署名

上游代码、文档与素材为 GPL-3.0-only，本分支作为衍生作品继续以 GPL-3.0-only 发布，
保留全部上游版权声明（`LICENSE`、`NOTICE`、`THIRD_PARTY_NOTICES.md`）、
第三方许可与作者署名。分发本分支时必须同样开源并保留这些文件。

## 视觉主题（1.1.0 起）

- 配色：深海蓝底（#0B1220 系）+ 蓝白玻璃面板 + 樱花粉强调色（#FF8FB1）。
  调色板集中在 `Skin.java`，替换 `MainActivity.DARK/WHITE/MUTED` 常量即可整体调整。
- 液态玻璃：卡片/胶囊使用半透明白 + 细描边叠加在深蓝底上；`减少透明效果` 开关仍可退回实色面板。
- 动态壁纸：`VideoWallpaper.java` 用 Media3 循环静音播放本地视频（MP4 / WebM / MKV，取决于设备解码），
  首页背景铺满并叠加可调压暗层。入口：设置 → 界面外观与偏好 → 首页壁纸与动态壁纸 → 选择视频壁纸。
  文件保存在应用私有目录 `wallpaper-video.mp4`；开启「减少动态效果」或关闭开关时回退到静态壁纸/环境背景。
