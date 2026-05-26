package org.linxing.linxing_agent.common.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class LlmManager {

    private final Map<String, OpenAiChatModel> models = new LinkedHashMap<>();
    private final Map<String, OpenAiStreamingChatModel> streamingModels = new LinkedHashMap<>();
    private final RagProperties ragProperties;
    private String defaultProvider;

    public LlmManager(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @PostConstruct
    public void init() {
        RagProperties.Llm llm = ragProperties.getLlm();
        this.defaultProvider = resolveDefaultProvider(llm);
        Map<String, RagProperties.LlmProviderConfig> providers = llm.getProviders();

        if (providers == null || providers.isEmpty()) {
            log.warn("未配置任何LLM provider");
            return;
        }

        for (Map.Entry<String, RagProperties.LlmProviderConfig> entry : providers.entrySet()) {
            String name = entry.getKey();
            RagProperties.LlmProviderConfig config = entry.getValue();

            OpenAiChatModel.OpenAiChatModelBuilder modelBuilder = OpenAiChatModel.builder()
                    .baseUrl(config.getBaseUrl())
                    .apiKey(config.getApiKey())
                    .modelName(config.getModel())
                    .temperature(llm.getTemperature())
                    .timeout(Duration.ofSeconds(llm.getTimeoutSeconds()))
                    .maxTokens(llm.getMaxTokens())
                    .logRequests(true)
                    .logResponses(true);

            if (config.getReturnThinking() != null) {
                modelBuilder.returnThinking(config.getReturnThinking());
            }

            if (config.getSendThinking() != null) {
                modelBuilder.sendThinking(config.getSendThinking());
            }

            OpenAiChatModel model = modelBuilder.build();

            models.put(name, model);
            log.info("LLM provider [{}] 初始化完成: model={}, baseUrl={}", name, config.getModel(), config.getBaseUrl());
        }
    }

    private String resolveDefaultProvider(RagProperties.Llm llm) {
        String configured = llm.getDefaultProvider();
        Map<String, RagProperties.LlmProviderConfig> providers = llm.getProviders();
        if (providers != null && providers.containsKey(configured)) {
            return configured;
        }
        if (providers != null && !providers.isEmpty()) {
            String fallback = providers.keySet().iterator().next();
            log.warn("默认provider [{}] 未配置, 回退到第一个可用provider [{}]", configured, fallback);
            return fallback;
        }
        return configured;
    }

    public OpenAiChatModel getModel(String provider) {
        OpenAiChatModel model = models.get(provider);
        if (model == null) {
            throw new IllegalArgumentException("未知的LLM provider: " + provider + ", 可用provider: " + models.keySet());
        }
        return model;
    }

    public OpenAiChatModel getDefaultModel() {
        return getModel(defaultProvider);
    }

    public OpenAiStreamingChatModel getStreamingModel(String provider) {
        OpenAiStreamingChatModel model = streamingModels.get(provider);
        if (model != null) {
            return model;
        }

        RagProperties.Llm llm = ragProperties.getLlm();
        Map<String, RagProperties.LlmProviderConfig> providers = llm.getProviders();
        RagProperties.LlmProviderConfig config = providers.get(provider);
        if (config == null) {
            throw new IllegalArgumentException("未知的LLM provider: " + provider);
        }

        model = OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModel())
                .temperature(llm.getTemperature())
                .timeout(Duration.ofSeconds(llm.getTimeoutSeconds()))
                .maxTokens(llm.getMaxTokens())
                .logRequests(true)
                .logResponses(true)
                .sendThinking(config.getSendThinking())
                .returnThinking(config.getReturnThinking())
                .build();
        streamingModels.put(provider, model);
        log.info("Streaming LLM provider [{}] 初始化完成: model={}", provider, config.getModel());
        return model;
    }

    public OpenAiStreamingChatModel getDefaultStreamingModel() {
        return getStreamingModel(defaultProvider);
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public Set<String> listProviders() {
        return Collections.unmodifiableSet(models.keySet());
    }
}
