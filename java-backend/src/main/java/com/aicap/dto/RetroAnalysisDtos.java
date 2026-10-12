package com.aicap.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Retrospective candidates only; null owner/deadline are explicit unknowns. */
public final class RetroAnalysisDtos {
    private RetroAnalysisDtos() {}
    public record Evidence(@NotBlank @Size(max=80) @JsonProperty("segment_id") String segmentId,
                           @NotBlank @Size(max=2000) String quote) {}
    public record Decision(@NotBlank @Size(max=1000) String text,
                           @NotNull @Size(min=1,max=10) List<@NotNull @Valid Evidence> evidence) {}
    public record Changes(@NotBlank @Size(max=200) String title,
                          @NotNull @Size(max=2000) String description,
                          @Min(1) @JsonProperty(value="owner_id",required=true) Integer ownerId,
                          @Size(max=200) @Pattern(regexp="(?s).*\\S.*")
                          @JsonProperty(value="deadline_text",required=true) String deadlineText) {}
    public record Proposal(@NotBlank @Size(max=80) @JsonProperty("proposal_id") String proposalId,
                           @NotNull @Pattern(regexp="create_action_item") String action,
                           @NotNull @Valid Changes changes,
                           @NotBlank @Size(max=1000) String reason,
                           @NotNull @Size(min=1,max=10) List<@NotNull @Valid Evidence> evidence) {}
    public record Result(@NotNull @Pattern(regexp="1\\.0") @JsonProperty("schema_version") String schemaVersion,
                         @NotBlank @Size(max=36) @JsonProperty("meeting_id") String meetingId,
                         @NotNull @Pattern(regexp="sprint_retrospective") @JsonProperty("meeting_type") String meetingType,
                         @NotBlank @Size(max=2000) String summary,
                         @NotNull @Size(max=20) List<@NotNull @Valid Decision> decisions,
                         @NotNull @Size(max=20) @JsonProperty("proposed_actions") List<@NotNull @Valid Proposal> proposedActions,
                         @NotNull @Size(max=20) @JsonProperty("open_questions") List<@NotBlank @Size(max=500) String> openQuestions) {}
    public record Submit(@NotNull @Pattern(regexp="[a-z0-9-]{1,80}") @JsonProperty("client_request_id") String clientRequestId,
                         @NotNull @Valid Result result) {}
}
