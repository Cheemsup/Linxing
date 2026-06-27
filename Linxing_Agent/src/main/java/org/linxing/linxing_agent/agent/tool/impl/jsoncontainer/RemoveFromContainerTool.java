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
public class RemoveFromContainerTool implements Tool {

    private static final String NAME = "remove_from_container";
    private static final String DESCRIPTION = "移除容器中指定数组路径、指定索引的元素。移除后后续元素索引前移。"
            + "当某元素修正3次仍不通过时，可移除该元素作为兜底。";
    private static final String BRIEF = "移除容器中指定索引的元素";
    private static final String DISPLAY_LABEL = "移除JSON内容";
    private static final String WHEN_TO_USE = "某元素反复修正仍不通过时，移除该元素作为兜底";

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
                        .description("要移除的元素索引，从0开始。移除后后续元素索引前移").build())
                .required("container_id", "array_path", "index")
                .build();
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, AgentContext context) {
        String arguments = request.getArguments();
        log.debug("[RemoveFromContainerTool] 收到参数: {}", arguments);

        try {
            var root = objectMapper.readTree(arguments);
            Map<String, Object> result = doRemove(
                    root.get("container_id"),
                    root.get("array_path"),
                    root.get("index"),
                    context
            );
            String resultJson = objectMapper.writeValueAsString(result);
            return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);
        } catch (IllegalArgumentException e) {
            log.warn("[RemoveFromContainerTool] 参数校验失败: {}", e.getMessage());
            return ToolCallResult.failure(request.getToolCallId(), NAME, e.getMessage());
        } catch (Exception e) {
            log.error("[RemoveFromContainerTool] 移除失败: {}", e.getMessage(), e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "移除失败: " + e.getMessage());
        }
    }

    /**
     * 供 subagent 体系使用的 @Tool 入口。
     * 与 {@link #execute(ToolCallRequest, AgentContext)} 共用核心移除逻辑，
     * 容器存储使用 {@link SubAgentContext#currentStore()}。
     */
    @dev.langchain4j.agent.tool.Tool("移除容器中指定数组路径、指定索引的元素。移除后后续元素索引前移。"
            + "当某元素修正3次仍不通过时，可移除该元素作为兜底。")
    public String removeFromContainer(
            @P("容器ID") String containerId,
            @P("数组路径，如 \"questions\"") String arrayPath,
            @P("要移除的元素索引，从0开始") int index) {

        JsonContainerStore store = SubAgentContext.currentStore();
        if (store == null) {
            return "错误：subagent 容器存储未绑定";
        }

        try {
            Map<String, Object> result = doRemove(
                    objectMapper.valueToTree(containerId),
                    objectMapper.valueToTree(arrayPath),
                    objectMapper.valueToTree(index),
                    store
            );
            return objectMapper.writeValueAsString(result);
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("[RemoveFromContainerTool] @Tool 移除失败: {}", e.getMessage(), e);
            return "移除失败: " + e.getMessage();
        }
    }

    /**
     * 核心移除逻辑，两个入口共用。
     */
    private Map<String, Object> doRemove(JsonNode containerIdNode, JsonNode arrayPathNode,
                                          JsonNode indexNode, JsonContainerStore store) {
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

        array.remove(index);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("containerId", containerId);
        result.put("arrayPath", arrayPath);
        result.put("removedIndex", index);
        result.put("currentCount", array.size());

        log.debug("[RemoveFromContainerTool] 移除成功: containerId={}, path={}, index={}, remaining={}",
                containerId, arrayPath, index, array.size());
        return result;
    }
}
