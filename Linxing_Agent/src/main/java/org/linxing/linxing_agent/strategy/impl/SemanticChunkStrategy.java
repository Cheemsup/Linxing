package org.linxing.linxing_agent.strategy.impl;

import dev.langchain4j.model.openai.OpenAiChatModel;
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
 * 语义分块策略，调用 LLM 识别语义边界进行智能分块，仅当用户显式指定时激活，超长文本自动降级为简单均分
 */
@Slf4j
@Component("semanticChunkStrategy")
public class SemanticChunkStrategy implements ChunkStrategy {

    private static final int DEFAULT_SEMANTIC_MAX_LENGTH = 10000;

    private final OpenAiChatModel chatModel;

    public SemanticChunkStrategy(OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public boolean supports(ChunkStrategyContext context) {
        return false; 
    }

    @Override
    public List<ChunkResult> execute(ChunkStrategyContext context) {
        String fullText = context.getFullText();
        int semanticMaxLength = getSemanticMaxLength(context);

        if (fullText == null || fullText.isEmpty()) {
            return List.of();
        }

        if (fullText.length() > semanticMaxLength) {
            log.warn("文本长度 {} 超过语义分块上限 {}，降级为简单均分", fullText.length(), semanticMaxLength);
            return fallbackSplit(fullText, context.getMaxChunkSize() != null ? context.getMaxChunkSize() : 800);
        }

        try {
            String prompt = buildPrompt(fullText);
            String response = chatModel.chat(prompt);

            List<ChunkResult> results = parseResponse(response, fullText);
            log.info("SemanticChunkStrategy 分块完成，LLM返回 {} 个片段", results.size());
            return results;
        } catch (Exception e) {
            log.error("LLM语义分块失败: {}", e.getMessage());
            return fallbackSplit(fullText, context.getMaxChunkSize() != null ? context.getMaxChunkSize() : 800);
        }
    }

    private String buildPrompt(String text) {
        return String.format("""
                你是一个文本分块助手。请阅读以下文本，标记出语义上应该分开的边界位置。
                返回格式：JSON 数组，每项为 {start: 起始字符索引, end: 结束字符索引, summary: 该段摘要}

                文本内容：
                %s
                """, text);
    }

    private List<ChunkResult> parseResponse(String response, String fullText) {
        List<ChunkResult> results = new ArrayList<>();

        try {
            String jsonStr = extractJsonArray(response);
            if (jsonStr == null) {
                return fallbackSplit(fullText, 800);
            }

            List<Segment> segments = parseSegments(jsonStr);
            for (Segment seg : segments) {
                if (seg.start() >= 0 && seg.end() <= fullText.length() && seg.start() < seg.end()) {
                    String chunkText = fullText.substring(seg.start(), seg.end()).trim();
                    if (!chunkText.isEmpty()) {
                        results.add(ChunkResult.builder()
                                .parentChunkId(null)
                                .chunkLevel(RagConstants.CHUNK_LEVEL_2)
                                .chunkText(chunkText)
                                .titlePath(seg.summary())
                                .chunkType(ChunkTypeConstants.GENERAL)
                                .sourceStrategy("SemanticChunkStrategy")
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("LLM响应解析失败: {}", e.getMessage());
            return fallbackSplit(fullText, 800);
        }

        return results.isEmpty() ? fallbackSplit(fullText, 800) : results;
    }

    private String extractJsonArray(String response) {
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }

    private List<Segment> parseSegments(String jsonArray) {
        List<Segment> segments = new ArrayList<>();
        // Simple JSON parsing for the expected format
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\{\\s*\"start\"\\s*:\\s*(\\d+)\\s*,\\s*\"end\"\\s*:\\s*(\\d+)\\s*,\\s*\"summary\"\\s*:\\s*\"([^\"]*)\"\\s*\\}");
        java.util.regex.Matcher matcher = pattern.matcher(jsonArray);
        while (matcher.find()) {
            int start = Integer.parseInt(matcher.group(1));
            int end = Integer.parseInt(matcher.group(2));
            String summary = matcher.group(3);
            segments.add(new Segment(start, end, summary));
        }
        return segments;
    }

    private List<ChunkResult> fallbackSplit(String text, int maxChunkSize) {
        List<ChunkResult> results = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChunkSize, text.length());
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                results.add(ChunkResult.builder()
                        .parentChunkId(null)
                        .chunkLevel(RagConstants.CHUNK_LEVEL_2)
                        .chunkText(chunk)
                        .titlePath(null)
                        .chunkType(ChunkTypeConstants.GENERAL)
                        .sourceStrategy("SemanticChunkStrategy")
                        .build());
            }
            start = end;
        }
        return results;
    }

    private int getSemanticMaxLength(ChunkStrategyContext context) {
        if (context.getExtra() != null && context.getExtra().containsKey("semanticMaxLength")) {
            return (int) context.getExtra().get("semanticMaxLength");
        }
        return DEFAULT_SEMANTIC_MAX_LENGTH;
    }

    private record Segment(int start, int end, String summary) {}
}
