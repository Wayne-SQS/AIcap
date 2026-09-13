package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 成员用户画像(1:1 users)。
 * 技术栈/工作能力/流程领域以 JSON 数组文本落库,元素形如 {"name":"Java","level":5}。
 */
@Data
@TableName("member_profiles")
public class MemberProfile {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer userId;
    /** 岗位/画像标题,如「技术负责人」 */
    private String title;
    /** JSON 数组:熟悉的技术栈 */
    private String techStack;
    /** JSON 数组:工作能力 */
    private String capabilities;
    /** JSON 数组:熟悉的开发流程领域 */
    private String processDomains;
    /** 画像摘要(一句话) */
    private String summary;
    /** 项目经验年限 */
    private Integer yearsExperience;
    private Integer updatedBy;
    private LocalDateTime updatedAt;
}
