package org.linxing.linxing_agent.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.linxing.linxing_agent.constant.LlmType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM Manager 测试类 - 验证各个LLM模型是否成功配置和可用
 */
@SpringBootTest
class LlmManagerTest {

    @Autowired
    private LlmManager llmManager;

    /**
     * 测试 LlmManager 是否成功注入
     */
    @Test
    void testLlmManagerInitialized() {
        assertNotNull(llmManager, "LlmManager 应该成功注入");
    }

    /**
     * 测试 CHAT_MODEL (deepseek) 是否配置成功
     */
    @Test
    void testChatModelConfigured() {
        assertDoesNotThrow(() -> {
            OpenAiChatModel model = llmManager.getModel(LlmType.CHAT_MODEL);
            assertNotNull(model, "CHAT_MODEL (deepseek) 应该成功配置");
        }, "获取 CHAT_MODEL 时不应该抛出异常");
    }

    /**
     * 测试 SEMANTIC_CHUNK_MODEL (glm) 是否配置成功
     */
    @Test
    void testSemanticChunkModelConfigured() {
        assertDoesNotThrow(() -> {
            OpenAiChatModel model = llmManager.getModel(LlmType.SEMANTIC_CHUNK_MODEL);
            assertNotNull(model, "SEMANTIC_CHUNK_MODEL (glm) 应该成功配置");
        }, "获取 SEMANTIC_CHUNK_MODEL 时不应该抛出异常");
    }

    /**
     * 测试 CONTEXT_ENRICH_MODEL (glm) 是否配置成功
     */
    @Test
    void testContextEnrichModelConfigured() {
        assertDoesNotThrow(() -> {
            OpenAiChatModel model = llmManager.getModel(LlmType.CONTEXT_ENRICH_MODEL);
            assertNotNull(model, "CONTEXT_ENRICH_MODEL (glm) 应该成功配置");
        }, "获取 CONTEXT_ENRICH_MODEL 时不应该抛出异常");
    }

    /**
     * 测试 QUERY_REWRITE (minimax) 是否配置成功
     */
    @Test
    void testQueryRewriteModelConfigured() {
        assertDoesNotThrow(() -> {
            OpenAiChatModel model = llmManager.getModel(LlmType.QUERY_REWRITE);
            assertNotNull(model, "QUERY_REWRITE (minimax) 应该成功配置");
        }, "获取 QUERY_REWRITE 时不应该抛出异常");
    }

    /**
     * 测试默认模型是否配置成功
     */
    @Test
    void testDefaultModelConfigured() {
        assertDoesNotThrow(() -> {
            OpenAiChatModel model = llmManager.getDefaultModel();
            assertNotNull(model, "默认模型应该成功配置");
        }, "获取默认模型时不应该抛出异常");
    }

    /**
     * 测试所有在 LlmConstants 中定义的模型是否都可用
     */
    @Test
    void testAllLlmProviderModelsAvailable() {
        String[] expectedModels = {
                LlmType.CHAT_MODEL,
                LlmType.SEMANTIC_CHUNK_MODEL,
                LlmType.CONTEXT_ENRICH_MODEL,
                LlmType.QUERY_REWRITE
        };

        for (String modelName : expectedModels) {
            assertTrue(llmManager.listProviders().contains(modelName),
                    "LlmManager 应该包含配置: " + modelName);
        }
    }

    /**
     * 测试获取不存在的模型时是否抛出异常
     */
    @Test
    void testGetNonExistentModelThrowsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            llmManager.getModel("non_existent_model");
        });
        assertTrue(exception.getMessage().contains("未知的LLM provider"),
                "异常消息应该包含 '未知的LLM provider'");
    }

    /**
     * 测试 CHAT_MODEL 能否进行简单的对话调用
     * 注意：此测试需要网络连接和有效的API密钥
     * 如果环境变量 SKIP_LLM_CHAT_TEST 设置为 true，则跳过此测试
     */
    @Test
    void testChatModelCanChat() {
        if ("true".equalsIgnoreCase(System.getenv("SKIP_LLM_CHAT_TEST"))) {
            return;
        }

        assertDoesNotThrow(() -> {
            OpenAiChatModel model = llmManager.getModel(LlmType.CHAT_MODEL);
            String response = model.chat("你好，请回复'测试成功'");
            assertNotNull(response, "CHAT_MODEL 应该返回非空响应");
            assertFalse(response.isBlank(), "CHAT_MODEL 应该返回非空白响应");
        }, "CHAT_MODEL 应该能够进行对话");
    }

    /**
     * 测试 SEMANTIC_CHUNK_MODEL 能否进行简单的对话调用
     * 注意：此测试需要网络连接和有效的API密钥
     * 如果环境变量 SKIP_LLM_CHAT_TEST 设置为 true，则跳过此测试
     */
    @Test
    void testSemanticChunkModelCanChat() {
        if ("true".equalsIgnoreCase(System.getenv("SKIP_LLM_CHAT_TEST"))) {
            return;
        }

        assertDoesNotThrow(() -> {
            OpenAiChatModel model = llmManager.getModel(LlmType.SEMANTIC_CHUNK_MODEL);
            String response = model.chat("你好，请回复'测试成功'");
            assertNotNull(response, "SEMANTIC_CHUNK_MODEL 应该返回非空响应");
            assertFalse(response.isBlank(), "SEMANTIC_CHUNK_MODEL 应该返回非空白响应");
        }, "SEMANTIC_CHUNK_MODEL 应该能够进行对话");
    }

    /**
     * 测试 CONTEXT_ENRICH_MODEL 能否进行简单的对话调用
     * 注意：此测试需要网络连接和有效的API密钥
     * 如果环境变量 SKIP_LLM_CHAT_TEST 设置为 true，则跳过此测试
     */
    @Test
    void testContextEnrichModelCanChat() {
        if ("true".equalsIgnoreCase(System.getenv("SKIP_LLM_CHAT_TEST"))) {
            return;
        }

        assertDoesNotThrow(() -> {
            OpenAiChatModel model = llmManager.getModel(LlmType.CONTEXT_ENRICH_MODEL);
            String response = model.chat("你好，请回复'测试成功'");
            assertNotNull(response, "CONTEXT_ENRICH_MODEL 应该返回非空响应");
            assertFalse(response.isBlank(), "CONTEXT_ENRICH_MODEL 应该返回非空白响应");
        }, "CONTEXT_ENRICH_MODEL 应该能够进行对话");
    }

    /**
     * 测试 QUERY_REWRITE 模型能否进行简单的对话调用
     * 注意：此测试需要网络连接和有效的API密钥
     * 如果环境变量 SKIP_LLM_CHAT_TEST 设置为 true，则跳过此测试
     */
    @Test
    void testQueryRewriteModelCanChat() {
        if ("true".equalsIgnoreCase(System.getenv("SKIP_LLM_CHAT_TEST"))) {
            return;
        }

        assertDoesNotThrow(() -> {
            OpenAiChatModel model = llmManager.getModel(LlmType.QUERY_REWRITE);
            String response = model.chat("你好，请回复'测试成功'");
            assertNotNull(response, "QUERY_REWRITE 应该返回非空响应");
            assertFalse(response.isBlank(), "QUERY_REWRITE 应该返回非空白响应");
        }, "QUERY_REWRITE 应该能够进行对话");
    }
}
