package org.linxing.linxing_agent.agent.tool.impl.jsoncontainer;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.core.JsonContainer;
import org.linxing.linxing_agent.agent.subagent.SubAgentContext;
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
    private static final String DISPLAY_LABEL = "追加JSON内容";
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
    public String displayLabel() {
        return DISPLAY_LABEL;
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
            Map<String, Object> result = doAppend(
                    root.get("container_id"),
                    root.get("array_path"),
                    root.get("items"),
                    context
            );
            String resultJson = objectMapper.writeValueAsString(result);
            return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);
        } catch (IllegalArgumentException e) {
            log.warn("[AppendToContainerTool] 参数校验失败: {}", e.getMessage());
            return ToolCallResult.failure(request.getToolCallId(), NAME, e.getMessage());
        } catch (Exception e) {
            log.error("[AppendToContainerTool] 追加失败: {}", e.getMessage(), e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "追加失败: " + e.getMessage());
        }
    }

    /**
     * 供 subagent 体系使用的 @Tool 入口。
     * 与 {@link #execute(ToolCallRequest, AgentContext)} 共用核心追加逻辑，
     * 容器存储使用 {@link SubAgentContext#currentStore()}。
     */
    @dev.langchain4j.agent.tool.Tool("向容器的指定数组路径追加元素。每次建议追加1-3个元素，避免单次输出过长。")
    public String appendToContainer(
            @P("容器ID，由 create_container 返回") String containerId,
            @P("数组路径，必须在 create_container 时声明，如 \"questions\"") String arrayPath,
            @P("要追加的元素JSON数组，每次1-3个元素") String itemsJson) {

        JsonContainerStore store = SubAgentContext.currentStore();
        if (store == null) {
            return "错误：subagent 容器存储未绑定";
        }

        try {
            JsonNode itemsNode = parseArrayNode(itemsJson, "items 必须是合法 JSON 数组");
            Map<String, Object> result = doAppend(
                    objectMapper.valueToTree(containerId),
                    objectMapper.valueToTree(arrayPath),
                    itemsNode,
                    store
            );
            return objectMapper.writeValueAsString(result);
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("[AppendToContainerTool] @Tool 追加失败: {}", e.getMessage(), e);
            return "追加失败: " + e.getMessage();
        }
    }

    /**
     * 核心追加逻辑，两个入口共用。
     */
    private Map<String, Object> doAppend(JsonNode containerIdNode, JsonNode arrayPathNode,
                                          JsonNode itemsNode, JsonContainerStore store) {
        String error = ContainerParamValidator.validateContainerIdFromNode(containerIdNode);
        if (error == null) {
            error = ContainerParamValidator.validateArrayPathFromNode(arrayPathNode);
        }
        if (error != null) {
            throw new IllegalArgumentException(error);
        }

        String containerId = containerIdNode.asText();
        String arrayPath = arrayPathNode.asText();

        JsonContainer container = store.getContainer(containerId);
        if (container == null) {
            throw new IllegalArgumentException("容器不存在: " + containerId);
        }

        ArrayNode array = container.getArrays().get(arrayPath);
        if (array == null) {
            throw new IllegalArgumentException(
                    "路径未声明: " + arrayPath + "，可用路径: " + container.getArrays().keySet());
        }

        if (itemsNode == null || !itemsNode.isArray()) {
            throw new IllegalArgumentException("items 必须是数组");
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

        log.debug("[AppendToContainerTool] 追加成功: containerId={}, path={}, appended={}, total={}",
                containerId, arrayPath, appendedCount, array.size());
        return result;
    }

    private ArrayNode parseArrayNode(String json, String errorMessage) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                throw new IllegalArgumentException(errorMessage);
            }
            return (ArrayNode) node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(errorMessage);
        }
    }
}
