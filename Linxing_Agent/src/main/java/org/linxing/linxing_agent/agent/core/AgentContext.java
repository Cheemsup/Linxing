package org.linxing.linxing_agent.agent.core;

import org.linxing.linxing_agent.agent.memory.AgentMemory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class AgentContext {

    private final Integer userId;
    private final Integer sessionId;
    private final AgentMemory memory;
    private final String query;
    private final Map<String, Object> metadata;
    private final Map<String, JsonContainer> containers;
    private int stepCount;
    private AgentStepListener stepListener;

    public AgentContext(Integer userId, Integer sessionId, AgentMemory memory, String query) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.memory = memory;
        this.query = query;
        this.metadata = new HashMap<>();
        this.containers = new LinkedHashMap<>();
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

    public String getQuery() {
        return query;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void putMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, Class<T> type) {
        Object value = metadata.get(key);
        return value != null ? (T) value : null;
    }

    public int getStepCount() {
        return stepCount;
    }

    public int incrementStep() {
        return ++stepCount;
    }

    public JsonContainer getContainer(String containerId) {
        return containers.get(containerId);
    }

    public void putContainer(String containerId, JsonContainer container) {
        containers.put(containerId, container);
    }

    public AgentStepListener getStepListener() {
        return stepListener;
    }

    public void setStepListener(AgentStepListener stepListener) {
        this.stepListener = stepListener;
    }
}
