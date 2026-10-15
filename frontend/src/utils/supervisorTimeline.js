/* 主管事件 → 人话。把编排层的 SSE 信封翻成时间轴条目 / 对话气泡。
   ---------------------------------------------------------------
   **事件类型以代码为准,不以设计文档为准。**
   `docs/02-契约/主管智能体_工作流落地设计.md` §6 那张表写的是 plan.proposed /
   dispatch.sent / run.progress / answer.final —— 那套名字**从未落地**。
   `agent-orchestrator/app/events.py:EVENT_TYPES` 才是真实清单(11 类),载荷字段
   逐条对着 `app/main.py#_plan_phase` 与 `app/loop.py#_run_loop` 核过。

   三个同事的名字取自 `docs/01-产品/智能体产品契约.md`:译(会议)/ 衡(画像)/ 绘(画图)。
   派活目标是**触发型工具名**(契约 §2.4,route 即工具名),所以要反查同事。 */

/* 触发型工具 → 同事。三枚都已上线(2026-10-15 画像域补了后台消费者),
   本表是展示层,不影响编排层路由 —— Java 侧新增触发型工具时会显示原始工具名。 */
const ROUTE_AGENTS = {
  start_meeting_analysis: { name: '译', role: '会议智能体', color: 'blue' },
  start_plan_draft: { name: '绘', role: '画图智能体', color: 'lilac' },
  start_profile_analysis: { name: '衡', role: '画像智能体', color: 'orange' }
}

/* 只读工具 → 中文名。名单取自三处:
   会议域 5 枚(platform/MeetingTools)、画像域 8 枚(profile/ProfileToolRegistry)、
   平台域 semantic_search(platform/PlatformToolCatalog)。认不出来就原样显示工具名 ——
   宁可漏译,不要瞎猜。 */
const TOOL_LABELS = {
  search_stories: '检索用户故事',
  search_pool: '检索需求池',
  list_tasks: '列出任务',
  list_members: '列出成员',
  project_summary: '项目总览',
  query_tasks: '查询任务',
  query_activities: '查询活动',
  query_members: '查询成员',
  query_member_profile: '查询成员画像',
  query_difficulty_history: '查询难度历史',
  query_profile_snapshots: '查询画像快照',
  query_corrections: '查询纠正记录',
  query_git_commits: '查询提交记录',
  semantic_search: '语义检索'
}

/** 工具名的中文显示名;未登记的原样返回 —— 下游新增工具时显示原始名,不猜 */
export function toolLabel(name) {
  return TOOL_LABELS[name] || name
}

/** route(触发型工具名)→ 同事;认不出返回 null */
export function routeAgent(route) {
  return route ? ROUTE_AGENTS[route] || null : null
}

/** `run.end.output` → 直接给用户看的一句话（契约 §3.3「最终答复信封」）。
 *
 *  编排层最后那一发带 `response_format=json_object`（供应商硬约束），所以 output 通常是一坨
 *  JSON —— **必须取 `answer` 字段再渲染**，否则对话气泡里就是裸 JSON。
 *  回退顺序 answer → summary/headline/message/text → 原样；历史 run 没有 answer 时保持原样，
 *  不做破坏性改写（宁可不美化，也不丢信息）。 */
export function readableAnswer(output) {
  const text = String(output == null ? '' : output).trim()
  if (!text) return '（本轮没有产出文本）'
  if (!text.startsWith('{')) return text
  let parsed
  try {
    parsed = JSON.parse(text)
  } catch {
    return text
  }
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    for (const key of ['answer', 'summary', 'headline', 'message', 'text']) {
      const value = parsed[key]
      if (typeof value === 'string' && value.trim()) return value.trim()
    }
  }
  return text
}

/** run.error 的 code → 人话。code 来自编排层各处 _Gate / PlatformError / ModelError。 */
const ERROR_TEXT = {
  platform_unavailable: '连不上平台后端',
  not_configured: '服务端未配置模型密钥',
  invalid_config: '模型配置不合法',
  provider_timeout: '模型调用超时',
  provider_unreachable: '模型服务不可达',
  provider_error: '模型服务报错',
  incomplete_response: '模型返回不完整',
  invalid_response: '模型返回无法解析',
  step_limit: '思考轮数用尽,没能收敛出结论',
  run_timeout: '本轮超过时限',
  tool_limit: '工具调用次数超上限',
  invalid_tool_call: '工具调用编号重复,已中止',
  invalid_tool_arguments: '工具参数不是合法 JSON',
  internal: '编排层内部异常'
}

/** 毫秒 → 「1.2s」/「340ms」 */
function dur(ms) {
  if (typeof ms !== 'number') return ''
  return ms >= 1000 ? (ms / 1000).toFixed(1) + 's' : ms + 'ms'
}

function percent(confidence) {
  if (typeof confidence !== 'number') return null
  return Math.round(confidence * 100) + '%'
}

/**
 * 把一条事件信封翻成展示条目。
 *
 * @param {object} env `{seq, run_id, session_id, ts, type, data}`
 * @returns {object|null} null 表示这条不用显示(纯内部噪音)
 *   - {tone, label, text, detail?, options?, answer?, done?}
 *   - `answer` 有值时该条同时要作为对话气泡渲染
 *   - `done` 为 true 表示本轮已终局
 */
export function describeEvent(env) {
  const data = (env && env.data) || {}
  const at = env.ts || ''
  switch (env.type) {
    case 'session.start':
      return { tone: 'info', label: '开始', text: '开始处理你的问题', at }

    case 'intent.routed': {
      const route = data.route || null
      const pct = percent(data.confidence)
      if (!route) {
        // route=null 是「问数据的回合」,不是反问 —— 这一点若抹平会把最常用的问答砍掉
        return {
          tone: 'info',
          label: '判断',
          text: '这个问题我自己查数据回答',
          detail: [data.reason, pct ? '把握 ' + pct : ''].filter(Boolean).join(' · '),
          at
        }
      }
      const agent = routeAgent(route)
      const who = agent ? `${agent.name}(${agent.role})` : route
      const parts = []
      if (data.reason) parts.push(data.reason)
      if (pct) parts.push('把握 ' + pct)
      // 留痕写失败要看得见 —— 静默少一行指标比明着少一行危险
      if (data.logged === false) parts.push('⚠ 路由留痕未写入')
      return {
        tone: 'act',
        label: '打算派活',
        text: `我打算让 ${who} 去做`,
        detail: parts.join(' · '),
        at
      }
    }

    case 'clarify.request': {
      const parts = []
      if (data.logged === false) parts.push('⚠ 路由留痕未写入')
      return {
        tone: 'warn',
        label: '需要确认',
        text: data.question || '需要你先确认一下',
        options: Array.isArray(data.options) ? data.options.filter(Boolean) : [],
        detail: parts.join(' · '),
        answer: data.question || '需要你先确认一下',
        done: true,
        at
      }
    }

    case 'agent.dispatched': {
      const agent = routeAgent(data.agent)
      const who = agent ? agent.name : data.agent
      const bits = []
      if (data.job_id) bits.push('编号 #' + data.job_id)
      if (data.status) bits.push(data.status)
      // 幂等防重:目标对象上已有未完成 run,返回的是既有那个,不是新建的
      if (data.deduplicated) bits.push('该对象已有运行中的活,复用了它')
      return {
        tone: 'act',
        label: '已派出',
        text: `已让 ${who} 开始干活`,
        detail: bits.join(' · '),
        at
      }
    }

    case 'model.step': {
      if (data.final) return { tone: 'info', label: '整理', text: '整理答复…', at }
      const names = Array.isArray(data.tool_names) ? data.tool_names : []
      return {
        tone: 'info',
        label: '第 ' + (data.step || '?') + ' 轮',
        text: names.length ? '准备查 ' + names.map(toolLabel).join('、') : '思考下一步',
        at
      }
    }

    case 'tool.call':
      return { tone: 'info', label: '查数据', text: '查 ' + toolLabel(data.name), at }

    case 'tool.result': {
      if (data.failure) {
        return {
          tone: 'warn',
          label: '没查到',
          text: toolLabel(data.name) + ' 没拿到数据',
          detail: data.error_code || '',
          at
        }
      }
      const bits = []
      if (typeof data.latency_ms === 'number') bits.push(dur(data.latency_ms))
      return {
        tone: 'ok',
        label: '拿到了',
        text: toolLabel(data.name) + ' 有结果',
        detail: bits.join(' · '),
        at
      }
    }

    case 'run.end': {
      // status=clarify 的这条只是收尾标注,真正的追问内容在上一条 clarify.request 里,
      // 避免同一个问题在时间轴里出现两次
      if (data.status === 'clarify') return null
      const bits = []
      if (data.tool_calls) bits.push(data.tool_calls + ' 次查询')
      if (typeof data.elapsed_ms === 'number') bits.push(dur(data.elapsed_ms))
      return {
        tone: 'ok',
        label: '完成',
        text: '答复已整理好',
        detail: bits.join(' · '),
        answer: readableAnswer(data.output),
        done: true,
        at
      }
    }

    case 'run.error': {
      const code = data.code || 'internal'
      const bits = []
      if (data.logged === false) bits.push('⚠ 路由留痕未写入')
      return {
        tone: 'err',
        label: '中断',
        text: ERROR_TEXT[code] || ('出错:' + code),
        detail: [data.message, bits.join('')].filter(Boolean).join(' · '),
        // answer **必须给值**：它同时是对话气泡的内容，而时间轴只在
        // `v-for="m in messages"` 里用 stepsFor(m.runId) 渲染 —— answer 为 null 时
        // 这轮既没有气泡、事件也挂不上任何消息节点，于是 run.error 的那几条
        // **一条都进不了 DOM**：用户看到"发出去、转一圈、什么都没有"，与成功无法区分。
        // （真实浏览器走查里实际发生过：派活被平台拒，窗口里毫无反应、console 也干净。）
        answer: '这一轮没跑完：' + (data.message || ERROR_TEXT[code] || code),
        done: true,
        at
      }
    }

    case 'agent.report':
      // S8′ 之前不会有,但契约 §3.4 允许它在终局之后到达(异步汇报)
      return { tone: 'ok', label: '回报', text: '收到子智能体回报', at }

    case 'artifact.ready':
      return { tone: 'ok', label: '产出', text: '产出已就绪', at }

    default:
      // 未知事件类型:显示出来而不是吞掉 —— 静默丢弃会让新的事件类型"悄悄没人接"
      return { tone: 'info', label: env.type, text: env.type, at }
  }
}
