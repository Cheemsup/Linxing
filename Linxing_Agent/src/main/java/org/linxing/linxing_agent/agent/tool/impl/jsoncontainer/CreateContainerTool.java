package org.linxing.linxing_agent.agent.tool.impl.jsoncontainer;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
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

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreateContainerTool implements Tool {

    private static final String NAME = "create_container";
    private static final String DESCRIPTION = "创建一个JSON容器，用于分批次构建复杂JSON数据。"
            + "返回容器ID，后续通过 append_to_container 追加数据，最终由 save 工具读取容器完成持久化。"
            + "当需要生成超过5个元素的数组数据时，应使用分批模式。";
    private static final String BRIEF = "创建JSON容器，用于分批构建复杂数据";
    private static final String DISPLAY_LABEL = "创建JSON容器";
    private static final String WHEN_TO_USE = "当需要生成大量数组数据（如超过5道试题）时，先创建容器再分批追加，避免一次性输出过长JSON导致语法错误";

    private final ObjectMapper objectMapper;
    private static final SecureRandom RANDOM = new SecureRandom();

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
                .addProperty("container_type", JsonStringSchema.builder()
                        .description("业务类型标识，如 \"exam\"、\"study_plan\" 等").build())
                .addProperty("metadata", JsonObjectSchema.builder()
                        .description("顶层元数据字段，如 exam 的 title、source_type 等").build())
                .addProperty("array_paths", JsonArraySchema.builder()
                        .description("需要分批追加的数组路径列表，如 [\"questions\"]。每个路径会初始化一个空数组")
                        .items(JsonStringSchema.builder().build())
                        .build())
                .required("container_type", "metadata", "array_paths")
                .build();
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, AgentContext context) {
        String arguments = request.getArguments();
        log.debug("[CreateContainerTool] 收到参数: {}", arguments);

        try {
            var root = objectMapper.readTree(arguments);
            Map<String, Object> result = doCreate(
                    root.get("container_type"),
                    root.get("metadata"),
                    root.get("array_paths"),
                    context
            );
            String resultJson = objectMapper.writeValueAsString(result);
            return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);
        } catch (IllegalArgumentException e) {
            log.warn("[CreateContainerTool] 参数校验失败: {}", e.getMessage());
            return ToolCallResult.failure(request.getToolCallId(), NAME, e.getMessage());
        } catch (Exception e) {
            log.error("[CreateContainerTool] 创建容器失败: {}", e.getMessage(), e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "创建容器失败: " + e.getMessage());
        }
    }

    /**
     * 供 subagent 体系使用的 @Tool 入口。
     * 与 {@link #execute(ToolCallRequest, AgentContext)} 共用核心创建逻辑，
     * 容器存储使用 {@link SubAgentContext#currentStore()}。
     */
    @dev.langchain4j.agent.tool.Tool("创建一个JSON容器，用于分批次构建复杂JSON数据。"
            + "返回容器ID，后续通过 append_to_container 追加数据。"
            + "当需要生成超过5个元素的数组数据时，应使用分批模式。")
    public String createContainer(
            @P("业务类型标识，如 \"exam\"、\"study_plan\" 等") String containerType,
            @P("顶层元数据字段，如 exam 的 title、source_type 等") String metadataJson,
            @P("需要分批追加的数组路径JSON数组，如 [\"questions\"]") String arrayPathsJson) {

        JsonContainerStore store = SubAgentContext.currentStore();
        if (store == null) {
            return "错误：subagent 容器存储未绑定";
        }

        try {
            ObjectNode metadataNode = parseObjectNode(metadataJson, "metadata 必须是合法 JSON 对象");
            ArrayNode arrayPathsNode = parseArrayNode(arrayPathsJson, "array_paths 必须是合法 JSON 数组");
            Map<String, Object> result = doCreate(
                    objectMapper.valueToTree(containerType),
                    metadataNode,
                    arrayPathsNode,
                    store
            );
            return objectMapper.writeValueAsString(result);
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("[CreateContainerTool] @Tool 创建容器失败: {}", e.getMessage(), e);
            return "创建容器失败: " + e.getMessage();
        }
    }

    /**
     * 核心创建逻辑，两个入口共用。
     */
    private Map<String, Object> doCreate(JsonNode containerTypeNode, JsonNode metadataNode,
                                          JsonNode arrayPathsNode, JsonContainerStore store) {
        if (containerTypeNode == null || !containerTypeNode.isTextual() || containerTypeNode.asText().isBlank()) {
            throw new IllegalArgumentException("container_type 不能为空");
        }
        if (metadataNode == null || !metadataNode.isObject()) {
            throw new IllegalArgumentException("metadata 必须是对象");
        }
        if (arrayPathsNode == null || !arrayPathsNode.isArray() || arrayPathsNode.isEmpty()) {
            throw new IllegalArgumentException("array_paths 必须是非空数组");
        }

        String containerType = containerTypeNode.asText();

        // 生成 containerId: {type}_{6位随机hex}
        String containerId = containerType + "_" + randomHex(6);

        // 初始化 arrays
        Map<String, ArrayNode> arrays = new LinkedHashMap<>();
        for (var pathNode : arrayPathsNode) {
            String path = pathNode.asText();
            arrays.put(path, objectMapper.createArrayNode());
        }

        JsonContainer container = new JsonContainer(
                containerId,
                containerType,
                (ObjectNode) metadataNode,
                arrays
        );

        store.putContainer(containerId, container);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("containerId", containerId);

        log.info("[CreateContainerTool] 创建容器成功: containerId={}, type={}, arrayPaths={}",
                containerId, containerType, arrays.keySet());
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

    private static String randomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(Integer.toHexString(RANDOM.nextInt(16)));
        }
        return sb.toString();
    }
}
