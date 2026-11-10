# REVIEW 1.9.36（architect / designer）

结论：通过
- 全维度多选（类型/地区/年份）：toggle 多选、逗号存储、标签「喜剧+爱情/中国大陆+美国/2024+2023」、本地 OR 匹配、远程取首值并提示。
- P1 修复：toggle 后立即刷新 L2 标签与已展开 L3 选中态（手机 renderMobileFilterTags+重开 options；TV renderFilterTags+重新 showFilterOptions）。
- 面板动画改 alpha+translationY（无逐帧 height）、Decelerate、Reduced 瞬时；打开/关闭不重载、不跳顶；应用筛选记录并恢复滚动。
- 面板文字跟随用户字体色；选中 60+60ms 缩放；清除淡出 120ms。
- 统一首页 loading：单实例（show/hide 幂等）、170ms 内容淡入、去除重复「正在加载…」。
- P2：滚动恢复固定 450ms；选中态深色优先对比；remote 多值提示可更准确。
