package com.aicap.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * GitHub 同步核心逻辑单测(纯函数,不启 Spring、不发网络请求):
 * - taskIdFrom:PR/Issue/commit 文本中提取任务号;
 * - scopeFrom:Angular 风格 "(scope):" 提取模块;
 * - truncate:长度截断。
 */
class GitHubActivitySyncServiceLogicTest {

    @Test
    void taskIdFrom_extractsFirstTaskId() {
        assertEquals("T03", GitHubActivitySyncService.taskIdFrom("完成 T03 权限模块,关联 T05"));
        assertEquals("T14", GitHubActivitySyncService.taskIdFrom("feat(profile): US34 github sync (T14)"));
        assertNull(GitHubActivitySyncService.taskIdFrom("无任务关联的提交"));
        assertNull(GitHubActivitySyncService.taskIdFrom(null));
        assertNull(GitHubActivitySyncService.taskIdFrom(""));
        // 只匹配独立 Txx,不匹配 T3 / t03 / T123
        assertNull(GitHubActivitySyncService.taskIdFrom("T3 是小时数"));
        assertNull(GitHubActivitySyncService.taskIdFrom("T123 超出两位"));
    }

    @Test
    void scopeFrom_extractsAngularScope() {
        assertEquals("auth", GitHubActivitySyncService.scopeFrom("feat(auth): 登录接口 401 修复"));
        assertEquals("profile", GitHubActivitySyncService.scopeFrom("fix(profile): 画像字段扩展"));
        assertEquals("", GitHubActivitySyncService.scopeFrom("feat: 无 scope 的提交"));
        assertEquals("", GitHubActivitySyncService.scopeFrom("直接改代码"));
        assertEquals("", GitHubActivitySyncService.scopeFrom(null));
        assertEquals("core", GitHubActivitySyncService.scopeFrom("  refactor(core): 重构"));
    }

    @Test
    void truncate_limitsLength() {
        assertEquals("abc", GitHubActivitySyncService.truncate("abc", 10));
        assertEquals("abc", GitHubActivitySyncService.truncate("abcdef", 3));
        assertEquals("", GitHubActivitySyncService.truncate(null, 3));
    }
}
