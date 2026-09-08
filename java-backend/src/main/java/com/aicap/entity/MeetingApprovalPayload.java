package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 审核批准的变更载荷(对齐 FastAPI models.MeetingApprovalPayload;suggestion_id 为 PK) */
@Data
@TableName("meeting_approval_payloads")
public class MeetingApprovalPayload {
    @TableId(type = IdType.INPUT)
    private String suggestionId;
    private String changesJson;
}
