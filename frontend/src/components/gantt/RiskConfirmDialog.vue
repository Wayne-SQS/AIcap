<script setup>
import { nextTick, ref } from 'vue'

const dialogEl = ref(null)
const taskId = ref('')
const title = ref('')
const groups = ref([])
const total = ref(0)

const emit = defineEmits(['cancel', 'confirm'])

function open(payload) {
  taskId.value = payload.taskId
  title.value = payload.title || `调整 ${payload.taskId} 后检测到风险`
  groups.value = payload.groups || []
  total.value = payload.total || groups.value.reduce((sum, group) => sum + group.items.length, 0)
  nextTick(() => dialogEl.value?.showModal())
}

function close() {
  dialogEl.value?.close()
}

function cancel() {
  close()
  emit('cancel')
}

function confirm() {
  close()
  emit('confirm')
}

defineExpose({ open, close })
</script>

<template>
  <dialog ref="dialogEl" class="risk-dialog" aria-labelledby="risk-heading" @cancel.prevent="cancel">
    <header>
      <h2 id="risk-heading">{{ title }}</h2>
      <button type="button" aria-label="取消调整" @click="cancel">×</button>
    </header>
    <div class="risk-body">
      <p class="risk-summary">影响分析完成 · 检测到 {{ total }} 项风险</p>
      <p v-if="!groups.length" class="small">未发现成员负载、任务依赖、Sprint、延期或后续任务风险。</p>
      <div class="risk-list">
        <section v-for="group in groups" :key="group.type" class="risk-group">
          <h3>{{ group.title }}</h3>
          <ul>
            <li v-for="item in group.items" :key="item">{{ item }}</li>
          </ul>
        </section>
      </div>
      <div class="actions risk-actions">
        <button type="button" @click="cancel">取消调整</button>
        <button type="button" class="primary" @click="confirm">仍然保存</button>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.risk-dialog{max-width:620px}
.risk-body{padding:16px}
.risk-summary{margin:0 0 12px;font-weight:700}
.risk-list{max-height:min(52vh,420px);overflow:auto;padding-right:4px}
.risk-group{border:2px solid var(--line);background:var(--paper-2);padding:9px 11px;margin-bottom:10px}
.risk-group:last-child{margin-bottom:0}
.risk-group h3{margin:0 0 6px;font-size:13px;font-family:var(--mono)}
.risk-group ul{margin:0;padding-left:20px}
.risk-group li{font-size:12.5px;line-height:1.65}
.risk-group li::marker{content:'⚠  '}
.risk-actions{margin-top:16px}
</style>
