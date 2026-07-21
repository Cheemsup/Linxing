package org.linxing.linxing_agent.agent.memory.deprecated;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.linxing.linxing_agent.agent.memory.window.runtime.AgentMemory;

/**
 * 属于旧体系的简单上下文管理机制的一部分，现在由于重新设计了上下文管理机制，已经不再使用。
 * 
 * WindowMemory
 * 
 * @Deprecated
 */
@Deprecated
public class WindowMemory implements AgentMemory {

    private final int maxMessages;
    private final List<ChatMessage> messages = new ArrayList<>();
    @Getter
    private SystemMessage systemMessage;

    public WindowMemory(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public void setSystemMessage(SystemMessage message) {
        this.systemMessage = message;
    }

    @Override
    public void add(ChatMessage message) {
        messages.add(message);
        evict();
    }

    @Override
    public void addAll(List<ChatMessage> messages) {
        this.messages.addAll(messages);
        evict();
    }

    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> result = new ArrayList<>();
        if (systemMessage != null) {
            result.add(systemMessage);
        }
        result.addAll(messages);
        return Collections.unmodifiableList(result);
    }

    @Override
    public void clear() {
        messages.clear();
        systemMessage = null;
    }

    @Override
    public int size() {
        return (systemMessage != null ? 1 : 0) + messages.size();
    }

    /**
     * 驱逐超出窗口大小的消息，以"工具调用组"为原子单位。
     * 工具调用组 = AiMessage(hasToolExecutionRequests) + 紧跟的所有 ToolExecutionResultMessage
     * 驱逐时必须整组移除，不能拆散 AiMessage 和其对应的 ToolResult
     */
    private void evict() {
        while (messages.size() > maxMessages && !messages.isEmpty()) {
            int groupEnd = findToolCallGroupEnd(0);
            // 移除从0到groupEnd（含）的所有消息，即一个完整的工具调用组或单条消息
            for (int i = 0; i <= groupEnd; i++) {
                messages.remove(0);
            }
        }
    }

    /**
     * 从指定位置开始，找到一个"工具调用组"的结束索引。
     * 如果起始位置是 AiMessage(hasToolExecutionRequests)，则组包含它和紧跟的所有 ToolExecutionResultMessage；
     * 否则组只包含起始位置这一条消息。
     * @return 组内最后一条消息的相对索引（相对于start）
     */
    private int findToolCallGroupEnd(int start) {
        ChatMessage first = messages.get(start);
        if (first instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
            // AiMessage + 紧跟的所有 ToolExecutionResultMessage 构成一组
            int end = start;
            while (end + 1 < messages.size()
                    && messages.get(end + 1) instanceof ToolExecutionResultMessage) {
                end++;
            }
            return end - start;
        }
        // 非工具调用的消息，自成一组
        return 0;
    }
}
