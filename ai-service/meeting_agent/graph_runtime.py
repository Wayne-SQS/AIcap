"""Shared skeleton for the five meeting workflows.

Daily / planning / review / retro / refinement were five verbatim copies of the
same linear graph: a node list wired start-to-end, compiled, invoked with
tracing off. The shape lives here once, so a sixth entry point adds nodes
instead of copying the runtime.

This is deliberately *not* LangGraph's checkpointer/interrupt. State exists for
one invocation only and no meeting content is persisted; see ai-service/README
for why enabling checkpointing would defeat that.
"""

from typing import Protocol, TypedDict

from langgraph.graph import END, START, StateGraph
from langsmith import tracing_context
from pydantic import ValidationError

from .contracts import ResultContractError


class CompletionModel(Protocol):
    def complete(self, messages: list[dict[str, str]]) -> dict: ...


class WorkflowError(Exception):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


class MeetingState(TypedDict, total=False):
    meeting_id: str
    transcript: str
    current_sprint: int | None
    context: dict
    candidate: dict
    result: dict


def run_linear_graph(state_class, nodes, *, payload):
    """Compile and run a start-to-END graph over ordered ``(name, node)`` pairs.

    Linear-only by construction: one super-step per node, plus START and END, so
    the recursion limit is derived rather than repeated per workflow. A graph
    that later grows a branch or a loop must set its own limit.
    """
    builder = StateGraph(state_class)
    for name, node in nodes:
        builder.add_node(name, node)
    path = [START, *(name for name, _ in nodes), END]
    for source, target in zip(path, path[1:]):
        builder.add_edge(source, target)
    with tracing_context(enabled=False):
        return builder.compile().invoke(payload, config={"recursion_limit": len(nodes) + 2})


def validate_candidate(validate_result, input_class, state):
    """Run a contract validator, then map failure to a stable WorkflowError code.

    ``ResultContractError`` carries a precise diagnostic (quote_mismatch,
    snapshot_mismatch, ...) but subclasses ``ValueError``, so it must be caught
    *first*: otherwise every contract failure collapses into
    invalid_model_proposal and the caller cannot tell a forged quote from a
    malformed payload.
    """
    try:
        result = validate_result(input_class.model_validate(state["context"]), state["candidate"])
    except ResultContractError as exc:
        raise WorkflowError(exc.code) from exc
    except (ValueError, ValidationError):
        raise WorkflowError("invalid_model_proposal") from None
    return {"result": result.model_dump()}
