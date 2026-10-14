import test from 'node:test'
import assert from 'node:assert/strict'
import { isProjectReadOnly, canReviewProject } from '../src/utils/projectPermissions.js'

test('four-view editing follows both account and selected-project roles', () => {
  assert.equal(isProjectReadOnly(true, 'member', 'viewer'), true)
  assert.equal(isProjectReadOnly(true, 'admin', 'viewer'), true)
  assert.equal(isProjectReadOnly(true, 'viewer', 'owner'), true)
  assert.equal(isProjectReadOnly(true, 'member', 'member'), false)
  assert.equal(isProjectReadOnly(true, 'admin', 'owner'), false)
  assert.equal(isProjectReadOnly(true, 'admin', undefined), true)
  assert.equal(isProjectReadOnly(false, undefined, undefined), false)
})

test('review and delete require owner-level roles in both scopes', () => {
  assert.equal(canReviewProject(true, 'admin', 'owner'), true)
  assert.equal(canReviewProject(true, 'owner', 'admin'), true)
  assert.equal(canReviewProject(true, 'admin', 'member'), false)
  assert.equal(canReviewProject(true, 'member', 'owner'), false)
  assert.equal(canReviewProject(false, 'admin', 'owner'), false)
})
