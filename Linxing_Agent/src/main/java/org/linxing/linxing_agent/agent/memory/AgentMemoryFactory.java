package org.linxing.linxing_agent.agent.memory;

import org.springframework.stereotype.Component;

/**
 * AgentMemory 工厂
 *
 * //TODO：如此简化的类貌似不必要存在了后续考虑删除以及替代
 */
@Component
public class AgentMemoryFactory {

    public AgentMemory create() {
        return new ListAgentMemory();
    }
}
