import { statusAnalysesApi } from './statusAnalyses'
import { planningAnalysesApi } from './planningAnalyses'
import { reviewAnalysesApi } from './reviewAnalyses'
import { retroAnalysesApi } from './retroAnalyses'
import { refinementAnalysesApi } from './refinementAnalyses'
import { assignmentSuggestionsApi } from './assignment'
import { api } from './client'

const sources = [
  { key: 'daily', label: 'Daily', api: statusAnalysesApi },
  { key: 'planning', label: 'Sprint Planning', api: planningAnalysesApi },
  { key: 'review', label: 'Sprint Review', api: reviewAnalysesApi },
  { key: 'retro', label: 'Sprint Retro', api: retroAnalysesApi },
  { key: 'refinement', label: 'Backlog Refinement', api: refinementAnalysesApi }
]
const arr = value => Array.isArray(value) ? value : []
const display = value => value == null || value === '' ? '' : typeof value === 'object' ? JSON.stringify(value) : String(value)
function proposalSummary(source, original = {}, approved = null) {
  const changes = approved?.changes || approved || original.changes || {}
  const story = original.story_id || changes.story_id || original.result?.story_id
  const before = original.expected?.status ?? original.expected?.sprint
  const after = changes.status ?? changes.sprint
  const statusName = value => ({ 0: '待办', 1: '进行中', 2: '已完成' })[value] ?? display(value)
  if (source === 'daily' || source === 'review') return [story && `需求 #${story}`, before != null && after != null && `${statusName(before)} → ${statusName(after)}`, original.reason].filter(Boolean).join(' · ')
  if (source === 'planning') return [story && `需求 #${story}`, before != null && after != null && `Sprint ${before} → ${after}`, original.reason].filter(Boolean).join(' · ')
  if (source === 'retro') return [changes.title || '新增行动项', changes.owner_id && `负责人 #${changes.owner_id}`, changes.deadline_text].filter(Boolean).join(' · ')
  if (source === 'refinement') return [changes.title || '新增需求', changes.priority && `优先级 ${changes.priority}`, changes.story_points && `${changes.story_points} 点`].filter(Boolean).join(' · ')
  if (source === 'assignment') {
    const target = original.context?.target_sprint
    const assignmentStory = original.context?.selected_stories?.[0]
    const candidates = arr(original.candidates)
    const ranked = candidates.slice(0, 2).map(candidate =>
      `${candidate.display_name || `成员 #${candidate.member_id}`} ${candidate.matched_requirements}/${candidate.total_requirements}`
    )
    const noCandidate = {
      requirements_needed: '需补充技能要求',
      no_eligible_members: '无符合角色范围的候选成员',
      no_recorded_skill_match: '暂无画像记录满足要求'
    }[original.status] || '暂无候选成员'
    const candidateSummary = candidates.length
      ? `候选 ${ranked.join('、')}${candidates.length > 2 ? ` 等 ${candidates.length} 人` : ''}`
      : noCandidate
    return [
      assignmentStory && `故事 ${assignmentStory.id}${assignmentStory.title ? ` · ${assignmentStory.title}` : ''}`,
      `目标 Sprint ${target ?? '未知'}`,
      candidateSummary,
      '容量未验证'
    ].filter(Boolean).join(' · ')
  }
  return display(original.summary || original.reason || original.story_id)
}
function normalizeRecord(source, proposal, execution) {
  const original = proposal.original_proposal || proposal
  const approved = proposal.approved_proposal || null
  const status = proposal.status || 'unknown'
  const executionStatus = execution?.execution_status || proposal.execution_status || 'not_started'
  const resultId = source === 'assignment'
    ? execution?.story_log_id || execution?.story_id
    : execution?.action_item_id || execution?.story_id || execution?.result_id || execution?.story_log_id || execution?.action_item_log_id
  return {
    summaryText: proposalSummary(source, original, approved) || '查看来源分析了解详情',
    reviewer: proposal.reviewed_by, reviewedAt: proposal.reviewed_at, reviewReason: proposal.reason || proposal.review_reason,
    executor: execution?.executed_by || proposal.executed_by,
    executedAt: execution?.executed_at || proposal.executed_at,
    resultId,
    flowStage: workflowStage(status, executionStatus)
  }
}
function workflowStage(status, executionStatus) {
  if (status === 'pending') return '分析完成 → 待审核'
  if (status === 'rejected') return '分析完成 → 已拒绝（未执行）'
  if (status === 'approved') {
    if (executionStatus === 'succeeded') return '分析完成 → 已批准 → 执行成功'
    if (executionStatus === 'not_started') return '分析完成 → 已批准 → 待执行'
    if (executionStatus === 'not_applicable' || executionStatus === 'not_needed') return '分析完成 → 已批准 → 无需执行'
    if (executionStatus === 'failed') return '分析完成 → 已批准 → 执行失败'
    if (executionStatus === 'conflict') return '分析完成 → 已批准 → 执行冲突'
  }
  return `分析完成 → ${status} → ${executionStatus}`
}
export function matchesQueueStatus(row, filter) {
  if (filter === 'pending') return row.status === 'pending'
  if (filter === 'reviewed') return ['approved', 'rejected', 'modified'].includes(row.status)
  if (filter === 'execution') return row.status === 'approved'
    || ['succeeded', 'failed', 'conflict', 'not_applicable', 'not_needed'].includes(row.executionStatus)
  return true
}
async function mapLimit(items, limit, fn) {
  let cursor = 0
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (cursor < items.length) {
      const index = cursor++
      await fn(items[index])
    }
  })
  await Promise.all(workers)
}

function createReadLimiter(limit) {
  let active = 0
  const waiting = []
  return async read => {
    if (active >= limit) await new Promise(resolve => waiting.push(resolve))
    active++
    try { return await read() }
    finally {
      active--
      waiting.shift()?.()
    }
  }
}

/** Read-only front-end aggregation over existing per-meeting records. Never writes or copies a decision. */
export async function loadReviewQueue(meetings) {
  const entries = [], errors = []
  const read = createReadLimiter(6)
  const jobs = (meetings || []).flatMap(meeting => [
    ...sources.map(source => ({ meeting, source })), { meeting, source: null }
  ])
  await mapLimit(jobs, 3, async ({ meeting, source }) => {
    if (source) {
      try {
        const analyses = arr(await read(() => source.api.list(meeting.id)))
        await mapLimit(analyses, 3, async analysis => {
          try {
            const reviewState = await read(() => source.api.reviews(meeting.id, analysis.id))
            const proposals = arr(reviewState?.proposals)
            let executions = []
            if (proposals.some(proposal => proposal.status === 'approved')) {
              try { executions = arr(await read(() => source.api.executions(meeting.id, analysis.id))) }
              catch (error) {
                errors.push({ meetingId: meeting.id, meetingTitle: meeting.title,
                  source: `${source.label} · ${analysis.id} · 执行记录`, message: error.message })
              }
            }
            for (const proposal of proposals) {
              const execution = executions.find(row => row.proposal_id === proposal.proposal_id) || null
              entries.push({
                id: `${source.key}:${meeting.id}:${analysis.id}:${proposal.proposal_id}`,
                source: source.key, sourceLabel: source.label, meetingId: meeting.id, meetingTitle: meeting.title,
                analysisId: analysis.id, createdAt: analysis.created_at || '', proposalId: proposal.proposal_id,
                status: proposal.status || 'unknown', executionStatus: execution?.execution_status || proposal.execution_status || 'not_started',
                proposal, execution, transcript: analysis.transcript || '', summary: analysis.result?.summary || '',
                submittedBy: analysis.submitted_by, sourceError: null,
                ...normalizeRecord(source.key, proposal, execution)
              })
            }
          } catch (error) { errors.push({ meetingId: meeting.id, meetingTitle: meeting.title, source: `${source.label} · ${analysis.id}`, message: error.message }) }
        })
      } catch (error) { errors.push({ meetingId: meeting.id, meetingTitle: meeting.title, source: source.label, message: error.message }) }
      return
    }
    try {
      const rows = await read(() => assignmentSuggestionsApi.list(meeting.id))
      for (const row of rows) {
        const status = row.review?.status || 'pending'
        entries.push({
          id: `assignment:${meeting.id}:${row.id}`, source: 'assignment', sourceLabel: 'Assignment 分配建议',
          meetingId: meeting.id, meetingTitle: meeting.title, analysisId: row.id, proposalId: row.id,
          createdAt: row.created_at, status, executionStatus: row.review?.execution_status || 'not_started',
          proposal: { original_proposal: row.result, approved_proposal: row.review?.input || null,
            status, decision: row.review?.input?.decision || null, reviewed_by: row.review?.reviewed_by,
            reviewed_at: row.review?.reviewed_at, reason: row.review?.input?.reason || '' },
          execution: row.review?.execution || null, transcript: meeting.transcript || '', summary: '', submittedBy: row.submitted_by,
          ...normalizeRecord('assignment', { original_proposal: row.result, approved_proposal: row.review?.input, ...row.review }, row.review?.execution)
        })
      }
    } catch (error) { errors.push({ meetingId: meeting.id, meetingTitle: meeting.title, source: 'Assignment 分配建议', message: error.message }) }
  })
  entries.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))
  return { entries, errors }
}

const analysisPaths = {
  daily: 'status-analyses', planning: 'planning-analyses', review: 'review-analyses',
  retro: 'retro-analyses', refinement: 'refinement-analyses'
}

function generalRow(suggestion) {
  const s = suggestion
  return {
    id: `general:${s.id}`, source: 'general', sourceLabel: s.origin === 'manual' ? '手动会议建议' : '通用会议 Agent',
    meetingId: s.meeting_id, meetingTitle: s.meeting_title || '', analysisId: '', proposalId: s.id,
    createdAt: s.created_at || '', status: s.status,
    executionStatus: s.pool_item_id ? 'succeeded' : s.execution_status || 'not_started',
    proposal: { original_proposal: { reason: s.note, evidence: [{ quote: s.evidence }], changes: s.changes },
      decision: s.status, reason: s.reason, reviewed_by: s.reviewed_by, reviewed_at: s.reviewed_at },
    execution: s.pool_item_id ? { result_id: s.pool_item_id, executed_by: s.executed_by, executed_at: s.executed_at } : null,
    summaryText: s.changes?.title ? `新增需求：${s.changes.title} · ${s.changes.priority || ''}` : s.note || '查看来源分析了解详情',
    flowStage: s.status === 'pending' ? '分析完成 → 待审核' : s.status === 'rejected' ? '分析完成 → 已拒绝（未执行）' : '分析完成 → 已批准 → 执行成功',
    reviewer: s.reviewed_by, reviewedAt: s.reviewed_at, reviewReason: s.reason,
    executor: s.executed_by, executedAt: s.executed_at, resultId: s.pool_item_id
  }
}

/** One server-filtered cursor page; only details for that page are read. */
export async function loadReviewQueuePage(filters = {}, cursor = null, limit = 50) {
  const params = new URLSearchParams({ status: filters.status || 'pending', source: filters.source || 'all', limit: String(limit) })
  if (filters.search) params.set('search', filters.search)
  if (filters.fromDate) params.set('from', filters.fromDate)
  if (filters.toDate) params.set('to', filters.toDate)
  if (cursor) params.set('cursor', cursor)
  const page = await api(`/api/review-queue?${params}`)
  if (!page || !Array.isArray(page.items) || !Object.hasOwn(page, 'nextCursor')) throw new Error('审核队列分页响应无效')
  return hydrateReviewQueuePage(page)
}

export async function loadReviewQueueSummary() {
  const summary = await api('/api/review-queue/summary')
  if (!summary || !Number.isSafeInteger(summary.pendingCount) || !Number.isSafeInteger(summary.executionCount)
      || !Object.hasOwn(summary, 'latest') || !Object.hasOwn(summary, 'recentMeeting')) throw new Error('审核队列摘要响应无效')
  const details = summary.latest
    ? await hydrateReviewQueuePage({ items: [summary.latest], nextCursor: null })
    : { entries: [], errors: [] }
  return { pendingCount: summary.pendingCount, executionCount: summary.executionCount,
    latestEntry: details.entries[0] || null, recentMeeting: summary.recentMeeting, errors: details.errors }
}

async function hydrateReviewQueuePage(page) {
  const entries = [], errors = []
  const read = createReadLimiter(6)
  const groups = new Map()
  for (const ref of page.items) {
    const key = `${ref.source}:${ref.meetingId || ''}:${ref.analysisId}`
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push(ref)
  }
  await mapLimit([...groups.values()], 6, async refs => {
    const ref = refs[0]
    try {
      if (ref.source === 'general') {
        const s = await read(() => api(`/api/suggestions/${encodeURIComponent(ref.proposalId)}`))
        entries.push(generalRow(s))
        return
      }
      if (ref.source === 'assignment') {
        const row = await read(() => api(`/api/meetings/${encodeURIComponent(ref.meetingId)}/assignment-suggestions/${encodeURIComponent(ref.analysisId)}`))
        const status = row.review?.status || 'pending'
        const proposal = { original_proposal: row.result, approved_proposal: row.review?.input || null,
          status, decision: row.review?.input?.decision || null, reviewed_by: row.review?.reviewed_by,
          reviewed_at: row.review?.reviewed_at, reason: row.review?.input?.reason || '' }
        entries.push({ id: `assignment:${ref.meetingId}:${row.id}`, source: 'assignment', sourceLabel: 'Assignment 分配建议',
          meetingId: ref.meetingId, meetingTitle: ref.meetingTitle, analysisId: row.id, proposalId: row.id,
          createdAt: row.created_at, status, executionStatus: row.review?.execution_status || 'not_started',
          proposal, execution: row.review?.execution || null, transcript: '', summary: '', submittedBy: row.submitted_by,
          ...normalizeRecord('assignment', { original_proposal: row.result, approved_proposal: row.review?.input, ...row.review }, row.review?.execution) })
        return
      }
      const source = sources.find(item => item.key === ref.source)
      const path = analysisPaths[ref.source]
      if (!source || !path) throw new Error('未知提案来源')
      const base = `/api/meetings/${encodeURIComponent(ref.meetingId)}/${path}/${encodeURIComponent(ref.analysisId)}`
      const [analysis, reviewState] = await Promise.all([
        read(() => api(base)), read(() => source.api.reviews(ref.meetingId, ref.analysisId))
      ])
      let executions = []
      if (arr(reviewState?.proposals).some(item => item.status === 'approved')) {
        try { executions = arr(await read(() => source.api.executions(ref.meetingId, ref.analysisId))) }
        catch (error) { errors.push({ meetingId: ref.meetingId, meetingTitle: ref.meetingTitle,
          source: `${source.label} · ${ref.analysisId} · 执行记录`, message: error.message }) }
      }
      for (const item of refs) {
        const proposal = arr(reviewState?.proposals).find(p => p.proposal_id === item.proposalId)
        if (!proposal) throw new Error(`提案 ${item.proposalId} 已变化，请刷新队列`)
        const execution = executions.find(row => row.proposal_id === item.proposalId) || null
        entries.push({ id: `${ref.source}:${ref.meetingId}:${ref.analysisId}:${item.proposalId}`,
          source: ref.source, sourceLabel: source.label, meetingId: ref.meetingId, meetingTitle: ref.meetingTitle,
          analysisId: ref.analysisId, createdAt: analysis.created_at || item.createdAt, proposalId: item.proposalId,
          status: proposal.status || item.status, executionStatus: execution?.execution_status || proposal.execution_status || item.executionStatus,
          proposal, execution, transcript: analysis.transcript || '', summary: analysis.result?.summary || '',
          submittedBy: analysis.submitted_by, sourceError: null,
          ...normalizeRecord(ref.source, proposal, execution) })
      }
    } catch (error) { errors.push({ meetingId: ref.meetingId, meetingTitle: ref.meetingTitle,
      source: ref.source, message: error.message }) }
  })
  entries.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))
  return { entries, errors, nextCursor: page.nextCursor }
}
