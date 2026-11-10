# REVIEW 1.9.29（architect）

结论：通过
- 聚合搜索开关（手机 MobileShell + TV SettingsScreen，默认 true）✔
- 关闭聚合按来源分组：groupHeaders 复用不叠加、排序隐藏、失败徽章保留 ✔
- 来源筛选 chips：「全部」+各源数量、单选/多选、已筛选提示/清除；筛选时不合并 ✔
- 分页 120 上限恢复（placeResult 先 state[0]++ 再判 shownLimit/displayed）；moreResults 正常 ✔
- 失败徽章 TV 焦点顺序、TV 开关、多选空态重试隐藏、单源重试重建分组、清除筛选复位 shownLimit ✔
- P0/P1=0；剩余 P2 4 条记录进 1.9.30（空态重试条件、resultHint 口径、resetGrid 清 posterCards、筛选时排序 chip）
