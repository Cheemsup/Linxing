package org.linxing.linxing_agent.agent.memory;

import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 极简累加器实现（2-B 起取代旧 {@code WindowMemory}）。
 *
 * <p>上下文管理改造后，memory 的职责仅剩"运行时对话流的顺序累加"——
 * <ul>
 *   <li>SystemMessage 不再进 memory：由 {@code ContextBuilder.buildMessages} 每轮幂等置于首位</li>
 *   <li>驱逐 / Projection 不再由 memory 负责：移交阶段 2 的 ContextBuilder（Rule Set 驱动，2-D 起）</li>
 *   <li>历史装配由 {@code ChatServiceImpl.chat} 的 Recovery 直接 {@link #addAll} 填入</li>
 * </ul>
 * 故 memory 不再需要窗口、systemMessage 字段、工具调用组驱逐等任何逻辑，退化为纯列表。
 *
 * <p>线程说明：单次 {@code AgentExecutor.execute} 调用内单线程读写，无需同步。
 */
public class ListAgentMemory implements AgentMemory {

    private final List<ChatMessage> messages = new ArrayList<>();

    @Override
    public void add(ChatMessage message) {
        messages.add(message);
    }

    @Override
    public void addAll(List<ChatMessage> messages) {
        this.messages.addAll(messages);
    }

    @Override
    public List<ChatMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    @Override
    public void clear() {
        messages.clear();
    }

    @Override
    public int size() {
        return messages.size();
    }
}
