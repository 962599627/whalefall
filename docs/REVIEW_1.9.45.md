# REVIEW 1.9.45（architect / qa）

结论：通过
- 筛选按 CMS 标准协议作为 query 参数发送（class/area/year 等，key 以源 schema 为准），HttpT4Engine 不再把筛选塞 base64 ext；JsonCmsEngine 取消硬编码白名单，仅拦截路由/鉴权 key。
- 远程站点所有选中维度进入请求；空结果回退无筛选请求并提示一次，日志打印脱敏最终 URL。
- 远程维度单选；本地源本地过滤不变；TV 同步。
