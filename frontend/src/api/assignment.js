import { api } from './client'

export async function recommendAssignment(meetingId, payload) {
  const result = await api(`/api/meetings/${encodeURIComponent(meetingId)}/assignment/recommendations`, {
    method: 'POST', body: JSON.stringify(payload)
  }, '/meeting-ai')
  return validateRecommendations(result, meetingId, payload)
}

export function validateRecommendations(result, meetingId, payload) {
  const context = result?.context
  if (result?.rule_version !== 'assignment-skills-v1' || result.writes_performed !== false
      || result.requirements_source !== 'caller_supplied'
      || !['provisional', 'requirements_needed', 'no_eligible_members', 'no_recorded_skill_match'].includes(result.status)
      || context?.meeting_id !== meetingId || context.target_sprint !== payload.target_sprint
      || context.scope !== 'read_only_preparation' || context.snapshot_consistency !== 'sequential_reads'
      || !Array.isArray(context.selected_stories) || context.selected_stories.length !== 1
      || context.selected_stories[0].id !== payload.story_ids[0]
      || !Array.isArray(context.members) || !Array.isArray(context.gaps) || !Array.isArray(context.tasks)
      || !Array.isArray(result.candidates) || !Array.isArray(result.excluded_viewer_ids)) {
    throw new Error('候选结果格式或归属无效，请重新查询')
  }
  const seen = new Set()
  for (const candidate of result.candidates) {
    const member = context.members.find(m => m.profile?.user_id === candidate.member_id)
    if (!member || member.profile.role === 'viewer' || seen.has(candidate.member_id)
        || !Number.isInteger(candidate.rank) || candidate.rank < 1
        || candidate.capacity_check !== 'unknown' || candidate.suitability !== 'requires_human_review'
        || candidate.total_requirements !== payload.requirements.length
        || !Array.isArray(candidate.matches) || candidate.matches.length !== payload.requirements.length
        || candidate.matched_requirements !== candidate.matches.filter(m => m.meets_requirement === true).length
        || !Array.isArray(member.owned_story_ids) || !Array.isArray(member.active_task_ids)
        || (member.target_sprint_task_ids !== null && !Array.isArray(member.target_sprint_task_ids))) {
      throw new Error('候选匹配依据无效，请重新查询')
    }
    candidate.matches.forEach((match, index) => {
      const expected = payload.requirements[index], actual = match.requirement
      if (!actual || actual.dimension !== expected.dimension || actual.name !== expected.name
          || actual.minimum_level !== expected.minimum_level
          || (match.recorded_level !== null && (!Number.isInteger(match.recorded_level) || match.recorded_level < 1 || match.recorded_level > 5))
          || match.meets_requirement !== (match.recorded_level !== null && match.recorded_level >= expected.minimum_level)) {
        throw new Error('候选技能依据无效，请重新查询')
      }
    })
    seen.add(candidate.member_id)
  }
  return result
}

const base = id => `/api/meetings/${encodeURIComponent(id)}/assignment-suggestions`
const positive = n => Number.isSafeInteger(n) && n > 0
const text = s => typeof s === 'string' && !!s.trim()
export function validateRecord(record, meetingId) {
  if (!text(record?.id) || record.meeting_id !== meetingId || !positive(record.submitted_by)
      || !text(record.created_at) || !/^[a-z0-9-]{1,80}$/.test(record.client_request_id)
      || !Array.isArray(record.input?.requirements) || !Array.isArray(record.input?.story_ids)) throw new Error('分配记录归属或格式无效')
  validateRecommendations(record.result, meetingId, record.input)
  if (record.review !== null) validateReview(record.review, record)
  return record
}
function validateReview(review, record) {
  const input = review?.input, approved = input?.decision === 'approve'
  if (!input || !['approve', 'reject'].includes(input.decision) || !text(input.reason)
      || typeof input.capacity_acknowledged !== 'boolean' || !positive(review.reviewed_by) || !text(review.reviewed_at)
      || review.status !== (approved ? 'approved' : 'rejected')
      || (approved ? !input.capacity_acknowledged || !record.result.candidates.some(c => c.member_id === input.member_id) : input.member_id !== null)
      || !(approved ? ['not_started', 'succeeded'] : ['not_applicable']).includes(review.execution_status)) throw new Error('审核确认无效，请刷新记录核对')
  if (review.execution_status === 'succeeded') validateExecution(review.execution, record)
  return review
}
function validateExecution(value, record) {
  const story = record.result.context.selected_stories[0]
  if (value?.suggestion_id !== record.id || value.meeting_id !== record.meeting_id || value.story_id !== story.id
      || value.execution_status !== 'succeeded' || value.previous_owner_id !== story.owner_id
      || value.new_owner_id !== record.review?.input?.member_id || value.new_owner_id === value.previous_owner_id
      || !positive(value.story_log_id) || !positive(value.executed_by) || !text(value.executed_at)) throw new Error('执行确认无效，请刷新记录核对或重试')
  return value
}
export const assignmentSuggestionsApi = {
  async save(meetingId, payload) {
    const record = validateRecord(await api(`/api/meetings/${encodeURIComponent(meetingId)}/assignment/suggestions`, { method: 'POST', body: JSON.stringify(payload) }, '/meeting-ai'), meetingId)
    if (record.client_request_id !== payload.client_request_id
        || JSON.stringify(record.input.story_ids) !== JSON.stringify(payload.story_ids)
        || record.input.target_sprint !== payload.target_sprint
        || record.input.requirements.length !== payload.requirements.length
        || record.input.requirements.some((r, i) => ['dimension', 'name', 'minimum_level'].some(k => r[k] !== payload.requirements[i][k]))) throw new Error('保存确认与本次请求不一致')
    return record
  },
  async list(meetingId) {
    const records = await api(base(meetingId))
    if (!Array.isArray(records)) throw new Error('建议列表格式无效')
    return records.map(r => validateRecord(r, meetingId))
  },
  async review(meetingId, record, payload) {
    const review = validateReview(await api(`${base(meetingId)}/${encodeURIComponent(record.id)}/review`, { method: 'POST', body: JSON.stringify(payload) }), record)
    if (['decision', 'member_id', 'reason', 'capacity_acknowledged'].some(k => review.input[k] !== payload[k])) throw new Error('审核确认与本次决定不一致，请刷新核对')
    return review
  },
  async execute(meetingId, record) {
    return validateExecution(await api(`${base(meetingId)}/${encodeURIComponent(record.id)}/execute`, { method: 'POST', body: '{}' }), record)
  }
}
