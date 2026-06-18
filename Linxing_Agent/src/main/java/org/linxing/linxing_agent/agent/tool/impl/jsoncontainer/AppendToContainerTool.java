package org.linxing.linxing_agent.agent.tool.impl.jsoncontainer;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.core.JsonContainer;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppendToContainerTool implements Tool {

    private static final String NAME = "append_to_container";
    private static final String DESCRIPTION = "向容器的指定数组路径追加元素。每次建议追加1-3个元素，避免单次输出过长。";
    private static final String BRIEF = "向容器追加数组元素";
    private static final String WHEN_TO_USE = "分批模式下，向已创建的容器追加数组数据时使用";

    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public String brief() {
        return BRIEF;
    }

    @Override
    public String whenToUse() {
        return WHEN_TO_USE;
    }

    @Override
    public JsonObjectSchema spec() {
        return JsonObjectSchema.builder()
                .addProperty("container_id", JsonStringSchema.builder()
                        .description("容器ID，由 create_container 返回").build())
                .addProperty("array_path", JsonStringSchema.builder()
                        .description("数组路径，必须在 create_container 时声明，如 \"questions\"").build())
                .addProperty("items", JsonArraySchema.builder()
                        .description("要追加的元素数组，每次1-3个元素")
                        .build())
                .required("container_id", "array_path", "items")
                .build();
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, AgentContext context) {
        String arguments = request.getArguments();
        log.debug("[AppendToContainerTool] 收到参数: {}", arguments);

        try {
            var root = objectMapper.readTree(arguments);

            var itemsNode = root.get("items");

            String error = ContainerParamValidator.validateContainerId(root);
            if (error == null) {
                error = ContainerParamValidator.validateArrayPath(root);
            }
            if (error != null) {
                return ToolCallResult.failure(request.getToolCallId(), NAME, error);
            }

            String containerId = root.get("container_id").asText();
            String arrayPath = root.get("array_path").asText();

            JsonContainer container = context.getContainer(containerId);
            if (container == null) {
                return ToolCallResult.failure(request.getToolCallId(), NAME,
                        "容器不存在: " + containerId);
            }

            ArrayNode array = container.getArrays().get(arrayPath);
            if (array == null) {
                return ToolCallResult.failure(request.getToolCallId(), NAME,
                        "路径未声明: " + arrayPath + "，可用路径: " + container.getArrays().keySet());
            }

            if (itemsNode == null || !itemsNode.isArray()) {
                return ToolCallResult.failure(request.getToolCallId(), NAME,
                        "items 必须是数组");
            }

            int appendedCount = 0;
            for (var item : itemsNode) {
                array.add(item.deepCopy());
                appendedCount++;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("containerId", containerId);
            result.put("arrayPath", arrayPath);
            result.put("currentCount", array.size());
            result.put("appendedCount", appendedCount);
            String resultJson = objectMapper.writeValueAsString(result);

            log.debug("[AppendToContainerTool] 追加成功: containerId={}, path={}, appended={}, total={}",
                    containerId, arrayPath, appendedCount, array.size());
            return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);
        } catch (Exception e) {
            log.error("[AppendToContainerTool] 追加失败: {}", e.getMessage(), e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "追加失败: " + e.getMessage());
        }
    }
}
