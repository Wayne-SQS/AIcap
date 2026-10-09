import { test, expect } from '@playwright/test'

test('会议流程状态能区分执行失败、冲突和无需执行', async ({ page }) => {
  await page.goto('/')
  const labels = await page.evaluate(async () => {
    const { executionStatusName } = await import('/src/api/workflowLabels.js')
    const { matchesQueueStatus } = await import('/src/api/reviewQueue.js')
    return {
      labels: ['not_started', 'not_applicable', 'not_needed', 'succeeded', 'failed', 'conflict', 'future_state']
        .map(status => executionStatusName(status)),
      filters: [
        matchesQueueStatus({ status: 'modified', executionStatus: 'not_started' }, 'reviewed'),
        matchesQueueStatus({ status: 'pending', executionStatus: 'not_started' }, 'execution'),
        matchesQueueStatus({ status: 'approved', executionStatus: 'not_started' }, 'execution'),
        matchesQueueStatus({ status: 'rejected', executionStatus: 'not_started' }, 'execution'),
        matchesQueueStatus({ status: 'approved', executionStatus: 'failed' }, 'execution')
      ]
    }
  })
  expect(labels.labels).toEqual(['未执行', '无需执行', '无需执行', '执行成功', '执行失败', '执行冲突', '未知执行状态'])
  expect(labels.filters).toEqual([true, false, true, false, true])
})
