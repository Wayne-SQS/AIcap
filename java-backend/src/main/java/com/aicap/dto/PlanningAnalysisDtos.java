package com.aicap.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** The existing Python SprintPlanningOutput wire contract, validated again on save. */
public final class PlanningAnalysisDtos {
    private PlanningAnalysisDtos() {}

    public record Sprint(@NotNull @Min(1) @Max(4) Integer sprint) {}
    public record Evidence(@NotBlank @Size(max=80) @JsonProperty("segment_id") String segmentId,
                           @NotBlank @Size(max=2000) String quote) {}
    public record Proposal(@NotBlank @Size(max=80) @JsonProperty("proposal_id") String proposalId,
                           @NotNull @Pattern(regexp="update_story_sprint") String action,
                           @NotNull @Pattern(regexp="US[0-9]+") @Size(max=10) @JsonProperty("story_id") String storyId,
                           @NotNull @Valid Sprint expected, @NotNull @Valid Sprint changes,
                           @NotBlank @Size(max=1000) String reason,
                           @NotNull @Size(min=1,max=10) List<@NotNull @Valid Evidence> evidence) {}
    public record Result(@NotNull @Pattern(regexp="1\\.0") @JsonProperty("schema_version") String schemaVersion,
                         @NotBlank @Size(max=36) @JsonProperty("meeting_id") String meetingId,
                         @NotNull @Pattern(regexp="sprint_planning") @JsonProperty("meeting_type") String meetingType,
                         @NotBlank @Size(max=2000) String summary,
                         @NotNull @Size(max=20) @JsonProperty("proposed_actions") List<@NotNull @Valid Proposal> proposedActions,
                         @NotNull @Size(max=20) @JsonProperty("open_questions") List<@NotBlank @Size(max=500) String> openQuestions) {}
    public record Submit(@NotNull @Pattern(regexp="[a-z0-9-]{1,80}") @JsonProperty("client_request_id") String clientRequestId,
                         @NotNull @Valid Result result) {}
}

