package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主管有界循环的检查点(S4′.2,契约 §4.5)。
 *
 * <p>编排层每步循环末尾把整个循环状态 upsert 进来，进程被杀后由携带新 JWT 的客户端
 * 重连触发恢复({@code node_name} 取单值 {@code "loop"}，滚动覆盖，整轮 run 只留一行)。
 *
 * <p><b>本表不含 {@code session_id} 列</b>(照抄契约 §4.5，不加列)——所以归属校验只能
 * 借 {@code agent_events} 反查该 run 属于哪个会话。{@code state_json} 内<b>绝不含 JWT</b>：
 * 这是与红线⑤共享的那条硬约束(令牌不进状态，故恢复必须由带新令牌的客户端重连触发)。
 */
@Data
@TableName("agent_checkpoints")
public class AgentCheckpoint {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runId;
    /** 单值 "loop"：滚动覆盖，故不能按 id 判断新旧(upsert 不改 id) */
    private String nodeName;
    /** 循环状态 JSON 文本；列是 JSON NOT NULL */
    private String stateJson;
    private LocalDateTime createdAt;
}
