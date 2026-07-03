package org.linxing.linxing_agent.rag.pipeline.handler;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.common.config.LlmManager;
import org.linxing.linxing_agent.rag.constant.ChunkType;
import org.linxing.linxing_agent.common.constant.LlmType;
import org.linxing.linxing_agent.rag.entity.Chunk;
import org.linxing.linxing_agent.rag.entity.DocRecord;
import org.linxing.linxing_agent.rag.pipeline.ChunkProcessingContext;
import org.linxing.linxing_agent.rag.pipeline.ChunkProcessingHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 上下文补充器（Order=2），对 context_weak 类型的短文本 Chunk 调用 LLM 生成背景描述，增强检索语义。
 *
 * TODO：改用Node体系后，此责任链节点可能需要废弃掉
 */
@Slf4j
@Component
@Order(2)
public class ContextEnricher implements ChunkProcessingHandler {

    private static final Pattern THINK_TAG = Pattern.compile("<think>[\\s\\S]*?</think>", Pattern.CASE_INSENSITIVE);

    /** 目标片段在全文中前后截取的邻近窗口字符数 */
    private static final int NEIGHBOR_WINDOW = 400;
    /** 完整片段定位失败时，用于二次探测的片段前缀长度 */
    private static final int LOCATE_PREFIX_LEN = 60;
    /** 二次探测前缀的最小有效长度，过短则放弃定位 */
    private static final int LOCATE_MIN_LEN = 10;

    private final LlmManager llmManager;

    public ContextEnricher(LlmManager llmManager) {
        this.llmManager = llmManager;
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public boolean handle(ChunkProcessingContext context) {
        Chunk chunk = context.getChunk();

        if (!ChunkType.CONTEXT_WEAK.equals(chunk.getChunkType())
                || isNotBlank(chunk.getContextPrefix())) {
            return true;
        }

        // 优先使用 indexText（Index Render，含语义增强结果），与 fullDocumentText 同源，邻近定位才能成功
        String chunkText = isNotBlank(chunk.getIndexText()) ? chunk.getIndexText() : chunk.getChunkText();
        if (chunkText == null || chunkText.isBlank()) {
            return true;
        }

        try {
            String prefix = generateContextPrefix(chunkText, context.getDocument(), context.getFullDocumentText());
            chunk.setContextPrefix(prefix);
            log.debug("Chunk {} 补充背景信息: {}", chunk.getId(),
                    prefix.length() > 60 ? prefix.substring(0, 60) + "..." : prefix);
        } catch (Exception e) {
            log.warn("Chunk {} 背景信息补充失败: {}", chunk.getId(), e.getMessage());
        }

        return true;
    }

    /**
     * 为短文本 Chunk 生成上下文背景描述。
     * 若能从全文中定位片段位置，则携带前后邻近内容一并发给 LLM；否则退回仅用 chunkText + 文件名。
     *
     * @param chunkText         当前 chunk 的文本
     * @param document          所属文档记录
     * @param fullDocumentText  文档全文（可能为 null）
     * @return LLM 生成的背景描述
     */
    private String generateContextPrefix(String chunkText, DocRecord document, String fullDocumentText) {
        String docTitle = document != null && document.getFileName() != null
                ? document.getFileName()
                : "未知文档";

        NeighborContext neighbor = (fullDocumentText != null && !fullDocumentText.isBlank())
                ? extractNeighborContext(chunkText, fullDocumentText)
                : null;

        String prompt;
        if (neighbor != null) {
            // 携带邻近上下文的增强 prompt
            prompt = String.format(
                    "以下文本片段来自文档\"%s\"的中间部分。\n" +
                    "【邻近上文】%s\n" +
                    "【目标片段】%s\n" +
                    "【邻近下文】%s\n" +
                    "请用1-2句话描述目标片段的背景和主题，注意结合邻近上下文：",
                    docTitle, neighbor.before(), chunkText, neighbor.after());
        } else {
            // 兜底：仅 chunkText + 文件名
            prompt = String.format(
                    "以下文本片段来自文档\"%s\"，请用1-2句话描述它的背景和主题：\n%s",
                    docTitle, chunkText);
        }

        String response = llmManager.getModel(LlmType.CONTEXT_ENRICH_MODEL).chat(prompt);
        if (response != null) {
            String cleaned = THINK_TAG.matcher(response).replaceAll("").trim();
            return cleaned.replaceAll("\\s+", " ");
        }
        return "";
    }

    /**
     * 从全文中定位 chunkText，提取其前后 NEIGHBOR_WINDOW 字符的邻近上下文。
     * 定位策略：先用完整片段匹配；失败且片段足够长时，用片段前缀二次探测。
     *
     * @param chunkText         目标片段
     * @param fullDocumentText  文档全文
     * @return 前后邻近上下文；定位失败返回 null
     */
    private NeighborContext extractNeighborContext(String chunkText, String fullDocumentText) {
        // 优先用完整片段定位
        int idx = fullDocumentText.indexOf(chunkText);

        // 完整匹配失败，用片段前缀二次探测（片段可能经 trim/裁剪与全文不完全一致）
        if (idx < 0 && chunkText.length() >= LOCATE_MIN_LEN) {
            int prefixLen = Math.min(LOCATE_PREFIX_LEN, chunkText.length());
            idx = fullDocumentText.indexOf(chunkText.substring(0, prefixLen));
        }

        if (idx < 0) {
            return null;
        }

        int chunkEnd = idx + chunkText.length();
        int start = Math.max(0, idx - NEIGHBOR_WINDOW);
        int end = Math.min(fullDocumentText.length(), chunkEnd + NEIGHBOR_WINDOW);

        String before = fullDocumentText.substring(start, idx).trim();
        String after = fullDocumentText.substring(chunkEnd, end).trim();

        // 前后都为空（如片段刚好占满文档），视为无有效邻近上下文
        if (before.isEmpty() && after.isEmpty()) {
            return null;
        }
        return new NeighborContext(before, after);
    }

    /** 邻近上下文：目标片段在全文中的前文与后文 */
    private record NeighborContext(String before, String after) {
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
