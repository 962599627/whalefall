# 鲸落 1.7.10 发布说明（手机 / 平板 / TV）

> 状态：**稳定版，已发布 GitHub Release v1.7.10**。构建提交 `4a3fe0d`，`git_dirty=false`。

## 版本信息

| 项 | 值 |
|---|---|
| versionName / versionCode | **1.7.10 / 17010** |
| 构建 | `scripts/build_android.py`；arm64 单 ABI 同源码；R8 用 Gradle/AGP 8.7.3 |

| 文件 | 大小 | sha256 |
|---|---|---|
| `WhaleFall-1.7.10-android-mobile.apk` | 62,455,680 B | `f1868afe9f51aa83bf10b140b5615e49137c6238941d14aa02a2c0946a7305e5` |
| `WhaleFall-1.7.10-android-tv.apk` | 36,679,067 B | `9e0d29dc390622352fb08a2e5c8040ef892c10219999fe558b95b1c55ea879d3` |
| `WhaleFall-1.7.10-android-mobile-arm64.apk` | 36,019,371 B | `f1234e9d515b8db1fa25ebed204b0d51e0ecaa1521e8ddcd6ed2fafed20127b8` |
| `WhaleFall-1.7.10-android-mobile-r8-arm64.apk` | 33,529,746 B | `8e507f5ed3e9dc3d695c6bbad4d3d27cca706bd55fe594f8a1cf5ea0bcd6e5b6` |

## 本版内容

1. **播放加载动画按设计规范重做**：7 帧圆形头像（assets 圆形裁切，280ms/帧 + 140ms 交叉淡入）、双层光晕呼吸、上下浮动、动态省略号 + 剧集名；手机/平板 168dp、TV 260dp；失败「加载失败 + 重试」；切后台停动效。
2. **网盘入口直达源插件面板**（1.7.3 起），插件弹窗外框套深色玻璃主题；账号设置删除无用的「清除 App 内登录」按钮，并说明源插件登录需到源面板退出（1.7.6）。
3. 含 1.4.x–1.7.x 全部修复（播放页点按/铺满、运行日志复制、更新多包体、字体颜色、壁纸生命周期、TV 对齐等）。

## 验证

- 用户真机确认加载动画显示正常、效果满意后发布。
- 发布产物：GitHub Release v1.7.10（4 包 + 4 sha + 4 feed + 源码归档），`releases/latest` = v1.7.10。

## 发布后核对

- [x] Release assets 齐全、Latest = v1.7.10。
- [x] `updates/*.json` = 1.7.10/17010 + variants。
- [x] 交付目录 `/sdcard/Download/DSH-Go/鲸落/` 含四包 + sha。
