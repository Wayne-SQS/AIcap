/* localStorage key 与全局常量(与旧版 index.html 逐字一致,勿改) */
export const TOKEN_KEY = 'aiguanli_token'
export const STORIES_KEY = 'aiguanli-pixel-stories-v1'
export const TASKS_KEY = 'aiguanli-pixel-tasks-v1'
export const LOG_KEY = 'aiguanli-pixel-log-v1'
export const POOL_KEY = 'aiguanli-pixel-pool-v1'
export const SUG_KEY = 'aiguanli-pixel-sug-v1'
export const DEC_KEY = 'aiguanli-pixel-dec-v1'

export const statuses = ['待办', '进行中', '已完成']
export const activities = ['进入与组织项目', '梳理需求', '安排与执行', '观察与协同', '复盘改进']
export const VIEW_NAMES = {
  overview: '项目总览',
  pool: '需求池',
  board: '用户故事看板',
  gantt: '甘特图',
  members: '成员任务图',
  profiles: '成员画像',
  uml: 'UML 图',
  ai: 'AI 工作台',
  review: '提案审核中心'
}

export function esc(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
}
