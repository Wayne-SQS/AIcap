# 爱管理 · 故事地图拖拽换格设计规格

> 日期:2026-09-15 · 状态:已实施并回归(后端契约 128 + 前端 E2E 44 + 验收 8 = **180 条全绿**)
> 依据:`docs/用户故事地图看板-项目管理知识摘要.md`(Patton 二维结构:横轴=活动流程顺序,纵轴=优先级/发布切片)

## 1. 现状核查:不是"被砍过",是原型就没做

有人反馈「故事地图不能拖动了,只有状态看板能拖」。三处证据表明**地图从旧版原型起就是只读的**,不是哪次改动砍掉的:

| 来源 | 事实 |
|---|---|
| `legacy/index.html:1073`(Vue 改造前的原型) | `<button class="card${map?' mapcard':''}" draggable="${!map}" …>` —— 地图卡 `draggable="false"` |
| `legacy/index.html:1141-1148` | 拖拽事件全部 `closest('.column')`,只认状态看板的三列;`.mapcell` 没有任何 drop 处理 |
| 移植后的 Vue 版 | `StoryCard` 为 `:draggable="!map && !isViewer"`,`BoardView.onDrop(i)` 只挂在 `#board .column` 上 —— 逐字保留旧语义 |

## 2. 三个方向该改什么(这是本设计的关键判断)

地图的每个格子 = **骨干活动 × 发布切片**,所以落点天然携带两个值。三个方向的管理语义完全不同:

| 拖拽方向 | 改动字段 | 判断 | 理由 |
|---|---|---|---|
| **纵向**(跨切片行) | `story.sprint` | ✅ 放开,**这是该视图唯一非它不可的操作** | Patton 的纵轴语义就是"自上而下按发布顺序切",上下挪卡片 = 重新切片 |
| **横向**(跨活动列) | `story.activity` | ⚠️ 放行但**一定弹确认** | 横轴是**用户流程顺序**(叙事骨架),不是优先级;不加提示会被误读成"调优先级" |
| 任意方向 | `story.status` | ❌ **不做** | 状态**不是地图的轴**(行=切片、列=活动),改状态请用状态看板 |

## 3. 级联不是可选项

改了切片却不挪子任务,当场就制造出「故事 Sprint 与执行排期脱节」—— 而 `checkConsistency()` 里正有一条告警在盯它,也正是「一体化」要求指向的问题。因此拖拽必须走与编辑弹窗**同一套位移规则**:

- 每个 Sprint = 2 周;周次夹紧到六周计划 `W1–W6`,且结束周不得早于开始周
- **只挪未完成且未取消的子任务**;已完成/已取消的保留原记录(防误伤历史)
- 规则收口在 store:`sprintShiftOf` / `shiftWeek` / `movableSubsOf` —— 此前公式只写在 `StoryEditorDialog.submit()` 里,拖拽若另写一遍必然漂移

> 顺带修掉一处**隐性不一致**:原 Sprint 级联用 `status !== 2` 判"未完成",会把**已取消**任务也挪走;而同文件里负责人级联用的是 `status !== 2 && status !== 3`。基线 T01–T16 没有已取消任务,所以差异一直不可见。现统一为「未完成且未取消」。

## 4. 交互细节

- **落点反馈**:`.mapcell.dragover` 复用状态看板换列时的同一套 `--yellow` 提示色,不引入新视觉语言
- **确认策略**:横向一定确认(叙事位置);纵向**只在会级联改写执行层数据时**确认(有未完成子任务)—— 没有子任务的卡片保持拖拽该有的流畅感
- **确认文案**必须说清"这次拖动会改什么",例如:
  ```
  将 US10 移到 A4 × Sprint 2:
  · 迭代 S1 → S2:级联挪动 1 条未完成子任务后移 2 周;已完成的 0 条保留原记录
  · 骨干活动 A3 安排与执行 → A4 观察与协同(横轴是用户流程顺序,移位会改变叙事位置)
  确认?
  ```
- **执行顺序**:先 `storyMovePlan` **算计划** → 需要时确认 → 再落库。避免"先写一半再问"
- **权限**:只读账号(viewer)卡片 `draggable="false"`,与其它写入口一致;`guard()` 作兜底
- **离线模式**同样生效(改本地 + 持久化 + 写变更记录)
- **总览(紧凑)态**的小矩形同样可拖

## 5. 实现落点

| 文件 | 改动 |
|---|---|
| `components/board/StoryCard.vue` | 两态卡片共用一个 `onDragStart`(只写 `dataTransfer` 载荷,落点自己解释语义);`:draggable="!isViewer"` |
| `components/board/StoryMapGrid.vue` | `@dragover/@dragleave/@drop` 挂在每个 `.mapcell`,新增 `data-cell="切片-活动"`,emit `move({ id, activity, sprint })` |
| `views/BoardView.vue` | `onMapMove`:算计划 → 确认 → 在线 `PATCH` 故事 + 逐条 `PATCH` 任务周次 → `loadAll`;离线走本地落位 |
| `stores/project.js` | `sprintShiftOf` / `shiftWeek` / `movableSubsOf`(规则唯一住处)、`storyMovePlan` / `applyStoryMoveLocal` |
| `components/board/StoryEditorDialog.vue` | 改用 store 的同一套规则(行为对基线逐字不变) |
| `styles/index.css` | 仅新增一条 `.mapcell.dragover{background:var(--yellow)}` |

## 6. 验证

新增 `FE-BRD-12`(纵向:切片 + 级联 + 反向还原)、`FE-BRD-13`(横向:只动活动列 / 不碰任何任务周次 / 只读账号不可拖 / 落点高亮)、`FE-BRD-14`(**补上此前完全无覆盖**的弹窗改 Sprint 级联路径)。夹具沿用基线的 `US10 ↔ T07`(后端无 `POST /api/tasks`,任务造不出来),结束逐字段还原 + `finally` 兜底。

**变异验证 5/5**(每条断言都能被还原修复而变红):

| 变异 | 报错 |
|---|---|
| 地图卡改回不可拖 | 「地图卡片必须可拖」 |
| 纵向不级联子任务 | 「未完成子任务应后移 2 周」 |
| 横向不弹确认 | 「确认框应点名是骨干活动变了」 |
| 只读账号也能拖 | 「只读账号的卡片必须不可拖」 |
| 位移公式 2 周改成 1 周 | 「未完成子任务应随之后移 2 周」 |

## 7. 调试记录:一次"测试抢跑"的教训

首次实机验证时拖拽"没生效",请求日志显示 `PATCH /api/stories/US10 → 401`,且 `PATCH` 出现在 `POST /api/auth/login → 200` **之前**。原因不是实现,而是**探针把登录弹窗后面的地图当成了就绪信号**:`#map .card` 在登录完成前就已从离线种子渲染出来,于是拖拽带着空 token 发了出去。

- 修法:等 `#user-chip` 可见 + `#mode-chip` 含「在线」再交互(正式用例用 `loginAndOpen` 的同一套信号)。
- 顺带发现一个**测试写法陷阱**:在同一个 JS 任务里连发 `dragover` + `drop`,Vue 还没来得及渲染 `.dragover` 高亮就被 drop 清掉了 —— 所以正式用例把拖拽拆成「dragover → 断言高亮 → drop」两步,既拿到反馈又守住了这个可交互提示。
