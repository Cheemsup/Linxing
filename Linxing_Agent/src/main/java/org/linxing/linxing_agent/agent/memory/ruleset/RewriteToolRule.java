package org.linxing.linxing_agent.agent.memory.ruleset;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Rewrite Tool Rule
 *
 * <p>描述"哪个 tool 调用结果需精简"。以 **tool_call_id** 为粒度——丢 {@code content}
 * 留 {@code step_data} 简要字段，重建为占位符版 {@code ToolExecutionResultMessage}
 *（保留 {@code tool_call_id} 不破坏配对）。
 *
 * <p>本类为不可变值对象。
 */
@Value
@Builder
public class RewriteToolRule {

    /** rule 唯一 id（UUID 字符串），供 rule 更新 tool 的 remove/replace 定位。 */
    String ruleId;

    /** 需精简结果的 tool 调用 id（与 agent_steps.step_data.tool_call_id 对应）。 */
    String toolCallId;

    /** 生成此 rule 的原因（LLM 判定的高体积低价值理由），便于审计。 */
    String reason;

    /** 需保留的 step_data 字段名列表；空表示全占位。 */
    @Builder.Default
    List<String> preserveFields = List.of();
}
