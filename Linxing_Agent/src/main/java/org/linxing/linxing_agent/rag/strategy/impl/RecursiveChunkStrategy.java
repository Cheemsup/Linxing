package org.linxing.linxing_agent.rag.strategy.impl;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.constant.ChunkType;
import org.linxing.linxing_agent.rag.constant.RagParameters;
import org.linxing.linxing_agent.rag.entity.ChunkResult;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategy;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategyContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归分块策略，使用 LangChain4j 的递归字符分割器，作为所有策略都不匹配时的通用兜底方案
 *
 * @deprecated 已废弃。Node 体系下所有文件类型走 NodeBasedChunkBuilder token 装箱，
 *             兜底语义由 Python 侧 linebased_parser 承担。保留仅供历史参考，后续应删除。
 */
@Deprecated
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
                    .chunkLevel(RagParameters.CHUNK_LEVEL_2)
                    .chunkText(segment.text())
                    .titlePath(null)
                    .chunkType(ChunkType.GENERAL)
                    .sourceStrategy("RecursiveChunkStrategy")
                    .build());
        }

        return results;
    }
}
