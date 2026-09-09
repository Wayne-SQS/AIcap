package com.aicap.dto;

import com.aicap.entity.Story;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 故事/看板域 DTO(字段名对齐 FastAPI schemas:snake_case) */
public final class StoryDtos {

    private StoryDtos() {
    }

    /** POST /api/stories 请求体(StoryIn;校验对齐 pydantic:title 必填≤200,priority 枚举,sprint/activity/status 范围) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoryIn(@NotNull @Size(max = 200) String title,
                          String description,
                          String acceptance,
                          @Pattern(regexp = "^(Must|Should|Could)$", message = "priority 必须是 Must/Should/Could")
                          String priority,
                          @NotNull @Min(1) @Max(3) Integer sprint,
                          @NotNull @Min(1) @Max(5) Integer activity,
                          @NotNull @Min(0) @Max(2) Integer status,
                          @JsonProperty("owner_id") Integer ownerId) {
        public StoryIn {
            if (description == null) description = "";
            if (acceptance == null) acceptance = "";
            if (priority == null) priority = "Must";
            if (sprint == null) sprint = 1;
            if (activity == null) activity = 2;
            if (status == null) status = 0;
        }
    }

    /** PATCH /api/stories/{id} 请求体(StoryPatch:字段可空,null 表示不更新;owner_id 传 null 也视为未提供) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoryPatch(@Size(max = 200) String title,
                             String description,
                             String acceptance,
                             @Pattern(regexp = "^(Must|Should|Could)$", message = "priority 必须是 Must/Should/Could")
                             String priority,
                             @Min(1) @Max(3) Integer sprint,
                             @Min(1) @Max(5) Integer activity,
                             @Min(0) @Max(2) Integer status,
                             @JsonProperty("owner_id") Integer ownerId) {
    }

    /** 故事输出(StoryOut) */
    public record StoryOut(String id, String title, String description, String acceptance,
                           String priority, Integer sprint, Integer activity, Integer status,
                           @JsonProperty("owner_id") Integer ownerId) {
    }

    /** 日志输出(LogOut) */
    public record LogOut(Integer id, @JsonProperty("story_id") String storyId,
                         @JsonProperty("log_type") String logType, String detail,
                         java.time.LocalDateTime createdAt) {
    }

    /** 删除响应 */
    public record DeleteOut(Boolean ok, String undone, Integer affected) {
    }

    public static StoryOut toOut(Story s) {
        return new StoryOut(s.getId(), s.getTitle(), s.getDescription(), s.getAcceptance(),
                s.getPriority(), s.getSprint(), s.getActivity(), s.getStatus(), s.getOwnerId());
    }

    public static List<StoryOut> toOutList(List<Story> list) {
        return list.stream().map(StoryDtos::toOut).toList();
    }
}
