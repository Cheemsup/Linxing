package org.linxing.linxing_agent.strategy.impl;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.constant.ChunkType;
import org.linxing.linxing_agent.constant.RagParameters;
import org.linxing.linxing_agent.strategy.RecursiveTextSplitter;
import org.linxing.linxing_agent.strategy.ChunkResult;
import org.linxing.linxing_agent.strategy.ChunkStrategy;
import org.linxing.linxing_agent.strategy.ChunkStrategyContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 行式分块策略，面向 log/csv/txt 等行式文本，按空行分段落，再对超长段落进行细化拆分
 */
@Slf4j
@Component("lineBasedChunkStrategy")
public class LineBasedChunkStrategy implements ChunkStrategy {

    private static final Set<String> LINE_BASED_EXTENSIONS = Set.of("log", "csv", "tsv", "txt");
    private static final String BLANK_LINE_SEP = "\\n\\s*\\n";

    @Override
    public boolean supports(ChunkStrategyContext context) {
        String ext = context.getFileType();
        if (ext != null && LINE_BASED_EXTENSIONS.contains(ext.toLowerCase())) {
            return true;
        }

        String text = context.getFullText();
        if (text == null || text.isEmpty()) {
            return false;
        }

        if (isLineBasedContent(text)) {
            return true;
        }

        if (hasFrequentBlankLines(text)) {
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

        List<String> paragraphs = splitByBlankLines(fullText);
        List<ChunkResult> results = new ArrayList<>();

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.length() <= maxChunkSize) {
                results.add(ChunkResult.builder()
                        .parentChunkId(null)
                        .chunkLevel(RagParameters.CHUNK_LEVEL_2)
                        .chunkText(trimmed)
                        .titlePath(null)
                        .chunkType(trimmed.startsWith("#") ? ChunkType.SECTION : ChunkType.GENERAL)
                        .sourceStrategy("LineBasedChunkStrategy")
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
                                .sourceStrategy("LineBasedChunkStrategy")
                                .build());
                    }
                }
            }
        }

        log.info("LineBasedChunkStrategy 分块完成，共 {} 个片段", results.size());
        return results;
    }

    private List<String> splitByBlankLines(String text) {
        List<String> paragraphs = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return paragraphs;
        }
        String[] parts = text.split(BLANK_LINE_SEP, -1);
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                paragraphs.add(part);
            }
        }
        return paragraphs;
    }

    private boolean isLineBasedContent(String text) {
        String[] lines = text.split("\n");
        if (lines.length < 5) {
            return false;
        }

        int nonEmptyCount = 0;
        double totalLen = 0;
        double sumSquaredLen = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                nonEmptyCount++;
                totalLen += trimmed.length();
                sumSquaredLen += trimmed.length() * trimmed.length();
            }
        }

        if (nonEmptyCount < 3) {
            return false;
        }

        double meanLen = totalLen / nonEmptyCount;
        double variance = (sumSquaredLen / nonEmptyCount) - (meanLen * meanLen);

        return variance < meanLen * meanLen * 0.5;
    }

    private boolean hasFrequentBlankLines(String text) {
        int blankLineBlockCount = 0;
        boolean inBlank = false;
        int consecutiveBlank = 0;

        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                if (!inBlank) {
                    inBlank = true;
                    consecutiveBlank = 1;
                } else {
                    consecutiveBlank++;
                }
            } else {
                if (inBlank && consecutiveBlank >= 2) {
                    blankLineBlockCount++;
                }
                inBlank = false;
                consecutiveBlank = 0;
            }
        }

        return blankLineBlockCount >= 3;
    }
}
