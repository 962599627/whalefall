# 小窗问题交接（TV / 旧安卓 + 新机）

> 目标基线：**1.9.342**（发布版，手机/平板/TV 小窗均正常）。本文件只讨论小窗问题。
> 交接时间：2026-11-14。仓库：`/root/workspace/marcomovie`，分支 `tv-logic-fixes`（工作区大量未提交改动）。

## 一、结论先行

- **1.9.342 是可用版本**，手机、平板、TV 小窗都正常。
- 之后所有小窗「修复」尝试（1.9.343~1.9.377）**全部回退**，不要基于这些版本继续。
- 再次修复时：**以 1.9.342 为唯一基线**，只允许改小窗相关代码，其它功能（主题/调色盘/简介/历史/线路 15s 回退）保持不变。

## 二、已确认的事实（含证据）

1. **旧机崩溃（关键）**：TV MiBox4C（Android 6 / SDK23 / armv7）在小窗阶段重挂/重绑 Surface 会原生崩溃：
   ```
   Fatal signal 11 (SIGSEGV) in libmedia_jni.so
   android::JMediaCodec::createByteBufferFromABuffer
   tid = ExoPlayer:Playb
   ```
   → 旧安卓**禁止** TextureView 重挂、禁止再次 `player.setSurface(...)`。

2. **黑屏机理**：离开播放页时 `PlaybackController` 的 `destroyed()` 执行 `setSurface(null)` 解绑；小窗复用播放层时没有有效 Surface → 黑块 + 缺字形方块（`Minikin: no font`）。

3. **手机为什么正常**：新系统走 TextureView 的 `onSurfaceTextureAvailable` 自动重建，重绑安全；旧系统没这个保障。

4. **返回逻辑坑**：曾出现「返回直接回首页、视频后台继续播放」「小窗显示到首页上」，根因是缩小窗判定过宽。当前工作区 MainActivity 已是：
   ```java
   boolean canMini = !back.isEmpty() && back.peek() != null && back.peek().detail;
   if (!miniMode && !isLive() && canMini) { minimizePlayback(); return; }
   ```
   **这条要保留**（TV 377 验证通过：返回回到选集页）。

5. **焦点**：TV 详情页「返回 ↓」「简介 ↓ 展开完整简介 ↓ 操作区」链已在 `TvDetailLayout` 修好（377 验证通过），保留。

6. **播放器右上角透明小块**：小窗残留视图，进入播放页时需清理 tag：`mini-center-play`、`mini-page`、`mini-hint`、`mini-bar`、`mini-hit` 等。

## 三、推荐方案（一次成型，不要再逐条试）

原则：**按安卓版本分流**，两条路径互不影响。

### A. 旧安卓（`Build.VERSION.SDK_INT < 24`，本机 TV/盒子）
- 小窗实现：**直接使用 1.9.285 的 `MiniDetails`**（653/763 行版本，`/tmp/Mini285.java` 已备；或从 tag/commit `3f0e7b6` 取）。
  - 该版本不做任何 Surface 重挂/重绑，不覆盖海报矩形之外的尺寸逻辑。
- `PlaybackController`：在旧安卓分支**不要解绑 Surface**：
  ```java
  boolean keepForMini = a.miniMode || a.expandedOverDetail;
  if (ticket == a.generation && a.player != null && !keepForMini) { a.player.setSurface(null); renderSurface = null; }
  ```
- 若进入小窗后仍黑：唯一可接受的恢复方式是「**不重建 TextureView**，监听 `onSurfaceTextureAvailable` 并在其回调里 `setSurface(newSurface)`」；绝不能主动 remove/add TextureView。

### B. 新安卓（`SDK_INT >= 24`：手机/平板/新电视）
- 保持 **1.9.342 的 `MiniDetails` + `PlaybackController`**（现网正常），不要引入版本分流以外的新逻辑。
- 小窗铺满：`controls.resize(小窗宽,小窗高)` + `video-output` LayoutParams `MATCH_PARENT` + `setScaleMode(true)`。

### 版本分流的落地方式
`PlaybackController.minimizePlayback` 里：
```java
a.miniDetails = (Build.VERSION.SDK_INT < 24) ? new MiniDetailsLegacy(a) : new MiniDetails(a);
```
- `MiniDetailsLegacy` = 1.9.285 `MiniDetails` 原样复制（只改类名/构造名）；
- 需要一个共同基类或接口提供 `refreshRect()` / `remove()` / `pageShown()`；
- `MainActivity.miniDetails` 字段类型改为该基类。

## 四、禁区（已经踩过的坑）

1. 不要在旧安卓上重挂/重绑 Surface（必崩）。
2. 不要为了「铺满」去改 `video-output` 的层级顺序（会盖住画面或让控制层透明块残留）。
3. 不要放宽缩小窗判定（会回首页+后台播放）。
4. 不要每次单点出包验证；改完一次成包，一次验证。
5. 不要动主题/调色盘/简介/历史/线路回退。

## 五、构建与验证

构建（proot 环境需 danger-full-access）：
```bash
cd /root/workspace/marcomovie
export MARCOMOVIE_JDK=/usr/lib/jvm/java-17-openjdk-arm64 \
  MARCOMOVIE_BUILD_TOOLS=/root/workspace/toolchain/sdk \
  MARCOMOVIE_ANDROID_JAR=/tmp/aosp/android-35/android.jar \
  MARCOMOVIE_PREBUILT_CATPAW_DIR=/root/workspace/native-prebuilt/catpaw \
  MARCOMOVIE_PREBUILT_DEADLINE_DIR=/root/workspace/native-prebuilt/deadline
python3 scripts/build_android.py            # 产出 TV + 通用手机包
# 手机精简：MARCOMOVIE_MOBILE_ABIS=arm64-v8a
# R8：MARCOMOVIE_R8=1 MARCOMOVIE_MOBILE_ABIS=arm64-v8a
```

电视安装与验证（MiBox4C，ADB 已配对）：
```bash
adb connect 192.168.10.2:5555
adb -s 192.168.10.2:5555 install -r -d dist/WhaleFall-<ver>-android-tv.apk
# 复现：首页→历史首卡→点预览播放→遥控 BACK
adb -s 192.168.10.2:5555 logcat -d -t 300 | grep -E "Mini|Player: lifecycle|libmedia_jni|FATAL"
adb -s 192.168.10.2:5555 exec-out screencap -p > /tmp/tv.png
```

验收标准（TV）：
1. 返回选集页后，预览卡里**有视频画面**（不是黑块/透出壁纸）；
2. 无 `FATAL SIGNAL` / `libmedia_jni` 崩溃；
3. 遥控焦点：返回↓→操作区、简介→展开完整简介→操作区；
4. 播放器右上角无透明残留小块。

## 六、交付约定

- 每次成包后复制到：`/sdcard/Download/DSH-Go/鲸落/`（TV 包 + 手机 arm64-r8 包）；
- 一个版本 ≥5 个修复项再出包，不再逐条发测试版；
- 发布前先让用户在设备上验证，不自动发 GitHub Release。

## 七、当前文件状态

- `MiniDetails.java`：工作区当前为 **1.9.285 版本**（滚动后已被覆盖，编译通过；如需 342 版从 tag `whalefall-1.9.342` 取）。
- `PlaybackController.java`：工作区当前为 **1.9.342 版本**。
- `MainActivity.java` / `TvDetailLayout.java`：保留了「返回栈顶详情才缩小窗」「TV 焦点链」「简介按钮」等已验证改动。
- `MiniDetailsLegacy.java` / `MiniWindow.java`：已删除（若采用方案 A 需重新生成）。
