package org.linxing.linxing_agent.pipeline;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.linxing.linxing_agent.entity.Chunk;
import org.linxing.linxing_agent.entity.DocRecord;

import java.util.HashMap;
import java.util.Map;

/**
 * 分块后处理上下文，封装单个 Chunk 及其所属文档信息，在责任链各 Handler 之间传递。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkProcessingContext {

    private Chunk chunk;

    private DocRecord document;

    private String fullDocumentText;

    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();

    private boolean shouldPersist;
}
