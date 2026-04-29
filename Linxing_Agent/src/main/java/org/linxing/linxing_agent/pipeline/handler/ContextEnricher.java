package org.linxing.linxing_agent.pipeline.handler;

import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.constant.ChunkTypeConstants;
import org.linxing.linxing_agent.entity.Chunk;
import org.linxing.linxing_agent.entity.DocRecord;
import org.linxing.linxing_agent.pipeline.ChunkProcessingContext;
import org.linxing.linxing_agent.pipeline.ChunkProcessingHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 上下文补充器（Order=2），对 context_weak 类型的短文本 Chunk 调用 LLM 生成背景描述，增强检索语义。
 */
@Slf4j
@Component
@Order(2)
public class ContextEnricher implements ChunkProcessingHandler {

    private static final Pattern THINK_TAG = Pattern.compile("<think>[\\s\\S]*?</think>", Pattern.CASE_INSENSITIVE);

    private final OpenAiChatModel chatModel;

    public ContextEnricher(OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public boolean handle(ChunkProcessingContext context) {
        Chunk chunk = context.getChunk();

        if (!ChunkTypeConstants.CONTEXT_WEAK.equals(chunk.getChunkType())
                || isNotBlank(chunk.getContextPrefix())) {
            return true;
        }

        String chunkText = chunk.getChunkText();
        if (chunkText == null || chunkText.isBlank()) {
            return true;
        }

        try {
            String prefix = generateContextPrefix(chunkText, context.getDocument());
            chunk.setContextPrefix(prefix);
            log.debug("Chunk {} 补充背景信息: {}", chunk.getId(),
                    prefix.length() > 60 ? prefix.substring(0, 60) + "..." : prefix);
        } catch (Exception e) {
            log.warn("Chunk {} 背景信息补充失败: {}", chunk.getId(), e.getMessage());
        }

        return true;
    }

    private String generateContextPrefix(String chunkText, DocRecord document) {
        String docTitle = document != null && document.getFileName() != null
                ? document.getFileName()
                : "未知文档";

        String prompt = String.format(
                "以下文本片段来自文档\"%s\"，请用1-2句话描述它的背景和主题：\n%s",
                docTitle, chunkText);

        String response = chatModel.chat(prompt);
        if (response != null) {
            String cleaned = THINK_TAG.matcher(response).replaceAll("").trim();
            return cleaned.replaceAll("\\s+", " ");
        }
        return "";
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
