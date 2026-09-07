/* Human-controlled revisions and scheduling. All server writes remain validated. */
function workflowDialog(title, content, submit) {
  const dialog = document.createElement('dialog');
  dialog.style.cssText = 'width:min(620px,92vw);max-height:90vh;overflow:auto';
  dialog.innerHTML = `<form><h3>${esc(title)}</h3>${content}<p role="alert"></p><div class="sug-acts"><button type="button" data-cancel>取消</button><button type="submit" class="primary">确认</button></div></form>`;
  document.body.append(dialog);
  dialog.querySelector('[data-cancel]').onclick = () => dialog.close();
  dialog.onclose = () => dialog.remove();
  dialog.querySelector('form').onsubmit = async e => {
    e.preventDefault();
    const button = dialog.querySelector('[type=submit]'); button.disabled = true;
    try { await submit(Object.fromEntries(new FormData(e.target))); dialog.close(); }
    catch (err) { dialog.querySelector('[role=alert]').textContent = err.message; }
    finally { button.disabled = false; }
  };
  dialog.showModal();
  return dialog;
}

document.addEventListener('click', e => {
  const link = e.target.closest('[data-open-meeting]');
  if (link) { meetingReviewState.selected = link.dataset.openMeeting; go('ai'); renderMeeting(); }
  const edit = e.target.closest('[data-edit-suggestion]');
  if (!edit || !onlineMeetingReview() || !mayReviewMeeting()) return;
  const s = meetingReviewState.suggestions.find(s => s.id === edit.dataset.editSuggestion);
  if (!s || s.status !== 'pending' || meetingReviewState.busy.has(s.id)) return;
  workflowDialog('修改后采纳', `
    <p class="small">原始建议和会议证据会保留；以下内容经你确认后写入需求池。</p>
    <blockquote>${esc(s.evidence)}</blockquote>
    <label class="field">需求标题<input name="title" required maxlength="200" value="${esc(s.changes.title)}"></label>
    <label class="field">需求描述<textarea name="description" maxlength="10000" rows="5">${esc(s.changes.description)}</textarea></label>
    <label class="field">优先级<select name="priority">${['Could','Should','Must'].map(p => `<option ${p === s.changes.priority ? 'selected' : ''}>${p}</option>`).join('')}</select></label>
    <label class="field">修改与审核理由<textarea name="reason" maxlength="1000" rows="2"></textarea></label>`, async values => {
      const {reason, ...changes} = values;
      const result = await api(`/api/suggestions/${s.id}/review`, {method:'POST', body:JSON.stringify({decision:'modify_and_approve', reason, changes})});
      meetingReviewState.suggestions = meetingReviewState.suggestions.map(row => row.id === s.id ? result : row);
      renderReview();
      notify('修改已采纳，原始建议已保留；创建需求 ' + result.pool_item_id);
      try { await loadAll(); renderAll(); } catch (err) { notify('审核已保存，刷新失败：' + err.message); }
    });
});

promotePool = async function (id) {
  const p = pool.find(x => x.id === id);
  if (!p) return;
  let members;
  try { members = apiMode ? await api('/api/auth/users') : [1,2,3,4].map(id => ({id, display_name:'成员 '+id})); }
  catch (err) { notify('无法加载负责人：' + err.message); return; }
  workflowDialog('安排需求到看板', `
    <p>${esc(p.title)}</p>
    <label class="field">目标 Sprint<select name="sprint" required><option value="">请选择 Sprint</option>${[1,2,3].map(n => `<option value="${n}">Sprint ${n}</option>`).join('')}</select></label>
    <label class="field">负责人<select name="owner_id"><option value="">未分配（待确认）</option>${members.map(m => `<option value="${m.id}">${esc(m.display_name)}</option>`).join('')}</select></label>
    <label class="field">活动阶段<select name="activity">${activities.map((a,i) => `<option value="${i+1}" ${i===1?'selected':''}>A${i+1} · ${esc(a)}</option>`).join('')}</select></label>
    <p class="small">尚未确定 Sprint 时可取消，需求继续保留在需求池。移入后初始状态为待办。</p>`, async values => {
      const body = {sprint:Number(values.sprint), owner_id:values.owner_id === '' ? null : Number(values.owner_id), activity:Number(values.activity)};
      if (apiMode) {
        await api(`/api/pool/${id}/promote`, {method:'POST', body:JSON.stringify(body)});
        try { await loadAll(); renderAll(); } catch (err) { notify('已移入看板，刷新失败：'+err.message); }
      } else {
        const newId='M'+String(Math.max(20,...stories.map(x=>+x.id.slice(1)||0))+1).padStart(2,'0');
        stories.push({id:newId, activity:body.activity, sprint:body.sprint, title:p.title, description:p.desc, priority:p.priority, acceptance:'来源：'+p.source+'（待补充验收条件）', owner:body.owner_id == null ? null : body.owner_id-1, status:0});
        pool=pool.filter(x=>x.id!==id);
        persistPool();persist();renderAll();
      }
      notify(`已移入 Sprint ${body.sprint} · ${body.owner_id == null ? '负责人待确认' : '已选择负责人'}`);
    });
};
