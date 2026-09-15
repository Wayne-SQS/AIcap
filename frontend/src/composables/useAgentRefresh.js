import { ref } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { useToast } from '@/composables/useToast'
import { usePermissionGuard } from '@/composables/usePermissionGuard'
import { tasksApi } from '@/api/tasks'
import { profileAgentApi } from '@/api/profileAgent'
import { memberUserId } from '@/data/memberIdentity'

/* 「四人成员中任意人完成任务 → 在当前任务上改成已完成 → 触发智能体 → 智能体读取并评估
   GitHub 仓库的提交物」这条链的**唯一入口**。

   为什么要有这一处:
   - 改状态与触发智能体必须成对出现。此前四个视图(PATCH /api/tasks/{id})都能改任务,
     但没有任何一处写状态,更没有一处去调智能体:后端 @Scheduled 自动同步在
     GITHUB_AUTO_SYNC_HOURS=0 时关闭,任务状态变化本身不触发任何东西,
     所以"点了已完成但智能体没动静"是必然的。
   - 触发顺序固定为「先读提交物,再评估」:GitHub 同步落库(activity_records)是评估的输入,
     反过来的话评估看到的是旧数据。

   **只在有人提交时才跑,不做定时轮询、不做全团队重评估**:
   - 没有任何定时任务参与(GITHUB_AUTO_SYNC_HOURS=0 → 每小时那次 @Scheduled 只空转一次、
     不发请求;profile-agent.scheduled.enabled=false → 每周一那次也不跑);
   - 评估范围收窄到**提交者本人**(POST /analysis/run?userId=):后端只对这个人做 1 次
     成员画像调用,而不是把 5 个人全部重新评估(6 次 LLM → 1 次)。全团队正式分析仍由
     面板上的「运行分析」/「送审」按钮人工触发,不在完成任务的路径上。

   分工(本文件只管把智能体叫起来并如实转述结果,不管智能体内部怎么算):
   ① POST /profile-agent/github/sync —— 读仓库提交物,增量落库(幂等,靠 github_event_id 唯一索引)
   ② POST /profile-agent/analysis/run?userId=<提交者> —— 只评估这一个人的提交物并留痕
   ③ 广播 AGENT_UPDATED_EVENT —— 画像面板/任务提交面板各自重新拉取,不靠刷新整页

   评估含 LLM 调用,单次可达数十秒,因此 refreshAgent() 由调用方 fire-and-forget(不 await),
   内部全程自带 try/catch,永远不会向外抛异常、不会卡住"标记完成"这个动作。

   连续完成多个任务:评估串行排队,不丢弃。
   成员点第二个任务时上一次评估可能还在跑(单人评估实测 ~14 秒),此时如果直接 return,
   第二次就永远不会被评估 —— 所以这里排队,当前这次跑完自动接着评估下一位;
   队列里的后续评估不再重复拉仓库(几秒前刚同步过同一个窗口)。 */

/** 智能体数据已更新:两个智能体面板监听它重新拉取(避免整页刷新) */
export const AGENT_UPDATED_EVENT = 'aicap:agent-updated'

/** 正在标记完成的任务 id(按钮显示「提交中…」);智能体评估串行执行,同时只跑一个 */
export const completingTaskId = ref(null)
export const agentUpdating = ref(false)

/** 排队等待评估的提交者(user_id);同一人只排一次 */
export const agentQueue = ref([])

/** 本地日期串:不能用 toISOString()——它按 UTC 输出,东八区本地零点会被减掉一天 */
export function isoDate(d) {
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 智能体评估窗口:近 30 天(默认口径与任务提交面板一致),覆盖成员当前的全部提交 */
export function agentRange(days = 30) {
  const end = new Date()
  const start = new Date(end.getFullYear(), end.getMonth(), end.getDate() - (days - 1))
  return { start: isoDate(start), end: isoDate(end) }
}

/** 广播「智能体已更新」,已挂载的面板立即重新拉取 */
export function notifyAgentUpdated() {
  window.dispatchEvent(new CustomEvent(AGENT_UPDATED_EVENT))
}

/** 取第一条非空文本(智能体的 ai_inference 是字符串数组) */
function firstText(v) {
  if (Array.isArray(v)) return v.find(x => typeof x === 'string' && x.trim()) || ''
  return typeof v === 'string' ? v : ''
}

/** toast 是单行短提示:长结论截断,完整结论仍留在面板与运行记录里 */
function brief(s, max = 70) {
  const t = String(s || '').replace(/\s+/g, ' ').trim()
  return t.length > max ? t.slice(0, max) + '…' : t
}

export function useAgentRefresh() {
  const project = useProjectStore()
  const session = useSessionStore()
  const { notify } = useToast()
  const { guard } = usePermissionGuard()

  /**
   * 叫醒智能体:读仓库提交物 → **只对提交者本人**做一次评估 → 通知面板。
   * 正在评估时再次调用 = 排队(不丢弃),当前这次跑完自动接着评估下一位,
   * 这样"连续完成多个任务"每一次都能被评估。
   * 不 await 即可安全调用(内部不抛异常);未配置 GitHub 时如实报「未配置」而不是假装成功。
   * @param {number|null} userId 提交者(点「已完成」那个人的负责人);缺省退回当前登录用户
   */
  async function refreshAgent(userId = null) {
    const who = userId != null ? userId : (session.currentUser?.id ?? null)
    if (agentUpdating.value) {
      if (who != null && !agentQueue.value.includes(who)) agentQueue.value.push(who)
      notify(who != null
        ? `智能体正在评估上一位，成员#${who} 的提交已排队（队列 ${agentQueue.value.length} 位）`
        : '智能体正在评估中，请稍候')
      return
    }
    agentUpdating.value = true
    try {
      await evaluateOne(who, true)   // 第一位:同步仓库 + 评估
      let next
      while ((next = agentQueue.value.shift()) != null) {
        await evaluateOne(next, false)   // 排队的:几秒前刚同步过同一窗口,不重复拉仓库
      }
    } finally {
      agentUpdating.value = false
      agentQueue.value = []
    }
  }

  /** 读一次仓库 + 评估一个人(单次评估的全部副作用都收在这里,保证异常不外溢) */
  async function evaluateOne(who, doSync) {
    const { start, end } = agentRange()
    let synced = false
    try {
      if (doSync) {
        const sync = await profileAgentApi.githubSync(start, end)
        // 未配置:后端返回单数 error,原样转述,不继续往下走评估
        if (sync && sync.error) {
          notify('智能体未读取仓库：' + sync.error)
          return
        }
        // 拉取失败(403 限流/超时/404/非 JSON)写进 errors[],不能因为 synced 缺失就显示成"新增 0 条"
        const errs = Array.isArray(sync?.errors) ? sync.errors : []
        if (errs.length) notify(`智能体读取仓库未完成：${errs.length} 项事件拉取失败 — ${errs[0]}`)
        else notify(`智能体已读取 GitHub 提交物：新增 ${sync?.synced ?? 0} 条，跳过 ${sync?.skipped ?? 0} 条`)
        notifyAgentUpdated()   // 同步完成先刷一次:活动列表/热力图立刻就能看到新提交
      }
      synced = true

      // userId 只传一个人:后端只评估这名成员的提交物(1 次 LLM,不是全团队 6 次)
      const run = await profileAgentApi.run(start, end, who)
      const m = (run?.members || [])[0]
      const engine = run?.engine === 'llm' ? 'LLM' : '规则引擎'
      const name = m?.display_name || (who != null ? `成员#${who}` : '提交者')
      const gist = firstText(m?.ai_inference) || m?.dynamic_profile?.delivery_timeliness
        || m?.dynamic_profile?.good_at || `${(m?.objective?.work_items || []).length} 项提交物`
      notify(`${name} 提交评估（${engine}）：${brief(gist)}`)
      notifyAgentUpdated()
    } catch (e) {
      // 已同步的内容仍然有效:只在"评估"这一段失败时如实报这一段的错
      notify((synced ? '智能体评估未完成：' : '智能体更新失败：') + (e.message || '未知错误'))
      if (synced) notifyAgentUpdated()
    }
  }

  /**
   * 在当前任务上把状态改成「已完成」,成功即触发智能体。
   * 在线:PATCH /api/tasks/{id} status=2(后端联动 progress=100)+ 重载项目数据;
   * 离线:只改本机内存数据并如实说明不会触发智能体。
   */
  async function completeTask(task) {
    if (!task) return
    if (guard('标记任务完成')) return
    if (project.taskStatusKey(task) === 'done') {
      notify(`${task.id} 已是已完成状态`)
      return
    }
    completingTaskId.value = task.id
    try {
      if (session.online) {
        await tasksApi.patch(task.id, { status: 2 })
        await project.loadAll()
      } else {
        task.status = 2
        task.progress = 100
      }
      project.addlog('edit', task.id, `${task.name} → 已完成`)
      if (!session.online) {
        notify(`${task.id} 已标记完成 · 离线演示模式不触发智能体`)
        return
      }
      notify(`${task.id} 已标记完成 · 已触发任务提交智能体`)
      // 评估对象 = 这条任务的负责人(干活/提交的那个人),而不是碰巧点按钮的人
      refreshAgent(task.owner != null ? memberUserId(task.owner) : null)   // 故意不 await:评估含 LLM
    } catch (e) {
      notify(e.message || '更新任务状态失败')
    } finally {
      completingTaskId.value = null
    }
  }

  return { completeTask, refreshAgent }
}
