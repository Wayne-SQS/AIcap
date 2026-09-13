<script setup>
import { reactive, ref, watch } from 'vue'
import { membersApi } from '@/api/members'
import { useToast } from '@/composables/useToast'

/* 成员画像编辑:技术栈 / 工作能力 / 熟悉的开发流程领域,三个维度均可用「名称 + 熟练度(1..5)」维护 */
const props = defineProps({
  profile: { type: Object, required: true }
})
const emit = defineEmits(['close', 'saved'])
const { notify } = useToast()

const LEVELS = [1, 2, 3, 4, 5]
const LEVEL_TXT = { 1: '了解', 2: '基本', 3: '熟练', 4: '擅长', 5: '精通' }

const DIMS = [
  { key: 'tech_stack', label: '技术栈', hint: '如 Java 23 / Spring Boot、MySQL 8、Playwright、Prompt 工程' },
  { key: 'capabilities', label: '工作能力', hint: '如 需求拆解、接口设计、缺陷定位、风险预警' },
  { key: 'process_domains', label: '熟悉的开发流程领域', hint: '如 需求与规划、设计与架构、编码与集成、测试与质量' }
]

const form = reactive({
  title: props.profile.title || '',
  summary: props.profile.summary || '',
  years_experience: props.profile.years_experience ?? 0,
  tech_stack: clone(props.profile.tech_stack),
  capabilities: clone(props.profile.capabilities),
  process_domains: clone(props.profile.process_domains)
})
const saving = ref(false)
const error = ref('')

function clone(items) {
  return (items || []).map(i => ({ name: i.name, level: i.level }))
}
function addRow(key) {
  if (form[key].length >= 20) { error.value = '每个维度最多 20 项'; return }
  form[key].push({ name: '', level: 3 })
}
function removeRow(key, index) {
  form[key].splice(index, 1)
}

watch(() => props.profile, p => {
  form.title = p.title || ''
  form.summary = p.summary || ''
  form.years_experience = p.years_experience ?? 0
  form.tech_stack = clone(p.tech_stack)
  form.capabilities = clone(p.capabilities)
  form.process_domains = clone(p.process_domains)
}, { deep: true })

async function save() {
  error.value = ''
  for (const dim of DIMS) {
    const rows = form[dim.key]
    if (rows.some(r => !String(r.name || '').trim())) { error.value = `${dim.label}存在空名称，请填写或删除该行`; return }
    const names = rows.map(r => String(r.name).trim().toLowerCase())
    if (new Set(names).size !== names.length) { error.value = `${dim.label}内名称不能重复`; return }
  }
  saving.value = true
  try {
    const payload = {
      title: form.title.trim(),
      summary: form.summary.trim(),
      years_experience: Number(form.years_experience) || 0,
      tech_stack: normalize(form.tech_stack),
      capabilities: normalize(form.capabilities),
      process_domains: normalize(form.process_domains)
    }
    const saved = await membersApi.saveProfile(props.profile.user_id, payload)
    notify(`已保存 ${saved.display_name} 的画像`)
    emit('saved', saved)
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}
function normalize(rows) {
  return rows.map(r => ({ name: String(r.name).trim(), level: Number(r.level) || 3 }))
}
</script>

<template>
  <div class="mask" @click.self="emit('close')">
    <div class="dialog" id="profile-dialog">
      <div class="dhead">
        <h3>{{ profile.display_name }} · 成员画像</h3>
        <span class="badge">{{ profile.role }}</span>
        <button class="x" @click="emit('close')">×</button>
      </div>

      <div class="dbody">
        <div class="grid2">
          <label class="field">岗位 / 画像标题
            <input v-model="form.title" maxlength="50" placeholder="如 技术负责人 · 架构与后端">
          </label>
          <label class="field">项目经验年限
            <input v-model.number="form.years_experience" type="number" min="0" max="50">
          </label>
        </div>
        <label class="field">画像摘要
          <textarea v-model="form.summary" maxlength="500" rows="2" placeholder="一句话说明该成员在团队中负责什么、擅长什么"></textarea>
        </label>

        <div v-for="dim in DIMS" :key="dim.key" class="dim">
          <div class="dim-head">
            <b>{{ dim.label }}</b>
            <span class="small">{{ dim.hint }}</span>
            <button class="add" @click="addRow(dim.key)">＋ 添加</button>
          </div>
          <p v-if="!form[dim.key].length" class="small">暂无条目，可点「添加」补充。</p>
          <div v-for="(row, i) in form[dim.key]" :key="dim.key + i" class="skill-row">
            <input v-model="row.name" maxlength="50" :placeholder="dim.label + '名称'">
            <select v-model.number="row.level">
              <option v-for="lv in LEVELS" :key="lv" :value="lv">{{ lv }} · {{ LEVEL_TXT[lv] }}</option>
            </select>
            <button class="del" @click="removeRow(dim.key, i)">删除</button>
          </div>
        </div>

        <p v-if="error" class="err" role="alert">{{ error }}</p>
      </div>

      <div class="dfoot">
        <button @click="emit('close')">取消</button>
        <button class="primary" id="profile-save" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存画像' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; z-index: 60; padding: 20px; }
.dialog { background: var(--panel, #fff); color: var(--fg, #222); border-radius: 12px; width: min(760px, 96vw); max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,.35); }
.dhead { display: flex; align-items: center; gap: 10px; padding: 14px 16px; border-bottom: 1px solid var(--line, #e3e3e3); }
.dhead h3 { margin: 0; font-size: 16px; }
.dhead .x { margin-left: auto; border: none; background: transparent; font-size: 20px; cursor: pointer; }
.badge { font-size: 12px; padding: 2px 8px; border-radius: 999px; background: rgba(0,0,0,.08); }
.dbody { padding: 14px 16px; overflow: auto; }
.grid2 { display: grid; grid-template-columns: 1fr 140px; gap: 10px; }
.field { display: flex; flex-direction: column; gap: 4px; font-size: 13px; margin-bottom: 10px; }
.field input, .field textarea { padding: 6px 8px; border: 1px solid var(--line, #ccc); border-radius: 6px; font: inherit; }
.dim { margin: 12px 0; padding-top: 8px; border-top: 1px dashed var(--line, #e3e3e3); }
.dim-head { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.dim-head .add { margin-left: auto; }
.skill-row { display: grid; grid-template-columns: 1fr 130px 70px; gap: 8px; margin-top: 6px; }
.skill-row input, .skill-row select { padding: 5px 8px; border: 1px solid var(--line, #ccc); border-radius: 6px; font: inherit; }
.err { color: #b3261e; }
.dfoot { display: flex; justify-content: flex-end; gap: 10px; padding: 12px 16px; border-top: 1px solid var(--line, #e3e3e3); }
</style>
