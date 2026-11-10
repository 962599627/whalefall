# REVIEW 1.9.44（architect / qa）

结论：通过
- 根因：海绵等源的筛选维度被解析为本地（def.remote=false），选择未进入请求、返回后本地二次过滤匹配不到 → 列表不变。
- 修复：有远程 api 的站点，所有选中维度进入 filters 请求参数；返回后不做本地二次过滤（以源结果为准）；纯本地源仍本地过滤。
- 远程维度单选（点新值替换/再点取消），标签单值；多选收敛。
- 日志：request filters={…} multi/variants、raw=N remoteRequest=true。
