package org.linxing.linxing_agent.agent.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.Builder;
import lombok.Data;
import org.linxing.linxing_agent.agent.core.AgentContext;

@Data
@Builder
public class ToolSpec {

    private final String name;
    private final String description;
    private final JsonObjectSchema parameters;
    private final Tool executor;

    /**
     * 转换为 LangChain4j 的工具规格，用于注入 LLM 请求
     * @return
     */
    public ToolSpecification toLangChain4jSpec() {
        return ToolSpecification.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .build();
    }

    /**
     * 委托执行器执行工具调用
     * @param request
     * @param context
     * @return
     */
    public ToolCallResult execute(ToolCallRequest request, AgentContext context) {
        return executor.execute(request, context);
    }
}
