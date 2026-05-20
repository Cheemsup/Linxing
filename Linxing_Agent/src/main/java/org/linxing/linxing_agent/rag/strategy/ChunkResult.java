package org.linxing.linxing_agent.rag.strategy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 分块结果数据类，表示一次分块策略执行后产出的单个文本块，包含层级、类型、标题路径等元信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkResult {

    private Integer parentChunkId;

    private Short chunkLevel;

    private String chunkText;

    private String titlePath;

    private String chunkType;

    private String sourceStrategy;

    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
