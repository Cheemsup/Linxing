package org.linxing.linxing_agent.agent.memory.longterm.worker;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.Getter;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.memory.longterm.tool.ListMemoryTool;
import org.linxing.linxing_agent.agent.memory.longterm.tool.ReadMemoryTool;
import org.linxing.linxing_agent.agent.memory.longterm.tool.WriteMemoryTool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;

/**
 * Memory Worker ReAct 小循环的 per-loop 载体。
 *本类是小循环专属轻量载体，持有：
 * <ul>
 *   <li>{@code userId}：用户隔离依据，透传给三把工具构造最小 context</li>
 *   <li>三把工具 Bean：{@code readMemoryTool}/{@code listMemoryTool}/{@code writeMemoryTool}</li>
 * </ul>
 *
 * <p>{@link #executeTool(ToolExecutionRequest)} 按 name 路由到对应记忆工具的 {@code execute}——
 * 三把工具均为 Spring Bean，已实现 {@link org.linxing.linxing_agent.agent.tool.Tool#execute} 接口，
 * LLM 产出的 tool_call arguments 直接透传，无需手工解析或拼装 JSON。
 * <p>学习阶段归档由 {@link WriteMemoryTool} 内部处理，本类不感知。
 *
 * @deprecated 2026.08.06 决策 7：对话后自动触发已移除，本载体不再被实例化，保留待评估。
 *             注意：{@code WriteMemoryTool} 现已 {@code shouldRegisterToMainAgent()=true} 开放给主 Agent，
 *             原文档所述"写收口"已不成立——写权限改由提示词约束（仅用户显式要求时写入）。
 */
@Deprecated
public class MemoryWorkerReActContext {

    @Getter
    private final Integer userId;
    private final ReadMemoryTool readMemoryTool;
    private final ListMemoryTool listMemoryTool;
    private final WriteMemoryTool writeMemoryTool;

    public MemoryWorkerReActContext(Integer userId,
                                    ReadMemoryTool readMemoryTool,
                                    ListMemoryTool listMemoryTool,
                                    WriteMemoryTool writeMemoryTool) {
        this.userId = userId;
        this.readMemoryTool = readMemoryTool;
        this.listMemoryTool = listMemoryTool;
        this.writeMemoryTool = writeMemoryTool;
    }

    /**
     * 按 tool name 分派到对应记忆工具执行，返回给 LLM 的结果文本。
     * <p>工具返回 success 时回填 result 文本；failure 时回填 error 文本（LLM 可据以纠正或退出）。
     */
    public String executeTool(ToolExecutionRequest req) {
        AgentContext ctx = new AgentContext(userId, null, null, null);
        String arguments = req.arguments() == null ? "{}" : req.arguments();
        ToolCallRequest request = ToolCallRequest.builder()
                .toolCallId(req.id())
                .toolName(req.name())
                .arguments(arguments)
                .build();
        ToolCallResult result = switch (req.name()) {
            case "read_memory" -> readMemoryTool.execute(request, ctx);
            case "list_memory" -> listMemoryTool.execute(request, ctx);
            case "write_memory" -> writeMemoryTool.execute(request, ctx);
            default -> null;
        };
        if (result == null) {
            return "error: 未知工具 " + req.name();
        }
        return result.isSuccess()
                ? result.getResult()
                : "error: " + result.getError();
    }
}
