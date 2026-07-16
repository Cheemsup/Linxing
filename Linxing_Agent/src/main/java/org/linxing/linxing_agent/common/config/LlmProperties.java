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
