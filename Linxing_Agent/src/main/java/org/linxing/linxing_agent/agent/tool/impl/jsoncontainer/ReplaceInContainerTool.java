package org.linxing.linxing_agent.agent.tool.impl.jsoncontainer;

import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
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
public class ReplaceInContainerTool implements Tool {

    private static final String NAME = "replace_in_container";
    private static final String DESCRIPTION = "替换容器中指定数组路径、指定索引的元素。用于 save 工具校验失败后精确修正错误元素。";
    private static final String BRIEF = "替换容器中指定索引的元素";
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

            var itemNode = root.get("item");

            String error = ContainerParamValidator.validateContainerId(root);
            if (error == null) {
                error = ContainerParamValidator.validateArrayPath(root);
            }
            if (error == null) {
                error = ContainerParamValidator.validateIndex(root);
            }
            if (error != null) {
                return ToolCallResult.failure(request.getToolCallId(), NAME, error);
            }

            String containerId = root.get("container_id").asText();
            String arrayPath = root.get("array_path").asText();
            int index = root.get("index").asInt();

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

            if (index < 0 || index >= array.size()) {
                return ToolCallResult.failure(request.getToolCallId(), NAME,
                        "索引越界: index=" + index + ", currentSize=" + array.size());
            }

            if (itemNode == null || !itemNode.isObject()) {
                return ToolCallResult.failure(request.getToolCallId(), NAME,
                        "item 必须是对象");
            }

            array.set(index, itemNode.deepCopy());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("containerId", containerId);
            result.put("arrayPath", arrayPath);
            result.put("replacedIndex", index);
            String resultJson = objectMapper.writeValueAsString(result);

            log.debug("[ReplaceInContainerTool] 替换成功: containerId={}, path={}, index={}",
                    containerId, arrayPath, index);
            return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);
        } catch (Exception e) {
            log.error("[ReplaceInContainerTool] 替换失败: {}", e.getMessage(), e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "替换失败: " + e.getMessage());
        }
    }
}
