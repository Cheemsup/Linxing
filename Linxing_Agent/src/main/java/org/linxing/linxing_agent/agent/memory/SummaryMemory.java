package org.linxing.linxing_agent.agent.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class SummaryMemory extends WindowMemory {

    private static final Logger log = LoggerFactory.getLogger(SummaryMemory.class);
    private static final int SUMMARY_TRIGGER_RATIO = 2;

    private final int maxTokens;
    private final OpenAiChatModel summaryModel;
    @Getter
    private String conversationSummary;

    public SummaryMemory(int maxMessages, int maxTokens, OpenAiChatModel summaryModel) {
        super(maxMessages);
        this.maxTokens = maxTokens;
        this.summaryModel = summaryModel;
    }

    public void summarizeIfNeeded() {
        int estimatedTokens = estimateTotalTokens();
        if (estimatedTokens <= maxTokens) {
            return;
        }

        List<ChatMessage> allMessages = messages();
        if (allMessages.size() < 4) {
            return;
        }

        List<ChatMessage> toSummarize = new ArrayList<>();
        int charsToCut = (estimatedTokens - maxTokens / SUMMARY_TRIGGER_RATIO) * 2;
        int accumulated = 0;

        for (ChatMessage msg : allMessages) {
            if (msg instanceof SystemMessage) continue;
            if (msg instanceof AiMessage ai && ai.hasToolExecutionRequests()) continue;
            String text = extractText(msg);
            accumulated += text != null ? text.length() : 0;
            if (accumulated > charsToCut) break;
            toSummarize.add(msg);
        }

        if (toSummarize.isEmpty()) {
            return;
        }

        String dialogText = buildDialogText(toSummarize);
        String existingSummary = conversationSummary;
        String summaryPrompt = existingSummary != null
                ? "之前的对话摘要：\n" + existingSummary + "\n\n请将以下新对话内容整合到上述摘要中，保留所有关键信息：\n" + dialogText
                : "请用2-3句话总结以下对话的关键信息，保留所有重要事实和结论：\n" + dialogText;

        try {
            String summary = summaryModel.chat(summaryPrompt);
            if (summary != null && !summary.isBlank()) {
                conversationSummary = summary;
                super.clear();
                if (getSystemMessage() != null) {
                    add(getSystemMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[SummaryMemory] 摘要生成失败: {}", e.getMessage());
        }
    }

    private int estimateTotalTokens() {
        int total = 0;
        for (ChatMessage msg : messages()) {
            String text = extractText(msg);
            if (text != null) {
                total += text.length() / 2;
            }
        }
        return total;
    }

    private String extractText(ChatMessage msg) {
        if (msg instanceof UserMessage) {
            return ((UserMessage) msg).singleText();
        }
        if (msg instanceof AiMessage) {
            return ((AiMessage) msg).text();
        }
        if (msg instanceof SystemMessage) {
            return ((SystemMessage) msg).text();
        }
        return null;
    }

    private String buildDialogText(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            String text = extractText(msg);
            if (text != null && !text.isBlank()) {
                String prefix = "";
                if (msg instanceof UserMessage) {
                    prefix = "用户：";
                } else if (msg instanceof AiMessage) {
                    prefix = "助手：";
                }
                sb.append(prefix).append(text).append("\n");
            }
        }
        return sb.toString();
    }

    @Override
    public void clear() {
        super.clear();
        conversationSummary = null;
    }
}
