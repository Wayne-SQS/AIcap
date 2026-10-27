"""主管智能体：只 `plan()`，不 `execute()`（契约 §5.4 / v1.1 §4 冲突 #1）。

执行权在编排层 —— 这个边界同时体现在三处，不是一句声明：

1. **代码结构**：本模块只有 `plan()`，没有 `execute()`。它把一句话解析成一个 `Plan`，
   不调任何平台工具、不落任何业务数据、不发任何事件；
2. **system prompt**：`_ROLE` 明写"你没有执行权，只输出一个 JSON"；
3. **动作落点**：派活（调触发型工具）与反问（发 `clarify.request`）都在
   `main.py` 的规划相里 —— 那是编排层。

## S5′ 的切法：模型只提案，Python 下判断

- 模型输出一段严格 JSON：`intent` / `route` / `confidence` / `reason` / `args` / `clarify`；
- 契约 §5.4 那张判定表在 `apply_rules()` 里**逐行机械执行**（有序表，先命中先返回）：
  模型给出 `confidence=0.95` 却漏了必填参数 —— 照反问；模型给出高 confidence、参数齐全，
  但自己提了一句"要不要确认一下" —— 照派活（写库/采集类动作不为确认而反问）；
  模型给 `route=null` 且很有把握 —— 那不是在犹豫，是"这一轮不需要子智能体"，
  交给工具循环自己答（把 `route=null` 一律当成反问，会把 S4′.2 那套问答直接砍掉）。

为什么阈值一定要写在 Python 里：**prompt 是建议，判决得是代码**。写进 prompt 的话，
调阈值就等于改 prompt，而模型完全可以不理它 —— 那种"看起来配了、实际没生效"的
静默失效正是本仓反复吃亏的地方（实施计划 §2.5）。

## 路由集合：运行时从 `/catalog` 拉，零硬编码

`routes_from_catalog()` 只看 catalog 的 `read_only` 一个字段：`read_only=false` 的工具
（S5′ 的三枚 `start_*` 触发型工具）才是"派活"的目标。于是 **Java 侧新增一枚触发型工具，
本层零改动**就多一条路由；某枚工具从 catalog 里消失，对应路由自动不可用 ——
`plan()` 不认识任何 catalog 里没有的路由（不做模糊匹配、不做别名兜底）。

## 对契约 §5.4 的三处实现性说明（不改字段、不改参数名）

1. **是 `async def`**：本层唯一的模型出口 `model_client.ModelClient.complete` 是
   异步的，同步签名落不了地。回归契约要跑的嵌套事件循环比 async 危险得多。
2. **多了 keyword-only 注入点**（`model` / `catalog` / `client`）：契约省略了依赖注入，
   与 `loop.run(jwt=…, client=…, model=…)` 同形。两个位置参数与 `Plan` 的六个字段逐字保留。
3. **`route` 的取值 = 触发型工具名**（如 `start_meeting_analysis`）：真实
   `PlatformToolCatalog` 给触发型工具发的 `agent` 全是 `"platform"`，没法用它当路由 id；
   §5.4 里那个 `"route": "meeting"` 的示例写于触发型工具定稿（§2.4）之前，已随 S5′ 回改。
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any, Sequence

from pydantic import BaseModel, Field

from . import config
from .java_client import JavaClient
from .model_client import ModelClient, ModelProtocol
from .schemas import Catalog, CatalogTool, ModelError

# 反问阈值：可配置（环境变量 `AICAP_CLARIFY_CONFIDENCE`，默认 0.6），**不写死在 prompt 里**。
# 调用时读这个模块级全局，所以测试与运维都能在进程内改它。
CLARIFY_CONFIDENCE = config.CLARIFY_CONFIDENCE

# 模型那一发 JSON 必须齐这六个字段（契约 §5.4），少一个就是 `invalid_response`。
PLAN_KEYS = ("intent", "route", "confidence", "reason", "args", "clarify")


class Plan(BaseModel):
    """`plan()` 的返回（契约 §5.4）。三种形态，判据只有 `route` / `clarify` 两个字段：

    - **派活给子智能体**：`route` 有值、`clarify` 为 `None` → 编排层调那枚触发型工具；
    - **反问**：`route` 为 `None`、`clarify` 有值 → 编排层发 `clarify.request` 后终局；
    - **这一轮不需要子智能体**：`route` 与 `clarify` **都为 `None`** → 那句话是"问数据"，
      编排层把它交给工具循环，由主管自己调只读工具作答（S4′.2 的循环就是干这个的）。

    第三种是 S5′ 落地时从"契约 §5.4 只有派活/反问两种"里补出来的：把 `route=null`
    一律当反问，会把对话框最常用的"提问"用法整个变成反问，等于砍掉问答。
    判据仍然是**机械的** —— 模型如实给的 `confidence` 与阈值比一次（见 `apply_rules`）。
    """

    intent: str
    route: str | None = None
    confidence: float = 0.0
    reason: str = ""
    args: dict[str, Any] = Field(default_factory=dict)
    clarify: dict[str, Any] | None = None


@dataclass(frozen=True)
class Route:
    """一条可派发的路由 —— 完全由 `/catalog` 派生。

    `name` 同时是**路由 id** 与**工具名**：派活就是
    `POST /api/agents/tools/{name}`（契约 §2.3，永远返回 200，业务失败在 `ok=false`）。

    `needs_approval` = catalog 里的 `read_only == false`，语义是 v1.1 §3.3 的
    「写库 + 开启采集：要批准」—— 这类动作的产出进审批队列，所以契约 §5.4 第三行
    规定**不反问**。它是**数据**（Java 侧声明），本层据此在 prompt 里把这类路由标成
    "不要反问确认"（`_route_lines`），并且**结构性**地保证模型自提的 `clarify`
    不会把它拦住（见 `apply_rules`）。
    """

    name: str
    agent: str
    description: str
    required: tuple[str, ...] = ()
    optional: tuple[str, ...] = ()
    needs_approval: bool = False


# --------------------------------------------------------------------------
# 路由表：/catalog → Route
# --------------------------------------------------------------------------


def routes_from_catalog(catalog: Catalog) -> tuple[Route, ...]:
    """把能力清单翻成路由表。**只看 `read_only` 一个字段**。

    14 个只读工具是循环里由模型自己调的观察手段，不是"派活"的目标；三枚触发型工具
    （`read_only=false`）才是。所以这里没有一份需要跟着 Java 同步维护的工具名清单 ——
    新增触发型工具只改 Java（`PlatformToolCatalog`），本层零改动。
    """
    routes: list[Route] = []
    for tool in catalog.tools:
        if tool.read_only:
            continue
        required, optional = _args_of(tool)
        routes.append(
            Route(
                name=tool.name,
                agent=tool.agent,
                description=tool.description,
                required=required,
                optional=optional,
                needs_approval=True,
            )
        )
    return tuple(routes)


def _args_of(tool: CatalogTool) -> tuple[tuple[str, ...], tuple[str, ...]]:
    """从 `args_schema` 取（必填, 可选）参数名。

    没有 schema 的工具（画像域只有 `args_hint`）两个都返回空元组：**不假装知道**。
    代价是"缺参"这一行判不出来、要等 Java 的 `invalid_tool_arguments` 如实拒绝 ——
    比本层凭空捏一个必填参数名出来好。
    """
    schema = tool.args_schema if isinstance(tool.args_schema, dict) else {}
    properties = schema.get("properties")
    names = (
        tuple(key for key in properties if isinstance(key, str)) if isinstance(properties, dict) else ()
    )
    raw_required = schema.get("required")
    required = (
        tuple(name for name in raw_required if isinstance(name, str))
        if isinstance(raw_required, list)
        else ()
    )
    return required, tuple(name for name in names if name not in required)


def find_route(routes: Sequence[Route], name: str | None) -> Route | None:
    """按 id 找路由。找不到就是**不可用**（不是"猜一个最像的"）。"""
    if not name:
        return None
    for route in routes:
        if route.name == name:
            return route
    return None


# --------------------------------------------------------------------------
# 会议指代的确定性解析（2026-10-18）
# --------------------------------------------------------------------------
#
# 背景（真机实测）：「分析最近一场站会」被反复追问 meeting_id —— plan() 是
# "一次模型调用 + 纯规则判定"，**规划相不调工具**，而"最近一场 / 标题里点名的那场"
# 是相对指代，不查列表就永远落不到具体 id。所以这里补一条**只读**解析路径：
# 需要时由 plan() 调一次 list_meetings（平台只读工具），再按下面的顺序确定性映射。
#
# 为什么不做成"让模型自己编一个 id"或"猜一个最像的"：编出来的 id 会让触发型工具
# 稳定返回 404/无效会议，而用户看到的是"分析已派发"—— 比反问更糟。

#: 触发型工具名（与 Java 的 `PlatformToolCatalog.START_MEETING_ANALYSIS` 同字面量）
MEETING_ANALYSIS_TOOL = "start_meeting_analysis"

#: 只读的会议列表工具名 —— 解析相对指代的唯一数据来源
MEETING_LIST_TOOL = "list_meetings"

#: 类型关键词 → 线上字面量。**长词在前**（"每日站会"先于"站会"），否则短词会先命中同一句话。
_MEETING_TYPE_WORDS: tuple[tuple[str, str], ...] = (
    ("每日站会", "daily_scrum"),
    ("站会", "daily_scrum"),
    ("晨会", "daily_scrum"),
    ("规划会", "sprint_planning"),
    ("计划会", "sprint_planning"),
    ("规划", "sprint_planning"),
    ("评审会", "sprint_review"),
    ("验收会", "sprint_review"),
    ("评审", "sprint_review"),
    ("回顾会", "sprint_retrospective"),
    ("复盘", "sprint_retrospective"),
    ("回顾", "sprint_retrospective"),
    ("需求梳理", "backlog_refinement"),
    ("梳理会", "backlog_refinement"),
    ("梳理", "backlog_refinement"),
)

#: 相对指代词 —— 命中就取倒序第一条（`list_meetings` 已按 `created_at DESC` 返回）
_RECENT_WORDS = ("最近", "最新", "上一场", "上上次", "最后", "刚才")


def infer_meeting_type(utterance: str) -> str | None:
    """从原话里认会议类型。认不出回 `None` —— **不猜**。"""
    for word, wire in _MEETING_TYPE_WORDS:
        if word in (utterance or ""):
            return wire
    return None


def _normalize_title(text: str) -> str:
    """标题匹配用：去掉空白/标点/大小写差异，只留可比较的字符。"""
    return re.sub(r"[\s《》「」【】\"'“”‘’·:：,，。.!！?？\-—_/\\]+", "", text or "").lower()


def _meetings_of_type(utterance: str, meetings: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    """原话点名了会议类型时，只在**该类型**的会议里挑。

    真机实测（2026-10-27）：项目里最新一条是「需求梳理会」，用户说「分析最近一次站会」，
    旧实现按「最近」直接取了那条梳理会 —— 分析跑完才在答复里补一句"其实这场不是站会"，
    等于先干了错的活再道歉。`list_meetings` 只回 `id/title/created_at`（无 type 字段），
    所以类型只能从标题里按同一套关键词认。
    """
    wire = infer_meeting_type(utterance)
    if wire is None:
        return []
    words = [word for word, mapped in _MEETING_TYPE_WORDS if mapped == wire]
    return [
        meeting
        for meeting in meetings
        if any(word in str(meeting.get("title") or "") for word in words)
    ]


def _match_meeting(utterance: str, meetings: Sequence[dict[str, Any]]) -> dict[str, Any] | None:
    """在**已按时间倒序**的会议列表里挑一场；挑不出来回 `None`。"""
    if not meetings:
        return None
    normalized = _normalize_title(utterance)
    named = [
        meeting
        for meeting in meetings
        if _normalize_title(str(meeting.get("title") or ""))
        and _normalize_title(str(meeting.get("title") or "")) in normalized
    ]
    if named:
        return named[0]
    typed = _meetings_of_type(utterance, meetings)
    if typed:
        return typed[0]
    if any(word in (utterance or "") for word in _RECENT_WORDS):
        return meetings[0]
    if len(meetings) == 1:
        return meetings[0]
    return None


def resolve_meeting_reference(
    utterance: str,
    meetings: Sequence[dict[str, Any]],
    proposal: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any] | None]:
    """把"最近一场 / 标题里点名的那场"回填进 `args`。**纯函数、无 IO、可单独测。**

    返回 `(新的 proposal, 选中的会议或 None)`。只对 `start_meeting_analysis` 生效：

    - `meeting_id` 缺失且能从标题点名 / 相对指代 / 唯一候选解析出来 → 回填；
    - `meeting_type` 缺失且原话里有类型关键词 → 回填；
    - 模型已经给了 `meeting_id` 就**不覆盖** —— 它可能真的知道某场会议的 id。

    **刻意不改 `confidence`**：解析到 id 不代表意图听准了，阈值判定仍归 `apply_rules`。
    """
    if proposal.get("route") != MEETING_ANALYSIS_TOOL:
        return proposal, None
    raw_args = proposal.get("args")
    args = dict(raw_args) if isinstance(raw_args, dict) else {}
    chosen: dict[str, Any] | None = None
    if _blank(args.get("meeting_id")):
        chosen = _match_meeting(utterance, meetings)
        if chosen is not None and not _blank(chosen.get("id")):
            args["meeting_id"] = chosen["id"]
    if _blank(args.get("meeting_type")):
        inferred = infer_meeting_type(utterance)
        if inferred:
            args["meeting_type"] = inferred
    return {**proposal, "args": args}, chosen


def _declares(catalog: Catalog, name: str) -> bool:
    return any(tool.name == name for tool in catalog.tools)


async def _resolve_meeting_step(
    proposal: dict[str, Any],
    utterance: str,
    *,
    user_jwt: str,
    project_id: str | None,
    client: JavaClient | None,
    catalog: Catalog,
) -> tuple[dict[str, Any], dict[str, Any] | None]:
    """必要时拉一次会议列表并解析指代。

    只在"模型要派会议分析、且没给 `meeting_id`"时发这次只读调用 —— 它是额外往返，
    不该出现在每一轮对话里。拿不到列表（工具没发布 / Java 异常 / 没有项目上下文）就
    **当作没有会议**：解析是增强，不能反过来把反问流程打挂（与
    `loop._history_messages` 的降级口径一致）。
    """
    if proposal.get("route") != MEETING_ANALYSIS_TOOL:
        return proposal, None
    args = proposal.get("args") if isinstance(proposal.get("args"), dict) else {}
    if not _blank(args.get("meeting_id")):
        return proposal, None
    if client is None or not project_id or not _declares(catalog, MEETING_LIST_TOOL):
        return proposal, {"attempted": False, "meetings": [], "resolved": False}
    try:
        outcome = await client.call_tool(
            MEETING_LIST_TOOL, {"project_id": project_id, "limit": 20}, user_jwt
        )
    except Exception:  # noqa: BLE001 —— PlatformError 等：解析失败不影响照常反问
        return proposal, {"attempted": False, "meetings": [], "resolved": False}
    result = outcome.result if isinstance(outcome.result, dict) else {}
    rows = result.get("items") if isinstance(result.get("items"), list) else []
    meetings = [row for row in rows if isinstance(row, dict)]
    resolved, chosen = resolve_meeting_reference(utterance, meetings, proposal)
    return resolved, {"attempted": True, "meetings": meetings, "resolved": chosen is not None}


def _refine_meeting_clarify(decision: Plan, context: dict[str, Any] | None) -> Plan:
    """这一轮确实做过会议解析、但没解析出来时，把反问改得更具体。

    - 本项目一场会议都没有 → 说清楚"先去会议室保存一场"，否则用户会一直补 id；
    - 有会议但认不出 → 选项换成**真实会议标题**（点一下就命中标题匹配）。
    """
    if not context or not context.get("attempted") or context.get("resolved"):
        return decision
    if decision.clarify is None or decision.route is not None:
        return decision
    meetings = context.get("meetings") or []
    if not meetings:
        clarify = {
            "question": "本项目还没有已保存的会议。请先在「会议工作区 → 文本会议」保存一场记录，再让我分析。",
            "options": [],
        }
    else:
        titles = [str(meeting.get("title") or "") for meeting in meetings[:4]]
        clarify = {
            "question": "要分析哪一场会议？",
            "options": [f"分析《{title}》" for title in titles if title],
        }
    return Plan(
        intent=decision.intent,
        route=decision.route,
        confidence=decision.confidence,
        reason=decision.reason,
        args=decision.args,
        clarify=clarify,
    )


def candidates(routes: Sequence[Route], *, chosen: str | None = None) -> list[dict[str, Any]]:
    """留痕里的候选集合（`routing_logs.candidates_json`）。

    契约只规定 body 里有 `candidates` 这个字段、没规定形状，这里定成
    「本轮**可用**的路由 + 哪一个被选中」：`[{"route", "required", "chosen"}]`。
    S7 复盘时它回答的是"当时有哪些选项"，而"为什么没选别的"在 `reason` 里 ——
    模型提了一个 catalog 里不存在的路由时，`reason` 会带上系统判定的那句
    「模型给的路由不在本轮可用路由表里」，不必再往候选里塞一条不存在的东西。
    """
    return [
        {"route": route.name, "required": list(route.required), "chosen": route.name == chosen}
        for route in routes
    ]


# --------------------------------------------------------------------------
# prompt
# --------------------------------------------------------------------------

_ROLE = (
    "你是 AIcap 项目主管的**路由规划器**。你只做一件事：把用户的一句话解析成一次路由决策。\n"
    "你**没有执行权**：不能改数据、不能开启采集、不能直接调用任何工具 —— 只输出一个 JSON。\n"
    "执行由编排层负责，你只负责把话说清楚。"
)

_TASK = "任务：判断用户想要哪个动作，并从下表里选一条路由（选不出来就如实说选不出来）。"

_OUTPUT_CONTRACT = (
    "输出：**只输出一个 JSON 对象**，不要 markdown 代码块、不要任何解释文字。字段逐字如下：\n"
    "{\n"
    '  "intent": "<snake_case 的意图短名，如 start_meeting>",\n'
    '  "route": "<下表里的路由名>" 或者 null,\n'
    '  "confidence": <0 到 1 之间的小数，你对这次判断的把握>,\n'
    '  "reason": "<一句话说明为什么这么选>",\n'
    '  "args": {<该路由的入参；键名只能用下表列出的参数名>},\n'
    '  "clarify": null 或者 {"question": "<一句追问>", "options": ["<选项1>", "<选项2>"]}\n'
    "}"
)

_RULES = (
    "规则：\n"
    "1. confidence 必须**如实**给：拿不准就给低分。要不要追问由系统按代码里的阈值判定，\n"
    "   虚报高分只会让系统替你拍板。\n"
    "2. 用户只是在**问问题**（查数据、要结论）时 route 给 null，并给**高** confidence ——\n"
    "   这类回合不需要子智能体，系统会让主管自己用只读工具查了作答。\n"
    "3. 用户要求的是一个**动作**、而下表里没有对应路由时（例如「出个报告」在报告能力\n"
    "   落地之前），给 route=null + **低** confidence，并在 clarify 里写清「要哪种」。\n"
    "4. 同一句话可能指向多个动作、或必填参数无法从用户原话中确定时，同样给低 confidence\n"
    "   与 clarify（一句追问 + 2~4 个选项）。\n"
    "5. 用户要求的若是**写库/开启采集**这类「要批准」的动作，**不要反问确认**：\n"
    "   给出最贴近的路由即可 —— 产出走审批队列，由人确认。\n"
    "6. 不得虚构参数值：推断不出的必填参数就不要填，更不要用占位字符串凑数。\n"
    "7. 只输出上面那六个字段，不要夹带任何其他内容。\n"
    "8. 会议分析特例：meeting_id 不必从原话里挖 —— 编排层会用会议列表工具解析「最近一场」"
    "或原话里点名的标题。所以**不要**因为推断不出 meeting_id 就降低 confidence；"
    "meeting_type 认不出来时照常低 confidence + clarify（类型不能替你猜）。\n"
    "9. 会议分析是**同步**的：会议分析工具的回执里就带 analysis_id。"
    "用户要「分析结果 / 结论」时，必须调 get_meeting_analysis(analysis_id) 读摘要再转述"
    "（summary、提案要点、待确认问题），并说明这是**待人工确认的提案**；"
    "不许凭记忆编造结论，也不许用「转写正文我读不到」代替回答 —— 摘要就是答案。\n"
    "10. 新增任务（「加一个/建一个任务」）是 start_plan_draft 的能力，**不要**回答"
    "「没有新增任务这个能力」。任务的负责人与执行周是必填项：原话里已经给出（点名成员、"
    "说了第几周或 Sprint N）就直接派；缺负责人或时间时**只问一次**（把缺的项合成一句，"
    "别一轮一轮问），拿到答复后再派。负责人用成员姓名表述即可，画图智能体会按真实成员表解析。\n"
    "11. 若上文包含本会话此前的轮次，用户这句话很可能是在回答你上一轮的反问：把回答里的"
    "信息并进那一轮同一个动作的 args（补齐缺的字段），不要当成全新请求，也不要重复追问"
    "已经回答过的信息；只有用户明显换话题时才丢弃上文。\n"
    "12. 若上文（纪要里的 [已派活: ...]）显示你已经为**同一个动作**派过活，而用户这句话"
    "只是重复/确认已经给过的信息、没有新增内容，**不要再派一次**：直接确认上一轮的 run_id"
    "与状态即可。例如上一轮已派「写实验报告 / 负责人李锐铭 / 第 5 周」，用户接着说"
    "「负责人就是李锐铭」——这是确认，不是新请求：route 给 null、confidence 给高，"
    "让系统用回程查询工具查那一轮的 run_id 并答复。只有用户明确说「再建一个/另建一条」"
    "或给出了**新**的任务/负责人/时间时，才重新派发。\n"
    "13. 答复用自然语言：不要出现 week_start / week_end / owner_id / request 这类内部字段名，"
    "说「第 3 周」「负责人李锐铭」。提到人时用 list_members 查到的显示名，"
    "**不要**写「成员 1」「成员 2」或数字编号。参数名是给代码看的，不是给用户看的。\n"
    "14. 能力边界要准：调整现有任务（改负责人 / 挪周次 / 改优先级）也走 start_plan_draft，"
    "但**没有「修订上一份草案」这条路** —— 用户改主意时就说「我按新参数重新生成一份草案，"
    "旧的那份你可以在工作台取消」，不要给系统做不到的选项。\n"
    "15. 「把某任务分给某人 / 把某人的任务挪一周」指的是**现有任务**：原话没点明是哪一条、"
    "而该成员名下有多条任务时，先反问是哪一条（或哪些），不要凭空新建一条同名任务。\n"
    "16. 「搜一下资料 / 找找文档 / 知识库里有没有…」是**问问题**，不是派活：route 给 null、"
    "confidence 给高，让系统用只读工具（含 semantic_search 语义检索）去查。"
    "不许回答「没有搜资料这个能力」，也不许把用户支到别处去找。"
)


def _route_label(route: Route) -> str:
    """路由的**短**中文名，只用于反问的兜底选项。

    别拿 `route.description` 当按钮文案：真机实测里三个按钮各塞进 200 字工具描述，
    对话框直接被撑爆、用户也无从选择。认不出来的路由退回工具名 —— 宁可漏译，不瞎猜。
    """
    return _SHORT_ROUTE.get(route.name, route.name)


# 触发型工具 → 短名。新增触发型工具忘了登记时退回工具名（`_route_label`），不会崩。
_SHORT_ROUTE = {
    "start_plan_draft": "规划草案（新增/调整任务）",
    "start_meeting_analysis": "会议分析",
    "start_profile_analysis": "成员画像分析",
}


def _route_lines(routes: Sequence[Route]) -> str:
    """路由表 + 各路由的必填参数 —— 契约要求 prompt 里把这两件事讲清楚。"""
    if not routes:
        return (
            "（本轮**没有任何可用路由**：触发型工具一枚也不可用，route 只能为 null。\n"
            "  问问题 → 高 confidence；要一个动作而这里没有对应路由 → 低 confidence + clarify。）"
        )
    lines: list[str] = []
    for route in routes:
        required = "、".join(route.required) if route.required else "无"
        optional = f"；可选参数：{'、'.join(route.optional)}" if route.optional else ""
        approval = "【写库/开启采集类，产出走审批队列，不要反问确认】" if route.needs_approval else ""
        lines.append(f"- {route.name}：{route.description}；必填参数：{required}{optional}{approval}")
    return "\n".join(lines)


def _plan_messages(
    utterance: str,
    routes: Sequence[Route],
    limitations: Sequence[str],
    project_id: str | None = None,
    history: Sequence[dict[str, Any]] | None = None,
    read_only_tools: Sequence[str] = (),
) -> list[dict[str, Any]]:
    """拼规划用的消息。`limitations` 必须带上（契约 §2.2）：
    不带的话模型会假设平台有它没有的能力，然后按那个假设派活。

    `project_id` 同理：多项目之后触发型工具的入参普遍要求它，不告诉模型它就只能猜
    （实测：读查询猜中过 P001，而派活那枚漏传 `project_id` → 平台以
    `invalid_arguments` 拒收，路由对、活派不出去）。

    `history` 是**本会话此前轮次**的 user/assistant 对（2026-10-27 补）：规划相原先只
    看当前这一句，于是「先反问、再补一句」这条最常用的路径每轮都从零开始 —— 真机实测
    「帮我给李锐铭加个任务」→ 问周次；答「第 5 周，写实验报告」→ 又问负责人；
    答「负责人就是李锐铭」→ 干脆问「想执行哪个动作」。把上文给它，模型才能把回答并进
    同一动作的 args。**只用于拼请求，不进 `Plan`、不进检查点**（检查点只存循环状态）。"""
    system = [
        _ROLE,
        "",
        _TASK,
        "",
        "可用路由（**只有下表里的路由可用**；表外的路由一律不存在）：",
        _route_lines(routes),
        "",
        _OUTPUT_CONTRACT,
        "",
        _RULES,
    ]
    # 只读工具**不是路由**，但模型必须知道它们存在：否则「搜一下资料」会被答成
    # 「我这边没有搜资料这个能力」（2026-10-27 真机实测）。工具名从 /catalog 现取，
    # 不在这里硬编码 —— 新增只读工具只改 Java。
    if read_only_tools:
        system += [
            "",
            "只读工具（**不需要路由**：问问题、查资料时系统会在工具循环里自己调用它们）："
            + "、".join(read_only_tools),
        ]
    if limitations:
        system += ["", "平台能力边界（必须遵守，不得假设超出范围的能力）："]
        system += [f"- {item}" for item in limitations]
    if project_id:
        system += [
            "",
            f"当前项目:project_id = {project_id}。调用任何需要 project_id 的工具时都必须用它，"
            "不要另猜、也不要省略。",
        ]
    return [
        {"role": "system", "content": "\n".join(system)},
        # 历史纪要夹在 system 与当前 user 之间，形状与工具循环完全一致
        # （`loop._history_messages` 的产出直接复用）：模型不会把"历史"当成 system 指令，
        # 但能像读普通对话一样把上一轮的反问与回答接起来。
        *(history or ()),
        {"role": "user", "content": utterance},
    ]


# --------------------------------------------------------------------------
# 解析：模型那段文本 → 合格提案
# --------------------------------------------------------------------------


def _parse_proposal(content: Any) -> dict[str, Any]:
    """解析并**逐字段校验**模型输出，不合格一律收敛成 `ModelError("invalid_response")`。

    两条边界：

    - **绝不抛裸异常**：`ValueError` / `KeyError` / `TypeError` 穿透到 `main` 就是 500，
      而这个失败是可预期的上游行为，必须走既有错误语义（同 `model_client`）。
    - **错误信息里只有字段名，没有内容**：模型那段文本可能含用户原话，
      把它拼进异常文本就等于把它写进日志（契约 §2.7 的隐私约束）。
    """
    if not isinstance(content, str) or not content.strip():
        raise ModelError("invalid_response", "路由规划：模型没有返回 JSON 文本")

    try:
        data = json.loads(content)
    except ValueError:
        # 刻意**不**从文本里抠第一段花括号做"容错"：那会把"prompt 没约束住模型"这件事
        # 悄悄咽下去，而咽下去的代价是后续所有判断都建立在一段来路不明的 JSON 上。
        raise ModelError("invalid_response", "路由规划：模型返回的不是合法 JSON") from None

    if not isinstance(data, dict):
        raise ModelError("invalid_response", "路由规划：模型返回的 JSON 顶层不是对象")
    missing = [key for key in PLAN_KEYS if key not in data]
    if missing:
        raise ModelError("invalid_response", f"路由规划：JSON 缺少字段 {'、'.join(missing)}")

    intent = data["intent"]
    if not isinstance(intent, str) or not intent.strip():
        raise ModelError("invalid_response", "路由规划：字段 intent 必须是非空字符串")

    raw_route = data["route"]
    if raw_route is not None and (not isinstance(raw_route, str) or not raw_route.strip()):
        raise ModelError("invalid_response", "路由规划：字段 route 必须是非空字符串或 null")

    confidence = data["confidence"]
    # bool 是 int 的子类，得单独挡掉 —— `"confidence": true` 不该被当成 1.0
    if isinstance(confidence, bool) or not isinstance(confidence, (int, float)):
        raise ModelError("invalid_response", "路由规划：字段 confidence 必须是数字")
    confidence = float(confidence)
    if not 0.0 <= confidence <= 1.0:
        raise ModelError("invalid_response", "路由规划：字段 confidence 必须落在 0 到 1 之间")

    reason = data["reason"]
    if not isinstance(reason, str):
        raise ModelError("invalid_response", "路由规划：字段 reason 必须是字符串")

    args = data["args"]
    if not isinstance(args, dict):
        raise ModelError("invalid_response", "路由规划：字段 args 必须是对象")

    return {
        "intent": intent.strip(),
        "route": raw_route.strip() if isinstance(raw_route, str) else None,
        "confidence": confidence,
        "reason": reason.strip(),
        "args": dict(args),
        "clarify": _parse_clarify(data["clarify"]),
    }


def _parse_clarify(value: Any) -> dict[str, Any] | None:
    if value is None:
        return None
    if not isinstance(value, dict):
        raise ModelError("invalid_response", "路由规划：字段 clarify 必须是对象或 null")
    question = value.get("question")
    options = value.get("options")
    if not isinstance(question, str) or not question.strip():
        raise ModelError("invalid_response", "路由规划：clarify.question 必须是非空字符串")
    if not isinstance(options, list) or any(not isinstance(item, str) for item in options):
        raise ModelError("invalid_response", "路由规划：clarify.options 必须是字符串数组")
    return {"question": question.strip(), "options": [item.strip() for item in options if item.strip()]}


# --------------------------------------------------------------------------
# 判定：契约 §5.4 的四行规则（纯函数）
# --------------------------------------------------------------------------


def apply_rules(
    proposal: dict[str, Any],
    routes: Sequence[Route],
    *,
    threshold: float | None = None,
) -> Plan:
    """把契约 §5.4 的判定表机械地施加到模型提案上。**纯函数、无 IO、可单独测。**

    有序执行（自上而下，先命中先返回，与下面代码里的 1..5 一一对应）：

    1. 模型点名了一个**本轮不可用**的路由 → 反问 —— 它想派活，但平台上没有那枚工具；
    2. `confidence < 阈值` → 反问；
    3. 没有路由可派、而模型有把握 → **不反问**，交给工具循环自己作答（`route=null`）；
    4. 该路由的必填参数缺失 → 反问；
    5. 其余 → 派活（命中"写库/开启采集"类动作时**也不反问**，产出走审批队列）。

    **`route=null` 不等于反问**（第 3 行）：它只说明"这一轮没有子智能体可派"。这种回合有两种，
    由模型如实给出的 `confidence` 区分，Python 只做机械比对：

    - **用户是在问问题**（"现在有几个任务在推进？"）→ 模型有把握 → 交给工具循环，
      主管自己用只读工具查了作答 —— 这正是 S4′.2 那套循环的用武之地，
      也是需求 1「打字就能说话」的落点。把 `route=null` 一律当成反问，等于砍掉问答；
    - **用户要的是一个动作、而本轮没有对应工具**（"出个报告"，S8′ 之前没有报告工具）
      → 模型如实给低 confidence 与一句追问 → 被第 2 行拦下反问。
      它的判决不来自 Python 里任何一张写死的映射表 —— 那正是"没有任何工具可派生的诉求
      一律走反问"的落地方式。

    第 5 行的"写库/开启采集类不反问"落在本层的**两处**，都是照字面实现的：

    - **prompt 里**明说这类动作"不要反问确认"（`_route_lines` 的方括号标注 + `_RULES`），
      让模型别把"要不要确认"当成一个选项；
    - **代码里**：要不要反问只由上面几条规则决定，**模型自己提的 `clarify` 不构成反问的
      理由** —— 所以这类动作不会因为"模型想确认一下"而被拦住。它的产出进审批队列，
      确认由人在队列里做（v1.1 §3.3「写库 + 开启采集：要批准」）。

    两处**刻意不**做（契约 §9 的"先讲清楚再动手"）：

    - 阈值那一条**不**给审批类动作开后门。有序表的读法是"先命中先返回"，
      `confidence` 低意味着连意图都没听准 —— 这时候派出去的是一个**错误的** run，
      而审批队列里多一个错 run 的代价是人的一次无效评审。所以照反问。
      （§5.4 自己的反问示例正是 `route=null` + `confidence=0.41`。）
    - 第 4 行对审批类**同样适用**：缺必填参数还硬派，等于调一枚注定
      `invalid_tool_arguments` 的工具、往队列里塞一个必然失败的 run。
      第 5 行要的是"不为确认而反问"，不是"缺参数也硬派"。
    """
    limit = CLARIFY_CONFIDENCE if threshold is None else threshold
    intent = str(proposal["intent"])
    confidence = float(proposal["confidence"])
    reason = str(proposal["reason"])
    args = dict(proposal["args"])
    proposed = proposal.get("route")
    route = find_route(routes, proposed if isinstance(proposed, str) else None)

    # 1) 点名了一个本轮不可用的路由：想派活，但平台上没有那枚工具 —— 认不出来就问
    if route is None and isinstance(proposed, str) and proposed:
        return _ask(
            intent,
            confidence,
            reason,
            proposal,
            routes,
            f"模型给的路由 {proposed} 不在本轮可用路由表里",
        )

    # 2) 阈值：连意图都没听准 → 问
    if confidence < limit:
        return _ask(
            intent,
            confidence,
            reason,
            proposal,
            routes,
            f"confidence {confidence:.2f} 低于阈值 {limit:.2f}",
        )

    # 3) 没有路由可派（模型有把握）：这一轮不需要子智能体 —— 交给工具循环自己作答。
    #    反问是第 1/2 行的事，这里不是。
    if route is None:
        return Plan(
            intent=intent,
            route=None,
            confidence=confidence,
            reason=reason,
            args={},
            clarify=None,
        )

    # 4) 必填参数缺失（即使 confidence 很高）→ 问，且要把缺的参数名问出来
    missing = [name for name in route.required if _blank(args.get(name))]
    if missing:
        return _ask(
            intent,
            confidence,
            reason,
            proposal,
            routes,
            f"必填参数缺失：{'、'.join(missing)}",
            route=route,
            missing=missing,
        )

    # 5) 派活。写库/开启采集类**不为确认而反问**（契约 §5.4 第三行）：模型自己提的
    #    `clarify`（"要不要确认一下？"）到这一步已经被丢掉 —— 产出走审批队列，确认在队列里做。
    return Plan(
        intent=intent,
        route=route.name,
        confidence=confidence,
        reason=reason,
        args=args,
        clarify=None,
    )


def _ask(
    intent: str,
    confidence: float,
    reason: str,
    proposal: dict[str, Any],
    routes: Sequence[Route],
    note: str,
    *,
    route: Route | None = None,
    missing: Sequence[str] = (),
) -> Plan:
    """组装一个反问的 `Plan`：`route=null`，`clarify` 必须有内容。

    `clarify` 优先用模型给的那一问（它更贴语境）；模型没给（或只给了一半）时用
    机械兜底 —— 兜底那问会把**缺失的参数名**说出来，这正是用户下一步要补的东西。
    """
    model_clarify = proposal.get("clarify")
    question = ""
    options: list[str] = []
    if isinstance(model_clarify, dict):
        question = str(model_clarify.get("question") or "")
        options = [str(item) for item in model_clarify.get("options") or []]
    if not question:
        question = _fallback_question(route=route, missing=missing, routes=routes)
    if not options:
        options = [_route_label(item) for item in routes[:4]]

    # 模型的原话是"为什么这么选"，系统这一句是"为什么改成问" —— 两句都留着，
    # 否则 S7 复盘时只看到一次莫名的反问，看不出是模型想派还是参数不全。
    merged = f"{reason}；系统判定反问：{note}" if reason else f"系统判定反问：{note}"
    return Plan(
        intent=intent,
        route=None,
        confidence=confidence,
        reason=merged,
        args={},
        clarify={"question": question, "options": options},
    )


def _fallback_question(
    *, route: Route | None, missing: Sequence[str], routes: Sequence[Route]
) -> str:
    if missing and route is not None:
        return f"要执行「{route.name}」还缺这些必填参数：{'、'.join(missing)}，请补充。"
    if routes:
        return "我不确定你想执行哪一个动作，请选一个："
    return "平台当前没有可派发的动作（触发型工具均不可用），请换个说法，或联系管理员。"


def _blank(value: Any) -> bool:
    """必填参数算不算"没给"。`0` / `False` 是**给了值**，只有 `None` 与空白字符串算缺。"""
    return value is None or (isinstance(value, str) and not value.strip())


# --------------------------------------------------------------------------
# 入口
# --------------------------------------------------------------------------


async def plan(
    utterance: str,
    user_jwt: str,
    *,
    project_id: str | None = None,
    model: ModelProtocol | None = None,
    catalog: Catalog | None = None,
    client: JavaClient | None = None,
    history: Sequence[dict[str, Any]] | None = None,
) -> Plan:
    """把一句自然语言路由成 `Plan`（契约 §5.4）。

    判定规则（**在 `apply_rules()` 里，不在 prompt 里**，逐行说明见该函数）：路由不可用 /
    `confidence < CLARIFY_CONFIDENCE`（默认 0.6）或必填参数缺失 →
    反问（`route=null`，填 `clarify`）；命中"写库/开启采集"类动作 → 不反问，
    直接派活并告知用户（产出走审批队列）；`route=null` 且模型有把握 →
    这一轮不需要子智能体，交给工具循环自己用只读工具作答；其余 → 派活。

    `model` 省略时按本层配置起一个真 `ModelClient`（没配密钥就是
    `not_configured`，如实失败）。

    `catalog` 省略时用 `client` 从 `/api/agents/catalog` 现拉 —— `user_jwt` 就是那次
    拉取的凭据（**权限永远来自 JWT**，本层不解析它、不落它）。`catalog` 是对契约签名的
    一处**增补**：调用方（`main` 的规划相）同一个 run 里只拉一次清单，把已经拿到的那份
    传进来，就省掉一次重复往返。

    `history` 是本会话此前轮次（`loop.plan_history_messages` 现拼的 user/assistant 对）。
    省略即无上文，行为与旧版逐字一致 —— 老调用方与老测试不受影响。

    失败一律是 `ModelError` / `PlatformError`（不抛裸异常），且**没有任何"兜底路由"**：
    没配模型密钥就是 `not_configured` 如实上报，绝不用关键词硬凑一个路由或 confidence
    去骗过阈值（那正是本仓最反感的静默降级）。**没有任何工具可派生的诉求一律走反问** ——
    本模块里不存在也不需要一张写死的路由映射表。
    """
    if catalog is None:
        if client is None:
            raise ValueError("plan() 需要 catalog 或 client 之一：可用路由只能从 /catalog 取")
        catalog = await client.catalog(user_jwt)

    routes = routes_from_catalog(catalog)
    messages = _plan_messages(
        utterance,
        routes,
        catalog.limitations,
        project_id=project_id,
        history=history,
        read_only_tools=[tool.name for tool in catalog.tools if tool.read_only],
    )
    resolved = model if model is not None else ModelClient()
    # `final=True` 要的是**结论**那一发：真实 `ModelClient` 会据此带上
    # `response_format=json_object` 并去掉 tools（见 model_client.complete）。
    response, _usage = await resolved.complete(messages, [], final=True)
    proposal = _parse_proposal(response.get("content"))
    proposal, meeting_context = await _resolve_meeting_step(
        proposal,
        utterance,
        user_jwt=user_jwt,
        project_id=project_id,
        client=client,
        catalog=catalog,
    )
    decision = apply_rules(proposal, routes)
    return _refine_meeting_clarify(decision, meeting_context)
