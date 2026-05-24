package org.linxing.linxing_agent.agent.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

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

    private void evict() {
        while (messages.size() > maxMessages) {
            ChatMessage evicted = messages.remove(0);
            if (evicted.type() == dev.langchain4j.data.message.ChatMessageType.AI) {
                evictOrphanToolResults();
            }
        }
    }

    private void evictOrphanToolResults() {
        Iterator<ChatMessage> it = messages.iterator();
        while (it.hasNext()) {
            ChatMessage msg = it.next();
            if (msg instanceof ToolExecutionResultMessage) {
                it.remove();
                break;
            }
        }
    }
}
