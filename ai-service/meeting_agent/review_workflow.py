"""Read-only Review graph. Caller must authorize meeting and analysis first."""
from langgraph.graph import END, START, StateGraph
from langsmith import tracing_context

from .contracts import TranscriptSegment
from .review_contracts import SprintReviewInput, SprintReviewOutput, validate_review_result
from .review_skill import SprintReviewSkill
from .story_tool import StoryReadTool
from .workflow import CompletionModel, MeetingState, WorkflowError


class SprintReviewWorkflow:
    def __init__(self, stories: StoryReadTool, model: CompletionModel):
        self._stories = stories
        self._model = model
        self._skill = SprintReviewSkill()

    def run(self, *, meeting_id: str, transcript: str, access_token: str,
            current_sprint: int | None = None) -> SprintReviewOutput:
        # Token stays in the invocation closure; no checkpoint, tracing or writes.
        def load_context(state: MeetingState):
            try:
                if not isinstance(transcript, str) or not transcript.strip() or len(transcript) > 16000:
                    raise ValueError('invalid transcript')
                context = SprintReviewInput(meeting_id=meeting_id, current_sprint=current_sprint,
                    transcript_segments=[TranscriptSegment(segment_id=f'S{i}', text=line)
                        for i, line in enumerate(transcript.splitlines(), 1) if line.strip()], stories=[])
            except ValueError:
                raise WorkflowError('invalid_meeting_input') from None
            stories = self._stories.get_review_stories(access_token=access_token)
            try:
                context = SprintReviewInput.model_validate({**context.model_dump(), 'stories': stories})
            except ValueError:
                raise WorkflowError('invalid_review_context') from None
            return {'context': context.model_dump()}

        def analyze(state: MeetingState):
            try:
                messages = self._skill.messages(SprintReviewInput.model_validate(state['context']))
            except ValueError:
                raise WorkflowError('invalid_review_context') from None
            return {'candidate': self._model.complete(messages)}

        def validate(state: MeetingState):
            try:
                result = validate_review_result(SprintReviewInput.model_validate(state['context']), state['candidate'])
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
        return SprintReviewOutput.model_validate(state['result'])
