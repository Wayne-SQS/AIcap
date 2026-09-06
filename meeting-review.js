/* Project-side MVP. This file does not call a model or generate AI suggestions. */
const meetingReviewState = {meetings: [], suggestions: [], selected: '', busy: new Set(), submission: null};
const legacyMeeting = renderMeeting;
const legacyReview = renderReview;
const legacyLoadAll = loadAll;
const legacySubmitAgent = renderSubmitAgent;
const onlineMeetingReview = () => apiMode && currentUser;
const maySubmitMeeting = () => currentUser && ['admin', 'owner', 'member'].includes(currentUser.role);
const mayReviewMeeting = () => currentUser && ['admin', 'owner'].includes(currentUser.role);

loadAll = async function () {
  await legacyLoadAll();
  const [meetings, review] = await Promise.all([api('/api/meetings'), api('/api/suggestions')]);
  meetingReviewState.meetings = meetings;
  meetingReviewState.suggestions = review;
  if (!meetings.some(m => m.id === meetingReviewState.selected)) {
    meetingReviewState.selected = meetings[0]?.id || '';
  }
};

renderMeeting = function () {
  if (!onlineMeetingReview()) { legacyMeeting(); return; }
  const state = meetingReviewState;
  const selected = state.meetings.find(m => m.id === state.selected);
  $('#meeting').innerHTML = `
    <p class="pool-note">项目侧接入 · 会议 Agent 尚未接入。此处手动录入会议和建议，用于验证审核闭环；不会自动分析或写入需求池。</p>
    <form id="meeting-save-form">
      <label class="field">会议标题<input name="title" required maxlength="200"></label>
      <label class="field">会议转写<textarea name="transcript" required maxlength="16000" rows="5"></textarea></label>
      <button class="primary" type="submit" ${maySubmitMeeting() ? '' : 'disabled'}>保存会议</button>
    </form>
    <div class="h-sec">已保存会议</div>
    <select id="meeting-select" aria-label="已保存会议"><option value="">请选择会议</option>${state.meetings.map(m => `<option value="${esc(m.id)}" ${m.id === state.selected ? 'selected' : ''}>${esc(m.title)}</option>`).join('')}</select>
    <button type="button" id="meeting-refresh">刷新会议与建议</button>
    <pre id="saved-transcript" style="white-space:pre-wrap;max-height:240px;overflow:auto">${esc(selected?.transcript || '暂无会议，请先保存。')}</pre>
    <form id="meeting-proposal-form">
      <div class="h-sec">手动录入待审建议 · 仅新增需求池条目</div>
      <label class="field">需求标题<input name="title" required maxlength="200"></label>
      <label class="field">需求描述<textarea name="description" maxlength="10000" rows="3"></textarea></label>
      <label class="field">会议证据（复制原文中的连续片段）<textarea name="evidence" required maxlength="5000" rows="2"></textarea></label>
      <label class="field">优先级（录入人选择，默认 Could）<select name="priority"><option>Could</option><option>Should</option><option>Must</option></select></label>
      <label class="field">建议说明<input name="note" maxlength="2000"></label>
      <button class="primary" type="submit" ${selected && maySubmitMeeting() ? '' : 'disabled'}>提交待审建议</button>
      <p class="small">负责人或管理员批准后才会创建需求；拒绝不改变项目数据。</p>
    </form>`;
  $('#meeting-select').onchange = e => { state.selected = e.target.value; renderMeeting(); };
  $('#meeting-refresh').onclick = async () => {
    try { await loadAll(); renderAll(); } catch (err) { notify('刷新失败：' + err.message); }
  };
  $('#meeting-save-form').onsubmit = async e => {
    e.preventDefault();
    const button = e.target.querySelector('button'); button.disabled = true;
    const values = Object.fromEntries(new FormData(e.target));
    let saved = false;
    try {
      const meeting = await api('/api/meetings', {method: 'POST', body: JSON.stringify(values)});
      saved = true; state.selected = meeting.id;
      state.meetings.unshift(meeting); renderMeeting();
      notify('会议已保存到服务器');
    } catch (err) { notify('保存失败：' + err.message); }
    finally { if (!saved && button.isConnected) button.disabled = false; }
  };
  $('#meeting-proposal-form').onsubmit = async e => {
    e.preventDefault();
    const button = e.target.querySelector('button'); button.disabled = true;
    const form = Object.fromEntries(new FormData(e.target));
    const body = {meeting_id: state.selected, action: 'pool.create', origin: 'manual',
      evidence: form.evidence, note: form.note,
      changes: {title: form.title, description: form.description, priority: form.priority}};
    const fingerprint = JSON.stringify(body);
    // Reuse the same request key after a network failure, even if the result was committed.
    if (state.submission?.fingerprint !== fingerprint) {
      state.submission = {fingerprint, key: crypto.randomUUID()};
    }
    body.client_request_id = state.submission.key;
    let submitted = false;
    try {
      const suggestion = await api('/api/suggestions', {method: 'POST', body: JSON.stringify(body)});
      submitted = true;
      state.suggestions = [suggestion, ...state.suggestions.filter(s => s.id !== suggestion.id)];
      renderMeeting(); renderReview();
      notify('建议已保存，等待负责人审核；尚未创建需求');
    } catch (err) { notify('提交失败：' + err.message); }
    finally { if (!submitted && button.isConnected) button.disabled = false; }
  };
};

renderReview = function () {
  const summary = document.querySelector('.decision summary');
  if (!onlineMeetingReview()) {
    if (summary) summary.textContent = '📋 决策留痕（本机演示）';
    legacyReview(); return;
  }
  if (summary) summary.textContent = '📋 决策留痕（服务器）';
  const statusName = {pending: '待审核', approved: '已采纳', rejected: '已拒绝'};
  $('#sug-list').innerHTML = `<p class="pool-note">真实会议建议 · 本轮仅支持新增需求池条目。${mayReviewMeeting() ? '你可以采纳或拒绝。' : '仅管理员或负责人可以审核。'}</p><button id="review-refresh">刷新建议</button>` +
    (meetingReviewState.suggestions.map(s => `<div class="sug-card" data-meeting-suggestion="${esc(s.id)}">
      <div class="sug-head"><b>${s.origin === 'manual' ? '手动录入' : '外部 Agent（提交方标记）'}</b><span class="sug-status ${esc(s.status)}">${statusName[s.status]}</span><span>${esc(s.id)}</span></div>
      <div class="sug-row"><b>会议</b><span>${esc(s.meeting_title)}</span></div>
      <div class="sug-row"><b>原文证据</b><span>${esc(s.evidence)}</span></div>
      <div class="sug-row"><b>新增需求</b><span>${esc(s.changes.title)} · ${esc(s.changes.priority)}</span></div>
      <div class="sug-row"><b>描述</b><span>${esc(s.changes.description)}</span></div>
      <div class="sug-row"><b>说明</b><span>${esc(s.note)}</span></div>
      <div class="sug-row"><b>执行结果</b><span>${s.pool_item_id ? '已创建需求 ' + esc(s.pool_item_id) + '（后续可能移入看板或删除）' : s.status === 'rejected' ? '未执行，业务数据未改变' : '未执行'}</span></div>
      ${s.status === 'pending' && mayReviewMeeting() ? `<div class="sug-acts"><button data-meeting-review="${esc(s.id)}" data-decision="approve" ${meetingReviewState.busy.has(s.id) ? 'disabled' : ''}>采纳</button><button data-meeting-review="${esc(s.id)}" data-decision="reject" ${meetingReviewState.busy.has(s.id) ? 'disabled' : ''}>拒绝</button></div>` : ''}
    </div>`).join('') || '<div class="empty">暂无待审建议，请到 AI 助手保存会议并录入建议。</div>');
  $('#decision-log').innerHTML = meetingReviewState.suggestions.filter(s => s.reviewed_at).map(s => `<div class="logrow"><time>${esc(s.reviewed_at)} UTC</time><b>${esc(s.id)}</b><span>审核人 #${s.reviewed_by} · ${statusName[s.status]} · ${esc(s.reason || '未填写理由')}${s.pool_item_id ? ' · ' + esc(s.pool_item_id) : ''}</span></div>`).join('') || '<div class="empty">暂无服务器审核记录</div>';
  $('#review-refresh').onclick = async () => {
    try { await loadAll(); renderAll(); } catch (err) { notify('刷新失败：' + err.message); }
  };
};

document.addEventListener('click', async e => {
  const button = e.target.closest('[data-meeting-review]');
  if (!button || !onlineMeetingReview()) return;
  const id = button.dataset.meetingReview;
  if (meetingReviewState.busy.has(id)) return;
  const reason = prompt('审核理由（可留空；取消则不审核）：');
  if (reason === null) return;
  meetingReviewState.busy.add(id); renderReview();
  try {
    const result = await api(`/api/suggestions/${id}/review`, {method: 'POST',
      body: JSON.stringify({decision: button.dataset.decision, reason})});
    meetingReviewState.suggestions = meetingReviewState.suggestions.map(s => s.id === id ? result : s);
    renderReview();
    notify(result.status === 'approved' ? '已采纳并创建需求 ' + result.pool_item_id : '已拒绝，未改变业务数据');
    try { await loadAll(); renderAll(); } catch (err) { notify('审核已保存，但刷新失败：' + err.message); }
  } catch (err) { notify('审核未确认，请刷新核对后重试：' + err.message); }
  finally { meetingReviewState.busy.delete(id); renderReview(); }
});

renderSubmitAgent = function () {
  legacySubmitAgent();
  if (onlineMeetingReview()) {
    const button = $('#submit-gen');
    button.disabled = true;
    button.textContent = '提交智能体为演示，尚未接入服务器审核';
  }
};
