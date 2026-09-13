import { api, apiBase, getAuthToken } from './client'

/* 会议音频:网页录音(浏览器已编码为 mp3)与本地 .mp3 文件
   上传走 multipart,不能复用 api()(它固定 Content-Type: application/json) */
export const audioApi = {
  list: meetingId => api(`/api/meetings/${meetingId}/audio`),

  remove: id => api('/api/audio/' + id, { method: 'DELETE' }),

  async upload(meetingId, file, { source = 'upload', durationMs = null } = {}) {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('source', source)
    if (durationMs != null) fd.append('duration_ms', String(Math.round(durationMs)))
    const token = getAuthToken()
    const headers = token ? { Authorization: 'Bearer ' + token } : {}
    const res = await fetch(`${apiBase()}/api/meetings/${meetingId}/audio`, { method: 'POST', headers, body: fd })
    if (!res.ok) {
      let msg = '上传失败 (' + res.status + ')'
      try { const j = await res.json(); if (typeof j.detail === 'string') msg = j.detail } catch { /* 非 JSON 响应 */ }
      throw new Error(msg)
    }
    return res.json()
  },

  /* 回放/下载需要鉴权头,<audio src> 无法带 header,故先取字节再转 objectURL */
  async fetchBlob(id) {
    const token = getAuthToken()
    const headers = token ? { Authorization: 'Bearer ' + token } : {}
    const res = await fetch(apiBase() + '/api/audio/' + id, { headers })
    if (!res.ok) throw new Error('音频读取失败 (' + res.status + ')')
    return res.blob()
  }
}
