import test from 'node:test'
import assert from 'node:assert/strict'
import { criticalPath } from './criticalPath.js'

const task = (id, start, end, dependsOn = '', status = 0) => ({ id, w: [start, end], dependsOn, status })

test('selects the longest weighted dependency branch', () => {
  const result = criticalPath([
    task('T01', 1, 1), task('T02', 1, 3),
    task('T03', 4, 4, 'T01,T02'), task('T04', 5, 6, 'T03'),
    task('T05', 1, 4)
  ])
  assert.deepEqual(result, { ids: ['T02', 'T03', 'T04'], weeks: 6, cycle: false })
})

test('ignores cancelled tasks and duplicate dependency references', () => {
  assert.deepEqual(criticalPath([
    task('T01', 1, 6, '', 3), task('T02', 1, 2),
    task('T03', 3, 3, 'T02,T02,T01')
  ]), { ids: ['T02', 'T03'], weeks: 3, cycle: false })
})

test('reports a dependency cycle and handles an empty project', () => {
  assert.deepEqual(criticalPath([task('T01', 1, 1, 'T02'), task('T02', 2, 2, 'T01')]),
    { ids: [], weeks: 0, cycle: true })
  assert.deepEqual(criticalPath([]), { ids: [], weeks: 0, cycle: false })
})
