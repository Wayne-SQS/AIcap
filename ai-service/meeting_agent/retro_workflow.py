"""Read-only Retro graph. Caller must authorize meeting and analysis first."""
from langgraph.graph import END, START, StateGraph
from langsmith import tracing_context

from .contracts import TranscriptSegment
from .retro_contracts import SprintRetroInput, SprintRetroOutput, validate_retro_result
from .retro_skill import SprintRetroSkill
from .member_tool import MemberReadTool
from .workflow import CompletionModel, MeetingState, WorkflowError


class SprintRetroWorkflow:
    def __init__(self, members: MemberReadTool, model: CompletionModel):
        self._members = members
        self._model = model
        self._skill = SprintRetroSkill()

    def run(self, *, meeting_id: str, transcript: str, access_token: str,
            current_sprint: int | None = None) -> SprintRetroOutput:
        # Token stays in the invocation closure; no checkpoint, tracing or writes.
        def load_context(state: MeetingState):
            try:
                if not isinstance(transcript, str) or not transcript.strip() or len(transcript) > 16000:
                    raise ValueError('invalid transcript')
                context = SprintRetroInput(meeting_id=meeting_id, current_sprint=current_sprint,
                    transcript_segments=[TranscriptSegment(segment_id=f'S{i}', text=line)
                        for i, line in enumerate(transcript.splitlines(), 1) if line.strip()], members=[])
            except ValueError:
                raise WorkflowError('invalid_meeting_input') from None
            profiles = self._members.get_member_profiles(access_token=access_token)
            try:
                context = SprintRetroInput.model_validate({**context.model_dump(), 'members': [{'user_id': m.user_id, 'display_name': m.display_name} for m in profiles]})
            except ValueError:
                raise WorkflowError('invalid_retro_context') from None
            return {'context': context.model_dump()}

        def analyze(state: MeetingState):
            try:
                messages = self._skill.messages(SprintRetroInput.model_validate(state['context']))
            except ValueError:
                raise WorkflowError('invalid_retro_context') from None
            return {'candidate': self._model.complete(messages)}

        def validate(state: MeetingState):
            try:
                result = validate_retro_result(SprintRetroInput.model_validate(state['context']), state['candidate'])
            except ValueError:
                raise WorkflowError('invalid_model_proposal') from None
            return {'result': result.model_dump()}

        builder = StateGraph(MeetingState)
        for name, node in (('load_context', load_context), ('analyze', analyze), ('validate', validate)):
            builder.add_node(name, node)
        for source, target in ((START, 'load_context'), ('load_context', 'analyze'),
                               ('analyze', 'validate'), ('validate', END)):
            builder.add_edge(source, target)
        with tracing_context(enabled=False):
            state = builder.compile().invoke({}, config={'recursion_limit': 5})
        return SprintRetroOutput.model_validate(state['result'])
