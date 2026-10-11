<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { membersApi } from '@/api/members'
import { useSessionStore } from '@/stores/session'
import { useToast } from '@/composables/useToast'
import { READONLY_TITLE } from '@/composables/usePermissionGuard'
import MemberProfileDialog from '@/components/members/MemberProfileDialog.vue'
import { ROLE_TXT } from '@/data/seed'

const session = useSessionStore()
const { notify } = useToast()
const profiles = ref([])
const error = ref('')
const editing = ref(null)
const online = computed(() => session.apiMode && !!session.currentUser)
async function loadProfiles() {
  if (!online.value) return
  try {
    profiles.value = await membersApi.profiles()
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}
function canEdit(profile) {
  const user = session.currentUser
  if (!user) return false
  return user.role === 'admin' || user.role === 'owner' || (user.role === 'member' && user.id === profile.user_id)
}
function onSaved(saved) {
  profiles.value = profiles.value.map(profile => profile.user_id === saved.user_id ? saved : profile)
  editing.value = null
  notify('画像已更新')
}
function levelWidth(item) {
  return `${Math.max(1, Math.min(5, Number(item?.level) || 0)) * 20}%`
}
function levelText(item) {
  const level = Math.max(1, Math.min(5, Number(item?.level) || 0))
  return ['基础', '入门', '熟练', '较强', '精通'][level - 1]
}
watch(online, value => { if (value) loadProfiles() }, { immediate: true })
onMounted(loadProfiles)
</script>

<template>
  <section class="view profile-view" id="view-profiles">
    <div class="hero">
      <div>
        <div class="eyebrow">TEAM PROFILE / 成员画像</div>
        <h1>成员画像</h1>
        <p>团队成员技能、能力与开发经验画像 · 与成员任务图共享同一成员数据。</p>
      </div>
    </div>

    <div v-if="!online" class="profile-note">离线演示模式：成员画像需连接后端读取（在线登录后自动加载）。</div>
    <div v-else-if="error" class="profile-note error" role="alert">画像加载失败：{{ error }}</div>
    <div v-else class="profile-grid" id="member-profiles">
      <article v-for="profile in profiles" :key="profile.user_id" class="profile-card">
        <header class="profile-card-head">
          <div>
            <div class="profile-name">{{ profile.display_name }}</div>
            <div class="profile-role">{{ ROLE_TXT[profile.role] || profile.role }}<span v-if="profile.title"> · {{ profile.title }}</span></div>
          </div>
          <div class="profile-head-actions">
            <div class="profile-years"><strong>{{ profile.years_experience || 0 }}</strong><span>年经验</span></div>
            <button v-if="canEdit(profile) || session.isViewer" class="profile-edit" :disabled="session.isViewer" :title="session.isViewer ? READONLY_TITLE : ''" @click="editing = profile">编辑画像</button>
          </div>
        </header>
        <p v-if="profile.summary" class="profile-summary">{{ profile.summary }}</p>
        <div class="profile-columns">
          <section class="profile-dimension profile-tech">
            <h2>技术栈 <span>会什么</span></h2>
            <div v-if="profile.tech_stack?.length" class="profile-tech-list">
              <span v-for="item in profile.tech_stack" :key="item.name" class="profile-tech-tag" :title="`${item.name} · 熟练度 ${item.level}/5`">
                {{ item.name }}<i :class="`tech-level level-${Math.max(1, Math.min(5, Number(item.level) || 0))}`"></i>
              </span>
            </div>
            <span v-else class="small">暂无记录</span>
          </section>
          <section class="profile-dimension profile-capabilities">
            <h2>工作能力 <span>熟练程度</span></h2>
            <div v-if="profile.capabilities?.length" class="profile-items">
              <div v-for="item in profile.capabilities" :key="item.name" class="profile-capability" :title="`${item.name} · 熟练度 ${item.level}/5`">
                <div class="profile-capability-label"><span>{{ item.name }}</span><b>{{ levelText(item) }}</b></div>
                <i class="profile-level-bar"><b :style="{ width: levelWidth(item) }"></b></i>
              </div>
            </div>
            <span v-else class="small">暂无记录</span>
          </section>
          <section class="profile-dimension profile-domains">
            <h2>熟悉领域 <span>常接触什么</span></h2>
            <div v-if="profile.process_domains?.length" class="profile-domain-list">
              <span v-for="item in profile.process_domains" :key="item.name" class="profile-domain-item" :title="`${item.name} · 熟悉度 ${item.level}/5`">
                <b>+</b>{{ item.name }}
              </span>
            </div>
            <span v-else class="small">暂无记录</span>
          </section>
        </div>
      </article>
    </div>
    <MemberProfileDialog v-if="editing" :profile="editing" @close="editing = null" @saved="onSaved" />
  </section>
</template>

<style scoped>
.profile-note{border:2px dashed var(--line);padding:10px 14px;color:var(--muted);background:rgba(255,255,255,.35)}
.profile-note.error{color:var(--ink);border-color:var(--red);background:rgba(226,161,143,.2)}
.profile-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:14px}
.profile-card{border:2px solid var(--ink);background:var(--paper);box-shadow:var(--shadow);padding:12px;position:relative}
.profile-card-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;border-bottom:2px solid var(--line);padding-bottom:8px}
.profile-name{font-size:17px;font-weight:900}.profile-role{font:12px var(--mono);color:var(--muted);margin-top:2px}
.profile-head-actions{display:flex;align-items:flex-start;gap:10px}.profile-years{display:flex;align-items:baseline;gap:4px;font-family:var(--mono);color:var(--muted)}
.profile-years strong{font-size:24px;color:var(--ink)}.profile-years span{font-size:11px}
.profile-summary{font-size:12.5px;color:var(--muted);line-height:1.45;margin:8px 0 10px;padding-left:8px;border-left:3px solid var(--green-2)}
.profile-columns{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:10px 14px}
.profile-dimension{min-width:0}.profile-dimension:last-child{grid-column:1/-1}
.profile-dimension h2{display:flex;align-items:baseline;gap:7px;font:700 11px var(--mono);color:var(--ink);margin:0 0 6px;padding-bottom:4px;border-bottom:1px dashed var(--line);text-transform:uppercase;letter-spacing:1px}
.profile-dimension h2 span{font:400 10px var(--mono);color:var(--muted);text-transform:none;letter-spacing:0}
.profile-tech-list{display:flex;flex-wrap:wrap;gap:5px}.profile-tech-tag{display:inline-flex;align-items:center;gap:5px;border:1px solid var(--ink);background:var(--paper-2);padding:3px 6px;font:11px var(--mono)}
.tech-level{display:inline-block;width:18px;height:5px;border:1px solid var(--ink);background:var(--green-2)}
.tech-level.level-1{background:linear-gradient(to right,var(--green-2) 0 20%,var(--paper) 20% 100%)}.tech-level.level-2{background:linear-gradient(to right,var(--green-2) 0 40%,var(--paper) 40% 100%)}.tech-level.level-3{background:linear-gradient(to right,var(--green-2) 0 60%,var(--paper) 60% 100%)}.tech-level.level-4{background:linear-gradient(to right,var(--green-2) 0 80%,var(--paper) 80% 100%)}
.profile-items{display:flex;flex-direction:column;gap:6px}.profile-capability-label{display:flex;justify-content:space-between;gap:8px;font-size:12px}.profile-capability-label span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.profile-capability-label b{flex:0 0 auto;color:var(--muted);font:10px var(--mono)}
.profile-level-bar{height:8px;margin-top:3px;border:1px solid var(--ink);background:var(--paper-2);display:block}.profile-level-bar b{display:block;height:100%;background:var(--green-2)}
.profile-domain-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:4px 12px}.profile-domain-item{min-width:0;font-size:11.5px;line-height:1.35;padding:3px 0;border-bottom:1px dotted var(--line);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.profile-domain-item b{margin-right:5px;color:var(--green-2);font-family:var(--mono)}
.profile-edit{font-size:11px;padding:4px 8px;white-space:nowrap}
@media (max-width:700px){.profile-grid{grid-template-columns:1fr}.profile-columns{grid-template-columns:1fr}.profile-dimension:last-child{grid-column:auto}}
</style>
