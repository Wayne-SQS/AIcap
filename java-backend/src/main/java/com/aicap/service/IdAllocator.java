package com.aicap.service;

import com.aicap.common.ApiException;
import com.aicap.entity.PoolItem;
import com.aicap.entity.Story;
import com.aicap.entity.Task;
import com.aicap.mapper.PoolItemMapper;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 故事 / 需求池编号分配(**单一实现**)。
 *
 * <p>此前 {@code nextStoryId()} 在 {@code StoryController} 与 {@code TaskPoolController} 里
 * 各写了一份逐字相同的私有方法,{@code nextPoolId()} 亦然 —— 编号规则一改要同步三处,
 * 漏一处就会出现两套编号口径。现集中到本类,控制器只注入调用。
 *
 * <p><b>并发语义(重要,勿误读)</b>:分配仍是「扫描当前最大号 + 1」,两个并发创建**可能**算出
 * 同一个号。本类做的是:
 * <ol>
 *   <li>插入前复查候选号是否已被占用,占用则顺延重算 —— 收窄竞态窗口;</li>
 *   <li>万一仍撞上({@code id} 是 varchar 主键),{@link com.aicap.common.GlobalExceptionHandler}
 *       把主键冲突映射为 <b>409 + 可操作文案</b>,而不是兜底成 500「服务器内部错误」。</li>
 * </ol>
 * 这**不是**互斥锁、也不是序列化分配 —— 真正的原子分配需要独立计数器行或数据库序列,
 * 属于尚未做的架构改动(要点见类尾部说明)。
 *
 * <p>契约测试 {@code StoryContractTest#createStory_concurrently_never500_andIdsDistinct} 钉住了
 * 保证边界:并发创建只允许「全部成功且编号互不相同」或「明确 409」,**不得出现 500**。
 */
@Service
@RequiredArgsConstructor
public class IdAllocator {

    private static final Pattern US_ID = Pattern.compile("^US(\\d+)$");
    private static final Pattern R_ID = Pattern.compile("^R(\\d+)$");
    private static final Pattern TASK_ID = Pattern.compile("^T(\\d+)$");

    /** 顺延探测上限:足以覆盖正常并发,且保证不会无限循环(超过即判定为异常并报 409) */
    private static final int MAX_PROBE = 50;

    private final StoryMapper storyMapper;
    private final PoolItemMapper poolItemMapper;
    private final TaskMapper taskMapper;

    /** 下一个可用的故事编号({@code US%02d});已被占用的号自动顺延 */
    public String nextStoryId(String projectId) {
        int max = 0;
        for (Story s : storyMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Story>().eq("project_id", projectId))) {
            Matcher m = US_ID.matcher(s.getId());
            if (m.matches()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        for (int i = 0; i < MAX_PROBE; i++) {
            String candidate = String.format("US%02d", max + 1 + i);
            if (storyMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Story>().eq("project_id", projectId).eq("code", candidate)) == null) {
                return candidate;
            }
        }
        throw ApiException.conflict("故事编号分配连续冲突，请稍后重试");
    }

    public String nextStoryId() { throw ApiException.badRequest("projectId 必填"); }

    /** 下一个可用的需求池编号({@code R%02d});与故事的 US 命名空间相互独立 */
    public String nextPoolId() {
        int max = 0;
        for (PoolItem p : poolItemMapper.selectList(null)) {
            Matcher m = R_ID.matcher(p.getId());
            if (m.matches()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        for (int i = 0; i < MAX_PROBE; i++) {
            String candidate = String.format("R%02d", max + 1 + i);
            if (poolItemMapper.selectById(candidate) == null) {
                return candidate;
            }
        }
        throw ApiException.conflict("需求池编号分配连续冲突，请稍后重试");
    }

    /**
     * 下一个可用的任务编号({@code T%02d});编号在<b>项目内</b>唯一。
     *
     * <p>此前"手工新增任务"在 {@code TaskPoolController} 里内联了同一段"扫最大值 + 1"。
     * 现在规划智能体的 {@code CREATE_TASK} 也要新建任务 —— 两处各写一份，编号规则一改
     * 就会分叉（本类存在的理由，见类注释）。所以收进这里，手工新增与 Agent 新增共用。
     */
    public String nextTaskId(String projectId) {
        String scoped = com.aicap.service.ProjectAccessService.normalize(projectId);
        int max = 0;
        for (Task t : taskMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task>().eq("project_id", scoped))) {
            Matcher m = TASK_ID.matcher(t.getId());
            if (m.matches()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        for (int i = 0; i < MAX_PROBE; i++) {
            String candidate = String.format("T%02d", max + 1 + i);
            if (taskMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Task>()
                    .eq("project_id", scoped).eq("code", candidate)) == 0) {
                return candidate;
            }
        }
        throw ApiException.conflict("任务编号分配连续冲突，请稍后重试");
    }

    /*
     * 未做的部分(刻意记录,避免被误认为已解决):
     * 要做到「并发创建绝不冲突」,需要把编号来源从「扫描最大值」换成单调计数器,例如
     *   ① 独立计数表 + 原子 UPDATE(name='story', next_value = next_value + 1) 取回新值;
     *   ② 或改用数据库原生序列。
     * 两者都要新增表/改 schema,且要同步 reset_test_data.sql 的截断清单,属架构改动,
     * 本次只做「消除重复实现 + 让残留竞态可安全重试」。
     */
}
