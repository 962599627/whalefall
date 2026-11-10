# REVIEW 1.9.38（architect / qa）

结论：通过
- 筛选能力统一：`schema && 可解析远程维度(def.remote && choices>1)` 同时驱动图标显示与点击展开；手机要求 filterSchemaSite 与当前源一致。
- 无筛选分类：不显示漏斗、点击只切分类、不弹面板、不再 toast「不支持筛选」；日志含 key/defs/icons。
- 回归：loading 单实例/最短 400ms/旧列表 alpha0.35 不白屏/内容淡入；展开筛选零加载零跳顶；历史/今晚看什么薄玻璃。
- P2：全本地维度 schema 分类无升级（符合有图标⇔可展开定义）；手机 450ms 锚点恢复；TV 无鲸鱼 loading；旧 showHomeFilters 死代码。
