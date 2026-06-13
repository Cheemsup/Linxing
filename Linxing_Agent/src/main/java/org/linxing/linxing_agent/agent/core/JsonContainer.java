package org.linxing.linxing_agent.agent.core;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON 容器，用于分批次构建复杂 JSON 数据。
 * 存储在 AgentContext 内存中，请求结束即销毁。
 */
@Getter
public class JsonContainer {

    private final String containerId;
    private final String containerType;
    private ObjectNode metadata;
    private final Map<String, ArrayNode> arrays;

    public JsonContainer(String containerId, String containerType, ObjectNode metadata, Map<String, ArrayNode> arrays) {
        this.containerId = containerId;
        this.containerType = containerType;
        this.metadata = metadata;
        this.arrays = arrays;
    }

    /**
     * 将 metadata 和 arrays 拼装为完整的 JSON ObjectNode。
     * metadata 字段展开到顶层，arrays 中每个 entry 作为顶层字段写入。
     */
    public ObjectNode assemble(tools.jackson.databind.ObjectMapper objectMapper) {
        ObjectNode root = metadata != null ? metadata.deepCopy() : objectMapper.createObjectNode();
        for (Map.Entry<String, ArrayNode> entry : arrays.entrySet()) {
            root.set(entry.getKey(), entry.getValue().deepCopy());
        }
        return root;
    }
}
