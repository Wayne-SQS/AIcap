package com.aicap.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
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

    /**
     * PATCH /api/members/{userId}/capacity 请求体(US27「成员每周可用容量的设置、保存和读取」)。
     *
     * <p>刻意用 {@link JsonNode} 而不是 {@code Integer}:必须区分**「字段缺失」与「显式 null」**——
     * 用 {@code Integer} 时两者在 Jackson 里都是 null,没法表达"只读不写"和"清空为未配置"的区别。
     * <ul>
     *   <li>字段缺失 → 422(这个端点的唯一职责就是设置容量,漏传视为调用错误)</li>
     *   <li>{@code "capacity_hours": null} → 清为「未配置」(与 0 是两种状态)</li>
     *   <li>{@code "capacity_hours": 0} 及以上 → 确认为该值</li>
     * </ul>
     */
    public record CapacityIn(@JsonProperty("capacity_hours") @JsonAlias("capacityHours") JsonNode capacityHours) {
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
