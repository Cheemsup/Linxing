package org.linxing.linxing_agent.agent.memory;

import org.springframework.stereotype.Component;

/**
 * AgentMemory 工厂（2-B 简化版）。
 *
 * <p>2-B 起 memory 退化为极简累加器 {@link ListAgentMemory}，不再有 window/summary 分支，
 * 不再需要 llmManager / tokenEstimator / maxMessages / maxTokens / memoryType 等依赖。
 * 摘要走独立持久化路径（{@code SummaryService}，thePlan P1-2），驱逐/Projection 移交
 * ContextBuilder（Rule Set 驱动，2-D 起）。
 */
@Component
public class AgentMemoryFactory {

    public AgentMemory create() {
        return new ListAgentMemory();
    }
}
