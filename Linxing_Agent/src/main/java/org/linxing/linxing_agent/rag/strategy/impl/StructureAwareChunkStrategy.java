package org.linxing.linxing_agent.rag.strategy.impl;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.constant.ChunkType;
import org.linxing.linxing_agent.rag.constant.RagParameters;
import org.linxing.linxing_agent.rag.entity.ChunkResult;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategy;
import org.linxing.linxing_agent.rag.strategy.ChunkStrategyContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 结构感知分块策略，面向 docx/pdf 等结构化文档，按段落间隔拆分并细化超长段落
 *
 * @deprecated 已废弃。docx/pdf 已统一由 Python 侧 DocumentParser 解析为 Node 序列，
 *             结构识别在 Node 层完成，本策略整体被取代。保留仅供历史参考，后续应删除。
 */
@Deprecated
@Slf4j
@Component("structureAwareChunkStrategy")
public class StructureAwareChunkStrategy implements ChunkStrategy {

    private static final Set<String> STRUCTURED_EXTENSIONS = Set.of("docx", "pdf", "doc");

    @Override
    public boolean supports(ChunkStrategyContext context) {
        String ext = context.getFileType();
        if (ext != null && STRUCTURED_EXTENSIONS.contains(ext.toLowerCase())) {
            return true;
        }
        return false;
    }

    @Override
    public List<ChunkResult> execute(ChunkStrategyContext context) {
        int maxChunkSize = context.getMaxChunkSize() != null ? context.getMaxChunkSize() : 800;
        int chunkOverlap = context.getChunkOverlap() != null ? context.getChunkOverlap() : 50;
        String fullText = context.getFullText();

        RecursiveTextSplitter refinementPipeline = new RecursiveTextSplitter(maxChunkSize, chunkOverlap);

        List<ChunkResult> results = new ArrayList<>();

        // For docx/pdf, we rely on pre-extracted text; split by major paragraph gaps
        String[] sections = fullText.split("\\n{3,}");
        if (sections.length <= 1) {
            sections = fullText.split("\\n{2,}");
        }

        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.length() <= maxChunkSize) {
                results.add(ChunkResult.builder()
                        .parentChunkId(null)
                        .chunkLevel(RagParameters.CHUNK_LEVEL_2)
                        .chunkText(trimmed)
                        .titlePath(null)
                        .chunkType(ChunkType.GENERAL)
                        .sourceStrategy("StructureAwareChunkStrategy")
                        .build());
            } else {
                List<String> subChunks = refinementPipeline.refine(trimmed);
                for (String subText : subChunks) {
                    if (!subText.isBlank()) {
                        results.add(ChunkResult.builder()
                                .parentChunkId(null)
                                .chunkLevel(RagParameters.CHUNK_LEVEL_2)
                                .chunkText(subText)
                                .titlePath(null)
                                .chunkType(ChunkType.GENERAL)
                                .sourceStrategy("StructureAwareChunkStrategy")
                                .build());
                    }
                }
            }
        }

        log.info("StructureAwareChunkStrategy 分块完成，共 {} 个片段", results.size());
        return results;
    }
}
