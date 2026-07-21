package org.linxing.linxing_agent.agent.memory.window.runtime;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

public interface AgentMemory {

    void add(ChatMessage message);

    void addAll(List<ChatMessage> messages);

    List<ChatMessage> messages();

    void clear();

    int size();
}
