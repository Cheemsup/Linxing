package org.linxing.linxing_agent.common.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.observability.LangfuseChatModelListenerFactory;
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
    private final LlmProperties llmProperties;
    private final LangfuseChatModelListenerFactory langfuseListenerFactory;
    private String defaultProvider;

    public LlmManager(LlmProperties llmProperties, LangfuseChatModelListenerFactory langfuseListenerFactory) {
        this.llmProperties = llmProperties;
        this.langfuseListenerFactory = langfuseListenerFactory;
    }

    @PostConstruct
    public void init() {
        LlmProperties llm = llmProperties;
        this.defaultProvider = resolveDefaultProvider(llm);
        Map<String, LlmProperties.LlmProviderConfig> providers = llm.getProviders();

        if (providers == null || providers.isEmpty()) {
            log.warn("未配置任何LLM provider");
            return;
        }

        for (Map.Entry<String, LlmProperties.LlmProviderConfig> entry : providers.entrySet()) {
            String name = entry.getKey();
            LlmProperties.LlmProviderConfig config = entry.getValue();

            OpenAiChatModel.OpenAiChatModelBuilder modelBuilder = OpenAiChatModel.builder()
                    .baseUrl(config.getBaseUrl())
                    .apiKey(config.getApiKey())
                    .modelName(config.getModel())
                    .temperature(llm.getTemperature())
                    .timeout(Duration.ofSeconds(llm.getTimeoutSeconds()))
                    .maxTokens(llm.getMaxTokens())
                    .maxRetries(llm.getRetry().getMaxRetries())
                    .listeners(langfuseListenerFactory.create(name));//0816 Langfuse 观测：每轮 LLM 调用打 generation span
                    // .logRequests(false)//打印http请求信息
                    // .logResponses(true);//打印模型响应信息

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

    private String resolveDefaultProvider(LlmProperties llm) {
        String configured = llm.getDefaultProvider();
        Map<String, LlmProperties.LlmProviderConfig> providers = llm.getProviders();
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

        LlmProperties llm = llmProperties;
        Map<String, LlmProperties.LlmProviderConfig> providers = llm.getProviders();
        LlmProperties.LlmProviderConfig config = providers.get(provider);
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
                // .logRequests(true)
                // .logResponses(true)
                .listeners(langfuseListenerFactory.create(provider))//0816 Langfuse 观测：主对话/子Agent 流式调用打 generation span
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
