package org.linxing.linxing_agent.agent.tool.impl.jsoncontainer;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.core.JsonContainer;
import org.linxing.linxing_agent.agent.subagent.SubAgentContext;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReplaceContainerMetadataTool implements Tool {

    private static final String NAME = "replace_container_metadata";
    private static final String DESCRIPTION = "更新容器的元数据字段。已有字段覆盖，新字段补充。用于补充缺少的元数据或修正错误值。";
    private static final String BRIEF = "更新容器元数据";
    private static final String DISPLAY_LABEL = "更新元信息";
    private static final String WHEN_TO_USE = "save 工具校验失败提示 metadata 缺少必填字段时，用此工具补充";

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
                .addProperty("metadata_updates", JsonObjectSchema.builder()
                        .description("要更新/补充的元数据字段，已有字段覆盖，新字段补充").build())
                .required("container_id", "metadata_updates")
                .build();
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, AgentContext context) {
        String arguments = request.getArguments();
        log.debug("[ReplaceContainerMetadataTool] 收到参数: {}", arguments);

        try {
            var root = objectMapper.readTree(arguments);
            Map<String, Object> result = doReplaceMetadata(
                    root.get("container_id"),
                    root.get("metadata_updates"),
                    context
            );
            String resultJson = objectMapper.writeValueAsString(result);
            return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);
        } catch (IllegalArgumentException e) {
            log.warn("[ReplaceContainerMetadataTool] 参数校验失败: {}", e.getMessage());
            return ToolCallResult.failure(request.getToolCallId(), NAME, e.getMessage());
        } catch (Exception e) {
            log.error("[ReplaceContainerMetadataTool] 更新失败: {}", e.getMessage(), e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "更新失败: " + e.getMessage());
        }
    }

    /**
     * 供 subagent 体系使用的 @Tool 入口。
     * 与 {@link #execute(ToolCallRequest, AgentContext)} 共用核心更新逻辑，
     * 容器存储使用 {@link SubAgentContext#currentStore()}。
     */
    @dev.langchain4j.agent.tool.Tool("更新容器的元数据字段。已有字段覆盖，新字段补充。用于补充缺少的元数据或修正错误值。")
    public String replaceContainerMetadata(
            @P("容器ID") String containerId,
            @P("要更新/补充的元数据字段JSON对象") String metadataUpdatesJson) {

        JsonContainerStore store = SubAgentContext.currentStore();
        if (store == null) {
            return "错误：subagent 容器存储未绑定";
        }

        try {
            ObjectNode updatesNode = parseObjectNode(metadataUpdatesJson,
                    "metadata_updates 必须是合法 JSON 对象");
            Map<String, Object> result = doReplaceMetadata(
                    objectMapper.valueToTree(containerId),
                    updatesNode,
                    store
            );
            return objectMapper.writeValueAsString(result);
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("[ReplaceContainerMetadataTool] @Tool 更新失败: {}", e.getMessage(), e);
            return "更新失败: " + e.getMessage();
        }
    }

    /**
     * 核心更新逻辑，两个入口共用。
     */
    private Map<String, Object> doReplaceMetadata(JsonNode containerIdNode,
                                                   JsonNode updatesNode,
                                                   JsonContainerStore store) {
        String error = ContainerParamValidator.validateContainerIdFromNode(containerIdNode);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }

        if (updatesNode == null || !updatesNode.isObject()) {
            throw new IllegalArgumentException("metadata_updates 必须是对象");
        }

        String containerId = containerIdNode.asText();

        JsonContainer container = store.getContainer(containerId);
        if (container == null) {
            throw new IllegalArgumentException("容器不存在: " + containerId);
        }

        ObjectNode metadata = container.getMetadata();
        if (metadata == null) {
            metadata = objectMapper.createObjectNode();
        }

        ObjectNode updatesObj = (ObjectNode) updatesNode;
        metadata.setAll(updatesObj);

        // 收集更新的字段名用于返回
        var updatesMap = objectMapper.convertValue(updatesObj, Map.class);
        List<String> updatedFields = new ArrayList<>((java.util.Set<String>) updatesMap.keySet());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("containerId", containerId);
        result.put("updatedFields", updatedFields);

        log.debug("[ReplaceContainerMetadataTool] 更新成功: containerId={}, fields={}",
                containerId, updatedFields);
        return result;
    }

    private ObjectNode parseObjectNode(String json, String errorMessage) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                throw new IllegalArgumentException(errorMessage);
            }
            return (ObjectNode) node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(errorMessage);
        }
    }
}
