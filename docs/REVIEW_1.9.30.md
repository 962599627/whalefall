# REVIEW 1.9.30（architect）

结论：通过
- 返回栈：Screen.pushedTag 精确 pop；handleBack 优先；FileManager 二级页返回统一 resume 保目录/tab/排序；无 P0/P1。
- 遥控常驻：离开页面不断、复用 server/token+resetIdle、20 分钟空闲回收、通知停止、onDestroy 兜底。
- 来源筛选固定行：「来源筛选：」+全部/各源数量/失败/清除；不随 resetGrid 消失；TV 可聚焦。
- P2 记录：无 resume 的入栈页会整页重建、web-player 返回来源区分、idle 回收 UI 提示、「全部」无总数、清除入口代码重复。
