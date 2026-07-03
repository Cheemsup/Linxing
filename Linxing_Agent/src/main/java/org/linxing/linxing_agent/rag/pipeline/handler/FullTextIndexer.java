package org.linxing.linxing_agent.rag.pipeline.handler;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.entity.Chunk;
import org.linxing.linxing_agent.rag.pipeline.ChunkProcessingContext;
import org.linxing.linxing_agent.rag.pipeline.ChunkProcessingHandler;
import org.linxing.linxing_agent.rag.utils.ChineseSegmenter;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 全文索引器（Order=3），将 Chunk 文本经中文分词后写入 tsContent，供 PostgreSQL 全文检索使用。
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
        // 优先使用 indexText（Index Render，含 VLM/LLM 语义增强结果），缺失时回退 chunkText
        String indexText = chunk.getIndexText();
        boolean useIndexText = indexText != null && !indexText.isEmpty();
        String text = useIndexText ? indexText : chunk.getChunkText();

        if (text != null && !text.isEmpty()) {
            String segmented = ChineseSegmenter.segment(text);
            chunk.setTsContent(segmented);
            log.debug("Chunk {} 全文索引预处理完成（{}），原始 {} 字符，分词后 {} 字符",
                    chunk.getId(),
                    useIndexText ? "indexText" : "chunkText",
                    text.length(), segmented.length());
        }

        return true;
    }
}
