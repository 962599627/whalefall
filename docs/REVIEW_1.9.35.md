# REVIEW 1.9.35（architect / designer）

结论：通过
## 交互
- 第一次点分类：只切换/加载，不弹面板（仅判断筛选能力）。
- 第二次点同一分类：L2（类型/地区/年份）+ L3（当前维度选项）同时展开。
- 第三次点：收起；点其它分类切换且不展开。
- 漏斗/≡ 仅在该分类有真实 filterSchema 时显示；无筛选分类不显示、不弹面板。
- BACK：L3→L2→收起，焦点回位；apply 不重播入场。
## 视觉（紧凑版）
- 薄玻璃面板 #1AFFFFFF、圆角 16、无 header、无阴影；手机 padding 10/6、TV 14/10。
- L2 文字标签 + 2dp 粉下划线（未选 #A9B7D0、选中 #FFB3C8），面板内文字不跟全局默认绿。
- L3 单行小胶囊（手机 32/TV 34，按内容宽，无 weight），选中 ACCENT 底 + BG_DEEP 字。
- 高度动画先填后测，展开 140ms/L3 120ms/收起 120ms，Reduced 瞬时，无 scaleY。
## P2
- 宽屏 L3 chip 34 vs 稿 32（2dp）；旧 filterOptionGrid 死代码；无筛选分类二次点击无 toast。
