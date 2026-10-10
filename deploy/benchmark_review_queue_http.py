"""Read-only HTTP latency sample for the authenticated review queue endpoints."""

import argparse
import json
import os
import statistics
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.request import ProxyHandler, Request, build_opener


def request_json(origin, path, token, timeout):
    headers = {'Authorization': 'Bearer ' + token}
    opener = build_opener(ProxyHandler({}))
    start = time.perf_counter()
    with opener.open(Request(origin + path, headers=headers), timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError(f'{path}: HTTP {response.status}')
        payload = json.load(response)
    return (time.perf_counter() - start) * 1000, payload


def login(origin, username, password, timeout):
    opener = build_opener(ProxyHandler({}))
    data = json.dumps({'username': username, 'password': password}).encode()
    with opener.open(Request(origin + '/api/auth/login', data=data,
                             headers={'Content-Type': 'application/json'}, method='POST'),
                     timeout=timeout) as response:
        token = json.load(response).get('access_token')
    if not isinstance(token, str) or not token:
        raise RuntimeError('Login did not return an access token')
    return token


def summarize(values):
    ordered = sorted(values)
    p95 = ordered[max(0, (95 * len(ordered) + 99) // 100 - 1)]
    return f'median={statistics.median(ordered):.1f}ms p95={p95:.1f}ms max={ordered[-1]:.1f}ms'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8088')
    parser.add_argument('--samples', type=int, default=20)
    parser.add_argument('--concurrency', type=int, default=5)
    parser.add_argument('--timeout', type=float, default=20)
    args = parser.parse_args()
    if not 1 <= args.samples <= 100 or not 1 <= args.concurrency <= 20:
        parser.error('samples must be 1..100 and concurrency 1..20')
    username = os.environ.get('AICAP_SMOKE_USERNAME')
    password = os.environ.get('AICAP_SMOKE_PASSWORD')
    if not username or not password:
        parser.error('Set AICAP_SMOKE_USERNAME and AICAP_SMOKE_PASSWORD')
    origin = args.url.rstrip('/')
    token = login(origin, username, password, args.timeout)
    paths = ('/api/review-queue?status=pending&source=all&limit=50',
             '/api/review-queue/summary')
    for path in paths:
        warmup_ms, warmup = request_json(origin, path, token, args.timeout)
        if path.endswith('/summary'):
            if not isinstance(warmup.get('pendingCount'), int):
                raise RuntimeError('Unexpected summary response')
            size = warmup['pendingCount']
        else:
            if not isinstance(warmup.get('items'), list):
                raise RuntimeError('Unexpected queue page response')
            size = len(warmup['items'])
        sequential = [request_json(origin, path, token, args.timeout)[0]
                      for _ in range(args.samples)]
        with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            concurrent = list(pool.map(
                lambda _: request_json(origin, path, token, args.timeout)[0],
                range(args.samples)))
        print(f'{path} size={size} warmup={warmup_ms:.1f}ms '
              f'sequential[{args.samples}] {summarize(sequential)} '
              f'concurrent[{args.samples},workers={args.concurrency}] {summarize(concurrent)}')


if __name__ == '__main__':
    main()
