package com.aicap.dto;

import com.aicap.entity.PoolItem;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 需求池 DTO(字段名对齐 FastAPI schemas:snake_case) */
public final class PoolDtos {

    private PoolDtos() {
    }

    /** POST /api/pool 请求体(PoolIn:title 必填 ≤200,priority 枚举;对齐 FastAPI PoolIn) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PoolIn(@NotNull @Size(max = 200) String title,
                         @Size(max = 10000) String description,
                         @Size(max = 200) String source,
                         @Pattern(regexp = "^(Must|Should|Could)$", message = "priority 必须是 Must/Should/Could")
                         String priority) {
        public PoolIn {
            if (description == null) description = "";
            if (source == null) source = "";
            if (priority == null) priority = "Could";
        }
    }

    /** 需求池条目输出(PoolOut) */
    public record PoolOut(String id, String title, String description, String source, String priority) {
    }

    /** 删除响应 */
    public record DeleteOut(Boolean ok) {
    }

    /** POST /api/pool/{id}/promote 请求体(PoolPromoteIn;sprint 必填 1..3,owner_id 可空,activity 1..5) */
    public record PoolPromoteIn(@NotNull @Min(1) @Max(3) Integer sprint,
                                @JsonProperty("owner_id") Integer ownerId,
                                @NotNull @Min(1) @Max(5) Integer activity) {
        public PoolPromoteIn {
            if (activity == null) activity = 2;
        }
    }

    public static PoolOut toOut(PoolItem p) {
        return new PoolOut(p.getId(), p.getTitle(), p.getDescription(), p.getSource(), p.getPriority());
    }

    public static List<PoolOut> toOutList(List<PoolItem> list) {
        return list.stream().map(PoolDtos::toOut).toList();
    }
}
