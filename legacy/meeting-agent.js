/* Meeting Agent UI: all model calls and project tools run on the server. */
const agentUI = {config: null, timer: null, generation: 0};
const beforeAgentLoad = loadAll;
loadAll = async function () {
  await beforeAgentLoad();
  agentUI.config = await api('/api/agent/config');
};
const beforeAgentMeeting = renderMeeting;
renderMeeting = function () {
  clearTimeout(agentUI.timer);
  const generation = ++agentUI.generation;
  beforeAgentMeeting();
  if (!onlineMeetingReview()) return;
  const id = meetingReviewState.selected;
  const panel = document.createElement('section');
  panel.id = 'meeting-agent-panel';
  panel.dataset.meetingId = id;
  $('#meeting-proposal-form').before(panel);
  renderAgentPanel(null);
  if (id) refreshAgentRun(id, generation).catch(err => showAgentError(err.message, id));
};
function showAgentError(message, id) {
  const box = $('#agent-message');
  if (box && $('#meeting-agent-panel')?.dataset.meetingId === id) box.textContent = message;
}
function renderAgentPanel(run) {
  const panel = $('#meeting-agent-panel');
  if (!panel) return;
  const configured = agentUI.config?.configured && agentUI.config?.worker_enabled;
  const active = run && ['queued', 'running'].includes(run.status);
  const labels = {queued: '排队中', running: '正在分析', awaiting_review: '分析完成 · 建议已送审', completed: '分析完成 · 没有新增建议', failed: '分析失败'};
  const result = run?.result;
  function facts(items) {
    return (items || []).map(item => `<li>${esc(item.text || item.description)}${Object.hasOwn(item, 'owner_mention') ? ` · 负责人：${esc(item.owner_mention || '待确认')} · 日期：${esc(item.deadline_text || '待确认')}` : ''}<br><span class="small">依据 ${esc(item.evidence.segment_id)}：${esc(item.evidence.quote)}</span></li>`).join('') || '<li>无</li>';
  }
  panel.innerHTML = `<div class="h-sec">会议 Agent</div>
    <p class="small">${configured ? '将会议转写和查询到的项目数据发送到已配置模型分析；生成的建议仍需人工审核。' : '服务端模型密钥尚未配置或工作线程未启用；可继续手动录入建议。'}</p>
    <p id="agent-message">${run ? `${labels[run.status]} · 尝试 ${run.attempt}` : '选择已保存会议后可开始分析'}</p>
    ${run?.error_message ? `<p role="alert">${esc(run.error_message)}</p>` : ''}
    <button id="agent-start" ${!configured || !maySubmitMeeting() || !panel.dataset.meetingId || !!run ? 'disabled' : ''}>分析会议</button>
    ${run?.status === 'failed' ? `<button id="agent-retry" ${!configured || !maySubmitMeeting() ? 'disabled' : ''}>重试分析</button>` : ''}
    ${run ? `<button id="agent-refresh">刷新结果</button>` : ''}
    ${result && maySubmitMeeting() ? '<button id="agent-reanalyze">复制会议并重新分析</button>' : ''}
    ${result ? `<div class="sum-grid"><div class="sum-card"><h4>摘要</h4><p>${esc(result.summary)}</p></div>
      <div class="sum-card"><h4>决议</h4><ul>${facts(result.decisions)}</ul></div>
      <div class="sum-card"><h4>行动项 · 待人工跟进</h4><ul>${facts(result.action_items)}</ul></div>
      <div class="sum-card"><h4>协调事项 · 待负责人确认</h4><ul>${facts(result.coordination_items)}</ul></div>
      <div class="sum-card"><h4>状态与验收约束</h4><ul>${facts(result.status_constraints)}</ul></div>
      <div class="sum-card"><h4>其他会议事实</h4><ul>${facts(result.source_notes)}</ul></div>
      <div class="sum-card"><h4>风险 / 待确认推断</h4><ul>${facts(result.risks)}</ul></div></div>
      <p class="small">以上行动项和协调事项已记录在会议分析中，尚未创建或修改项目任务；负责人需确认后在项目侧跟进。</p><h4>未决问题与数据限制</h4><ul>${[...result.unresolved_questions, ...result.limitations].map(t => `<li>${esc(t)}</li>`).join('')}</ul>
      <p>已保存 ${(result.suggestion_ids || []).length} 条建议；审核和执行状态请查看审核中心。</p>
      ${(result.skipped_proposals || []).map(p => `<p>未重复创建：${esc(p.title)} · ${esc(p.reason)}</p>`).join('')}
      <button id="agent-go-review">查看审核中心</button>` : ''}
    ${run?.events ? `<details><summary>运行记录 · ${esc(run.model)} · ${esc(run.prompt_version)}</summary>${run.events.map(e => `<div class="logrow"><b>第 ${e.attempt} 次 · ${esc(e.kind)}</b><pre style="white-space:pre-wrap;max-height:200px;overflow:auto">${esc(JSON.stringify(e.detail, null, 2))}</pre></div>`).join('')}</details>` : ''}`;
  const id = panel.dataset.meetingId;
  $('#agent-start').onclick = () => startAgentRun(id);
  if ($('#agent-retry')) $('#agent-retry').onclick = () => startAgentRun(id, run.id);
  if ($('#agent-refresh')) $('#agent-refresh').onclick = () => refreshAgentRun(id, agentUI.generation).catch(e => showAgentError(e.message, id));
  if ($('#agent-reanalyze')) $('#agent-reanalyze').onclick = async () => {
    const button = $('#agent-reanalyze'); button.disabled = true;
    try {
      const original = meetingReviewState.meetings.find(m => m.id === id);
      const meeting = await api('/api/meetings', {method:'POST', body:JSON.stringify({title:original.title.slice(0,190)+' · 重新分析', transcript:original.transcript})});
      meetingReviewState.meetings.unshift(meeting); meetingReviewState.selected = meeting.id;
      renderMeeting(); await startAgentRun(meeting.id);
    } catch (err) { showAgentError('重新分析失败：'+err.message, id); if (button.isConnected) button.disabled = false; }
  };
  if ($('#agent-go-review')) $('#agent-go-review').onclick = () => go('review');
  if (active) {
    clearTimeout(agentUI.timer);
    const generation = agentUI.generation;
    agentUI.timer = setTimeout(() => refreshAgentRun(id, generation).catch(e => showAgentError('刷新失败，请点击刷新结果：' + e.message, id)), 1500);
  }
}
async function refreshAgentRun(id, generation) {
  if (!onlineMeetingReview() || curView !== 'ai' || generation !== agentUI.generation) return;
  const rows = await api(`/api/meetings/${id}/runs`);
  const run = rows[0] ? await api(`/api/agent-runs/${rows[0].id}`) : null;
  if (!onlineMeetingReview() || generation !== agentUI.generation || meetingReviewState.selected !== id) return;
  renderAgentPanel(run);
  if (run && ['awaiting_review', 'completed'].includes(run.status)) {
    const suggestions = await api('/api/suggestions');
    if (!onlineMeetingReview() || generation !== agentUI.generation) return;
    meetingReviewState.suggestions = suggestions;
    renderReview();
  }
}
async function startAgentRun(id, retryId) {
  const generation = agentUI.generation;
  const button = $(retryId ? '#agent-retry' : '#agent-start');
  if (button) button.disabled = true;
  try {
    const path = retryId ? `/api/agent-runs/${retryId}/retry` : `/api/meetings/${id}/runs`;
    const run = await api(path, {method: 'POST'});
    if (onlineMeetingReview() && generation === agentUI.generation && meetingReviewState.selected === id) {
      renderAgentPanel(run);
      await refreshAgentRun(id, generation);
    }
  } catch (err) {
    showAgentError('未能启动分析：' + err.message, id);
    if (button?.isConnected) button.disabled = false;
  }
}
