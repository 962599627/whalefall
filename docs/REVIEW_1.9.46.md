# REVIEW 1.9.46（architect / qa）

结论：通过
- 根因确认：海绵等源 type=3 + csp_*Guard 为 JS 爬虫源，筛选须经 CspEngine.category → spider.categoryContent(id,pg,true,extend)，且 key 必须是 spider 返回的 filters 定义。
- type3 使用 spider 远程 filters（任意 key class/area/year/by/lang…），不做本地兜底/白名单；其它引擎保留 1.9.45 CMS query。
- CspEngine 传 extend 并校验：applied（count 与无筛选不同）/ignored（相同）日志；手机/TV 同步。
