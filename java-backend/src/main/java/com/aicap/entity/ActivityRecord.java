package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 成员提交活动事实记录(commit/PR/Review/缺陷修复等)。
 * 画像智能体的核心数据源;task_id 可空——未关联任务的活动本身就是文档 4.6 的「提交异常」证据。
 */
@Data
@TableName("activity_records")
public class ActivityRecord {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer userId;
    /** 关联任务(Txx);null=未关联任务 */
    private String taskId;
    /** commit/pr/review/bugfix/task_done/note */
    private String activityType;
    /** 活动标题,如「修复登录接口 401」 */
    private String title;
    /** 证据细节 */
    private String detail;
    /** 所属模块,如 权限/前端 */
    private String module;
    /** manual=手动录入 / import=批量导入 / github=GitHub 同步 */
    private String source;
    private LocalDateTime happenedAt;
    /** GitHub 事件唯一ID(commit sha/PR号/review id/issue号),同步幂等去重 */
    private String githubEventId;
    private Integer createdBy;
    private LocalDateTime createdAt;
}
