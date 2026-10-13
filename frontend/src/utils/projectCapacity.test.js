import assert from 'node:assert/strict'
import test from 'node:test'

import { capacityBreakdown, weeklyCapacity } from './projectCapacity.js'

test('explicit zero weekly capacity stays zero across project duration', () => {
  const member = { id: 0, weeklyCapacity: 0, capacity: 60 }
  assert.equal(weeklyCapacity(member), 0)
  assert.deepEqual(capacityBreakdown(member, { total_sprints: 3 }), {
    weeks: 6,
    weekly: 0,
    total: 0
  })
})

test('missing weekly capacity still uses the member fallback', () => {
  assert.equal(weeklyCapacity({ id: 0, weeklyCapacity: null, capacity: 60 }), 15)
})
