package org.linxing.linxing_agent.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 大模型配置，对应 application.yaml 中平级的 `llm` 段。
 * 历史上该段嵌套在 `rag:` 之下，现已上移为顶级配置项。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private String defaultProvider;
    private Map<String, LlmProviderConfig> providers = new HashMap<>();
    private Double temperature;
    private int timeoutSeconds;
    private int maxTokens;
    private Retry retry = new Retry();

    /**
     * LLM 调用失败重试配置（0814 重试机制改造）。
     * <p>maxRetries 用于非流式模型构建时显式设置 langchain4j 内置重试次数（在线场景）；
     * 退避参数（initialBackoffMs/backoffMultiplier/jitterRatio）供改造 A 的主对话
     * 循环内单轮重试使用——langchain4j 内置 RetryPolicy 的退避固定 500ms×1.5 不可调。
     */
    @Data
    public static class Retry {
        private int maxRetries = 2;
        private int initialBackoffMs = 500;
        private double backoffMultiplier = 2;
        private double jitterRatio = 0.2;
    }

    @Data
    public static class LlmProviderConfig {
        private String apiKey;
        private String baseUrl;
        private String groupId;
        private String model;
        private Boolean returnThinking;
        private Boolean sendThinking;
    }
}
