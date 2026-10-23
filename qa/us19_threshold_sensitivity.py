"""US19 预测参数（窗口 W / 样本下限 N）的离线敏感性回放。

只读脚本：不连数据库、不调接口、不写任何业务数据。它用合成历史回答一个问题 ——
当前实现的默认值（W=42 天、N=3 条完成、观察期至少 14 天）在"给定这套合成假设"下
是不是一个合理的相对选择，而不是"真实项目准不准"。

口径与 ProgressForecastController#calculate 对齐：

    history_start = max(该项目最早的 task_log, T - (W - 1))
    observed_days = (T - history_start) + 1
    recent        = 截止 T 的"最新状态为完成且当前仍为完成"的任务数（落在窗口内）
    open>0 且 recent>=N 且 observed_days>=14 时：
        estimated_days = ceil(open * observed_days / recent)
        estimated_date = T + estimated_days

合成假设（结论只在这些假设内成立，见文末 limitations）：
- 每个项目有一个潜在日吞吐 rate ~ U(0.2, 3.0)，按周乘一个 U(0.6, 1.4) 的波动因子；
- 每天完成数 ~ Poisson(rate)，从当日未完成任务里随机挑；
- 已完成任务有 4% 的概率在 1~7 天后被重开；
- 每天按 0.20*rate 的期望新增任务（范围缓慢增长）；起步 40 个未完成任务；
- 不做估时差异、依赖阻塞、取消任务。

用法：
    python qa/us19_threshold_sensitivity.py            # 打印表格
    python qa/us19_threshold_sensitivity.py --selftest # 只跑算法口径自检
"""

from __future__ import annotations

import argparse
import math
import random
import statistics

PROJECTS = 200
DAYS = 500
SEED = 20261023
EVAL_START = 30
EVAL_END = 220
EVAL_STEP = 10
INITIAL_TASKS = 40
MIN_OBSERVED_DAYS = 14
REOPEN_RATE = 0.04
ARRIVAL_FACTOR = 0.20
WINDOWS = (14, 21, 28, 42)
MIN_SAMPLES = (1, 3, 5)
DEFAULT_WINDOW = 42
DEFAULT_MIN_SAMPLES = 3
COVERAGE_FLOOR = 0.60


def simulate_project(rng: random.Random) -> dict:
    """返回 tasks 的完整状态历史：{task_id: [(day, status), ...]}，status 2=完成。"""
    rate = rng.uniform(0.2, 3.0)
    events: dict[int, list[tuple[int, int]]] = {i: [(0, 0)] for i in range(INITIAL_TASKS)}
    open_ids = set(events)
    next_id = INITIAL_TASKS
    reopen: dict[int, int] = {}
    weekly = {week: rng.uniform(0.6, 1.4) for week in range(DAYS // 7 + 2)}
    for day in range(DAYS):
        # 重开到期的任务重新算未完成
        for task_id, when in list(reopen.items()):
            if when == day:
                events[task_id].append((day, 0))
                open_ids.add(task_id)
                del reopen[task_id]
        # 新增任务
        arrivals = _poisson(rng, ARRIVAL_FACTOR * rate)
        for _ in range(arrivals):
            events[next_id] = [(day, 0)]
            open_ids.add(next_id)
            next_id += 1
        # 完成
        expected = rate * weekly[day // 7]
        completions = min(_poisson(rng, expected), len(open_ids))
        if completions:
            for task_id in rng.sample(sorted(open_ids), completions):
                events[task_id].append((day, 2))
                open_ids.discard(task_id)
                if rng.random() < REOPEN_RATE:
                    reopen[task_id] = day + rng.randint(1, 7)
    return {'events': events}


def _poisson(rng: random.Random, mean: float) -> int:
    """Knuth 采样；mean 很小时直接返回 0/1，避免长循环。"""
    if mean <= 0:
        return 0
    if mean > 20:
        return max(0, int(round(rng.gauss(mean, math.sqrt(mean)))))
    limit = math.exp(-mean)
    count, product = 0, rng.random()
    while product > limit:
        count += 1
        product *= rng.random()
    return count


def status_at(events: dict, task_id: int, day: int) -> int | None:
    """某任务在 day 当天的状态；尚未创建返回 None（否则未来新增的任务会被算成未完成）。"""
    history = events[task_id]
    if history[0][0] > day:
        return None
    status = 0
    for when, value in history:
        if when > day:
            break
        status = value
    return status


def forecast(project: dict, as_of: int, window: int, min_samples: int) -> dict:
    """与 Java 同口径的预测；返回 {'status', 'estimated_date'(int|None), 'open'}。"""
    events = project['events']
    total = 0
    open_ids, done_ids = [], []
    first_log = None
    latest_done: dict[int, int] = {}
    for task_id, history in events.items():
        status = status_at(events, task_id, as_of)
        if status is None:
            continue
        total += 1
        (open_ids if status in (0, 1) else done_ids).append(task_id)
        # history[0] 是创建时的初始状态；真实系统只在状态变化时写 task_logs，
        # 所以观察起点只从第 2 条事件起算。
        for when, value in history[1:]:
            if when > as_of:
                break
            if first_log is None or when < first_log:
                first_log = when
            if value == 2:
                latest_done[task_id] = when
    start = max(first_log if first_log is not None else as_of, as_of - (window - 1))
    observed_days = as_of - start + 1
    recent = sum(1 for task_id in done_ids
                 if latest_done.get(task_id) is not None and latest_done[task_id] >= start)
    open_count = len(open_ids)
    if total == 0 or (open_count == 0 and not done_ids):
        return {'status': 'empty_scope', 'estimated_date': None, 'open': open_count}
    if open_count == 0:
        return {'status': 'complete', 'estimated_date': as_of, 'open': 0}
    if recent < min_samples or observed_days < MIN_OBSERVED_DAYS:
        return {'status': 'insufficient_data', 'estimated_date': None, 'open': open_count}
    days = math.ceil(open_count * observed_days / recent)
    return {'status': 'forecast', 'estimated_date': as_of + days, 'open': open_count}


def actual_completion(project: dict, as_of: int) -> int | None:
    """as_of 当天仍未完成的任务，第一次全部完成的那天；模拟期内没完成返回 None。"""
    events = project['events']
    pending = {task_id for task_id in events
               if status_at(events, task_id, as_of) in (0, 1)}
    if not pending:
        return as_of
    for day in range(as_of, DAYS):
        pending = {task_id for task_id in pending if status_at(events, task_id, day) != 2}
        if not pending:
            return day
    return None


def collect(project: dict, as_of_dates: list[int], window: int, min_samples: int) -> list[dict]:
    rows = []
    for as_of in as_of_dates:
        predicted = forecast(project, as_of, window, min_samples)
        if predicted['status'] in ('complete', 'empty_scope'):
            continue
        actual = actual_completion(project, as_of)
        rows.append({'as_of': as_of, 'predicted': predicted, 'actual': actual})
    return rows


def summarize(rows: list[dict]) -> dict:
    eligible = len(rows)
    dated = [row for row in rows if row['predicted']['estimated_date'] is not None]
    errors = [abs(row['predicted']['estimated_date'] - row['actual'])
              for row in dated if row['actual'] is not None]
    signed = [row['predicted']['estimated_date'] - row['actual']
              for row in dated if row['actual'] is not None]
    return {
        'samples': eligible,
        'coverage': len(dated) / eligible if eligible else 0.0,
        'finished': len(errors) / len(dated) if dated else 0.0,
        'mae': statistics.fmean(errors) if errors else float('nan'),
        'p90': _percentile(errors, 0.90) if errors else float('nan'),
        'bias': statistics.fmean(signed) if signed else float('nan'),
        'n_errors': len(errors),
    }


def _percentile(values: list[int], quantile: float) -> float:
    ordered = sorted(values)
    index = max(0, math.ceil(quantile * len(ordered)) - 1)
    return float(ordered[index])


def build_synthetic(seed: int = SEED, projects: int = PROJECTS) -> list[dict]:
    master = random.Random(seed)
    built = []
    for _ in range(projects):
        built.append(simulate_project(random.Random(master.randrange(1 << 30))))
    return built


def run() -> list[dict]:
    projects = build_synthetic()
    as_of_dates = list(range(EVAL_START, EVAL_END + 1, EVAL_STEP))
    results = []
    for window in WINDOWS:
        for min_samples in MIN_SAMPLES:
            rows = []
            for project in projects:
                rows.extend(collect(project, as_of_dates, window, min_samples))
            stats = summarize(rows)
            stats.update({'window': window, 'min_samples': min_samples})
            results.append(stats)
    return results


def selftest() -> None:
    # 口径自检：10 个任务、3 个在第 1 天完成 → 观察起点=1、观察 21 天、剩 7 个
    # → ceil(7*21/3)=49 天，预计日期 = 21+49
    events = {i: [(0, 0)] for i in range(10)}
    for task_id in range(3):
        events[task_id] = [(0, 0), (1, 2)]
    project = {'events': events}
    result = forecast(project, 21, 42, 3)
    assert result['status'] == 'forecast', result
    assert result['estimated_date'] == 21 + 49, result
    # 同样数据换成 N=5 → 数据不足
    assert forecast(project, 21, 42, 5)['status'] == 'insufficient_data'
    # 窗口被压到 7 天后观察期不足 14 天
    assert forecast(project, 21, 7, 1)['status'] == 'insufficient_data'
    # 重开：最新状态为未完成时不计入 recent
    reopened = {'events': {0: [(0, 0), (2, 2), (5, 0)], 1: [(0, 0)], 2: [(0, 0)]}}
    assert forecast(reopened, 20, 42, 1)['status'] == 'insufficient_data'
    print('selftest ok')


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--selftest', action='store_true', help='只跑口径自检')
    args = parser.parse_args(argv)
    if args.selftest:
        selftest()
        return 0
    results = run()
    print(f"{'W':>3} {'N':>2} {'样本':>6} {'有日期%':>8} {'完成%':>7} "
          f"{'MAE(天)':>8} {'P90(天)':>8} {'偏差(天)':>9}")
    for stats in results:
        mark = '  ← 当前默认' if (stats['window'] == DEFAULT_WINDOW
                                  and stats['min_samples'] == DEFAULT_MIN_SAMPLES) else ''
        print(f"{stats['window']:>3} {stats['min_samples']:>2} {stats['samples']:>6} "
              f"{stats['coverage'] * 100:>7.1f}% {stats['finished'] * 100:>6.1f}% "
              f"{stats['mae']:>8.2f} {stats['p90']:>8.1f} {stats['bias']:>9.2f}{mark}")
    eligible = [s for s in results if s['coverage'] >= COVERAGE_FLOOR]
    best = min(eligible, key=lambda s: (s['mae'], s['p90'])) if eligible else None
    if best:
        print(f"覆盖率≥{COVERAGE_FLOOR:.0%} 下 MAE 最优：W={best['window']} N={best['min_samples']}"
              f"（MAE {best['mae']:.2f} 天，P90 {best['p90']:.1f} 天）")
    print('注意：以上仅证明合成假设下参数的相对优劣，不代表真实项目命中率。')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
