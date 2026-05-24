package org.linxing.linxing_agent.agent.core;

import org.linxing.linxing_agent.agent.memory.AgentMemory;

public class AgentContext {

    private final Integer userId;
    private final Integer sessionId;
    private final AgentMemory memory;
    private int stepCount;

    public AgentContext(Integer userId, Integer sessionId, AgentMemory memory) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.memory = memory;
        this.stepCount = 0;
    }

    public Integer getUserId() {
        return userId;
    }

    public Integer getSessionId() {
        return sessionId;
    }

    public AgentMemory getMemory() {
        return memory;
    }

    public int getStepCount() {
        return stepCount;
    }

    public int incrementStep() {
        return ++stepCount;
    }
}
