"""`orchestrator.plan()` —— 主管的路由规划（契约 §5.4，S5′）。

这一层只做两件事，用例也按这两件事分：

1. **提案**（模型 + prompt）：输出严格 JSON 的六字段；prompt 里必须有**本轮可用路由**
   与**各路由的必填参数**，且这些路由此刻是**从 `/catalog` 现拉的**——新增/下线一枚
   触发型工具，本层零改动。阈值**不在 prompt 里**。
2. **判决**（Python）：四行判定表在 `apply_rules()` 里机械执行，和模型怎么说无关。

全程用 `FakePlatform`（`httpx.MockTransport`），不需要 Java/MySQL/Docker；
模型用 `ScriptedModel`，它会把每次收到的 messages 记下来供断言 prompt。
"""

from __future__ import annotations

import json

import pytest

from app import config, orchestrator
from app.java_client import JavaClient
from app.orchestrator import (
    Plan,
    Route,
    apply_rules,
    candidates,
    find_route,
    plan,
    routes_from_catalog,
)
from app.schemas import Catalog, CatalogTool, ModelError, PlatformProtocolError, PlatformUnavailable

from fake_platform import (
    MEETING_ROUTE,
    PLAN_DRAFT_ROUTE,
    FakePlatform,
    ScriptedModel,
    plan_payload,
    plan_reply,
)

BASE = "http://java.test"
TOKEN = "tok-ok"


def _catalog(platform: FakePlatform) -> Catalog:
    """同步用例里拉一次清单（那些用例本身没有事件循环，`asyncio.run` 是安全的）。"""
    import asyncio

    return asyncio.run(load_catalog(platform))


async def load_catalog(platform: FakePlatform) -> Catalog:
    async with JavaClient(BASE, transport=platform.transport()) as client:
        return await client.catalog(TOKEN)


def routes_of(platform: FakePlatform) -> tuple[Route, ...]:
    return routes_from_catalog(_catalog(platform))


async def run_plan(platform: FakePlatform, model, utterance: str = "分析一下上次评审会") -> Plan:
    async with JavaClient(BASE, transport=platform.transport()) as client:
        return await plan(utterance, TOKEN, model=model, client=client)


def system_prompt_of(model: ScriptedModel) -> str:
    messages = model.seen[0]
    return messages[0]["content"]


# ==========================================================================
# 当前项目上下文：多项目之后工具调用普遍要 project_id
# ==========================================================================


async def test_prompt_carries_the_current_project_when_given():
    """当前项目必须进 prompt：不告诉模型它只能猜 project_id。
    真机实测过这条缺口 —— 读查询猜中了 P001，而派活那枚漏传 project_id，
    被平台以 `invalid_arguments` 拒收（路由全对、活派不出去）。"""
    platform = FakePlatform()
    model = ScriptedModel(plan_reply())

    async with JavaClient(BASE, transport=platform.transport()) as client:
        await plan("分析一下上次评审会", TOKEN, model=model, client=client, project_id="P007")

    system = system_prompt_of(model)
    assert "P007" in system, "当前项目必须进 prompt"
    assert "project_id" in system


async def test_prompt_omits_the_project_line_when_not_given():
    """不传 project_id 时不得凭空写一行 —— 老调用方(省略该参数)的 prompt 形状不变。"""
    model = ScriptedModel(plan_reply())

    await run_plan(FakePlatform(), model)

    assert "当前项目" not in system_prompt_of(model)


def test_catalog_declares_project_id_for_scoped_tools():
    """结构化 schema 和画像域的工具都可接收项目上下文。

    它决定"能不能给这枚工具兜底注入 project_id"：会议域的 list_members 严格校验未知参数，
    多塞一个就会被判 invalid_tool_arguments（真机实测的回归），所以只有自己声明了要它的
    工具才允许补。画像域由 Java 统一入口要求项目，虽然只有 args_hint 也要补。"""
    catalog = Catalog(
        platform_version="1.0",
        tools=[
            CatalogTool(name="start_plan_draft", agent="platform", description="派活",
                        args_schema={"type": "object",
                                     "properties": {"project_id": {"type": "string"},
                                                    "request": {"type": "string"}}},
                        read_only=False),
            CatalogTool(name="list_members", agent="meeting", description="成员名单",
                        args_schema={"type": "object", "properties": {}}),
            CatalogTool(name="query_member_profile", agent="profile", description="画像",
                        args_hint="参数：userId（必填）"),
        ],
    )

    assert catalog.declares_project_id("start_plan_draft") is True
    assert catalog.declares_project_id("list_members") is False
    assert catalog.declares_project_id("query_member_profile") is True
    assert catalog.declares_project_id("不存在") is False


# ==========================================================================
# 路由集合：运行时从 /catalog 拉、零硬编码
# ==========================================================================


def test_routes_are_derived_from_the_catalog_read_only_flag():
    """`read_only=false` 才是"派活"的目标；只读工具不进路由表。"""
    routes = routes_of(FakePlatform())

    names = [route.name for route in routes]
    assert names == [MEETING_ROUTE, PLAN_DRAFT_ROUTE]
    assert "query_tasks" not in names and "query_member_profile" not in names

    meeting = find_route(routes, MEETING_ROUTE)
    assert meeting is not None
    assert meeting.required == ("meeting_id",)
    assert meeting.needs_approval is True, "触发型工具 = 写库/开启采集类（产出走审批队列）"


def test_no_read_only_tool_ever_becomes_a_route():
    """只读工具哪怕出现在 catalog 里，也不该成为一条可派发的路由。"""
    catalog = Catalog.model_validate(
        {
            "platform_version": "1.0",
            "tools": [
                {
                    "name": "search_stories",
                    "agent": "meeting",
                    "description": "查故事",
                    "args_schema": {"type": "object", "properties": {}},
                    "read_only": True,
                }
            ],
            "limitations": [],
        }
    )

    assert routes_from_catalog(catalog) == ()


def test_a_new_trigger_tool_becomes_a_route_without_touching_python():
    """Java 侧加一枚触发型工具 → 本层**零改动**多一条路由（含它的必填参数）。

    这是"清单从 `/catalog` 拉、不硬编码"的可断言形式：`start_retro_draft` 这个名字在
    `app/` 里一次都没出现过。
    """
    platform = FakePlatform()
    platform.add_trigger_tool(
        "start_retro_draft", required=("period",), description="生成复盘草案（走审批队列）"
    )

    routes = routes_of(platform)

    assert [route.name for route in routes] == [MEETING_ROUTE, PLAN_DRAFT_ROUTE, "start_retro_draft"]
    assert find_route(routes, "start_retro_draft").required == ("period",)


async def test_a_new_trigger_tool_is_dispatchable_end_to_end():
    """新增的那条路由走完整个 `plan()`：prompt 里出现它，模型选它就派得出去。"""
    platform = FakePlatform()
    platform.add_trigger_tool("start_retro_draft", required=("period",), description="生成复盘草案")
    model = ScriptedModel(
        plan_reply(intent="start_retro", route="start_retro_draft", args={"period": "第 5 周"})
    )

    decision = await run_plan(platform, model)

    assert decision.route == "start_retro_draft"
    assert decision.args == {"period": "第 5 周"}
    assert "start_retro_draft" in system_prompt_of(model)
    assert "period" in system_prompt_of(model)


async def test_a_route_whose_tool_is_gone_is_unavailable_and_asks():
    """catalog 里没有那枚工具 → 路由不可用 → 反问（不做模糊匹配、不猜）。"""
    platform = FakePlatform()
    platform.include_triggers = False
    model = ScriptedModel(plan_reply())

    decision = await run_plan(platform, model)

    assert decision.route is None
    assert decision.clarify is not None
    assert MEETING_ROUTE not in system_prompt_of(model)
    assert "没有任何可用路由" in system_prompt_of(model)


# ==========================================================================
# prompt：可用路由 + 必填参数 + 能力边界；阈值**不**在里面
# ==========================================================================


async def test_prompt_carries_routes_required_args_and_limitations():
    platform = FakePlatform()
    platform.limitations = ["未配置当前 Sprint 日期"]
    model = ScriptedModel(plan_reply())

    await run_plan(platform, model)

    system = system_prompt_of(model)
    assert MEETING_ROUTE in system and PLAN_DRAFT_ROUTE in system
    assert "meeting_id" in system and "request" in system, "各路由的必填参数必须写清楚"
    assert "未配置当前 Sprint 日期" in system, "契约 §2.2：能力边界必须进 prompt"
    assert "没有执行权" in system, "只 plan() 不 execute() 的边界要写进 system prompt"


async def test_threshold_is_not_written_into_the_prompt(monkeypatch: pytest.MonkeyPatch):
    """阈值**可配置、写在代码里**：prompt 里不许出现那个数，否则调阈值就要改 prompt。"""
    monkeypatch.setattr(orchestrator, "CLARIFY_CONFIDENCE", 0.83)
    model = ScriptedModel(plan_reply())

    await run_plan(FakePlatform(), model)

    system = system_prompt_of(model)
    assert "0.83" not in system
    assert "0.6" not in system.replace("0 到 1", ""), "0 到 1 是区间说明，不是阈值"


async def test_plan_asks_for_a_json_final_turn_without_tools():
    """规划那一发是 `final=True` 且**不带工具**：要的是结论，不是又一次工具调用。"""
    seen: list[tuple[list, list, bool]] = []

    class Recording:
        async def complete(self, messages, tools, *, final: bool = False):
            seen.append((messages, tools, final))
            return plan_reply(), {}

    await run_plan(FakePlatform(), Recording())

    messages, tools, final = seen[0]
    assert final is True
    assert tools == []
    assert messages[1] == {"role": "user", "content": "分析一下上次评审会"}, "用户原话进 user 消息"


async def test_plan_pulls_the_catalog_with_the_callers_jwt():
    """`user_jwt` 的用途就是那次清单拉取 —— 权限永远来自 JWT，Java 不认就如实报错。"""
    platform = FakePlatform(jwt="tok-owner")
    model = ScriptedModel(plan_reply())

    async with JavaClient(BASE, transport=platform.transport()) as client:
        decision = await plan("分析一下上次评审会", "tok-owner", model=model, client=client)

    assert decision.route == MEETING_ROUTE
    assert platform.calls == [], "规划相不调任何工具"

    with pytest.raises(PlatformProtocolError) as caught:
        async with JavaClient(BASE, transport=platform.transport()) as client:
            await plan("分析一下", "wrong-token", model=model, client=client)

    assert caught.value.status == 401


async def test_plan_requires_a_catalog_source():
    """既没给 catalog 也没给 client：这是编程错误，**不能**退化成"没有路由也能跑"。"""
    with pytest.raises(ValueError):
        await plan("分析一下", TOKEN, model=ScriptedModel(plan_reply()))


# ==========================================================================
# 未配置模型：如实失败，绝不猜
# ==========================================================================


async def test_unconfigured_model_fails_honestly_and_never_guesses(
    monkeypatch: pytest.MonkeyPatch,
):
    """没配密钥 → `not_configured` 如实抛出。

    **不猜路由、不用关键词硬凑一个 confidence 骗过阈值** —— 所以这里断言的是
    "抛异常"，而不是"返回了一个 route=null 的 Plan"：后者看起来像正常反问，
    实际是把"没接上模型"伪装成了"模型没听懂"。
    """
    monkeypatch.setattr(config, "AICAP_LLM_API_KEY", "")
    monkeypatch.setattr(config, "AICAP_LLM_MODEL", "")
    platform = FakePlatform()

    with pytest.raises(ModelError) as caught:
        # 不注入假模型 → 走真实 ModelClient（未配置时在建连之前就失败）
        async with JavaClient(BASE, transport=platform.transport()) as client:
            await plan("要开会", TOKEN, client=client)

    assert caught.value.code == "not_configured"
    assert platform.calls == [] and platform.events == [], "没配模型就不该有任何副作用"


# ==========================================================================
# 响应校验：非法 JSON / 缺字段 / 类型不对 → invalid_response（不抛裸异常）
# ==========================================================================


@pytest.mark.parametrize(
    ("label", "content"),
    [
        ("不是 JSON", "我觉得你想开会"),
        ("被 markdown 包起来", "```json\n" + json.dumps(plan_payload()) + "\n```"),
        ("顶层是数组", "[]"),
        ("顶层是字符串", '"start_meeting"'),
        ("空", "   "),
        ("缺字段", json.dumps({k: v for k, v in plan_payload().items() if k != "route"})),
        ("confidence 是字符串", json.dumps(plan_payload(confidence="0.9"))),
        ("confidence 越界", json.dumps(plan_payload(confidence=1.4))),
        ("confidence 是 bool", json.dumps(plan_payload(confidence=True))),
        ("intent 不是字符串", json.dumps(plan_payload(intent=7))),
        ("args 不是对象", json.dumps(plan_payload(args=["meeting_id"]))),
        ("clarify 缺 question", json.dumps(plan_payload(clarify={"options": ["a"]}))),
        ("clarify.options 不是字符串数组", json.dumps(plan_payload(clarify={"question": "?", "options": [1]}))),
    ],
)
async def test_malformed_model_output_is_invalid_response(label: str, content: str):
    model = ScriptedModel({"role": "assistant", "content": content})

    with pytest.raises(ModelError) as caught:
        await run_plan(FakePlatform(), model)

    assert caught.value.code == "invalid_response", label


async def test_malformed_output_never_echoes_the_utterance():
    """错误文本里**只有字段名**：模型那段文本可能含用户原话，异常文本会进日志。"""
    secret = "客户 A 的报价是 123 万"
    model = ScriptedModel({"role": "assistant", "content": f"听不懂：{secret}"})

    with pytest.raises(ModelError) as caught:
        await run_plan(FakePlatform(), model, utterance=secret)

    rendered = f"{caught.value.message}{caught.value}"
    assert secret not in rendered


# ==========================================================================
# 判定表（apply_rules，纯函数）
# ==========================================================================


def rule_routes() -> tuple[Route, ...]:
    return routes_from_catalog(_catalog(FakePlatform()))


def test_high_confidence_with_complete_args_dispatches():
    """第 5 行：其余 → 派活。"""
    decision = apply_rules(plan_payload(confidence=0.92), rule_routes())

    assert decision.route == MEETING_ROUTE
    assert decision.clarify is None
    assert decision.args == {"meeting_id": "m-1"}
    assert decision.confidence == 0.92
    assert decision.reason == "用户要求分析这场会议"


def test_low_confidence_asks_even_when_the_route_looks_right():
    """第 2 行：`confidence < 阈值` → 反问（`route=null`，填 `clarify`）。

    模型选的路由可用、参数也齐 —— 阈值照样说了算。这正是"Python 下判断"的意义：
    prompt 里怎么说的不作数。
    """
    decision = apply_rules(plan_payload(confidence=0.41, clarify={"question": "是这场会吗？", "options": ["是", "不是"]}), rule_routes())

    assert decision.route is None
    assert decision.confidence == 0.41
    assert decision.clarify == {"question": "是这场会吗？", "options": ["是", "不是"]}
    assert "低于阈值" in decision.reason, "S7 复盘要能看出是阈值判的"


def test_confidence_exactly_at_the_threshold_dispatches():
    """边界：`<` 是严格小于，等于阈值不算低。"""
    decision = apply_rules(plan_payload(confidence=orchestrator.CLARIFY_CONFIDENCE), rule_routes())

    assert decision.route == MEETING_ROUTE


def test_missing_required_arg_asks_even_with_high_confidence():
    """第 3 行：必填参数缺失 → 反问（即使 confidence 是 0.95）。

    模型没给 clarify 时用机械兜底：把**缺的参数名**问出来，用户才知道要补什么。
    """
    decision = apply_rules(plan_payload(confidence=0.95, args={}), rule_routes())

    assert decision.route is None
    assert decision.args == {}
    assert decision.clarify is not None
    assert "meeting_id" in decision.clarify["question"]
    assert "必填参数缺失" in decision.reason


@pytest.mark.parametrize("empty", [None, "", "   "])
def test_blank_required_arg_counts_as_missing(empty):
    decision = apply_rules(plan_payload(confidence=0.95, args={"meeting_id": empty}), rule_routes())

    assert decision.route is None
    assert decision.clarify is not None


def test_zero_is_a_value_not_a_missing_arg():
    """`0` 是"给了值"（`user_id=0` 这种边界不该被当成缺参）。"""
    routes = (Route(name="start_x", agent="platform", description="x", required=("user_id",)),)

    decision = apply_rules(plan_payload(route="start_x", args={"user_id": 0}), routes)

    assert decision.route == "start_x"
    assert decision.clarify is None


def test_rules_are_mechanical_regardless_of_what_the_model_claims():
    """同一份提案：参数齐 → 派活；把参数抽掉 → 反问。判定只看字段，不看模型的态度。"""
    routes = rule_routes()

    assert apply_rules(plan_payload(), routes).route == MEETING_ROUTE
    assert apply_rules(plan_payload(args={}), routes).route is None


def test_a_proposed_but_unavailable_route_asks():
    """模型提了一个 catalog 里不存在的路由（§5.4 旧示例里的 `meeting` 就是这种）
    → 反问，不做别名匹配。"""
    decision = apply_rules(plan_payload(route="meeting", args={}), rule_routes())

    assert decision.route is None
    assert "不在本轮可用路由表里" in decision.reason


def test_no_route_with_high_confidence_is_handed_to_the_tool_loop():
    """`route=null` + 有把握 = "这一轮不需要子智能体"，**不是**反问。

    用户只是在问问题（"现在有几个任务在推进？"）时就是这一种：主管自己用只读工具查了
    作答。硬把它变成反问，等于把 S4′.2 那套问答砍掉。
    """
    decision = apply_rules(plan_payload(route=None, confidence=0.9, args={}, clarify=None), rule_routes())

    assert decision.route is None
    assert decision.clarify is None, "不反问"
    assert decision.args == {}
    assert decision.confidence == 0.9


def test_no_route_with_low_confidence_asks_with_an_honest_question():
    """`route=null` + 没把握 = 反问（"出个报告"在 S8′ 之前就是这一种）。

    没有可用路由时兜底话术必须**如实**说明平台没有可派发的动作，而不是编一个选项出来。
    """
    decision = apply_rules(plan_payload(route=None, confidence=0.41, args={}, clarify=None), ())

    assert decision.route is None
    assert decision.clarify is not None
    assert "没有可派发的动作" in decision.clarify["question"]
    assert decision.clarify["options"] == []
    assert "低于阈值" in decision.reason


def test_approval_action_is_dispatched_without_asking_for_confirmation():
    """第 4 行：命中"写库/开启采集"类动作 → **不反问**，直接派活。

    模型自己提了一句"要不要确认一下"（`clarify` 非空），阈值与必填参数也都过了 ——
    这类动作的产出走审批队列，确认在队列里做，所以照派。这是它与
    `test_high_confidence_with_complete_args_dispatches` 的区别所在。
    """
    decision = apply_rules(
        plan_payload(
            route=PLAN_DRAFT_ROUTE,
            args={"request": "把 US12 的验收标准改成三条"},
            clarify={"question": "确认要生成草案吗？", "options": ["确认", "取消"]},
        ),
        rule_routes(),
    )

    assert decision.route == PLAN_DRAFT_ROUTE
    assert decision.clarify is None, "写库/采集类不为确认而反问"


def test_the_prompt_marks_approval_routes_as_do_not_ask():
    routes = rule_routes()

    text = orchestrator._route_lines(routes)

    assert text.count("不要反问确认") == len(routes) == 2


def test_candidates_are_the_routes_available_and_the_chosen_one():
    routes = rule_routes()

    rows = candidates(routes, chosen=MEETING_ROUTE)

    assert rows == [
        {"route": MEETING_ROUTE, "required": ["meeting_id"], "chosen": True},
        {"route": PLAN_DRAFT_ROUTE, "required": ["request"], "chosen": False},
    ]


# ==========================================================================
# 阈值可配置：环境变量 → config → 本模块（调用时读）
# ==========================================================================


def test_threshold_comes_from_config_at_import(monkeypatch: pytest.MonkeyPatch):
    """契约默认 0.6，可由环境变量覆盖（`AICAP_CLARIFY_CONFIDENCE`）。"""
    assert config.CLARIFY_CONFIDENCE == 0.6
    assert orchestrator.CLARIFY_CONFIDENCE == config.CLARIFY_CONFIDENCE


def test_threshold_is_read_at_call_time(monkeypatch: pytest.MonkeyPatch):
    """改阈值立即生效（不用改 prompt、不用重启）：0.41 在 0.6 下要问，在 0.3 下直接派。"""
    monkeypatch.setattr(orchestrator, "CLARIFY_CONFIDENCE", 0.3)
    proposal = plan_payload(confidence=0.41)

    decision = apply_rules(proposal, rule_routes())

    assert decision.route == MEETING_ROUTE
    assert decision.clarify is None


async def test_default_threshold_makes_a_041_proposal_ask():
    """同一份提案在默认阈值下是反问 —— 与上一条对照，阈值确实在起作用。"""
    model = ScriptedModel(plan_reply(confidence=0.41))

    decision = await run_plan(FakePlatform(), model)

    assert decision.route is None
    assert decision.clarify is not None


# ==========================================================================
# 端到端：模型回复 → Plan（含 Java 侧不可达时的如实失败）
# ==========================================================================


async def test_plan_surfaces_platform_failure_instead_of_guessing():
    """Java 不在 → `PlatformUnavailable`，**不**降级成"没有路由所以反问"。"""
    import httpx

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("down", request=request)

    async with JavaClient(BASE, transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(PlatformUnavailable):
            await plan("要开会", TOKEN, model=ScriptedModel(plan_reply()), client=client)


async def test_plan_with_injected_catalog_skips_the_extra_round_trip():
    """传了 catalog 就不再拉一次 —— 同一个 run 里清单只取一遍。"""
    platform = FakePlatform()
    catalog = await load_catalog(platform)
    before = len(platform.trace)

    async with JavaClient(BASE, transport=platform.transport()) as client:
        decision = await plan(
            "要开会", TOKEN, model=ScriptedModel(plan_reply()), catalog=catalog, client=client
        )

    assert decision.route == MEETING_ROUTE
    assert len(platform.trace) == before


# ==========================================================================
# 会议指代解析（2026-10-18）：list_meetings + resolve_meeting_reference
# ==========================================================================
#
# 真机实测的问题：「分析最近一场站会」被反复追问 meeting_id —— plan() 规划相不调工具，
# 而相对指代必须查一次列表才能落成具体 id。这里的用例钉三件事：
# ① 纯函数把"最近/标题点名/类型关键词"确定性映射成 args；② plan() 只在必要时发那次
# 只读调用；③ 解析不出来时反问换成本项目真实的会议标题，一场都没有时明说"先去保存"。

MEETINGS = [
    {"id": "mt-new", "title": "站会 2026-10-18", "created_at": "2026-10-18T09:00:00"},
    {"id": "mt-old", "title": "Sprint2 规划会 · 真实调用验证", "created_at": "2026-09-13T09:40:39"},
]


def _platform_with_meetings(rows=None) -> FakePlatform:
    platform = FakePlatform()
    platform.add_read_only_tool(
        "list_meetings",
        result={"items": list(rows if rows is not None else MEETINGS), "truncated": False},
        required=("project_id",),
    )
    return platform


async def plan_with_project(platform: FakePlatform, model, utterance: str, project_id: str = "P001") -> Plan:
    async with JavaClient(BASE, transport=platform.transport()) as client:
        return await plan(utterance, TOKEN, model=model, client=client, project_id=project_id)


def test_infer_meeting_type_maps_keywords_and_refuses_unknown():
    assert orchestrator.infer_meeting_type("分析最近一场站会") == "daily_scrum"
    assert orchestrator.infer_meeting_type("看看规划会") == "sprint_planning"
    assert orchestrator.infer_meeting_type("回顾一下") == "sprint_retrospective"
    assert orchestrator.infer_meeting_type("帮我梳理一下需求") == "backlog_refinement"
    assert orchestrator.infer_meeting_type("随便聊聊") is None


def test_resolve_meeting_reference_prefers_title_then_recency():
    proposal = {"route": MEETING_ROUTE, "args": {}}

    by_title, chosen = orchestrator.resolve_meeting_reference(
        "分析《Sprint2 规划会 · 真实调用验证》", MEETINGS, proposal
    )
    assert chosen is not None and by_title["args"]["meeting_id"] == "mt-old"
    assert by_title["args"]["meeting_type"] == "sprint_planning"

    by_recency, chosen = orchestrator.resolve_meeting_reference("分析最近一场站会", MEETINGS, proposal)
    assert chosen is not None and by_recency["args"]["meeting_id"] == "mt-new"
    assert by_recency["args"]["meeting_type"] == "daily_scrum"

    ambiguous, chosen = orchestrator.resolve_meeting_reference("分析一下会议", MEETINGS, proposal)
    assert chosen is None and "meeting_id" not in ambiguous["args"]


def test_resolve_meeting_reference_never_overwrites_model_id():
    proposal = {"route": MEETING_ROUTE, "args": {"meeting_id": "m-1"}}
    resolved, chosen = orchestrator.resolve_meeting_reference("分析最近一场", MEETINGS, proposal)
    assert resolved["args"]["meeting_id"] == "m-1" and chosen is None


def test_non_meeting_route_is_left_alone():
    proposal = {"route": PLAN_DRAFT_ROUTE, "args": {}}
    resolved, chosen = orchestrator.resolve_meeting_reference("分析最近一场站会", MEETINGS, proposal)
    assert resolved == proposal and chosen is None


async def test_plan_resolves_latest_meeting_without_asking():
    platform = _platform_with_meetings()
    model = ScriptedModel(plan_reply(args={}))  # 模型没给 meeting_id
    decision = await plan_with_project(platform, model, "分析最近一场站会")
    assert decision.route == MEETING_ROUTE
    assert decision.args == {"meeting_id": "mt-new", "meeting_type": "daily_scrum"}
    assert decision.clarify is None
    assert any(call["name"] == "list_meetings" for call in platform.calls)


async def test_plan_never_calls_list_meetings_when_id_already_given():
    platform = _platform_with_meetings()
    model = ScriptedModel(plan_reply())  # 默认带 meeting_id=m-1
    decision = await plan_with_project(platform, model, "分析会议 m-1")
    assert decision.route == MEETING_ROUTE
    assert not any(call["name"] == "list_meetings" for call in platform.calls)


async def test_plan_says_no_meetings_exist_instead_of_asking_for_an_id():
    platform = _platform_with_meetings([])
    model = ScriptedModel(plan_reply(route=MEETING_ROUTE, args={"meeting_id": ""}, confidence=0.9))
    decision = await plan_with_project(platform, model, "分析最近一场站会")
    assert decision.route is None
    assert decision.clarify is not None
    assert "还没有已保存的会议" in decision.clarify["question"]


async def test_plan_offers_real_titles_when_it_cannot_tell_which_meeting():
    platform = _platform_with_meetings()
    model = ScriptedModel(plan_reply(route=MEETING_ROUTE, args={"meeting_id": ""}, confidence=0.9))
    decision = await plan_with_project(platform, model, "分析一下会议")
    assert decision.route is None
    assert decision.clarify is not None
    assert decision.clarify["question"] == "要分析哪一场会议？"
    assert any("站会 2026-10-18" in option for option in decision.clarify["options"])
