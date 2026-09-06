# 爱管理 · 前端 E2E 测试执行记录

> 执行日期:2026-09-06 · 浏览器:Edge(headless,msedge channel)+ playwright-core
> 页面来源:`http://127.0.0.1:8090`(仓库根静态服务);后端 QA 实例:`127.0.0.1:8001/AIcap_qa`
> 说明:index.html 内 API_BASE 经内存改写指向 8001/死端口,源文件未改动;浏览器启动参数含
> `--disable-web-security`(仅测试环,规避 Chromium 对跨源 localhost 的 Private-Network 拦截)。

## 结果摘要

```
浏览器已启动(chromium)
前端离线演示模式(FE):  35 项断言全部通过
前端在线模式+联调(OE): OE-01..OE-11、OE-C1 通过;OE-12 失败(BUG-FE-01)
===== UI E2E 结果: 47 passed / 1 failed =====
FAIL: OE-12 刷新后状态栏仍显示已连接(期望;已知缺陷 BUG-FE-01)
  chip="在线 · 未登录" userChipVisible=false
```

## 覆盖清单

| 套件 | 覆盖内容 | 结果 |
|---|---|---|
| suiteOffline(FE) | 离线回退、总览统计、看板三列/计数、Sprint/负责人/搜索筛选、地图切换、新建(M21)/编辑/删除/拖拽状态、变更记录、需求池增删+移入、AI 审核采纳+决策留痕、AI 对话、甘特/成员/UML 渲染、JSON/CSV 导出、恢复演示 | 35/35 ✅ |
| suiteOnline(OE) | 在线登录弹窗、admin/member 登录、在线同步 20 条、渲染与 API 计数一致、UI 新建→落库、拖拽→PATCH→服务端 status、刷新共享一致、member 越权删除被拒、清理造数 | 11/12 ✅ + 1 缺陷 |
| suiteCleanup(OE-C) | 删除 E2E 新故事、还原 M05 → 总数回 20 | 1/1 ✅ |

## 关键证据(节选)

- OE-07:在线新建「在线E2E故事」→ 服务端可查 id=M21,总数 20→21。
- OE-08:拖拽 M05 到已完成 → `GET /api/stories` 中 M05.status=2。
- OE-09:刷新页面后 M05 仍位于"已完成"列 → 证明写入走 API、数据源为共享库。
- OE-10/11:成员3 删除 M03 → 页面 toast「无权限执行此操作」,M03 保留。
- OE-C1:清理后 QA 库 story 总数回 20、M05.status 还原 0。

## 环境副作用核查

- 开发库 `AIcap`(8000)未做任何写操作,探测仍为 20 故事/16 任务(只读验证)。
- QA 库 `AIcap_qa`(8001)在套件结束后已清理回种子态。
- 测试用浏览器/服务器进程均在会话中后台运行,未改动仓库源文件。
