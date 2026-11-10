# QA 1.9.46（qa）

结论：可打包
- P0=0 / P1=0；版本 1.9.46/19046；编译通过。
- 通过：type3 定义来自 spider filters、extend 传入 categoryContent、applied/ignored 校验日志、HTTP 源路径保留、本地源不变。
- 真机清单：选类型后抓 `csp extend=…` 与 `csp applied/ignored`；看列表条数是否变化；若 ignored 发 filters 片段定位 key。
- Release notes 保留「未真机验证」。
