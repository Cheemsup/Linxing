package org.linxing.linxing_agent.rag.node;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 图片节点实现类，表示文档中的图片。
 *
 * originalContent: 图片 URL 或占位符 [IMG_ID]
 * semanticText: VLM 生成的图片描述（待后续增强）
 * metadata.imagePath: 图片存储路径
 * metadata.caption: 图片标题（可选）
 * metadata.width/height: 图片尺寸（可选）
 * metadata.hash: 图片哈希值（用于去重，可选）
 */
@Data
@Builder
public class ImageNode implements DocumentNode {

    /** 图片存储路径（相对路径，如 /chunk_images/1/101/img_001.png） */
    private String imagePath;

    /** 图片标题（可选） */
    private String caption;

    /** VLM 生成的语义描述（增强后填充） */
    @Builder.Default
    private String semanticDescription = null;

    /** 元数据 */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Override
    public NodeType type() {
        return NodeType.IMAGE;
    }

    @Override
    public String originalContent() {
        // 返回占位符格式，用于 Display Render
        String id = getId();
        return "[" + id + "]";
    }

    @Override
    public String semanticText() {
        // 增强后返回 VLM 描述；未增强时返回 caption 或占位信息
        if (semanticDescription != null && !semanticDescription.isBlank()) {
            return semanticDescription;
        }
        if (caption != null && !caption.isBlank()) {
            return "[图片: " + caption + "]";
        }
        return "[图片: " + getId() + "]";
    }

    @Override
    public Map<String, Object> metadata() {
        // 确保 imagePath 和 caption 存在于 metadata 中
        if (imagePath != null && !metadata.containsKey("imagePath")) {
            metadata.put("imagePath", imagePath);
        }
        if (caption != null && !metadata.containsKey("caption")) {
            metadata.put("caption", caption);
        }
        return metadata;
    }

    /**
     * 设置 VLM 生成的语义描述
     *
     * @param description VLM 生成的图片描述
     */
    public void setSemanticDescription(String description) {
        this.semanticDescription = description;
        // 同步更新 metadata
        metadata.put("semantic", description);
    }
}