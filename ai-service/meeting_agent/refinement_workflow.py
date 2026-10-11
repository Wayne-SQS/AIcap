"""Read-only Refinement graph. Caller must authorize meeting and analysis first."""
from .contracts import TranscriptSegment
from .graph_runtime import (CompletionModel, MeetingState, WorkflowError, run_linear_graph,
                            validate_candidate)
from .refinement_contracts import BacklogRefinementInput, BacklogRefinementOutput, validate_refinement_result
from .refinement_skill import BacklogRefinementSkill
from .story_tool import StoryReadTool


class BacklogRefinementWorkflow:
    def __init__(self, stories: StoryReadTool, model: CompletionModel):
        self._stories = stories
        self._model = model
        self._skill = BacklogRefinementSkill()

    def run(self, *, meeting_id: str, transcript: str, access_token: str,
            current_sprint: int | None = None) -> BacklogRefinementOutput:
        # Token stays in the invocation closure; no checkpoint, tracing or writes.
        def load_context(state: MeetingState):
            try:
                if not isinstance(transcript, str) or not transcript.strip() or len(transcript) > 16000:
                    raise ValueError('invalid transcript')
                context = BacklogRefinementInput(meeting_id=meeting_id, current_sprint=current_sprint,
                    transcript_segments=[TranscriptSegment(segment_id=f'S{i}', text=line)
                        for i, line in enumerate(transcript.splitlines(), 1) if line.strip()], stories=[])
            except ValueError:
                raise WorkflowError('invalid_meeting_input') from None
            stories = self._stories.get_refinement_stories(access_token=access_token)
            try:
                context = BacklogRefinementInput.model_validate({**context.model_dump(), 'stories': stories})
            except ValueError:
                raise WorkflowError('invalid_refinement_context') from None
            return {'context': context.model_dump()}

        def analyze(state: MeetingState):
            try:
                messages = self._skill.messages(BacklogRefinementInput.model_validate(state['context']))
            except ValueError:
                raise WorkflowError('invalid_refinement_context') from None
            return {'candidate': self._model.complete(messages)}

        def validate(state: MeetingState):
            return validate_candidate(validate_refinement_result, BacklogRefinementInput, state)

        state = run_linear_graph(
            MeetingState,
            (('load_context', load_context), ('analyze', analyze), ('validate', validate)),
            payload={},
        )
        return BacklogRefinementOutput.model_validate(state['result'])
