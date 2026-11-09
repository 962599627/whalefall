# 鲸落 1.3.0 发布说明草稿（手机 / 平板）

> 状态：**待用户真机验证后发布**。构建与评审已完成；B 类真机证据未采集，Release notes 必须保留「未真机验证」标注。
> 维护：Lead ｜ 日期：2026-11-09

## 版本信息

| 项 | 值 |
|---|---|
| versionName / versionCode | **1.3.0 / 13000**（单一事实源 `android/version.properties`） |
| 包名 | `com.whalefall.movie`，minSdk 23 / targetSdk 28 |
| 构建链 | `scripts/build_android.py`（Python 主链） |
| 产物 | `dist/WhaleFall-1.3.0-android-mobile.apk`、`dist/WhaleFall-1.3.0-android-tv.apk` + `.sha256` |
| 交付路径 | `/sdcard/Download/DSH-Go/鲸落/` |
| 发布动作 | 由用户执行：更新 `updates/android-*.json` → `gh release upload`（62MB 约 10 分钟） |

## 最终构建记录（2026-11-09 21:37，clean 树构建）

| 项 | mobile | tv |
|---|---|---|
| 文件 | `WhaleFall-1.3.0-android-mobile.apk` | `WhaleFall-1.3.0-android-tv.apk` |
| 大小 | 61,996,331 B | 36,223,814 B |
| sha256 | `ae287617267585022d05da6baef0638741d5163892a1a63966243420dc6e60e1` | `5cae8a0711b75fa75b3484bbfa349d0aef0048fec3a27777709c734967d0b450` |

- `git_commit` = `2bc0a31e3e96a157944dcb8af42b4f55930e147c`，**`git_dirty=false`**（先 commit 再构建）。
- `build-validation.json`：签名 + zipalign 校验通过；`device_install_tested=false`、`device_playback_tested=false`。
- 已拷到 `/sdcard/Download/DSH-Go/鲸落/`，`sha256sum -c` 校验 OK。
- 更新 feed 草稿已填：`updates/android-mobile.json` / `updates/android-tv.json`（发布时随 Release 一起上传；未发布前 App 内检查更新可能 404，属预期）。


## 本包内容

1. **首页海报网格虚拟化（性能第一刀）**：首页从 `ScrollView + LinearLayout` 全量构建改为 RecyclerView 虚拟化（header / 影片行 / 加载更多三类 item），海报只为「可见区 + 预取」创建；片库/电视台/频道管理沿用既有 RecyclerView 列表。
2. **陕西卫视地址修正**：改为陕西网络广播电视台官方直播页（`http://live.snrtv.com/star`）；页面可能先弹「确定前往」，点一次即可。
3. **设置页版本号修复**：设置 → 关于 的版本号改为读取安装包版本，不再硬编码「1.2.0」。

## 验证与证据

- C 类（代码评审）：`docs/ARCH_REVIEW_HOME_VIRTUALIZATION.md` §4 —— 静态通过、静态必修 0 项；核心证据：`getItemCount()=2+ceil(N/cols)`、`onBindViewHolder` 只绑一行、旧 `renderVideoGrid` 全量构建已删除。
- 设计走查：`docs/DESIGN_REVIEW_1.3.0.md` —— 视觉不变量 17 条、无 P0（D-01 首页列数断点与全站 600/840/1080 不一致，属既有问题，本版冻结）。
- 频道审计：`docs/LIVE_CHANNEL_AUDIT.md` —— 67 条（官方直播页 46、官网首页 21、直连 m3u8 0）；本版只改 1 条，重庆候选地址因 404 未采用。
- **B 类（真机）**：**未采集**。验证清单：`docs/VERIFY_1.3.0.md`；证据目录：`/sdcard/Download/DSH-Go/鲸落/verify/1.3.0/`。
- **未真机验证项（发布须知）**：首页虚拟化的滚动流畅度/无白块/海报 1s 补齐、首屏与「加载更多」两个时刻不崩溃、返回/切 tab 滚动位置误差、TV 路径冒烟；1.2.2 的 V1–V5 仅有用户口头确认、无证据文件。

## 已知限制（不在本版修复）

- 首页列数断点（520/720dp → 3/4/5 列）与全站规范（600/840/1080）不一致（D-01，既有）。
- 追加下一页时 `notifyDataSetChanged` 会重绑可见行（E3-S2 下轮做「追加不重建」）。
- 切 tab 回首页会重复追加当前页（基线既有行为，非本版引入）。
- 15 条卫视仍为官网首页（待用户提供官方直播页地址）；港澳台/地方台未纳入预置清单。
- 实测 Baseline Profile / AGP9 迁移不在本版范围（仅手写 BP 进包，不得宣称「Baseline Profile 已优化启动」）。

## 发布前检查清单（用户执行）

- [ ] clean 工作区构建（`git_dirty=false`），记录 commit 与 sha256。
- [ ] `updates/android-mobile.json` / `android-tv.json` 的 versionName/versionCode/size/sha256/url 与包一致。
- [ ] GitHub Release 上传 mobile + tv 包与 sha，Release notes 附本文件内容并保留「未真机验证」标注。
- [ ] 覆盖安装验证：设置底部显示「鲸落 1.3.0」。
