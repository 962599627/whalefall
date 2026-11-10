# 鲸落 1.7.5 发布说明（手机 / 平板 / TV）

> 状态：**稳定版，已发布 GitHub Release v1.7.5**。构建提交 `81f8f01`，`git_dirty=false`。

## 版本信息

| 项 | 值 |
|---|---|
| versionName / versionCode | **1.7.5 / 17005** |
| 构建 | `scripts/build_android.py`（主包/TV 包）；arm64 单 ABI 同源码；R8 用 Gradle/AGP 8.7.3 |

| 文件 | 大小 | sha256 |
|---|---|---|
| `WhaleFall-1.7.5-android-mobile.apk` | 62,070,138 B | `d46794c26e4c0d2e2039379f1295ab1d341a0212e77441fee576616161197f64` |
| `WhaleFall-1.7.5-android-tv.apk` | 36,293,525 B | `f1de0ff1a8c550d177b04aeed05363efd0ddc8176322262873eb4106d8722a12` |
| `WhaleFall-1.7.5-android-mobile-arm64.apk` | 35,633,829 B | `cf1b25305a1b3241cf1cdcc8d8de3a5b7c5764969cbd9330a33d4f61593ccfee` |
| `WhaleFall-1.7.5-android-mobile-r8-arm64.apk` | 33,146,644 B | `80ec23f6dce2961dc670d761627aa5ef3bc2684ec8e4ddae3e2931f0c74bc3cd` |

## 本版内容

1. **网盘入口直达源插件账号面板**（去掉我们中间两层 UI）：首页「网盘」胶囊 / 设置「网盘与账号」→ 直接打开源插件面板。
2. **插件弹窗外框套用深色玻璃主题**：圆角/描边/标题/底部按钮粉色系（插件内部写死绿/红块无法改，源码未公开）。
3. **账号设置（可选）**：清除凭据、原生网盘扫码登录（夸克/UC/百度/阿里，按上游开源实现移植）。
4. 含 1.4.x–1.7.x 全部修复（播放页、运行日志、更新多包体、字体颜色、壁纸、TV 对齐等）。

## 验证

- 用户确认源插件功能正常、外框主题生效；插件内部配色为已知技术边界。
- 发布产物：GitHub Release v1.7.5（4 包 + 4 sha + 4 feed + 源码归档），应用内「检查更新」feed 指向该 Release。

## 发布后核对

- [x] Release assets 齐全、Latest = v1.7.5。
- [x] `updates/*.json` = 1.7.5/17005 + variants。
- [x] 交付目录 `/sdcard/Download/DSH-Go/鲸落/` 含四包 + sha。
