# REVIEW 1.9.28（architect）

结论：通过
- P1-A TV「去恢复」行可聚焦（navButton + 首焦点顺序）✔
- P1-B 回退候选仅取 available、优先 preferred、无可用不写 prefs、提示合并 toast ✔
- P1-C 管理线路全量列表 + 禁用标注 + 去恢复 + 真实下标重排 ✔
- 隐藏计数三处口径一致；入口覆盖：首页/片库/搜索/默认偏好/启动回退均按 available 过滤 ✔
- 无 P0/P1；P2 5 条留 1.9.29（文案合并、identity helper、TV 优先 preferred、默认源对话框提示、空配置措辞）
