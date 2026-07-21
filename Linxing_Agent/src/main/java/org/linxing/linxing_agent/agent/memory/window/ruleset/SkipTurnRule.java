package org.linxing.linxing_agent.agent.memory.window.ruleset;

import lombok.Builder;
import lombok.Value;

/**
 * Skip Turn Rule
 *
 * <p>描述"哪个 Conversation Turn 可不参与本轮 Prompt 构建"。以 **Conversation Turn** 为
 * 最小原子单元——一个 Turn = UserMessage →（可选 Assistant 中间回复）→ ToolCall → ToolResult
 * →（可选 Skill/MCP）→ Assistant Final，**整 Turn 跳过、不切断 tool 配对**。
 *
 * <p><b>Turn 标识</b>：用 {@code turnStartMessageId}——该 Turn 起始 UserMessage 在 DB 的
 * chat_message id。相比数组下标，它不随 Recovery 截断/排序变化，跨轮次稳定。
 * 2-D Builder 消费时需把 Recovery 产出的 langchain4j 消息与实体 id 关联以定位 Turn 边界。
 *
 * <p>本类为不可变值对象；RuleSet 每次更新产出新实例，Store 用 WriteLock 仅保护引用替换。
 */
@Value
@Builder
public class SkipTurnRule {

    /** rule 唯一 id（UUID 字符串），供 rule 更新 tool 的 remove/replace 定位。 */
    String ruleId;

    /** 该 Turn 起始 UserMessage 的 chat_message id。 */
    Integer turnStartMessageId;

    /** 生成此 rule 的原因（LLM 判定的低价值理由），便于审计。 */
    String reason;
}
