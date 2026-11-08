<div align="center">
  <img src="docs/images/app-icon-512.png" width="128" alt="鲸落 Whalefall 应用图标" />
  <h1>鲸落 · Whalefall</h1>
  <p>沉入故事深处 —— 为手机、平板与电视打造的原生 Android 播放器</p>
  <p>
    <a href="#下载">下载</a> ·
    <a href="#功能">功能</a> ·
    <a href="#构建">构建</a> ·
    <a href="docs/USER_GUIDE.md">使用指南</a> ·
    <a href="docs/WHALEFALL_DESIGN_SYSTEM.md">设计系统</a>
  </p>
</div>

> 本应用是播放与内容管理工具，不内置、不提供、不托管、不售卖任何影视资源，也不提供影视资源配置地址或账号。
> 内容与配置需由用户自行合法获取，并确保拥有相应访问与使用权。

## 这是什么

鲸落（Whalefall）是一个原生 Android 客户端：手机、平板、电视共用一套代码，但各自有独立布局与交互——
手机上是大图海报流与手势播放，电视上是遥控器焦点导航与大屏海报墙，平板横屏则是左右分栏的信息密度。
首页、搜索、选集、播放、文件管理、网盘授权都在设备本地完成，不依赖服务端。

## 下载

- Releases 中提供安装包：手机 / 平板版与电视版
- 手机与平板：`WhaleFall-1.2.0-android-mobile.apk`（通用）或 `arm64` 专用包（体积更小）
- 电视：`WhaleFall-1.2.0-android-tv.apk`
- 安装前请核对 `*.sha256`

## 功能

### 界面

- **三端自适应**：手机底部四栏 Dock / 平板横屏右侧 Dock / 电视遥控焦点气泡；自动识别设备形态
- **液态玻璃 + 樱花粉 + 深海蓝**设计语言，统一的设计 token 与矢量图标，详见 [docs/WHALEFALL_DESIGN_SYSTEM.md](docs/WHALEFALL_DESIGN_SYSTEM.md)
- **动态壁纸 / 静态壁纸 / 屏保**：空闲进入屏保（时长 1/5/10/30 分钟可选，时钟样式可切换），视频播放时不触发
- **首页可配置**：继续观看、今日首推、今晚看什么、分类筛选，各部分可开关
- **观影统计**：本周观影部数 / 时长 / 活跃天数与最近观看进度

### 播放

- Media3 播放内核，支持软解与字幕增强；双击暂停、左右滑动快进、左右半屏上下滑调亮度与音量、长按 2 倍速
- 选集面板、倍速、换源、字幕、画面比例、播放信息；**播放队列**自动续播
- 片头片尾跳过时间点、自动连播下一集
- 直播：分组/频道双栏列表、数字键换台、频道收藏

### 内容与配置

- 片源配置导入、切换与备份；全局搜索（多片源并发、历史搜索词）
- 收藏 / 观看历史本地保存，支持单条删除与「清楚已看完 / 7 天前 / 全部」批量清理
- 网盘登录引导页：四个网盘入口 + 授权状态 / 清除账号，说明保留在本地

### 文件与工具

- **内置文件管理**：上传（系统文件管理器）、新建文件夹、重命名、删除；文本用 Markdown 阅读器预览（支持标题/列表/表格/图片/代码块），图片全屏预览，APK 可直接安装
- **管理员后台（局域网网页）**：扫码或地址访问，管理片源、收藏、电视台、文件、播放设置；带一次性访问令牌、10 分钟有效、局域网与 Host 校验
- **FTP 上传**：局域网内 `ftp://设备IP:2121`，用户 `wf` + 面板上显示的随机口令

## 构建

本项目使用自带的轻量构建脚本（无需安装 Gradle）：

```bash
export MARCOMOVIE_JDK=/usr/lib/jvm/java-17-openjdk-arm64
export MARCOMOVIE_BUILD_TOOLS=/path/to/android-build-tools
export MARCOMOVIE_ANDROID_JAR=/path/to/android.jar
python3 scripts/build_android.py
```

产物在 `dist/`，含手机版、电视版与 `sha256`。仅构建 arm64 手机包：设置 `MARCOMOVIE_MOBILE_ABIS=arm64-v8a`。
Gradle(AGP) 迁移验证见 [docs/GRADLE_MIGRATION.md](docs/GRADLE_MIGRATION.md)。

## 目录

```
android/      Android 工程（java / res / assets / manifest）
shared/java/  手机与电视共用的纯 Java 逻辑
scripts/      构建与依赖脚本
docs/         使用指南、设计系统、审计与迁移记录
gradle/       Gradle(AGP) 迁移验证工程
tools/        离线依赖与构建工具
```

## 关于本项目与上游

鲸落基于开源项目 **Marco Movie**（作者 Pilipala，GPL-3.0-only）二次开发：重做了品牌与界面体系，并新增移动端布局、文件管理与局域网后台、屏保、观影统计等功能。
上游协议与署名要求保留，衍生代码同样以 **GPL-3.0-only** 发布，详见 [LICENSE](LICENSE) 与 [docs/WHALEFALL.md](docs/WHALEFALL.md)。
