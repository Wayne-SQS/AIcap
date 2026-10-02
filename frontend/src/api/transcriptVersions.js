import { api } from './client'
const path = id => `/api/meetings/${encodeURIComponent(id)}/transcript-versions`
function validate(row, meetingId) {
  if (!row || typeof row.id !== 'string' || !row.id || row.meeting_id !== meetingId || row.provenance !== 'caller_submitted'
      || row.audio_id !== row.draft?.audio_id || typeof row.draft?.text !== 'string' || !Array.isArray(row.draft?.segments)
      || (row.confirmation !== null && (!row.confirmation?.analysis_meeting_id || !row.confirmation?.confirmed_by
      || typeof row.confirmation?.input?.text !== 'string'))) throw new Error('转写版本响应无效')
  return row
}
export async function listVersions(id) {
  const rows = await api(path(id))
  if (!Array.isArray(rows)) throw new Error('转写历史响应无效')
  return rows.map(row => validate(row, id))
}
export async function saveVersion(id, result) {
  const draft = { audio_id: result.audio.audio_id, sha256: result.audio.sha256, duration_ms: result.duration_ms,
    language: result.language, text: result.text, segments: result.segments }
  // Stable per-content request: retrying even after reload cannot duplicate the same draft.
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify(draft)))
  const key = Array.from(new Uint8Array(digest), x => x.toString(16).padStart(2, '0')).join('')
  const row = validate(await api(path(id), { method: 'POST', body: JSON.stringify({ client_request_id: key, draft }) }), id)
  if (row.client_request_id !== key || JSON.stringify(row.draft) !== JSON.stringify(draft)) {
    // Object key order is not significant in JSON.
    if (row.client_request_id !== key || row.draft.audio_id !== draft.audio_id || row.draft.sha256 !== draft.sha256
        || row.draft.text !== draft.text || row.draft.duration_ms !== draft.duration_ms
        || row.draft.language !== draft.language || JSON.stringify(row.draft.segments) !== JSON.stringify(draft.segments)) throw new Error('保存结果与本次草稿不符')
  }
  return row
}
export async function confirmVersion(id, version, input) {
  const row = validate(await api(`${path(id)}/${encodeURIComponent(version)}/confirm`, { method: 'POST', body: JSON.stringify(input) }), id)
  if (row.id !== version || row.confirmation?.input?.text !== input.text || row.confirmation?.input?.title !== input.title
      || row.confirmation?.input?.acknowledged !== true
      || JSON.stringify(row.confirmation?.input?.speaker_alignment ?? null) !== JSON.stringify(input.speaker_alignment ?? null)) throw new Error('人工确认响应与提交不符，请刷新历史核对')
  return row
}
