# -*- coding: utf-8 -*-
"""会议语义固定样例复验。

对四类会议各建一场固定样例会议，调用 ai-service 分析端点，记录模型输出。
不执行审核/执行：结果只作为复验证据，人工审核门禁保持不变。
"""
import json
import os
import sys
import urllib.request
import urllib.error
from datetime import datetime, timezone
from pathlib import Path

JAVA = os.environ.get("AICAP_QA_JAVA_URL", "http://127.0.0.1:8080").rstrip("/")
AI = os.environ.get("AICAP_QA_AI_URL", "http://127.0.0.1:8090").rstrip("/")
OUT = Path(os.environ.get("AICAP_QA_SEMANTIC_OUTPUT", str(
    Path(__file__).resolve().parent / "evidence" / "semantic-recheck-latest.json")))

CASES = [
    {
        "key": "daily",
        "path": "/api/meetings/{id}/analyze",
        "meeting_type": "daily_scrum",
        "title": "Sprint 3 每日站会 2026-10-22（语义复验样例）",
        "transcript": """李锐铭：昨天把 GitHub 同步的成员映射改完了，US34 可以验收。
高思晗：US36 的成员状态页做完了，但还没写测试。
孙秋实：T12 现在卡在等接口，接口那边还没给。
罗子涵：我这边风险列表可以跑，但数据是旧的，今天的活动还没同步。
李锐铭：那 US36 算完成了吗？
高思晗：功能能点，测试还没补，先别算完成。
孙秋实：阻塞的原因是我不知道接口什么时候给，需要确认。""",
    },
    {
        "key": "review",
        "path": "/api/meetings/{id}/review/analyze",
        "meeting_type": "sprint_review",
        "title": "Sprint 3 评审会 2026-10-22（语义复验样例）",
        "transcript": """孙秋实：今天演示 US34、US35、US36，US37 只演示了建议生成，复盘报告还没做。
李锐铭：US34 我验收通过。
高思晗：US35 的提交分析只是演示，数据量不够，不算验收。
罗子涵：US36 的成员状态页功能可用，但测试还没补。
孙秋实：那 US37 算不算完成？
李锐铭：US37 是 Should，建议生成有，但影响说明刚补，复盘报告没做，不能算完成。""",
    },
    {
        "key": "retro",
        "path": "/api/meetings/{id}/retro/analyze",
        "meeting_type": "sprint_retrospective",
        "title": "Sprint 3 回顾会 2026-10-22（语义复验样例）",
        "transcript": """孙秋实：这轮最大的问题是回归测试拖到最后。
李锐铭：以后每轮第一天先跑一次全量回归。
高思晗：我来负责把回归写进检查清单。
罗子涵：测试库残留的问题我来跟，下次联调前清理。
孙秋实：还有文档总是最后才写。
李锐铭：文档初稿可以跟开发同步写，不要等到最后。""",
    },
    {
        "key": "refinement",
        "path": "/api/meetings/{id}/refinement/analyze",
        "meeting_type": "backlog_refinement",
        "title": "Sprint 3 需求梳理会 2026-10-22（语义复验样例）",
        "transcript": """高思晗：如果接口没通，就先做前端展示，后端后面接。
孙秋实：刚才说的那个故事，验收标准要补一条数据不足的情况。
李锐铭：US38 想加一个导出功能，但还没想清楚范围。
罗子涵：导出功能先放需求池，等确认了再做。
高思晗：对，先把 US37 的影响说明补上。""",
    },
]


def req(method, url, body=None, token=None, timeout=180):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read().decode("utf-8"))
        except Exception:
            payload = {"raw": "unreadable"}
        return e.code, payload
    except Exception as e:  # noqa: BLE001
        return -1, {"error": repr(e)}


def main():
    results = {"generated_at": datetime.now(timezone.utc).isoformat(),
               "note": "模型输出仅作复验证据，未审核、未执行", "cases": []}
    status, body = req("POST", JAVA + "/api/auth/login", {
        "username": os.environ.get("AICAP_QA_USERNAME", "李锐铭"),
        "password": os.environ.get("AICAP_QA_PASSWORD", "123456"),
    })
    if status != 200:
        print("login failed", status, body)
        return 1
    token = body.get("access_token") or body.get("token")
    if not token:
        print("no token in login response", body)
        return 1

    for case in CASES:
        print("==", case["key"])
        status, meeting = req("POST", JAVA + "/api/meetings", {
            "project_id": "P001",
            "title": case["title"],
            "transcript": case["transcript"],
        }, token)
        record = {"key": case["key"], "meeting_create_status": status}
        if status not in (200, 201):
            record["meeting_create_body"] = meeting
            results["cases"].append(record)
            print("  create failed", status, meeting)
            continue
        meeting_id = meeting.get("id")
        record["meeting_id"] = meeting_id
        req_id = "semantic-recheck-%s-20261022" % case["key"]
        astatus, aout = req("POST", AI + case["path"].format(id=meeting_id), {
            "client_request_id": req_id,
            "meeting_type": case["meeting_type"],
        }, token)
        record["analysis_status"] = astatus
        record["analysis"] = aout
        # 摘要：提案数 + 关键字段
        summary = {}
        if isinstance(aout, dict):
            for field in ("proposals", "items", "result", "status"):
                if field in aout:
                    summary[field] = aout[field] if field != "proposals" else len(aout.get("proposals") or [])
            summary["keys"] = list(aout.keys())[:12]
        record["summary"] = summary
        results["cases"].append(record)
        print("  analysis", astatus, summary)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print("written", OUT)
    return 0 if len(results["cases"]) == len(CASES) and all(
        case.get("analysis_status") == 200 for case in results["cases"]) else 1


if __name__ == "__main__":
    sys.exit(main())
