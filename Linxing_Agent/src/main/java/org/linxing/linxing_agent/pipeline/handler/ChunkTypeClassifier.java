package org.linxing.linxing_agent.pipeline.handler;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.constant.ChunkTypeConstants;
import org.linxing.linxing_agent.entity.Chunk;
import org.linxing.linxing_agent.pipeline.ChunkProcessingContext;
import org.linxing.linxing_agent.pipeline.ChunkProcessingHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 分块类型分类器（Order=0），根据文本特征将 Chunk 分类为 code/table/qa_pair/section/context_weak/general 等类型。
 */
@Slf4j
@Component
@Order(0)
public class ChunkTypeClassifier implements ChunkProcessingHandler {

    private static final Pattern CODE_BLOCK = Pattern.compile("```");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$", Pattern.MULTILINE);
    private static final Pattern QA_PATTERN = Pattern.compile("^(Q:|A:|问：|答：)", Pattern.MULTILINE);
    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+", Pattern.MULTILINE);

    @Override
    public int order() {
        return 0;
    }

    @Override
    public boolean handle(ChunkProcessingContext context) {
        Chunk chunk = context.getChunk();
        String text = chunk.getChunkText();
        if (text == null || text.isEmpty()) {
            return true;
        }

        String existingType = chunk.getChunkType();

        String detected = classify(text, existingType);
        if (!detected.equals(existingType)) {
            chunk.setChunkType(detected);
            log.debug("Chunk {} 类型分类: {} → {}", chunk.getId(), existingType, detected);
        }

        return true;
    }

    private String classify(String text, String existingType) {
        if (CODE_BLOCK.matcher(text).find()) {
            return ChunkTypeConstants.CODE;
        }

        if (TABLE_ROW.matcher(text).find()) {
            return ChunkTypeConstants.TABLE;
        }

        if (QA_PATTERN.matcher(text).find()) {
            return ChunkTypeConstants.QA_PAIR;
        }

        if (HEADING_PATTERN.matcher(text).find()) {
            return ChunkTypeConstants.SECTION;
        }

        if (existingType != null && isStructuredType(existingType)) {
            return existingType;
        }

        if (isWeakContext(text)) {
            return ChunkTypeConstants.CONTEXT_WEAK;
        }

        return ChunkTypeConstants.GENERAL;
    }

    private boolean isStructuredType(String chunkType) {
        return ChunkTypeConstants.SECTION.equals(chunkType) || ChunkTypeConstants.CODE.equals(chunkType)
                || ChunkTypeConstants.TABLE.equals(chunkType) || ChunkTypeConstants.QA_PAIR.equals(chunkType);
    }

    private boolean isWeakContext(String text) {
        String trimmed = text.trim();
        if (trimmed.length() < 50) {
            return true;
        }

        String[] words = trimmed.split("\\s+");
        return words.length < 15 && !trimmed.contains("。") && !trimmed.contains(".");
    }
}
