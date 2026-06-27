package org.linxing.linxing_agent.agent.tool.impl.jsoncontainer;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
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
public class ReplaceInContainerTool implements Tool {

    private static final String NAME = "replace_in_container";
    private static final String DESCRIPTION = "替换容器中指定数组路径、指定索引的元素。用于 save 工具校验失败后精确修正错误元素。";
    private static final String BRIEF = "替换容器中指定索引的元素";
    private static final String DISPLAY_LABEL = "替换JSON内容";
    private static final String WHEN_TO_USE = "save 工具校验失败返回索引级错误时，用此工具精确修正指定位置的元素";

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
                        .description("容器ID").build())
                .addProperty("array_path", JsonStringSchema.builder()
                        .description("数组路径，如 \"questions\"").build())
                .addProperty("index", JsonIntegerSchema.builder()
                        .description("要替换的元素索引，从0开始").build())
                .addProperty("item", JsonObjectSchema.builder()
                        .description("替换后的完整元素对象").build())
                .required("container_id", "array_path", "index", "item")
                .build();
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, AgentContext context) {
        String arguments = request.getArguments();
        log.debug("[ReplaceInContainerTool] 收到参数: {}", arguments);

        try {
            var root = objectMapper.readTree(arguments);
            Map<String, Object> result = doReplace(
                    root.get("container_id"),
                    root.get("array_path"),
                    root.get("index"),
                    root.get("item"),
                    context
            );
            String resultJson = objectMapper.writeValueAsString(result);
            return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);
        } catch (IllegalArgumentException e) {
            log.warn("[ReplaceInContainerTool] 参数校验失败: {}", e.getMessage());
            return ToolCallResult.failure(request.getToolCallId(), NAME, e.getMessage());
        } catch (Exception e) {
            log.error("[ReplaceInContainerTool] 替换失败: {}", e.getMessage(), e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "替换失败: " + e.getMessage());
        }
    }

    /**
     * 供 subagent 体系使用的 @Tool 入口。
     * 与 {@link #execute(ToolCallRequest, AgentContext)} 共用核心替换逻辑，
     * 容器存储使用 {@link SubAgentContext#currentStore()}。
     */
    @dev.langchain4j.agent.tool.Tool("替换容器中指定数组路径、指定索引的元素。用于校验失败后精确修正错误元素。")
    public String replaceInContainer(
            @P("容器ID") String containerId,
            @P("数组路径，如 \"questions\"") String arrayPath,
            @P("要替换的元素索引，从0开始") int index,
            @P("替换后的完整元素对象JSON") String itemJson) {

        JsonContainerStore store = SubAgentContext.currentStore();
        if (store == null) {
            return "错误：subagent 容器存储未绑定";
        }

        try {
            JsonNode itemNode = parseObjectNode(itemJson, "item 必须是合法 JSON 对象");
            Map<String, Object> result = doReplace(
                    objectMapper.valueToTree(containerId),
                    objectMapper.valueToTree(arrayPath),
                    objectMapper.valueToTree(index),
                    itemNode,
                    store
            );
            return objectMapper.writeValueAsString(result);
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("[ReplaceInContainerTool] @Tool 替换失败: {}", e.getMessage(), e);
            return "替换失败: " + e.getMessage();
        }
    }

    /**
     * 核心替换逻辑，两个入口共用。
     */
    private Map<String, Object> doReplace(JsonNode containerIdNode, JsonNode arrayPathNode,
                                           JsonNode indexNode, JsonNode itemNode,
                                           JsonContainerStore store) {
        String error = ContainerParamValidator.validateContainerIdFromNode(containerIdNode);
        if (error == null) {
            error = ContainerParamValidator.validateArrayPathFromNode(arrayPathNode);
        }
        if (error == null) {
            error = ContainerParamValidator.validateIndexFromNode(indexNode);
        }
        if (error != null) {
            throw new IllegalArgumentException(error);
        }

        if (itemNode == null || !itemNode.isObject()) {
            throw new IllegalArgumentException("item 必须是对象");
        }

        String containerId = containerIdNode.asText();
        String arrayPath = arrayPathNode.asText();
        int index = indexNode.asInt();

        JsonContainer container = store.getContainer(containerId);
        if (container == null) {
            throw new IllegalArgumentException("容器不存在: " + containerId);
        }

        ArrayNode array = container.getArrays().get(arrayPath);
        if (array == null) {
            throw new IllegalArgumentException(
                    "路径未声明: " + arrayPath + "，可用路径: " + container.getArrays().keySet());
        }

        if (index < 0 || index >= array.size()) {
            throw new IllegalArgumentException(
                    "索引越界: index=" + index + ", currentSize=" + array.size());
        }

        array.set(index, itemNode.deepCopy());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("containerId", containerId);
        result.put("arrayPath", arrayPath);
        result.put("replacedIndex", index);

        log.debug("[ReplaceInContainerTool] 替换成功: containerId={}, path={}, index={}",
                containerId, arrayPath, index);
        return result;
    }

    private JsonNode parseObjectNode(String json, String errorMessage) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                throw new IllegalArgumentException(errorMessage);
            }
            return node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(errorMessage);
        }
    }
}
