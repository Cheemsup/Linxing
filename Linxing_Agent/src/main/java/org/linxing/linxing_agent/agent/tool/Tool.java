package org.linxing.linxing_agent.agent.tool;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.linxing.linxing_agent.agent.core.AgentContext;

import java.util.List;

public interface Tool {

    /**
     * 工具唯一标识
     */
    String name();

    /**
     * 工具完整描述，用于 LLM 生成精确调用参数
     */
    String description();

    /**
     * 一句话简要描述，用于目录展示（渐进式披露 Phase 1）
     */
    default String brief() {
        return description();
    }

    /**
     * 适用场景描述，帮助 LLM 判断何时使用此工具（渐进式披露 Phase 1）
     */
    default String whenToUse() {
        return "";
    }

    /**
     * 前端展示名，用于在对话步骤中向用户展示此工具的人类可读的友好名称。
     * 默认复用 {@link #brief()}，具体工具可覆盖以提供更友好的文案。
     */
    default String displayLabel() {
        return brief();
    }

    /**
     * 前置条件列表，如需要特定权限、依赖数据等（渐进式披露 Phase 1，可选）
     */
    default List<String> prerequisites() {
        return List.of();
    }

    /**
     * 返回工具的完整 JSON Schema，描述参数结构（渐进式披露 Phase 2）
     */
    JsonObjectSchema spec();

    /**
     * 执行工具调用
     * @param request 工具调用请求
     * @param context Agent 运行时上下文，提供 userId、query 等信息
     * @return
     */
    ToolCallResult execute(ToolCallRequest request, AgentContext context);

    /**
     * 是否注册为主 Agent 可见可调的工具（进 ToolRegistry 自动发现 + 能力目录）。
     * <p>元工具（如仅向 Memory Worker 暴露的 WriteMemoryTool）继承重写为false
     */
    default boolean shouldRegisterToMainAgent() {
        return true;
    }

    /**
     * @deprecated 使用 {@link #execute(ToolCallRequest, AgentContext)} 代替
     */
    @Deprecated
    default ToolCallResult execute(ToolCallRequest request) {
        throw new UnsupportedOperationException("请使用 execute(request, context)");
    }
}
