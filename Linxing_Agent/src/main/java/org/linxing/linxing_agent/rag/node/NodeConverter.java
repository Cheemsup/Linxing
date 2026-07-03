package org.linxing.linxing_agent.rag.node;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.dto.NodeDTO;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Node DTO 转换器。
 * 将 Python 服务返回的 NodeDTO 序列转换为 Java DocumentNode 序列。
 */
@Slf4j
@Component
public class NodeConverter {

    /**
     * 批量转换 NodeDTO 序列为 DocumentNode 序列。
     *
     * @param dtos NodeDTO 序列（来自 Python 解析）
     * @return DocumentNode 序列（用于后续 ChunkBuilder）
     */
    public List<DocumentNode> convert(List<NodeDTO> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return List.of();
        }
        return dtos.stream()
                .map(this::convertNode)
                .toList();
    }

    /**
     * 单个 NodeDTO 转换为 DocumentNode。
     *
     * @param dto 单个 NodeDTO
     * @return 对应的 DocumentNode 实现
     */
    private DocumentNode convertNode(NodeDTO dto) {
        String typeStr = dto.getType();
        if (typeStr == null || typeStr.isBlank()) {
            log.warn("NodeDTO 缺少 type 字段，id: {}", dto.getId());
            typeStr = "text";
        }

        NodeType type;
        try {
            type = NodeType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知的 Node 类型: {}，id: {}，fallback 为 TEXT", typeStr, dto.getId());
            type = NodeType.TEXT;
        }

        // 构建 metadata
        Map<String, Object> metadata = buildMetadata(dto);

        // 根据 type 创建对应的 Node 实现
        switch (type) {
            case TEXT:
                return TextNode.builder()
                        .text(dto.getText())
                        .metadata(metadata)
                        .build();

            case HEADING:
                return HeadingNode.builder()
                        .text(dto.getText())
                        .level(dto.getLevel())
                        .metadata(metadata)
                        .build();

            case IMAGE:
                return ImageNode.builder()
                        .imagePath(dto.getImagePath())
                        .caption(dto.getCaption())
                        .metadata(metadata)
                        .build();

            case CODE:
                return CodeNode.builder()
                        .code(dto.getText())
                        .language(dto.getLanguage())
                        .metadata(metadata)
                        .build();

            case TABLE:
                return TableNode.builder()
                        .html(dto.getHtml())
                        .metadata(metadata)
                        .build();

            case FORMULA:
                return FormulaNode.builder()
                        .formula(dto.getText())
                        .metadata(metadata)
                        .build();

            default:
                log.warn("未处理的 Node 类型: {}，id: {}，fallback 为 TextNode", type, dto.getId());
                return TextNode.builder()
                        .text(dto.getText() != null ? dto.getText() : "")
                        .metadata(metadata)
                        .build();
        }
    }

    /**
     * 构建 Node metadata。
     * 从 NodeDTO 提取通用字段和特定字段，统一存入 metadata Map。
     *
     * @param dto NodeDTO
     * @return metadata Map
     */
    private Map<String, Object> buildMetadata(NodeDTO dto) {
        Map<String, Object> metadata = new HashMap<>();

        // 通用字段
        if (dto.getId() != null) {
            metadata.put("id", dto.getId());
        }
        if (dto.getPage() != null) {
            metadata.put("page", dto.getPage());
        }
        if (dto.getBbox() != null) {
            metadata.put("bbox", dto.getBbox());
        }

        // 特定字段（根据 type）
        if (dto.getLevel() != null) {
            metadata.put("level", dto.getLevel());
        }
        if (dto.getLanguage() != null) {
            metadata.put("language", dto.getLanguage());
        }
        if (dto.getImagePath() != null) {
            metadata.put("imagePath", dto.getImagePath());
        }
        if (dto.getHash() != null) {
            metadata.put("hash", dto.getHash());
        }
        if (dto.getCaption() != null) {
            metadata.put("caption", dto.getCaption());
        }
        if (dto.getHtml() != null) {
            metadata.put("html", dto.getHtml());
        }
        if (dto.getRowCount() != null) {
            metadata.put("rowCount", dto.getRowCount());
        }
        if (dto.getColCount() != null) {
            metadata.put("colCount", dto.getColCount());
        }

        // Python 侧结构识别产出的标题路径与父子关系（阶段二协议扩展）
        if (dto.getTitlePath() != null) {
            metadata.put("titlePath", dto.getTitlePath());
        }
        if (dto.getParentId() != null) {
            metadata.put("parentId", dto.getParentId());
        }

        return metadata;
    }
}