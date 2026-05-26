package org.linxing.linxing_agent.agent.core;

public interface AgentStepListener {

    void onStep(AgentStepEvent event);

    default void onStream(String token) {
        // 默认空实现，不关心流式token的监听器可忽略
    }
}
