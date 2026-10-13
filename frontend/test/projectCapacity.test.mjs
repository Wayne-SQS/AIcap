import test from 'node:test'
import assert from 'node:assert/strict'
import { capacityBreakdown, projectWeeks, totalCapacity, weeklyCapacity } from '../src/utils/projectCapacity.js'

test('explicit weekly capacities remain individual and scale with a six-week project', () => {
  const project = { total_sprints: 3 }
  assert.deepEqual([15, 14, 16, 13].map((weeklyCapacityHours, id) =>
    totalCapacity({ id, weekly_capacity_hours: weeklyCapacityHours }, project)), [90, 84, 96, 78])
})

test('project duration follows dates before sprint count and recalculates on project switch', () => {
  const member = { id: 1, weekly_capacity_hours: 14 }
  assert.equal(projectWeeks({ start_date: '2026-01-01', end_date: '2026-01-28', total_sprints: 3 }), 4)
  assert.equal(totalCapacity(member, { total_sprints: 2 }), 56)
  assert.equal(totalCapacity(member, { total_sprints: 4 }), 112)
})

test('legacy P003/P004 capacities use stable member defaults instead of historical six-week totals', () => {
  const legacyMembers = [60, 48, 60, 54].map((capacity_hours, id) => ({ id, capacity_hours }))
  assert.deepEqual(legacyMembers.map(weeklyCapacity), [15, 14, 16, 13])
  assert.deepEqual(legacyMembers.map(member => totalCapacity(member, { total_sprints: 3 })), [90, 84, 96, 78])
  assert.deepEqual(legacyMembers.map(member => totalCapacity(member, { total_sprints: 2 })), [60, 56, 64, 52])
  assert.deepEqual(legacyMembers.map(member => totalCapacity(member, { total_sprints: 4 })), [120, 112, 128, 104])
})

test('legacy six-week capacity is only used when member identity is unavailable', () => {
  assert.equal(weeklyCapacity({ capacity_hours: 60 }), 10)
  assert.equal(totalCapacity({ capacity_hours: 60 }, { total_sprints: 3 }), 60)
})

test('missing weekly and legacy values use stable distinct defaults and 100 percent uses project total', () => {
  const a = capacityBreakdown({ id: 0 }, { total_sprints: 3 })
  const b = capacityBreakdown({ id: 1 }, { total_sprints: 3 })
  assert.notEqual(a.weekly, b.weekly)
  assert.equal(a.total / a.total * 100, 100)
})
