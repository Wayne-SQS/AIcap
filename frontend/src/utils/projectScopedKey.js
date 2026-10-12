export function projectScopedKey(projectId, code) {
  const project = String(projectId || 'none').trim().toUpperCase()
  return `${project}::${String(code ?? '').trim()}`
}
