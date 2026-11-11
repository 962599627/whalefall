# 鲸落开发流程（强制执行）

> 目的：避免「开发写完直接打包」，保证每次交付都有评审与验收。

## 每次功能/修复批次必须走完 5 步

1. **需求确认**：Lead 把用户需求拆成验收标准（含失败场景），交给 dev。
2. **开发实现**：dev 只改约定范围文件；交付时给 `文件:行`、开关/日志点、未实现项。
3. **UI 评审（designer）**：凡涉及界面/文案/交互的批次，designer 必须出「视觉/交互问题 + 修改建议」，与 architect 评审并行；纯逻辑修复可跳过但需在交付说明标注。
4. **架构评审（architect）**：必须出结论（通过/有条件通过/不通过）+ P0/P1 清单；P0/P1 未修完不得打包。
5. **测试验收（qa）**：出「入口→预期→代码路径→判定」清单 + 回归风险；P0/P1 未闭环不得打包。
6. **打包门禁（Lead）**：
   - 版本号一致（version.properties = AndroidManifest）；
   - 工作树干净（git_dirty=false）；
   - 变更清单与「入口→行为」清单齐备；
   - **先问用户是否打包**，得到确认后才构建与交付。

## 禁止
- 开发完成后直接构建/交付。
- 用「看起来可以」代替评审结论。
- 同一问题反复盲改；必须先拿到日志/复现证据。
- 未经用户确认发布到 GitHub。

## 本轮状态（1.9.27）
- [x] 功能 1/3/5 开发完成
- [ ] P1 修复（7 项）进行中
- [ ] architect 复审
- [ ] qa 验收
- [ ] 询问用户打包

## 遥控焦点硬规则（1.9.72 起）
- 任何新增可点击控件（按钮/卡片/条目/弹层格子/图标）必须同时具备：
  1. `focusable/focusableInTouchMode`；
  2. 统一焦点样式 `TvNav.focusRing`（或 `FocusMotion.style` 且 markRing）；
  3. 明确的方向可达性（显式 nextFocus 或几何搜索可命中）；
- 禁止只做点击不做焦点；禁止 scale>1.02 / 外发光 / 描边出界 / 盖相邻元素；
- 验收必须提供 `I/TvFocusAudit page=… clickable=N focusable=N rings=N missing=[]` 日志（missing 为空）；
- 缺少焦点态的改动不得进入打包门禁。

## UI 状态不变量（1.9.73 起）
新增任何 UI 状态（导航选中、面板、浮层、小窗、loading、页面栈）必须：
1. 定义**唯一性不变量**，例如「导航栏恰好 1 个选中且等于当前 tab」「同一时刻最多 1 个筛选面板/小窗/loading/弹层」；
2. 接入 `UiAudit.audit(a, page)`：页面渲染 / 切 tab / 进设置子页 / 开关面板 / 进出播放器后主动执行；
3. 违规日志格式固定：`E/UiAudit rule=<不变量> got=N ... action=fix|log`，可自动修复的立即修正；
4. 验收必须附 `I/UiAudit page=… navSelected=1 expected=… issues=[]`；有 issues 不得打包；
5. 设置→诊断→「UI 自检」可现场复检并复制报告。

## 持续焦点审计（1.9.74 起）
- TV/遥控焦点模式下每 1s（低端机/Reduced 2s）扫描当前可见树；
- 列表 Adapter 数据变化、滚动停止、Dialog show/dismiss、切 tab、弹层开关后立即扫描；
- 扫描范围 = 所有 clickable 或带 OnClickListener 的控件（含 TextView 行、ImageView 图标、卡片子项）；
- 缺 focusable / 缺焦点样式 → **自动补齐（幂等）**，不得遗漏；
- 日志必须出现 `I/TvFocusAudit page=… clickable=N focusable=M rings=K missing=[]`；**missing 非空不得打包**；
- 设置→「UI 自检」显示最近一次 missing 明细（含类名+父容器）。
