package org.linxing.linxing_agent.agent.tool.impl.jsoncontainer;

import tools.jackson.databind.JsonNode;

/**
 * 容器工具公共参数校验器。
 * <p>
 * 所有 JSONContainer 工具在解析参数前都需要先调用这里的方法，把参数缺失或类型错误
 * 转换为大模型可读的文本错误，避免NullPointerException。
 */
public final class ContainerParamValidator {

    private ContainerParamValidator() {
        // 工具类
    }

    /**
     * 校验 container_id 是否为空或非法。
     *
     * @param root 工具入参 JSON
     * @return 错误信息，合法时返回 null
     */
    public static String validateContainerId(JsonNode root) {
        return validateContainerIdFromNode(root.get("container_id"));
    }

    /**
     * 校验 container_id 节点是否为空或非法。
     *
     * @param node container_id 节点
     * @return 错误信息，合法时返回 null
     */
    public static String validateContainerIdFromNode(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return "container_id 不能为空";
        }
        return null;
    }

    /**
     * 校验 array_path 是否为空或非法。
     *
     * @param root 工具入参 JSON
     * @return 错误信息，合法时返回 null
     */
    public static String validateArrayPath(JsonNode root) {
        return validateArrayPathFromNode(root.get("array_path"));
    }

    /**
     * 校验 array_path 节点是否为空或非法。
     *
     * @param node array_path 节点
     * @return 错误信息，合法时返回 null
     */
    public static String validateArrayPathFromNode(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return "array_path 不能为空";
        }
        return null;
    }

    /**
     * 校验 index 是否为整数。
     *
     * @param root 工具入参 JSON
     * @return 错误信息，合法时返回 null
     */
    public static String validateIndex(JsonNode root) {
        return validateIndexFromNode(root.get("index"));
    }

    /**
     * 校验 index 节点是否为整数。
     *
     * @param node index 节点
     * @return 错误信息，合法时返回 null
     */
    public static String validateIndexFromNode(JsonNode node) {
        if (node == null || !node.isInt()) {
            return "index 必须是整数";
        }
        return null;
    }
}
