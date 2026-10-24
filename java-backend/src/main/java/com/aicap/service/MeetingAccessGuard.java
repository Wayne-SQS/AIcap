package com.aicap.service;

import com.aicap.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 会议入口的项目权限判定:{@code meetingId → meetings.project_id → 项目成员/写权限}。
 *
 * <p>AI 分析接口只拿得到 meetingId(ai-service 手里没有 projectId)。若权限口径要求调用方
 * 自带 projectId,越权者只要填一个自己所属的项目就能绕过 —— 校验的对象必须是从会议行反解出的
 * 真实所属项目,而不是调用方声明的项目。会议不存在 404、存在但非成员 403,两者不混同。
 */
@Service
@RequiredArgsConstructor
public class MeetingAccessGuard {

    private final JdbcTemplate jdbc;
    private final ProjectAccessService projectAccess;

    /** 会议所属项目;历史数据 project_id 为空时按全仓既有约定归入 P001。 */
    public String projectIdOf(String meetingId) {
        var rows = jdbc.queryForList("SELECT project_id FROM meetings WHERE id=?", meetingId);
        if (rows.isEmpty()) throw ApiException.notFound("会议不存在");
        Object projectId = rows.getFirst().get("project_id");
        return projectId == null || projectId.toString().isBlank() ? "P001" : projectId.toString();
    }

    public void requireMember(String meetingId) {
        projectAccess.requireMember(projectIdOf(meetingId));
    }

    public void requireWriter(String meetingId) {
        projectAccess.requireWriter(projectIdOf(meetingId));
    }

    /** A caller-supplied projectId must never redirect a meeting proposal to another project. */
    public void requireSameProject(String meetingId, String requestedProjectId) {
        if (!projectIdOf(meetingId).equals(ProjectAccessService.normalize(requestedProjectId))) {
            throw ApiException.forbidden("会议不属于指定项目");
        }
    }
}
