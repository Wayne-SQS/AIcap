"""LOCAL TEST FIXTURE ONLY: deterministic Chat Completions responses, never real AI.
Run explicitly with AICAP_TEST_FIXTURE=1 and bind to 127.0.0.1.
"""
import asyncio
import json
import os
from fastapi import FastAPI
from fastapi.responses import JSONResponse

if os.environ.get('AICAP_TEST_FIXTURE') != '1':
    raise RuntimeError('This is a test fixture. Set AICAP_TEST_FIXTURE=1 explicitly.')

app = FastAPI(title='TEST FIXTURE — not a real AI provider')
failed_once = set()


@app.post('/chat/completions')
async def completion(body: dict):
    context = json.loads(body['messages'][1]['content'])
    segments = context['segments']
    transcript = '\n'.join(s['text'] for s in segments)
    await asyncio.sleep(0.2)
    if '[FAIL_ONCE]' in transcript and transcript not in failed_once:
        failed_once.add(transcript)
        return JSONResponse(status_code=503, content={'error': 'Intentional test fixture failure'})
    if 'response_format' in body:
        evidence = {'segment_id': segments[0]['segment_id'], 'quote': segments[0]['text']}
        if '[BAD_EVIDENCE]' in transcript:
            evidence['quote'] = 'This sentence is absent from the transcript.'
        result = {'summary': 'TEST FIXTURE：此输出仅用于联调，不代表真实模型效果。',
                  'decisions': [], 'action_items': [], 'risks': [],
                  'source_notes': [{'text':s['text'], 'evidence':{'segment_id':s['segment_id'], 'quote':s['text']}} for s in segments],
                  'unresolved_questions': ['Sprint 和负责人待确认'],
                  'proposals': [{'action': 'pool.create', 'title': segments[0]['text'][:180],
                                 'description': 'TEST FIXTURE 新需求', 'note': '测试建议', 'evidence': evidence}]}
        message = {'role': 'assistant', 'content': json.dumps(result, ensure_ascii=False)}
        finish = 'stop'
    elif any(m['role'] == 'tool' for m in body['messages']):
        message = {'role': 'assistant', 'content': 'TEST FIXTURE：真实项目工具查询已完成。'}
        finish = 'stop'
    else:
        message = {'role': 'assistant', 'content': None, 'tool_calls': [
            {'id': 'fixture-' + name, 'type': 'function',
             'function': {'name': name, 'arguments': '{"keyword":"Agent演示"}'}}
            for name in ('search_stories', 'search_pool')]}
        finish = 'tool_calls'
    return {'choices': [{'finish_reason': finish, 'message': message}],
            'usage': {'prompt_tokens': 10, 'completion_tokens': 10, 'total_tokens': 20}}
