<script setup>
import { ref, reactive, nextTick, computed } from 'vue'
import { useProjectStore } from '@/stores/project'
import { useSessionStore } from '@/stores/session'
import { useToast } from '@/composables/useToast'
import { usePermissionGuard } from '@/composables/usePermissionGuard'
import { poolApi } from '@/api/pool'
import { authApi } from '@/api/auth'
import { activities } from '@/constants'

/* 需求池:结构对齐旧版 L634-654,逻辑对齐 pool 保存(L1441-1455)/dropPool(L1456-1460)/
   promotePool 工作流弹窗(meeting-improvements.js L43-67):在线走 API、离线改本地 */
const project = useProjectStore()
const session = useSessionStore()
const { notify } = useToast()
const { guard } = usePermissionGuard()

const showForm = ref(false)
const form = reactive({ title: '', desc: '', pri: 'Could' })

/* 移入看板工作流弹窗(对齐 workflowDialog + 安排需求表单) */
const promoteEl = ref(null)
const promoteForm = reactive({ id: '', title: '', sprint: '', owner_id: '', activity: 2 })
const members = ref([])
const promoting = ref(false)

function startAdd() {
  if (guard('新增需求')) return
  showForm.value = true
}
function cancelForm() {
  showForm.value = false
  form.title = ''
  form.desc = ''
}

async function save() {
  if (guard('保存需求')) return
  const title = form.title.trim(), desc = form.desc.trim()
  if (!title) { notify('请填写需求标题'); return }
  if (session.apiMode) {
    try {
      await poolApi.create({ title, description: desc || '（待补充描述）', source: '手动新增', priority: form.pri })
      cancelForm()
      await project.loadAll()
      notify('已保存到需求池（服务器）')
    } catch (err) { notify(err.message) }
  } else {
    const d = new Date()
    project.pool.unshift({
      id: 'R' + String(Math.max(4, ...project.pool.map(x => +x.id.slice(1) || 0)) + 1).padStart(2, '0'),
      title, desc: desc || '（待补充描述）', source: '手动新增 · 成员1',
      created: String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'),
      priority: form.pri
    })
    project.persistPool()
    cancelForm()
    notify('已保存到需求池')
  }
}

async function dropPool(id) {
  if (guard('移除需求')) return
  if (session.apiMode) {
    try { await poolApi.remove(id); await project.loadAll(); notify(id + ' 已从需求池移除') }
    catch (err) { notify(err.message) }
    return
  }
  project.pool = project.pool.filter(x => x.id !== id)
  project.persistPool()
  notify(id + ' 已从需求池移除')
}

async function openPromote(id) {
  if (guard('移入看板')) return
  const p = project.pool.find(x => x.id === id)
  if (!p) return
  promoteForm.id = id
  promoteForm.title = p.title
  promoteForm.sprint = ''
  promoteForm.owner_id = ''
  promoteForm.activity = 2
  try {
    members.value = session.apiMode ? await authApi.users() : [1, 2, 3, 4].map(i => ({ id: i, display_name: '成员 ' + i }))
  } catch (err) { notify('无法加载负责人：' + err.message); return }
  nextTick(() => { promoteEl.value?.showModal() })
}

function closePromote() { promoteEl.value?.close() }

function onPromoteClick(e) {
  if (e.target !== promoteEl.value) return
  const r = e.target.getBoundingClientRect()
  if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) e.target.close()
}

async function submitPromote() {
  const body = {
    sprint: Number(promoteForm.sprint),
    owner_id: promoteForm.owner_id === '' ? null : Number(promoteForm.owner_id),
    activity: Number(promoteForm.activity)
  }
  promoting.value = true
  try {
    if (session.apiMode) {
      await poolApi.promote(promoteForm.id, body)
      try { await project.loadAll() } catch (err) { notify('已移入看板，刷新失败：' + err.message) }
    } else {
      const newId = 'M' + String(Math.max(20, ...project.stories.map(x => +x.id.slice(1) || 0)) + 1).padStart(2, '0')
      const p = project.pool.find(x => x.id === promoteForm.id)
      project.stories.push({
        id: newId, activity: body.activity, sprint: body.sprint, title: p.title, description: p.desc,
        priority: p.priority, acceptance: '来源：' + p.source + '（待补充验收条件）',
        owner: body.owner_id == null ? null : body.owner_id - 1, status: 0
      })
      project.pool = project.pool.filter(x => x.id !== promoteForm.id)
      project.persistPool()
      project.persist()
    }
    closePromote()
    notify(`已移入 Sprint ${body.sprint} · ${body.owner_id == null ? '负责人待确认' : '已选择负责人'}`)
  } catch (err) { notify(err.message) }
  finally { promoting.value = false }
}
</script>

<template>
  <section class="view" id="view-pool">
    <div class="hero">
      <div>
        <div class="eyebrow">REQUIREMENT POOL / 需求池</div>
        <h1>需求池</h1>
        <p>会议中临时出现、尚未确定 Sprint 或负责人的需求先放这里，不丢失、不提前承诺。</p>
      </div>
      <button class="primary" id="pool-add" @click="startAdd">＋ 新增需求</button>
    </div>
    <p class="pool-note">需求池条目允许 Sprint / 负责人为空；细化后可「移入看板」成为正式用户故事，并保留来源与历史。需求池不计入已承诺 Sprint 完成率。</p>
    <div class="pool-form" id="pool-form" v-show="showForm">
      <input id="pool-title" v-model="form.title" placeholder="需求标题（如：支持第三方账号登录）">
      <textarea id="pool-desc" v-model="form.desc" placeholder="描述 / 来源（如：会议 2026-09-06 · 成员2 提出）"></textarea>
      <div class="row">
        <select id="pool-pri" v-model="form.pri"><option>Must</option><option>Should</option><option>Could</option></select>
        <button class="primary" id="pool-save" @click="save">保存到需求池</button>
        <button id="pool-cancel" @click="cancelForm">取消</button>
      </div>
    </div>
    <div class="pool-list" id="pool-list">
      <div v-for="p in project.pool" :key="p.id" class="pool-item">
        <span class="picon">◈</span>
        <div class="pbody">
          <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">
            <h3>{{ p.title }}</h3><span class="tag" :class="p.priority">{{ p.priority }}</span><span class="mono small">{{ p.id }}</span>
          </div>
          <p class="pdesc">{{ p.desc }}</p>
          <div class="pmeta"><span>来源 · {{ p.source }}</span><span>创建 · {{ p.created }}</span></div>
        </div>
        <div class="pacts">
          <button class="primary" :data-promote="p.id" @click="openPromote(p.id)">移入看板</button>
          <button class="danger" :data-drop="p.id" @click="dropPool(p.id)">移除</button>
        </div>
      </div>
      <div v-if="!project.pool.length" class="empty">需求池为空</div>
    </div>

    <dialog id="promote" ref="promoteEl" @click="onPromoteClick">
      <form @submit.prevent="submitPromote">
        <header><h2>安排需求到看板</h2><button type="button" aria-label="关闭" @click="closePromote">×</button></header>
        <div class="formbody">
          <p>{{ promoteForm.title }}</p>
          <label class="field">目标 Sprint
            <select name="sprint" v-model="promoteForm.sprint" required>
              <option value="" disabled>请选择 Sprint</option>
              <option v-for="n in 3" :key="n" :value="String(n)">Sprint {{ n }}</option>
            </select>
          </label>
          <label class="field">负责人
            <select name="owner_id" v-model="promoteForm.owner_id">
              <option value="">未分配（待确认）</option>
              <option v-for="m in members" :key="m.id" :value="String(m.id)">{{ m.display_name }}</option>
            </select>
          </label>
          <label class="field">活动阶段
            <select name="activity" v-model.number="promoteForm.activity">
              <option v-for="(a, i) in activities" :key="a" :value="i + 1">A{{ i + 1 }} · {{ a }}</option>
            </select>
          </label>
          <p class="small">尚未确定 Sprint 时可取消，需求继续保留在需求池。移入后初始状态为待办。</p>
          <div class="sug-acts">
            <button type="button" @click="closePromote">取消</button>
            <button type="submit" class="primary" :disabled="promoting">确认</button>
          </div>
        </div>
      </form>
    </dialog>
  </section>
</template>
