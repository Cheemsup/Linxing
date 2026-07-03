package org.linxing.linxing_agent.rag.strategy;

import dev.langchain4j.data.document.Document;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 分块策略上下文，封装文件类型、全文内容、分块参数等信息，供策略选择与执行时使用
 *
 * @deprecated 已废弃。仅服务于已废弃的 {@link ChunkStrategy} 旧路径，Node 体系下不再使用。
 */
@Deprecated
@Data
@Builder
public class ChunkStrategyContext {

    private String fileType;

    private String fileName;

    private String fullText;

    private Document document;

    private Integer maxChunkSize;

    private Integer chunkOverlap;

    /** 标题区块拆分阈值：超长标题区块按句子拆分时的字符上限（如 1000）。仅对 > 此阈值的区块做拆分 */
    private Integer chunkThreshold;

    @Builder.Default
    private Map<String, Object> extra = new HashMap<>();
}
