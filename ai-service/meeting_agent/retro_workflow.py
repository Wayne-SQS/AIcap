"""Read-only Retro graph. Caller must authorize meeting and analysis first."""
from .contracts import TranscriptSegment
from .graph_runtime import (CompletionModel, MeetingState, WorkflowError, run_linear_graph,
                            validate_candidate)
from .member_tool import MemberReadTool
from .retro_contracts import SprintRetroInput, SprintRetroOutput, validate_retro_result
from .retro_skill import SprintRetroSkill


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
            return validate_candidate(validate_retro_result, SprintRetroInput, state)

        state = run_linear_graph(
            MeetingState,
            (('load_context', load_context), ('analyze', analyze), ('validate', validate)),
            payload={},
        )
        return SprintRetroOutput.model_validate(state['result'])
