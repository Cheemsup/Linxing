package org.linxing.linxing_agent.pipeline.handler;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.entity.Chunk;
import org.linxing.linxing_agent.pipeline.ChunkProcessingContext;
import org.linxing.linxing_agent.pipeline.ChunkProcessingHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标题路径提取器（Order=1），当策略层未设置 titlePath 时，从当前 Chunk 自身的文本中提取标题回填。
 * 只对无结构感知策略（StructureAware、LineBased、Recursive、Semantic）产生的 chunk 生效，
 * 结构化策略（Markdown、HTML、Code）已在策略层完成标题提取，此处会跳过。
 */
@Slf4j
@Component
@Order(1)
public class TitlePathExtractor implements ChunkProcessingHandler {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile(
            "^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern HTML_HEADING = Pattern.compile(
            "<(h[1-6])[^>]*>(.*?)</\\1>", Pattern.CASE_INSENSITIVE);

    @Override
    public int order() {
        return 1;
    }

    @Override
    public boolean handle(ChunkProcessingContext context) {
        Chunk chunk = context.getChunk();

        if (chunk.getTitlePath() != null && !chunk.getTitlePath().isEmpty()) {
            return true;
        }

        String chunkText = chunk.getChunkText();
        if (chunkText == null || chunkText.isEmpty()) {
            return true;
        }

        String titlePath = extractFromText(chunkText);
        if (titlePath != null) {
            chunk.setTitlePath(titlePath);
            log.debug("Chunk {} 提取标题路径: {}", chunk.getId(), titlePath);
        }

        return true;
    }

    private String extractFromText(String text) {
        String mdTitle = extractMarkdownTitle(text);
        if (mdTitle != null) {
            return mdTitle;
        }

        String htmlTitle = extractHtmlTitle(text);
        if (htmlTitle != null) {
            return htmlTitle;
        }

        return null;
    }

    private String extractMarkdownTitle(String text) {
        Matcher matcher = MARKDOWN_HEADING.matcher(text);
        if (matcher.find()) {
            String headingText = matcher.group(2).trim();
            if (!headingText.isEmpty() && headingText.length() < 120) {
                return headingText;
            }
        }
        return null;
    }

    private String extractHtmlTitle(String text) {
        Matcher matcher = HTML_HEADING.matcher(text);
        if (matcher.find()) {
            String headingText = matcher.group(2).replaceAll("<[^>]+>", "").trim();
            if (!headingText.isEmpty() && headingText.length() < 120) {
                return headingText;
            }
        }
        return null;
    }
}
