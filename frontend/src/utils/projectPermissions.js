const WRITER_ROLES = new Set(['admin', 'owner', 'member'])
const REVIEWER_ROLES = new Set(['admin', 'owner'])

export function isProjectReadOnly(online, userRole, projectRole) {
  if (!online) return false
  return !WRITER_ROLES.has(userRole) || !WRITER_ROLES.has(projectRole)
}

export function canReviewProject(online, userRole, projectRole) {
  return Boolean(online && REVIEWER_ROLES.has(userRole) && REVIEWER_ROLES.has(projectRole))
}
