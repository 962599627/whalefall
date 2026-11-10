# REVIEW 1.9.40（architect / qa）

结论：通过
- 应用筛选后强制保持面板展开（L2+L3、选中态更新），不再因刷新触发 hidden 收起。
- ver defs 短暂为空时使用缓存 `lastMobileFilterDefs` 渲染；缓存也无才静默返回，全程不 collapse；仅在源切换时清缓存。
- 动画代次 seq 保护旧收起回调；TV 同步（renderFilterTags panelKeep、showFilterOptions 强制面板 VISIBLE）。
- 自证日志：panel keep after apply / tags useCache / tag click panelBefore/After。
