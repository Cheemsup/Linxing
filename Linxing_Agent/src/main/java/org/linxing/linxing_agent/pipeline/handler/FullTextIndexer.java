package org.linxing.linxing_agent.pipeline.handler;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.entity.Chunk;
import org.linxing.linxing_agent.pipeline.ChunkProcessingContext;
import org.linxing.linxing_agent.pipeline.ChunkProcessingHandler;
import org.linxing.linxing_agent.utils.ChineseSegmenter;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 全文索引器（Order=3），将 Chunk 文本经中文分词后写入 tsContent，供 PostgreSQL 全文检索使用。
 * TODO:后续优化查询时可以用于构建混合检索（Hybrid Search）—— 向量检索 + 全文检索双路召回，用 RRF 或加权融合排序
 */
@Slf4j
@Component
@Order(3)
public class FullTextIndexer implements ChunkProcessingHandler {

    @Override
    public int order() {
        return 3;
    }

    @Override
    public boolean handle(ChunkProcessingContext context) {
        Chunk chunk = context.getChunk();
        String chunkText = chunk.getChunkText();

        if (chunkText != null && !chunkText.isEmpty()) {
            String segmented = ChineseSegmenter.segment(chunkText);
            chunk.setTsContent(segmented);
            log.debug("Chunk {} 全文索引预处理完成，原始 {} 字符，分词后 {} 字符",
                    chunk.getId(), chunkText.length(), segmented.length());
        }

        return true;
    }
}
