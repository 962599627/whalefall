# 鲸落 1.4.11 发布说明草稿（手机 / 平板 / TV）

> 状态：**待用户 V36 日志做最终判定**。1.4.11 = 1.4.10 全部内容 + 诊断 v3。
> 维护：Lead ｜ 日期：2026-11-10 13:53

## 版本信息

| 项 | 值 |
|---|---|
| versionName / versionCode | **1.4.11 / 14011** |
| 构建 | `scripts/build_android.py`（干净提交 `965937d`，`git_dirty=false`）；R8 Gradle/AGP 8.7.3；arm64 单 ABI 同源码 |

| 文件 | 大小 | sha256 |
|---|---|---|
| `WhaleFall-1.4.11-android-mobile.apk` | 62,041,387 B | `ecc94e3746071c7a3f2b7987ff1334b5eb31857203daf0c1c60c483f78a6e589` |
| `WhaleFall-1.4.11-android-tv.apk` | 36,268,870 B | `cf38eae45d50dab8c70a56d7598d604171df5e674342370753bf92d253f14521` |
| `WhaleFall-1.4.11-android-mobile-arm64.apk` | 35,605,078 B | `f3c81a604b3a60573e8a8c2cc7cf2f69ad21cd6ff1d226308778e3b6a1cc0724` |
| `WhaleFall-1.4.11-android-mobile-r8-arm64.apk` | 33,121,825 B | `6cf6d198a0e802d1362d1e82f5c64a1f9c34296712c596e6b1bb24ab37251427` |

## 本版内容

1. **诊断 v3**：Cookie 库逐行 `host/name`（最多 40 行）；全部非 movie prefs 的键名清单（值只记长度/base64 形态，不打印）；databases 表名清单；files/ 大文件名与前后各 2MB 采样（`sampled` 必须 >0）。
2. **Cookie 匹配放宽**：host 只要含 quark/baidu/alipan/aliyundrive、UC 允许 `.uc.`/`login.uc` 等变体；登录 Cookie 名也扩充。
3. **技术边界文案**：判定不可读时明写「登录态可能在源服务端/加密存储」，并提示手动标记。
4. 含 1.4.10/1.4.9/1.4.8/1.4.7/1.4.6/1.4.5 全部内容。

## 判定路径（V36）

- 若日志出现真实夸克登录 Cookie（如 host=.quark.cn name=__puus）→ 下一版精确匹配即可自动读取；
- 若 prefs 键名/DB 表名/files 大文件里有凭据线索 → 下一版定点解析；
- 若全部无线索 → 登录态在源服务端/加密存储，本机不可读，保留「由源管理·无法读取」+ 手动标记，写进已知限制。

## 发布前检查清单

- [ ] V36 日志回传并给出结论。
- [ ] 结论后决定是否还需要 1.4.12。
