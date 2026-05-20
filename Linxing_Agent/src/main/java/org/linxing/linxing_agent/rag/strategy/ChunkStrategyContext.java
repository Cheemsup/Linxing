package org.linxing.linxing_agent.rag.strategy;

import dev.langchain4j.data.document.Document;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 分块策略上下文，封装文件类型、全文内容、分块参数等信息，供策略选择与执行时使用
 */
@Data
@Builder
public class ChunkStrategyContext {

    private String fileType;

    private String fileName;

    private String fullText;

    private Document document;

    private Integer maxChunkSize;

    private Integer chunkOverlap;

    @Builder.Default
    private Map<String, Object> extra = new HashMap<>();
}
