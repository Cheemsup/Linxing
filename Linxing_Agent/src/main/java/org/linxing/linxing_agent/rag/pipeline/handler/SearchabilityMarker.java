package org.linxing.linxing_agent.rag.pipeline.handler;

import org.linxing.linxing_agent.rag.constant.RagParameters;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.entity.Chunk;
import org.linxing.linxing_agent.rag.pipeline.ChunkProcessingContext;
import org.linxing.linxing_agent.rag.pipeline.ChunkProcessingHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 可检索标记器（Order=4），根据 Chunk 层级设置 isSearchable 标志，仅 Level 2 小块参与向量检索。
 */
@Slf4j
@Component
@Order(4)
public class SearchabilityMarker implements ChunkProcessingHandler {

    @Override
    public int order() {
        return 4;
    }

    @Override
    public boolean handle(ChunkProcessingContext context) {
        Chunk chunk = context.getChunk();

        if (chunk.getIsSearchable() == null) {
            boolean searchable = chunk.getChunkLevel() != null && chunk.getChunkLevel() == RagParameters.CHUNK_LEVEL_2;
            chunk.setIsSearchable(searchable);
            log.debug("Chunk {} 设置 isSearchable={} (chunkLevel={})",
                    chunk.getId(), searchable, chunk.getChunkLevel());
        }

        return true;
    }
}
