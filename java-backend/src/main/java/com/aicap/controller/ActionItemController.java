package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Read-only business action items. Creation is exclusively through approved execution. */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/meetings/{meetingId}/action-items")
public class ActionItemController {
    private final JdbcTemplate jdbc;
    private void check(String meetingId) {
        Roles.any();
        if(jdbc.queryForList("SELECT id FROM meetings WHERE id=?",meetingId).isEmpty()) throw ApiException.notFound("会议不存在");
    }
    @GetMapping
    public List<Map<String,Object>> list(@PathVariable String meetingId) {
        check(meetingId);
        return jdbc.queryForList("SELECT * FROM action_items WHERE meeting_id=? ORDER BY created_at DESC,id",meetingId);
    }
    @GetMapping("/{actionItemId}/logs")
    public List<Map<String,Object>> logs(@PathVariable String meetingId,@PathVariable String actionItemId) {
        check(meetingId);
        if(jdbc.queryForList("SELECT id FROM action_items WHERE meeting_id=? AND id=?",meetingId,actionItemId).isEmpty())
            throw ApiException.notFound("行动项不存在");
        return jdbc.queryForList("SELECT * FROM action_item_logs WHERE action_item_id=? ORDER BY id",actionItemId);
    }
}
