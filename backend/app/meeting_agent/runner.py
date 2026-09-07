"""Bounded tool loop. Only validated drafts leave this module; never executes writes."""
import json
import re
import time
from pydantic import ValidationError
from .. import config
from .prompts import SYSTEM_PROMPT
from .schemas import AgentError, Analysis
from .tools import TOOLS, execute_tool


def segments_for(transcript):
    # Chunk long paragraphs as well; no audio timestamp/speaker is fabricated.
    lines = [line.strip() for line in re.split(r'(?<=[。！？!?])|[\r\n]+', transcript) if line.strip()]
    chunks = [line[i:i + 2000] for line in lines for i in range(0, len(line), 2000)]
    return [{'segment_id': f'seg-{i + 1}', 'text': text} for i, text in enumerate(chunks)]


def validate_analysis(content, segments):
    try:
        analysis = Analysis.model_validate_json(content or '')
    except (ValidationError, ValueError):
        raise AgentError('invalid_output', '模型结果不符合结构约定，未创建建议')
    by_id = {s['segment_id']: s['text'] for s in segments}
    for item in [*analysis.decisions, *analysis.action_items, *analysis.coordination_items, *analysis.status_constraints, *analysis.source_notes, *analysis.risks, *analysis.proposals]:
        evidence = item.evidence
        if evidence.segment_id not in by_id or evidence.quote not in by_id[evidence.segment_id]:
            raise AgentError('invalid_evidence', '模型引用无法在原文片段中核对，未创建建议')
    for item in [*analysis.action_items, *analysis.coordination_items]:
        for mention in (item.owner_mention, item.deadline_text):
            if mention is not None and (not mention or mention not in item.evidence.quote):
                raise AgentError('invented_assignment', '负责人或日期缺少原文依据，未创建建议')
    items = [*analysis.decisions, *analysis.action_items, *analysis.coordination_items,
             *analysis.status_constraints, *analysis.source_notes, *analysis.risks, *analysis.proposals]
    covered = {item.evidence.segment_id for item in items}
    missing = [sid for sid in by_id if sid not in covered]
    if missing:
        raise AgentError('incomplete_analysis', '会议片段未被分析：' + ', '.join(missing) + '；未创建建议')
    return analysis


def analyze(transcript, user_id, session_factory, model, emit, ensure_active):
    start = time.monotonic()
    segments = segments_for(transcript)
    def active():
        ensure_active()
        if time.monotonic() - start > config.AICAP_AGENT_MAX_SECONDS:
            raise AgentError('run_timeout', '分析超过时限，请缩短会议文本后重试')
    context = execute_tool(session_factory, user_id, 'project_summary', {})
    emit('context', context)
    messages = [{'role': 'system', 'content': SYSTEM_PROMPT},
                {'role': 'user', 'content': json.dumps({'segments': segments, 'project': context}, ensure_ascii=False)}]
    called = set()
    call_ids = set()
    for step in range(config.AICAP_AGENT_MAX_STEPS):
        active()
        response, usage = model.complete(messages, TOOLS)
        active()
        emit('model_step', {'step': step + 1, 'usage': usage,
                            'tool_names': [c['function']['name'] for c in response.get('tool_calls', [])]})
        messages.append(response)
        calls = response.get('tool_calls') or []
        if calls:
            if len(calls) > 8:
                raise AgentError('tool_limit', '单轮工具调用过多')
            for call in calls:
                active()
                if call['id'] in call_ids:
                    raise AgentError('invalid_tool_call', '模型重复了工具调用编号')
                call_ids.add(call['id'])
                if len(call_ids) > 24:
                    raise AgentError('tool_limit', '工具调用次数超过上限')
                try:
                    arguments = json.loads(call['function']['arguments'])
                except (TypeError, ValueError):
                    raise AgentError('invalid_tool_arguments', '模型工具参数不是 JSON')
                name = call['function']['name']
                result = execute_tool(session_factory, user_id, name, arguments)
                called.add(name)
                emit('tool', {'name': name, 'arguments': arguments, 'result': result})
                messages.append({'role': 'tool', 'tool_call_id': call['id'],
                                 'content': json.dumps(result, ensure_ascii=False)})
            continue
        # Do not accept an ungrounded draft, even if the model ignores the prompt.
        if not {'search_stories', 'search_pool'}.issubset(called):
            messages.append({'role': 'user', 'content': '请先调用 search_stories 和 search_pool 查询相关真实需求，再输出最终 JSON。'})
            continue
        active()
        messages.append({'role': 'user', 'content': '现在根据已查询事实输出最终 JSON，严格遵守 schema；所有证据逐字引用。'})
        final, usage = model.complete(messages, [], final=True)
        active()
        emit('model_final', {'usage': usage})
        try:
            result = validate_analysis(final.get('content'), segments)
        except AgentError as error:
            if error.code != 'incomplete_analysis':
                raise
            emit('coverage_retry', {'message': error.message})
            messages.append(final)
            messages.append({'role': 'user', 'content': error.message +
                             '。请重新输出完整 JSON，逐段补齐行动项、协调事项、状态约束或背景事实；保留已经正确的内容。'})
            active()
            repaired, usage = model.complete(messages, [], final=True)
            active()
            emit('model_repair', {'usage': usage})
            result = validate_analysis(repaired.get('content'), segments)
        emit('validated', {'proposal_count': len(result.proposals), 'segment_count': len(segments)})
        return result
    raise AgentError('step_limit', '分析达到工具轮数上限，未保存任何建议；可重试')
