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
 * TODO：分析是否应该进一步解耦其与org.linxing.linxing_agent.agent.tool这个包的关系，仿照org/linxing/linxing_agent/agent/memory/window的静态方式注入
 *
 * <p><b>决议（2026.07.22）：不改，保留当前 Bean 注入方式。</b>
 * <p>经分析，当前耦合不污染主 Agent 体系，反而是有意设计：
 * <ul>
 *   <li>{@code WriteMemoryTool} 覆写 {@code shouldRegisterToMainAgent()=false}，被
 *       {@link org.linxing.linxing_agent.agent.tool.ToolRegistry} 显式跳过——不进注册中心、不进目录。</li>
 *   <li>{@code ReadMemoryTool}/{@code ListMemoryTool} 默认 {@code true}，有意暴露为主 Agent 只读工具
 *       （主对话中 Agent 需 read_memory/@引用 读取记忆全文）——进目录是设计意图，非泄漏。</li>
 * </ul>
 * <p>由此实现"读开放/写收口"的精确权限：写记忆仅 Memory Worker 内部可见，读记忆对主 Agent 开放。
 * <p>对比 window 的静态注入模式（{@link org.linxing.linxing_agent.agent.memory.window.projection.snip.SkipTurnReActContext}）：
 * 静态方法无法被 ToolRegistry 发现与过滤，反而会失去这套开关能力。故此处保留 Bean 注入，否决该 TODO。
 */
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
