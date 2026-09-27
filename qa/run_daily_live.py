"""Windows live integration: disposable MySQL + real Java/Python/Vite; deterministic model HTTP only.

Run with ai-service/.venv/Scripts/python.exe qa/run_daily_live.py after packaging Java.
All logs/data remain under qa/.daily-live; only processes created here are stopped.
"""
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
        context = json.loads(request['messages'][-1]['content'])
        story = next(story for story in context['stories'] if story['id'] == 'US13')
        segment = context['transcript_segments'][0]
        result = dict(schema_version='1.0', meeting_id=context['meeting_id'], meeting_type='daily_scrum',
            summary='US13 今天开始开发，提交人工确认。', open_questions=[], proposed_actions=[dict(
                proposal_id='p1', action='update_story_status', story_id='US13',
                expected={'status': story['status']}, changes={'status': 1}, reason='会议明确今天开始开发',
                evidence=[dict(segment_id=segment['segment_id'], quote=segment['text'])])])
        model_calls += 1
        raw = json.dumps({'choices': [{'finish_reason': 'stop', 'message': {'role': 'assistant', 'content': json.dumps(result, ensure_ascii=False)}}]}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


def main():
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
        java = start('java', [JAVA, '-jar', ROOT / 'java-backend/target/aicap-java-backend.jar'], env=env)
        wait_http('http://127.0.0.1:18180/api/health', java)
        model = ThreadingHTTPServer(('127.0.0.1', 18191), Model)
        threading.Thread(target=model.serve_forever, daemon=True).start()
        python = start('python', [sys.executable, '-m', 'uvicorn', 'meeting_agent.api:create_app',
            '--factory', '--host', '127.0.0.1', '--port', '18190'], cwd=ROOT / 'ai-service', env=env)
        wait_http('http://127.0.0.1:18190/health', python)
        vite = start('vite', [NODE, ROOT / 'frontend/node_modules/vite/bin/vite.js', '--host',
            '127.0.0.1', '--port', '15173', '--strictPort'], cwd=ROOT / 'frontend', env=env)
        wait_http('http://127.0.0.1:15173', vite)
        print('Real MySQL, Java, Python and Vite ready; running browser flow.', flush=True)
        result = subprocess.run([NODE, 'node_modules/@playwright/test/cli.js', 'test',
            '--config=playwright.live.config.js'], cwd=ROOT / 'frontend', env=env,
            creationflags=FLAGS, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (RUN / 'browser.log').write_bytes(result.stdout)
        print(result.stdout.decode('utf-8', errors='replace'), flush=True)
        if result.returncode:
            raise RuntimeError('Browser integration failed')
        counts = sql("SELECT (SELECT status FROM aicap_daily_e2e.stories WHERE id='US13'),"
            "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_status_analyses),"
            "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_status_proposal_reviews),"
            "(SELECT COUNT(*) FROM aicap_daily_e2e.meeting_status_proposal_executions),"
            "(SELECT COUNT(*) FROM aicap_daily_e2e.story_logs WHERE story_id='US13' AND log_type='move')")
        assert counts == '1\t1\t1\t1\t1', counts
        assert model_calls == 1, model_calls
        report = dict(status='passed', database=sql('SELECT VERSION()'), model='deterministic HTTP fixture',
            model_calls=model_calls, counts=counts.split('\t'), scope='real browser/Vite/Python/Java/JWT/MySQL')
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
