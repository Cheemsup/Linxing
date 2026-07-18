package org.linxing.linxing_agent.agent.memory.deprecated;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.Getter;

import org.linxing.linxing_agent.agent.memory.WindowMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 属于旧体系的简单上下文管理机制的一部分，现在由于重新设计了上下文管理机制，已经不再使用。
 * 
 * @Deprecated
 * 
 */
@Deprecated
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

        // 计算需要切割的字符数
        int charsToCut = (estimatedTokens - maxTokens / SUMMARY_TRIGGER_RATIO) * 2;

        // 找到分界点：以"工具调用组"为原子单位，确保不拆散 AiMessage+ToolResult 对
        int cutIndex = findCutIndex(allMessages, charsToCut);
        if (cutIndex <= 0) {
            return;
        }

        // 收集待摘要的消息（0 ~ cutIndex-1），跳过 SystemMessage
        List<ChatMessage> toSummarize = new ArrayList<>();
        for (int i = 0; i < cutIndex; i++) {
            ChatMessage msg = allMessages.get(i);
            if (msg instanceof SystemMessage) continue;
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
                SystemMessage savedSystemMsg = getSystemMessage();
                super.clear();
                if (savedSystemMsg != null) {
                    setSystemMessage(savedSystemMsg);
                }
                add(SystemMessage.from("【对话历史摘要】\n" + summary));
            }
        } catch (Exception e) {
            log.warn("[SummaryMemory] 摘要生成失败: {}", e.getMessage());
        }
    }

    /**
     * 找到分界点索引，确保不在工具调用组中间切割。
     * 工具调用组 = AiMessage(hasToolExecutionRequests) + 紧跟的所有 ToolExecutionResultMessage
     * 分界点必须落在组的最后一条消息之后，保证被摘要和被保留的消息都是完整的组
     */
    private int findCutIndex(List<ChatMessage> allMessages, int charsToCut) {
        int accumulated = 0;
        int i = 0;
        while (i < allMessages.size() && accumulated < charsToCut) {
            ChatMessage msg = allMessages.get(i);
            if (msg instanceof SystemMessage) {
                i++;
                continue;
            }
            String text = extractText(msg);
            accumulated += text != null ? text.length() : 0;

            // 如果当前消息是工具调用组的开始，将整个组一起跳过
            if (msg instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                // 跳过紧跟的所有 ToolExecutionResultMessage
                while (i + 1 < allMessages.size()
                        && allMessages.get(i + 1) instanceof ToolExecutionResultMessage) {
                    i++;
                    text = extractText(allMessages.get(i));
                    accumulated += text != null ? text.length() : 0;
                }
            }
            i++;
        }
        return i;
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
        if (msg instanceof ToolExecutionResultMessage) {
            return ((ToolExecutionResultMessage) msg).text();
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
                } else if (msg instanceof ToolExecutionResultMessage) {
                    prefix = "工具结果：";
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
