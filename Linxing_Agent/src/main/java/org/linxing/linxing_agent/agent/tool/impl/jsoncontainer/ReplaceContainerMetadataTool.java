package org.linxing.linxing_agent.agent.tool.impl.jsoncontainer;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.core.JsonContainer;
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

            var updatesNode = root.get("metadata_updates");

            String error = ContainerParamValidator.validateContainerId(root);
            if (error != null) {
                return ToolCallResult.failure(request.getToolCallId(), NAME, error);
            }

            String containerId = root.get("container_id").asText();

            JsonContainer container = context.getContainer(containerId);
            if (container == null) {
                return ToolCallResult.failure(request.getToolCallId(), NAME,
                        "容器不存在: " + containerId);
            }

            if (updatesNode == null || !updatesNode.isObject()) {
                return ToolCallResult.failure(request.getToolCallId(), NAME,
                        "metadata_updates 必须是对象");
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
            String resultJson = objectMapper.writeValueAsString(result);

            log.debug("[ReplaceContainerMetadataTool] 更新成功: containerId={}, fields={}",
                    containerId, updatedFields);
            return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);
        } catch (Exception e) {
            log.error("[ReplaceContainerMetadataTool] 更新失败: {}", e.getMessage(), e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "更新失败: " + e.getMessage());
        }
    }
}
