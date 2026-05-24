package org.linxing.linxing_agent.agent.tool;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

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
     * @param request
     * @return
     */
    ToolCallResult execute(ToolCallRequest request);
}
