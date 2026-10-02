import { api } from './client'
export async function transcribeAudio(meetingId, audio, language) {
  const result = await api(`/api/meetings/${encodeURIComponent(meetingId)}/transcription/run`, {
    method: 'POST', body: JSON.stringify({ audio_id: audio.id, language })
  }, '/meeting-ai')
  if (result?.audio?.meeting_id !== meetingId || result.audio.audio_id !== audio.id || result.audio.sha256 !== audio.sha256
      || result.audio.byte_size !== audio.byte_size || result.storage_status !== 'not_saved'
      || result.diarization_status !== 'not_available' || result.requires_human_review !== true
      || !Number.isInteger(result.duration_ms) || result.duration_ms <= 0 || result.duration_ms > 600000
      || !Array.isArray(result.segments) || result.segments.length > 2000
      || result.status !== (result.segments.length ? 'draft' : 'no_speech')) throw new Error('转写结果归属或状态无效')
  let previous = 0
  result.segments.forEach((s, i) => {
    if (s.segment_id !== `S${i+1}` || s.speaker_id !== null || !Number.isInteger(s.start_ms) || !Number.isInteger(s.end_ms)
        || s.start_ms < previous || s.start_ms < 0 || s.end_ms <= s.start_ms || s.end_ms > result.duration_ms
        || typeof s.text !== 'string' || !s.text.trim()) throw new Error('转写时间戳或片段无效')
    previous = s.start_ms
    if(s.words !== undefined && s.words !== null) {
      if(!Array.isArray(s.words) || !s.words.length || s.words.length > 1000) throw new Error('词级时间戳无效')
      let wordPrevious=s.start_ms
      s.words.forEach((word,j)=>{
        if(word.word_id!==`${s.segment_id}W${j+1}` || !Number.isInteger(word.start_ms) || !Number.isInteger(word.end_ms)
            || word.start_ms<wordPrevious || word.start_ms<s.start_ms || word.end_ms<=word.start_ms || word.end_ms>s.end_ms
            || typeof word.text!=='string' || !word.text.trim()) throw new Error('词级时间戳无效')
        wordPrevious=word.start_ms
      })
      if(s.words.map(word=>word.text).join('').trim()!==s.text) throw new Error('词级文字与片段不一致')
    }
  })
  if (result.text !== result.segments.map(s => s.text).join('\n')) throw new Error('转写文本与片段不一致')
  return result
}
