import { api } from './client'

export async function diarizeAudio(meetingId, audio, numSpeakers) {
  const result = await api(`/api/meetings/${encodeURIComponent(meetingId)}/diarization/run`, {
    method: 'POST', body: JSON.stringify({ audio_id: audio.id, num_speakers: numSpeakers })
  }, '/meeting-ai')
  if (result?.audio?.meeting_id !== meetingId || result.audio.audio_id !== audio.id
      || result.audio.sha256 !== audio.sha256 || result.audio.byte_size !== audio.byte_size
      || result.identity_status !== 'anonymous_only' || result.storage_status !== 'not_saved'
      || result.requires_human_review !== true || !Number.isInteger(result.duration_ms)
      || result.duration_ms <= 0 || result.duration_ms > 600000 || !Array.isArray(result.turns)
      || result.turns.length > 4000 || !Number.isInteger(result.speaker_count)
      || result.status !== (result.turns.length ? 'draft' : 'no_speech')) throw new Error('说话人分离结果归属或状态无效')
  let previous = 0; const seen = []
  result.turns.forEach(turn => {
    if (!Number.isInteger(turn.start_ms) || !Number.isInteger(turn.end_ms) || turn.start_ms < previous
        || turn.start_ms < 0 || turn.end_ms <= turn.start_ms || turn.end_ms > result.duration_ms
        || !/^SPK(?:[1-9]|[12][0-9]|3[0-2])$/.test(turn.speaker_id)) throw new Error('说话人时间段无效')
    if (!seen.includes(turn.speaker_id)) {
      if (turn.speaker_id !== `SPK${seen.length + 1}`) throw new Error('说话人标签序列无效')
      seen.push(turn.speaker_id)
    }
    previous = turn.start_ms
  })
  if (result.speaker_count !== seen.length) throw new Error('说话人数与时间段不一致')
  return result
}
