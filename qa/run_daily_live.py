"""Windows live integration: disposable MySQL + real Java/Python/Vite; fixture model by default.

Run with ai-service/.venv/Scripts/python.exe qa/run_daily_live.py after packaging Java.
Use --suite planning, review, retro, refinement, assignment or transcription (default: daily).
Transcription also requires --stt-audio pointing to the synthetic English en.mp3 fixture.
All logs/data remain under qa/.daily-live; only processes created here are stopped.
Opt-in --real-model --suite refinement --env-file PATH allows one provider call.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
sys.stdout.reconfigure(encoding='utf-8')

ROOT = Path(__file__).resolve().parents[1]
MYSQL = Path(os.environ.get('AICAP_TEST_MYSQL_HOME', r'C:\Program Files\MySQL\MySQL Server 8.0'))
JAVA = Path(os.environ.get('AICAP_TEST_JAVA_HOME', r'D:\LHWYAN\download\IntelliJ IDEA 2025.1.2\jbr')) / 'bin/java.exe'
NODE = shutil.which('node')
PORTS = [13317, 18180, 18190, 18191, 15173]
RUN = ROOT / 'qa/.daily-live' / uuid.uuid4().hex
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
processes = []
handles = []
model_calls = 0
real_client = None
real_model_name = None
model_lock = threading.Lock()


def start(name, args, cwd=ROOT, env=None):
    log = (RUN / (name + '.log')).open('wb')
    handles.append(log)
    process = subprocess.Popen([str(x) for x in args], cwd=cwd, env=env, stdout=log,
                               stderr=subprocess.STDOUT, creationflags=FLAGS)
    processes.append(process)
    return process


def wait_http(url, process):
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f'Service exited: {url}; inspect {RUN}')
        try:
            if httpx.get(url, timeout=2, trust_env=False).status_code == 200:
                return
        except httpx.HTTPError:
            pass
        time.sleep(.5)
    raise RuntimeError(f'Service not ready: {url}')


def sql(statement):
    return subprocess.check_output([str(MYSQL / 'bin/mysql.exe'), '--no-defaults',
        '--protocol=TCP', '--host=127.0.0.1', '--port=13317', '--user=root', '--skip-password',
        '--batch', '--skip-column-names', '--default-character-set=utf8mb4', '-e', statement],
        creationflags=FLAGS, timeout=15).decode('utf-8').strip()


class Model(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        global model_calls
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if real_client is not None:
            # Explicit opt-in only, single provider attempt; never retry a paid call.
            with model_lock:
                if model_calls:
                    self.send_error(429, 'model_call_budget_exhausted')
                    return
                model_calls += 1
            try:
                candidate = real_client.complete(request['messages'])
                (RUN / 'model-candidate.json').write_text(json.dumps(candidate, ensure_ascii=False, indent=2), encoding='utf-8')
                raw = json.dumps({'choices': [{'finish_reason': 'stop', 'message': {'role': 'assistant', 'content': json.dumps(candidate, ensure_ascii=False)}}]}).encode()
            except Exception:
                # Do not echo upstream errors, headers or configuration.
                (RUN / 'model-error.json').write_text('{"error":"real_model_request_failed"}', encoding='utf-8')
                self.send_error(502, 'real_model_request_failed')
                return
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
            return
        context = json.loads(request['messages'][-1]['content'])
        story = next((story for story in context.get('stories', []) if story['id'] == 'US13'), {})
        segment = context['transcript_segments'][0]
        result = dict(schema_version='1.0', meeting_id=context['meeting_id'], meeting_type='daily_scrum',
            summary='US13 今天开始开发，提交人工确认。', open_questions=[], proposed_actions=[dict(
                proposal_id='p1', action='update_story_status', story_id='US13',
                expected={'status': story.get('status')}, changes={'status': 1}, reason='会议明确今天开始开发',
                evidence=[dict(segment_id=segment['segment_id'], quote=segment['text'])])])
        if context['meeting_type'] == 'sprint_planning':
            result.update(meeting_type='sprint_planning', summary='US13 调整到 Sprint 3，提交人工确认。')
            result['proposed_actions'][0].update(action='update_story_sprint',
                expected={'sprint': story['sprint']}, changes={'sprint': 3}, reason='会议明确调整到 Sprint 3')
        elif context['meeting_type'] == 'sprint_review':
            result.update(meeting_type='sprint_review', summary='US13已验收通过，提交人工确认。')
            result['proposed_actions'][0].update(changes={'status': 2}, reason='会议明确验收通过')
        elif context['meeting_type'] == 'sprint_retrospective':
            evidence = [dict(segment_id=segment['segment_id'], quote=segment['text'])]
            result.update(meeting_type='sprint_retrospective', summary='决定完善发布检查表，负责人及时间待确认。',
                decisions=[dict(text='完善发布检查表', evidence=evidence)],
                open_questions=['由谁负责，何时完成？'], proposed_actions=[dict(
                    proposal_id='p1', action='create_action_item',
                    changes=dict(title='完善发布检查表', description='补充发布检查步骤', owner_id=None, deadline_text=None),
                    reason='会议明确决定跟进', evidence=evidence)])
        elif context['meeting_type'] == 'backlog_refinement':
            result.update(meeting_type='backlog_refinement', summary='决定新增周报导出，详细要求待人工补充。',
                open_questions=['验收标准和排期待确认'], proposed_actions=[dict(
                    proposal_id='p1', action='create_story',
                    changes=dict(title='导出周报CSV', description=None, acceptance=None, priority=None, sprint=None, activity=None),
                    reason='会议明确新增需求', evidence=[dict(segment_id=segment['segment_id'], quote=segment['text'])])])
        model_calls += 1
        raw = json.dumps({'choices': [{'finish_reason': 'stop', 'message': {'role': 'assistant', 'content': json.dumps(result, ensure_ascii=False)}}]}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


def main():
    global real_client, real_model_name
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', choices=('daily', 'planning', 'review', 'retro', 'refinement', 'assignment', 'transcription'), default='daily')
    parser.add_argument('--stt-audio', type=Path, help='Transcription only: synthetic English MP3 generated by run_stt_local.py.')
    parser.add_argument('--real-model', action='store_true', help='Refinement only: one real provider call using synthetic meeting and seeded stories.')
    parser.add_argument('--env-file', type=Path, help='Model configuration file; used only with --real-model.')
    args = parser.parse_args()
    suite = args.suite
    if suite == 'transcription':
        if args.stt_audio is None or not args.stt_audio.is_file() or args.stt_audio.suffix.lower() != '.mp3':
            parser.error('--suite transcription requires --stt-audio pointing to a synthetic English MP3')
    elif args.stt_audio is not None:
        parser.error('--stt-audio requires --suite transcription')
    if args.real_model:
        if suite != 'refinement' or args.env_file is None:
            parser.error('--real-model requires --suite refinement and --env-file')
        sys.path.insert(0, str(ROOT / 'ai-service'))
        from start_service import load_config
        from meeting_agent.model_client import ChatModelClient, ModelSettings
        load_config(args.env_file)
        settings = ModelSettings.from_env()
        real_client, real_model_name = ChatModelClient(settings), settings.model
    elif args.env_file is not None:
        parser.error('--env-file requires --real-model')
    for port in PORTS:
        with socket.socket() as sock:
            sock.settimeout(.3)
            if sock.connect_ex(('127.0.0.1', port)) == 0:
                raise RuntimeError(f'Port {port} already has a listener')
    RUN.mkdir(parents=True)
    data = RUN / 'mysql'
    print(f'Artifacts: {RUN}', flush=True)
    model = None
    database = None
    try:
        init = start('mysql-init', [MYSQL / 'bin/mysqld.exe', '--no-defaults', '--initialize-insecure',
            f'--basedir={MYSQL}', f'--datadir={data}', '--console'])
        if init.wait(timeout=120) != 0:
            raise RuntimeError('MySQL initialization failed')
        database = start('mysql', [MYSQL / 'bin/mysqld.exe', '--no-defaults', f'--basedir={MYSQL}',
            f'--datadir={data}', '--port=13317', '--bind-address=127.0.0.1', '--mysqlx=0', '--console'])
        for _ in range(80):
            if database.poll() is not None:
                raise RuntimeError('MySQL exited')
            try:
                sql('SELECT 1'); break
            except subprocess.CalledProcessError:
                time.sleep(.5)
        else:
            raise RuntimeError('MySQL not ready')
        sql('CREATE DATABASE aicap_daily_e2e CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
        env = os.environ.copy()
        env.update(DB_URL='jdbc:mysql://127.0.0.1:13317/aicap_daily_e2e?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false',
            DB_USER='root', DB_PASSWORD='', SERVER_PORT='18180', AICAP_AGENT_WORKER_ENABLED='false',
            AICAP_SEED_ON_START='true', AICAP_AUDIO_DIR=str(RUN / 'audio'),
            JWT_SECRET='isolated-daily-e2e-secret-0123456789-abcdef', AICAP_LLM_API_KEY='fixture-only',
            AICAP_LLM_BASE_URL='http://127.0.0.1:18191', AICAP_LLM_MODEL='fixture',
            VITE_API_BASE='http://127.0.0.1:18180', AICAP_JAVA_BASE_URL='http://127.0.0.1:18180', AICAP_AI_PROXY_TARGET='http://127.0.0.1:18190')
        env.update(AICAP_LIVE_REAL_MODEL='1' if args.real_model else '0', AICAP_LIVE_ARTIFACT_DIR=str(RUN))
        if suite == 'transcription':
            env['AICAP_STT_EVAL_AUDIO'] = str(args.stt_audio.resolve())
        java = start('java', [JAVA, '-jar', ROOT / 'java-backend/target/aicap-java-backend.jar'], env=env)
        wait_http('http://127.0.0.1:18180/api/health', java)
        model = ThreadingHTTPServer(('127.0.0.1', 18191), Model)
        threading.Thread(target=model.serve_forever, daemon=True).start()
        python = start('python', [sys.executable, 'start_service.py', '--environment-only',
            '--java-url', 'http://127.0.0.1:18180', '--port', '18190'], cwd=ROOT / 'ai-service', env=env)
        wait_http('http://127.0.0.1:18190/health', python)
        vite = start('vite', [NODE, ROOT / 'frontend/node_modules/vite/bin/vite.js', '--host',
            '127.0.0.1', '--port', '15173', '--strictPort'], cwd=ROOT / 'frontend', env=env)
        wait_http('http://127.0.0.1:15173', vite)
        print('Real MySQL, Java, Python and Vite ready; running browser flow.', flush=True)
        result = subprocess.run([NODE, 'node_modules/@playwright/test/cli.js', 'test',
            '--config=playwright.live.config.js', f'{suite}-flow.spec.js'], cwd=ROOT / 'frontend', env=env,
            creationflags=FLAGS, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
        (RUN / 'browser.log').write_bytes(result.stdout)
        print(result.stdout.decode('utf-8', errors='replace'), flush=True)
        if result.returncode:
            raise RuntimeError('Browser integration failed')
        counts = sql("SELECT (SELECT status FROM aicap_daily_e2e.stories WHERE id='US13'),"
            "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_status_analyses),"
            "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_status_proposal_reviews),"
            "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_status_proposal_executions),"
            "(SELECT COUNT(*) FROM aicap_daily_e2e.story_logs WHERE story_id='US13' AND log_type='move')")
        if suite == 'planning':
            counts = sql("SELECT (SELECT sprint FROM aicap_daily_e2e.stories WHERE id='US13'),"
                "(SELECT status FROM aicap_daily_e2e.stories WHERE id='US13'),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_planning_analyses),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_planning_proposal_reviews),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_planning_proposal_executions),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.story_logs WHERE story_id='US13' AND log_type='edit')")
            assert counts == '4\t0\t1\t1\t1\t1', counts
        elif suite == 'review':
            counts = sql("SELECT (SELECT status FROM aicap_daily_e2e.stories WHERE id='US13'),"
                "(SELECT sprint FROM aicap_daily_e2e.stories WHERE id='US13'),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_review_analyses),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_review_proposal_reviews),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_review_proposal_executions),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.story_logs WHERE story_id='US13' AND log_type='move')")
            assert counts == '2\t2\t1\t1\t1\t1', counts
        elif suite == 'retro':
            counts = sql("SELECT (SELECT COUNT(*) FROM aicap_daily_e2e.meeting_retro_analyses),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_retro_proposal_reviews),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_retro_proposal_executions),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.action_items),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.action_item_logs),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.story_logs)")
            assert counts == '1\t1\t1\t1\t1\t0', counts
        elif suite == 'refinement':
            counts = sql("SELECT (SELECT COUNT(*) FROM aicap_daily_e2e.meeting_refinement_analyses),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_refinement_proposal_reviews),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_refinement_proposal_executions),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.story_logs WHERE log_type='create'),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.stories WHERE title='导出周报CSV')")
            assert counts == '1\t1\t1\t1\t1', counts
        elif suite == 'assignment':
            counts = sql("SELECT (SELECT COUNT(*) FROM aicap_daily_e2e.meeting_assignment_analyses),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_assignment_analyses WHERE JSON_UNQUOTE(JSON_EXTRACT(review_json,'$.execution_status'))='succeeded'),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_assignment_analyses WHERE JSON_UNQUOTE(JSON_EXTRACT(review_json,'$.status'))='rejected'),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.story_logs WHERE detail LIKE '会议分配建议执行：%')")
            assert counts == '3\t1\t1\t1', counts
        elif suite == 'transcription':
            counts = sql("SELECT (SELECT COUNT(*) FROM aicap_daily_e2e.meeting_audio),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_status_analyses),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.story_logs),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_transcript_versions),"
                "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_transcript_versions WHERE analysis_meeting_id IS NOT NULL)")
            assert counts == '1\t1\t0\t1\t1', counts
        else:
            assert counts == '1\t1\t1\t1\t1', counts
        assert model_calls == (0 if suite == 'assignment' else 1), model_calls
        report = dict(status='passed', suite=suite, database=sql('SELECT VERSION()'), model='faster-whisper-base-local' if suite == 'transcription' else 'none' if suite == 'assignment' else real_model_name if args.real_model else 'deterministic HTTP fixture',
            model_mode='local_stt' if suite == 'transcription' else 'not_used' if suite == 'assignment' else 'real_provider_single_call' if args.real_model else 'fixture',
            model_calls=model_calls, counts=counts.split('\t'), scope='real browser/Vite/Python/Java/JWT/MySQL')
        report['speech_model_calls'] = 2 if suite == 'transcription' else 0
        (RUN / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False), flush=True)
    finally:
        if model:
            model.shutdown(); model.server_close()
        if database is not None and database.poll() is None:
            try:
                actual = Path(sql('SELECT @@datadir')).resolve()
                if actual == data.resolve():
                    subprocess.run([str(MYSQL / 'bin/mysqladmin.exe'), '--no-defaults', '--protocol=TCP',
                        '--host=127.0.0.1', '--port=13317', '--user=root', '--skip-password', 'shutdown'],
                        creationflags=FLAGS, capture_output=True, timeout=30, check=True)
                    database.wait(timeout=30)
            except (OSError, subprocess.SubprocessError):
                print('Graceful database shutdown failed; stopping owned process.', flush=True)
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait()
        for handle in handles:
            handle.close()


if __name__ == '__main__':
    main()
