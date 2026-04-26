package org.linxing.linxing_agent.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
@RequiredArgsConstructor
public class LangChain4jConfig {

    private final RagProperties ragProperties;

    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhEmbeddingModel();
    }

    @Bean
    public OpenAiChatModel openAiChatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(ragProperties.getLlm().getBaseUrl())
                .apiKey(ragProperties.getLlm().getApiKey())
                .modelName(ragProperties.getLlm().getModel())
                .temperature(ragProperties.getLlm().getTemperature())
                .timeout(java.time.Duration.ofSeconds(ragProperties.getLlm().getTimeoutSeconds()))
                .maxTokens(ragProperties.getLlm().getMaxTokens())
                .logRequests(true)
                .logResponses(true)
                .build();
    }
}
