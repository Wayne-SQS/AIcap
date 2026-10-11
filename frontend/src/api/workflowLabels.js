const executionStatusNames = {
  not_started: '未执行',
  not_applicable: '无需执行',
  not_needed: '无需执行',
  succeeded: '执行成功',
  failed: '执行失败',
  conflict: '执行冲突'
}

export function executionStatusName(value) {
  return executionStatusNames[value] || '未知执行状态'
}
