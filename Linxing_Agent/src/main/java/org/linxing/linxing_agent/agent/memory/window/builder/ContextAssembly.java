package org.linxing.linxing_agent.agent.memory.window.builder;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import lombok.Builder;
import lombok.Data;
import org.linxing.linxing_agent.agent.memory.window.projection.ProjectionPolicy;

import java.util.List;

/**
 * {@link ContextBuilder#build} 的一次性装配产物。
 */
@Data
@Builder
public class ContextAssembly {

    /** 经投影后的最终消息列表（SystemMessage 首位 → 历史段投影 → 当前用户问），直接写入 AgentMemory。 */
    private List<ChatMessage> messages;

    /** 本轮工具规格（与 messages 同期装配，供 AgentExecutor 取用 + token 估算口径）。 */
    private List<ToolSpecification> toolSpecs;

    /** messages + toolSpecs 的 token 估算值，用于 Projection 策略判定。 */
    private long totalTokens;

    /** 基于 totalTokens 判定的 Projection 策略；为 SUMMARY 时由外部决定是否落盘并二次 build。 */
    private ProjectionPolicy policy;
}
