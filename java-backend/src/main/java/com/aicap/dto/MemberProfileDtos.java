package com.aicap.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 成员画像 DTO(输出对齐项目既有 snake_case 契约)。
 * ProfileIn 为整体替换语义:三个维度必须给出(可为空数组),未知字段由全局严格 JSON 配置拒绝(422)。
 */
public final class MemberProfileDtos {

    private MemberProfileDtos() {
    }

    /** 技术栈 / 能力 / 领域 的单项:level 1=了解 … 5=精通 */
    public record SkillItem(@NotBlank @Size(max = 50) String name,
                            @NotNull @Min(1) @Max(5) Integer level) {
    }

    /** PATCH /api/members/{userId}/profile 请求体(契约 snake_case;同时容忍 camelCase 别名) */
    public record ProfileIn(@Size(max = 50) String title,
                            @NotNull @Size(max = 20) @JsonProperty("tech_stack") @JsonAlias("techStack")
                            List<@Valid SkillItem> techStack,
                            @NotNull @Size(max = 20) @JsonAlias("capabilities")
                            List<@Valid SkillItem> capabilities,
                            @NotNull @Size(max = 20) @JsonProperty("process_domains") @JsonAlias("processDomains")
                            List<@Valid SkillItem> processDomains,
                            @Size(max = 500) String summary,
                            @Min(0) @Max(50) @JsonProperty("years_experience") @JsonAlias("yearsExperience")
                            Integer yearsExperience) {
        public ProfileIn {
            if (title == null) title = "";
            if (summary == null) summary = "";
            if (yearsExperience == null) yearsExperience = 0;
            // 三个维度不做缺省兜底:缺字段必须 422,避免"漏传即清空画像"
        }
    }

    /** GET /api/members/profiles 单项输出(含 users 的基础字段) */
    public record ProfileOut(@JsonProperty("user_id") Integer userId,
                             String username,
                             @JsonProperty("display_name") String displayName,
                             String role,
                             String color,
                             @JsonProperty("capacity_hours") Integer capacityHours,
                             String title,
                             @JsonProperty("tech_stack") List<SkillItem> techStack,
                             List<SkillItem> capabilities,
                             @JsonProperty("process_domains") List<SkillItem> processDomains,
                             String summary,
                             @JsonProperty("years_experience") Integer yearsExperience,
                             @JsonProperty("updated_at") LocalDateTime updatedAt) {
    }
}
