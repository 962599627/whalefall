# 构建变体约定（2026-11-13 起）

> 用户明确：**R8 是给 arm64 版本用的**，不要再把「通用包 R8」当成独立卖点。

## 正式发布变体（手机/平板）

| 变体 | 构建参数 | 说明 |
|---|---|---|
| mobile | 默认 | 通用包（含 arm64-v8a + armeabi-v7a），体积最大 |
| mobile-arm64 | `MARCOMOVIE_MOBILE_ABIS=arm64-v8a` | 纯 64 位，体积小 |
| mobile-arm64-r8 | `MARCOMOVIE_R8=1` + `MARCOMOVIE_MOBILE_ABIS=arm64-v8a` | **推荐**：64 位 + R8，最小 |
| mobile-r8 | `MARCOMOVIE_R8=1` | 通用包 R8；仅在没有 arm64 需求时提供，不再作为主推 |

## TV 变体

| 变体 | 构建参数 | 说明 |
|---|---|---|
| tv | 默认（armeabi-v7a） | MiBox4C 等 32 位盒子 |
| tv-arm64 | `MARCOMOVIE_TV_ABIS=arm64-v8a` | 64 位电视/盒子 |

## 版本纪律

- 一次需求 = 一次版本号；中途重编不升版本（同名覆盖）。
- 发布前必须先问用户（不再自行发布/上传）。
- 发版资产：TV 包 + 手机变体（优先 arm64-r8）+ SHA256SUMS + 两个 feed JSON。
- 每次发版后更新 `verify/<version>-release/README.md`。

## 测试交付规则（2026-11-13 用户确认）

- 日常功能测试**只给 arm64-r8 包**（约 34MB），不再给通用包/R8 通用包；
- **只有做 TV 相关功能时**才额外给 TV 包；
- 发布 GitHub 时才汇总多版本资产。
