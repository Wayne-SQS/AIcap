"""Text -> project snapshot -> Daily Skill -> validated proposals, without writes."""

from .contracts import DailyScrumInput, DailyScrumOutput, TranscriptSegment, validate_daily_result
from .daily_skill import DailyScrumSkill
from .graph_runtime import (CompletionModel, MeetingState, WorkflowError, run_linear_graph,
                            validate_candidate)
from .story_tool import StoryReadTool


class DailyScrumWorkflow:
    def __init__(self, story_tool: StoryReadTool, model: CompletionModel):
        self._stories = story_tool
        self._model = model
        self._skill = DailyScrumSkill()

    def run(self, *, meeting_id: str, transcript: str, access_token: str,
            current_sprint: int | None = None) -> DailyScrumOutput:
        """Caller must authorize the meeting and analysis before invoking.

        Token is scoped to this call's closure, never graph state or LLM input.
        No checkpoint/tracing is enabled until a persistence policy is implemented.
        """
        def normalize(state: MeetingState):
            try:
                text = state["transcript"]
                if not isinstance(text, str) or not text.strip() or len(text) > 16000:
                    raise ValueError("invalid transcript")
                segments = [TranscriptSegment(segment_id=f"S{i}", text=line)
                            for i, line in enumerate(text.splitlines(), 1) if line.strip()]
                context = DailyScrumInput(meeting_id=state["meeting_id"],
                                          current_sprint=state["current_sprint"],
                                          transcript_segments=segments, stories=[])
            except ValueError:
                raise WorkflowError("invalid_meeting_input") from None
            return {"context": context.model_dump()}

        def load_context(state: MeetingState):
            context = DailyScrumInput.model_validate(state["context"])
            loaded = self._stories.load_daily_context(
                access_token=access_token, meeting_id=context.meeting_id,
                transcript_segments=context.transcript_segments, current_sprint=context.current_sprint,
            )
            return {"context": loaded.model_dump()}

        def analyze(state: MeetingState):
            context = DailyScrumInput.model_validate(state["context"])
            return {"candidate": self._model.complete(self._skill.messages(context))}

        def validate(state: MeetingState):
            return validate_candidate(validate_daily_result, DailyScrumInput, state)

        state = run_linear_graph(
            MeetingState,
            (("normalize", normalize), ("load_context", load_context),
             ("analyze", analyze), ("validate", validate)),
            payload={"meeting_id": meeting_id, "transcript": transcript,
                     "current_sprint": current_sprint},
        )
        return DailyScrumOutput.model_validate(state["result"])
