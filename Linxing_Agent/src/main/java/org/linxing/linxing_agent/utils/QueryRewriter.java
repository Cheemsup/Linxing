package org.linxing.linxing_agent.utils;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.constant.RagConstants;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@Deprecated
public class QueryRewriter {

    private final OpenAiChatModel chatLanguageModel;

    public String rewriteQuery(String originalQuery) {
        try {
            PromptTemplate promptTemplate = PromptTemplate.from(RagConstants.QUERY_REWRITE_PROMPT);

            Map<String, Object> variables = new HashMap<>();
            variables.put("query", originalQuery);

            Prompt prompt = promptTemplate.apply(variables);

            String rewrittenQuery = chatLanguageModel.chat(prompt.text());

            return rewrittenQuery.trim();

        } catch (Exception e) {
            log.warn("查询优化失败，使用原始查询: {}", e.getMessage());
            return originalQuery;
        }
    }
}
