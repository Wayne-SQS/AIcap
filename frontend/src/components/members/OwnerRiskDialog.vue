<script setup>
import { ref } from 'vue'

const emit = defineEmits(['cancel', 'confirm'])
const dialog = ref(null)
const state = ref({ taskId: '', memberName: '', risks: [], groups: [] })

function open(next) {
  state.value = next
  dialog.value?.showModal()
}
function close(action) {
  dialog.value?.close()
  emit(action)
}
function onCancel(event) {
  event.preventDefault()
  close('cancel')
}
defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="risk-dialog" @cancel="onCancel">
    <form method="dialog" class="risk-dialog-panel">
      <div class="dialog-head">
        <div>
          <div class="eyebrow">OWNER CHANGE / 风险确认</div>
          <h2>重新分配 {{ state.taskId }} 后检测到风险</h2>
        </div>
      </div>
      <p class="risk-dialog-intro">目标成员：<b>{{ state.memberName }}</b> · 共检测到 {{ state.risks.length }} 项风险</p>
      <p v-if="!state.groups.length && !state.risks.length" class="small">未发现成员负载、依赖、Sprint、延期或后续任务风险。</p>
      <div class="risk-dialog-body">
        <section v-for="group in (state.groups.length ? state.groups : [{ title: '成员负载超限', items: state.risks.map(risk => risk.text) }])" :key="group.type || group.title" class="risk-group">
          <h3>{{ group.title }}</h3>
          <p v-for="item in group.items" :key="item" class="risk-item">⚠ {{ item }}</p>
        </section>
      </div>
      <div class="dialog-actions">
        <button type="button" class="btn-secondary" @click="close('cancel')">取消调整</button>
        <button type="button" class="btn-primary" @click="close('confirm')">仍然分配</button>
      </div>
    </form>
  </dialog>
</template>
