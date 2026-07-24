package org.linxing.linxing_agent.agent.memory;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TokenEstimator 工具规格 token 估算单测。
 */
@DisplayName("TokenEstimator: estimateToolSpecs / estimateContext")
class TokenEstimatorToolSpecTest {

    private TokenEstimator newEstimator() throws Exception {
        TokenEstimator estimator = new TokenEstimator(new ObjectMapper());
        // @Value 未注入，反射预置 encodingName 再触发 init
        java.lang.reflect.Field nameField = TokenEstimator.class.getDeclaredField("encodingName");
        nameField.setAccessible(true);
        nameField.set(estimator, "cl100k_base");
        java.lang.reflect.Method init = TokenEstimator.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(estimator);
        return estimator;
    }

    @Test
    @DisplayName("空列表返回 0")
    void emptyReturnsZero() throws Exception {
        TokenEstimator estimator = newEstimator();
        assertEquals(0, estimator.estimateToolSpecs(null));
        assertEquals(0, estimator.estimateToolSpecs(List.of()));
    }

    @Test
    @DisplayName("非空 specs 返回正数，且随内容增长")
    void nonEmptyReturnsPositive() throws Exception {
        TokenEstimator estimator = newEstimator();
        ToolSpecification spec = ToolSpecification.builder()
                .name("search_knowledge_base")
                .description("在知识库中检索相关文档")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query", "检索关键词")
                        .build())
                .build();
        long single = estimator.estimateToolSpecs(List.of(spec));
        assertTrue(single > 0, "非空 spec 应返回正数 token");

        long doubled = estimator.estimateToolSpecs(List.of(spec, spec));
        assertEquals(single * 2, doubled, "两份相同 spec token 应翻倍");
    }

    @Test
    @DisplayName("estimateContext = 消息段 + 工具规格段")
    void estimateContextIsSum() throws Exception {
        TokenEstimator estimator = newEstimator();
        long msgTokens = estimator.estimate(List.of(UserMessage.from("你好世界")));
        ToolSpecification spec = ToolSpecification.builder()
                .name("resolve")
                .description("按需获取工具完整定义")
                .build();
        long specTokens = estimator.estimateToolSpecs(List.of(spec));
        assertEquals(msgTokens + specTokens,
                estimator.estimateContext(List.of(UserMessage.from("你好世界")), List.of(spec)));
    }
}
