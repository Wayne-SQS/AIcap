<script setup>
import { ref } from 'vue'

/* 关卡三选一弹窗:Promise 化,结构对齐旧版 L728-744 + openUndoneChoice(L1386-1401)
   resolve 'cancel'/'keep'/'detach';关闭(含「取消删除」)= null */
const dialogEl = ref(null)
const text = ref('')
let resolver = null

function open(cardId, undoneCount) {
  return new Promise(resolve => {
    resolver = resolve
    text.value = `看板卡 ${cardId} 下有 ${undoneCount} 条未完成子任务,请选择处理方式:`
    dialogEl.value?.showModal()
  })
}
function choose(v) {
  const r = resolver; resolver = null
  dialogEl.value?.close()
  if (r) r(v)
}
function onClosed() {
  if (resolver) { const r = resolver; resolver = null; r(null) }
}
defineExpose({ open })
</script>

<template>
  <dialog id="undone-choice" ref="dialogEl" aria-labelledby="undone-heading" @close="onClosed">
    <form onsubmit="event.preventDefault()">
      <header>
        <h2 id="undone-heading">删除看板卡 · 未完成子任务处理</h2>
      </header>
      <div class="formbody">
        <p class="small" id="undone-text" style="margin:0">{{ text }}</p>
        <div class="actions" style="flex-direction:column;gap:10px;align-items:stretch">
          <button type="button" data-undone="cancel" class="danger" @click="choose('cancel')">① 标记已取消(真废弃,子任务状态改为「已取消」)</button>
          <button type="button" data-undone="keep" class="primary" @click="choose('keep')">② 保留状态继续跟踪(默认 · 仅解绑,脱离该卡)</button>
          <button type="button" data-undone="detach" @click="choose('detach')">③ 转为独立任务(脱离该卡,整体挪入下个 Sprint)</button>
        </div>
        <p class="small" style="margin:8px 0 0">已完成子任务一律保留原记录不动,不影响历史。</p>
        <div class="actions"><button type="button" @click="dialogEl?.close()">取消删除</button></div>
      </div>
    </form>
  </dialog>
</template>
