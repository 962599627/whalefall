# 鲸落 1.9.34 筛选交互稿（分类 chip 三级）

> 适用范围：手机首页 / TV 首页的片库分类筛选。实现文件：`MobileShell.java`（手机）、`HomeScreen.java`（TV）。

## 交互表

| 当前状态 | 操作 | 结果 |
| --- | --- | --- |
| 未选中该分类 | 点击带漏斗的分类 chip | 切换分类 → 展开 L2 标签行（类型 / 地区 / 年份） |
| 已选中分类，L2 未展开 | 再次点击同一分类 chip | 展开 L2 标签行 |
| 已选中分类，L2 已展开、L3 未展开 | 点击同一分类 chip | **展开「类型」维度的 L3 选项**（等效于点第一个标签） |
| 已选中分类，L3 已展开 | 点击同一分类 chip | 收起面板（L3 + L2 全部收起） |
| L2 展开中 | 点击某个标签 | 展开/收起该维度的 L3 选项 |
| L3 展开中 | 点击某个选项 | 立即应用筛选、收起 L3、标签显示选中值 |
| L3 展开中 | 再次点击已选中的非「全部」选项 | 取消该维度筛选，回到「全部」 |
| L3 展开中 | 点击「全部」 | 清除该维度筛选 |
| 任意展开层 | BACK | 先收 L3 → 再收 L2 → 恢复正常返回逻辑 |
| 切换分类 | 点击其它分类 chip | 清空筛选，按新分类加载并展开 L2 |

## 视觉/尺寸（1.9.34）

- L1：分类 chip 带漏斗图标；选中分类漏斗 `BG_DEEP #070D18`；未选中但有筛选 `ACCENT #FF8FB1`；无筛选 `TEXT_TERTIARY`；选中底为 `ACCENT → ACCENT_SOFT` 渐变 + 深色字；不加粉点。
- L2：独立玻璃面板内横向滚动；未选 `#14FFFFFF` 底 + `#1AFFFFFF` 边；展开/已选 `#33FF8FB1` 底 + `#66FFB3C8` 边；文字 `ACCENT_SOFT` / `TEXT_SECONDARY`；文案「类型 / 地区 / 年份」，已选显示「类型 · 动作」。
- L3：手机单行横滚、chip `wrap_content`（padding 12–14dp）；平板/TV 换行网格（8/6 列），chip 同样 `wrap_content` 不按 weight 拉伸；选中 `ACCENT` 底 + `#0B1220` 字 + `#66FFB3C8` 描边。
- 动效：L2 展开 160ms 淡入 + 上移 6dp；L3 180ms；收起 140ms；`Prefs.REDUCED` 时瞬时。
- 日志：`I/Filter expand tab=… options=N layout=scroll|grid`；`I/Filter category=… tab=… value=…`。
