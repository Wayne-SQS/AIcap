/* 成员身份:后端 users.id(1 基自增) ↔ 前端成员下标(0 基)的唯一转换处。
 *
 * 背景:前端种子 MEMBERS 用 0 基下标当成员 id(界面显示 P1..P5),后端用 users.id 从 1 自增,
 * 两者一直靠「下标 = id - 1」对齐。该偏移原先散落在 10 处 —— stores/project.js 的
 * mapStory / mapTask / mergeMembers、StoryEditorDialog、TaskEditorDialog、PoolView
 * (其中 PoolView 还从 members 反推出 `{ id: m.id + 1 }` 伪造了一份 user 列表)。
 * 任一处写错都会造成「负责人串位」,而且症状是姓名对不上、不报错,极难排查;故集中到本模块。
 *
 * 已知局限(刻意保留,不是遗漏):这仍是**位置对齐**,不是身份对齐。
 * 若 users 表出现删除或非连续 id,`id - 1` 会产生空洞,mergeMembers 会退回种子姓名而不是报错。
 * 对此有两道防线:
 *   1) mergeMembers 检测到 users.id 非 1..N 连续时 console.warn;
 *   2) project.checkConsistency() 检查成员下标是否连续 0..N-1,不连续则记为 error。
 * 彻底改为按 userId 对齐需要改动 20+ 处及大量 E2E 断言(`String(id - 1)` 被直接断言),
 * 回归风险高于收益,故不做 —— 本模块的作用是把「风险点唯一化 + 可观测」。
 */

/** users.id(1 基) → 前端成员下标(0 基);null/undefined 原样透传(= 未分配) */
export function memberIndex(userId) {
  return userId == null ? null : userId - 1
}

/** 前端成员下标(0 基) → users.id(1 基);null/undefined 原样透传(= 未分配) */
export function memberUserId(index) {
  return index == null ? null : index + 1
}

/** 成员展示标签 P1..P5(输入为 0 基下标);未分配显示「未分配」 */
export function memberLabel(index) {
  return index == null ? '未分配' : 'P' + (index + 1)
}

/** users.id 是否为 1..N 连续(位置对齐成立的前提);用于 mergeMembers 的告警 */
export function isContiguousUserIds(ids) {
  const sorted = [...ids].sort((a, b) => a - b)
  return sorted.length > 0 && sorted.every((id, i) => id === i + 1)
}
