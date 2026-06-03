package org.linxing.linxing_agent.agent.memory;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.linxing.linxing_agent.common.config.LlmManager;
import org.linxing.linxing_agent.common.constant.LlmType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AgentMemoryFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryFactory.class);

    private final LlmManager llmManager;

    @Value("${agent.memory.type:window}")
    private String memoryType;

    @Value("${agent.memory.max-messages:40}")
    private int maxMessages;

    @Value("${agent.memory.max-tokens:32000}")
    private int maxTokens;

    public AgentMemoryFactory(LlmManager llmManager) {
        this.llmManager = llmManager;
    }

    public AgentMemory create() {
        if ("summary".equalsIgnoreCase(memoryType)) {
            try {
                OpenAiChatModel summaryModel = llmManager.getModel(LlmType.SUMMARY_MODEL);
                log.info("[AgentMemoryFactory] 创建 SummaryMemory (maxMessages={}, maxTokens={})",
                        maxMessages, maxTokens);
                return new SummaryMemory(maxMessages, maxTokens, summaryModel);
            } catch (Exception e) {
                log.warn("[AgentMemoryFactory] 创建 SummaryMemory 失败，回退到 WindowMemory: {}", e.getMessage());
            }
        }
        log.info("[AgentMemoryFactory] 创建 WindowMemory (maxMessages={})", maxMessages);
        return new WindowMemory(maxMessages);
    }
}
