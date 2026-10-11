"""Read-only Planning graph: context -> model -> validated pending proposals."""
from typing import TypedDict

from langgraph.graph import END, START, StateGraph
from langsmith import tracing_context

from .planning_context import PlanningContextReader, SprintPlanningInput
from .planning_contracts import SprintPlanningOutput, validate_planning_result
from .planning_skill import SprintPlanningSkill
from .workflow import CompletionModel, WorkflowError


class PlanningState(TypedDict, total=False):
    context: dict
    candidate: dict
    result: dict


class SprintPlanningWorkflow:
    def __init__(self, context_reader: PlanningContextReader, model: CompletionModel):
        self._reader = context_reader
        self._model = model
        self._skill = SprintPlanningSkill()

    def run(self, *, meeting_id: str, transcript: str, access_token: str,
            current_sprint: int | None = None, target_sprint: int | None = None) -> SprintPlanningOutput:
        """Caller authorizes meeting/analysis first. No persistence or execution.

        Token stays in this invocation's closure, outside graph state and model
        input. No tracing/checkpoints, automatic retries or partial results.
        Read-tool and provider errors propagate as their existing stable codes.
        """
        def load_context(state: PlanningState):
            context = self._reader.load(meeting_id=meeting_id, transcript=transcript,
                access_token=access_token, current_sprint=current_sprint, target_sprint=target_sprint)
            return {'context': context.model_dump(warnings=False)}

        def analyze(state: PlanningState):
            try:
                messages = self._skill.messages(SprintPlanningInput.model_validate(state['context']))
            except ValueError:
                raise WorkflowError('invalid_planning_context') from None
            return {'candidate': self._model.complete(messages)}

        def validate(state: PlanningState):
            try:
                result = validate_planning_result(
                    SprintPlanningInput.model_validate(state['context']), state['candidate'])
            except ValueError:
                raise WorkflowError('invalid_model_proposal') from None
            return {'result': result.model_dump()}

        builder = StateGraph(PlanningState)
        for name, node in (('load_context', load_context), ('analyze', analyze), ('validate', validate)):
            builder.add_node(name, node)
        for source, target in ((START, 'load_context'), ('load_context', 'analyze'),
                               ('analyze', 'validate'), ('validate', END)):
            builder.add_edge(source, target)
        graph = builder.compile()
        with tracing_context(enabled=False):
            state = graph.invoke({}, config={'recursion_limit': 5})
        return SprintPlanningOutput.model_validate(state['result'])
