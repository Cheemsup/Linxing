package org.linxing.linxing_agent.strategy.impl;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.constant.ChunkTypeConstants;
import org.linxing.linxing_agent.constant.RagConstants;
import org.linxing.linxing_agent.strategy.ChunkResult;
import org.linxing.linxing_agent.strategy.ChunkStrategy;
import org.linxing.linxing_agent.strategy.ChunkStrategyContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归分块策略，使用 LangChain4j 的递归字符分割器，作为所有策略都不匹配时的通用兜底方案
 */
@Slf4j
@Component("recursiveChunkStrategy")
public class RecursiveChunkStrategy implements ChunkStrategy {

    @Override
    public boolean supports(ChunkStrategyContext context) {
        return true;
    }

    @Override
    public List<ChunkResult> execute(ChunkStrategyContext context) {
        int maxChunkSize = context.getMaxChunkSize() != null ? context.getMaxChunkSize() : 800;
        int chunkOverlap = context.getChunkOverlap() != null ? context.getChunkOverlap() : 50;

        Document document = context.getDocument();
        if (document == null) {
            document = Document.from(context.getFullText());
        }

        DocumentSplitter splitter = DocumentSplitters.recursive(maxChunkSize, chunkOverlap);
        List<TextSegment> segments = splitter.split(document);

        log.info("RecursiveChunkStrategy 分块完成，共 {} 个片段", segments.size());

        List<ChunkResult> results = new ArrayList<>();
        for (TextSegment segment : segments) {
            results.add(ChunkResult.builder()
                    .parentChunkId(null)
                    .chunkLevel(RagConstants.CHUNK_LEVEL_2)
                    .chunkText(segment.text())
                    .titlePath(null)
                    .chunkType(ChunkTypeConstants.GENERAL)
                    .sourceStrategy("RecursiveChunkStrategy")
                    .build());
        }

        return results;
    }
}
