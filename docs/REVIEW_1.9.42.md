# REVIEW 1.9.42（architect / qa）

结论：通过
- 保留全维度多选（类型/地区/年份同维度可多选），远程源多选改为**按每个选中值分别请求 + 合并去重**（≤6 变体，超出提示），不再退回本地过滤，保证结果与源一致。
- 单值/本地源走原单次请求 + 本地过滤；多选请求只显示一次 loading，完成后一次性替换列表并恢复滚动位置。
- 面板保持展开（1.9.40 缓存 defs/不因空结果收起）；动画代次防竞态；TV 同步分请求合并。
- 日志：multi plan dims/variants、multi unsupported、panel keep after apply。
