import { api } from './client'

/* 成员画像:技术栈 / 工作能力 / 熟悉的开发流程领域(各成员互不相同)
   契约:GET /api/members/profiles、PATCH /api/members/{userId}/profile(snake_case) */
export const membersApi = {
  profiles: () => api('/api/members/profiles'),

  saveProfile: (userId, payload) => api(`/api/members/${userId}/profile`, {
    method: 'PATCH',
    body: JSON.stringify(payload)
  })
}
