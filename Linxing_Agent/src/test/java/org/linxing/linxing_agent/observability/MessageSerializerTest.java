package org.linxing.linxing_agent.observability;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MessageSerializer} 批量单测：各类消息 OpenAI-compatible JSON 化 + 图片摘要化 + 截断。
 * 断言均走 JsonNode 解析（不脆弱的字符串精确匹配），关注结构正确性。
 */
class MessageSerializerTest {

    private MessageSerializer serializer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        serializer = new MessageSerializer(objectMapper);
    }

    @Test
    @DisplayName("system 消息 → role=system + content")
    void systemMessage() throws Exception {
        String json = serializer.serializeMessages(List.of(SystemMessage.from("你是助手")));
        JsonNode node = objectMapper.readTree(json);
        assertEquals("system", node.get(0).get("role").asText());
        assertEquals("你是助手", node.get(0).get("content").asText());
    }

    @Test
    @DisplayName("user 单文本 → content 为字符串")
    void userSingleText() throws Exception {
        String json = serializer.serializeMessages(List.of(UserMessage.from("你好")));
        JsonNode node = objectMapper.readTree(json);
        assertEquals("user", node.get(0).get("role").asText());
        assertTrue(node.get(0).get("content").isTextual());
        assertEquals("你好", node.get(0).get("content").asText());
    }

    @Test
    @DisplayName("user 文本+图片 → content 为数组，图片摘要化不落 base64 原文")
    void userMultiModal() throws Exception {
        UserMessage user = UserMessage.from(
                TextContent.from("看这张图"),
                ImageContent.from(URI.create("https://example.com/a.png")));
        String json = serializer.serializeMessages(List.of(user));
        JsonNode node = objectMapper.readTree(json);
        JsonNode content = node.get(0).get("content");
        assertTrue(content.isArray());
        assertEquals(2, content.size());
        assertEquals("text", content.get(0).get("type").asText());
        assertEquals("看这张图", content.get(0).get("text").asText());
        assertEquals("image_url", content.get(1).get("type").asText());
        String imageUrl = content.get(1).get("image_url").get("url").asText();
        assertTrue(imageUrl.startsWith("[图片"), "图片应摘要化为〔图片〕标记, 实际: " + imageUrl);
        assertFalse(imageUrl.contains("base64"), "不应落 base64 原文");
    }

    @Test
    @DisplayName("assistant 带思考 → role=assistant + reasoning_content")
    void assistantWithThinking() throws Exception {
        AiMessage ai = AiMessage.builder().text("答案是 A").thinking("先检索知识库").build();
        String json = serializer.serializeMessages(List.of(ai));
        JsonNode node = objectMapper.readTree(json);
        assertEquals("assistant", node.get(0).get("role").asText());
        assertEquals("答案是 A", node.get(0).get("content").asText());
        assertEquals("先检索知识库", node.get(0).get("reasoning_content").asText());
    }

    @Test
    @DisplayName("assistant 带工具调用 → tool_calls 数组含 id/type/function")
    void assistantWithToolCalls() throws Exception {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_abc")
                .name("search_knowledge_base")
                .arguments("{\"query\":\"操作系统\"}")
                .build();
        AiMessage ai = AiMessage.builder().toolExecutionRequests(List.of(req)).build();
        String json = serializer.serializeMessages(List.of(ai));
        JsonNode node = objectMapper.readTree(json);
        JsonNode call = node.get(0).get("tool_calls").get(0);
        assertEquals("assistant", node.get(0).get("role").asText());
        assertEquals("call_abc", call.get("id").asText());
        assertEquals("function", call.get("type").asText());
        assertEquals("search_knowledge_base", call.get("function").get("name").asText());
        assertTrue(call.get("function").get("arguments").asText().contains("操作系统"));
    }

    @Test
    @DisplayName("tool 结果消息 → role=tool + tool_call_id + content")
    void toolResultMessage() throws Exception {
        ToolExecutionResultMessage toolMsg = new ToolExecutionResultMessage(
                "call_abc", "search_knowledge_base", "{\"result\": \"OK\"}");
        String json = serializer.serializeMessages(List.of(toolMsg));
        JsonNode node = objectMapper.readTree(json);
        assertEquals("tool", node.get(0).get("role").asText());
        assertEquals("call_abc", node.get(0).get("tool_call_id").asText());
        assertTrue(node.get(0).get("content").asText().contains("OK"));
    }

    @Test
    @DisplayName("混合消息列表按序 JSON 化")
    void mixedMessages() throws Exception {
        List<dev.langchain4j.data.message.ChatMessage> messages = List.of(
                SystemMessage.from("你是助手"),
                UserMessage.from("你好"),
                AiMessage.builder().text("你好！有什么可以帮你？").build(),
                UserMessage.from("介绍 Langfuse"));
        String json = serializer.serializeMessages(messages);
        JsonNode node = objectMapper.readTree(json);
        assertEquals(4, node.size());
        assertEquals("system", node.get(0).get("role").asText());
        assertEquals("user", node.get(1).get("role").asText());
        assertEquals("assistant", node.get(2).get("role").asText());
        assertEquals("user", node.get(3).get("role").asText());
    }

    @Test
    @DisplayName("serializeResponse → assistant 消息含 reasoning_content 与 tool_calls")
    void serializeResponse() throws Exception {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_x")
                .name("web_search")
                .arguments("{}")
                .build();
        AiMessage ai = AiMessage.builder()
                .text("")
                .thinking("先搜索")
                .toolExecutionRequests(List.of(req))
                .build();
        ChatResponse response = ChatResponse.builder()
                .aiMessage(ai)
                .modelName("deepseek-chat")
                .tokenUsage(new TokenUsage(100, 20))
                .build();
        String json = serializer.serializeResponse(response);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("assistant", node.get("role").asText());
        assertEquals("先搜索", node.get("reasoning_content").asText());
        assertEquals("web_search", node.get("tool_calls").get(0).get("function").get("name").asText());
    }

    @Test
    @DisplayName("空/未包含消息 → []")
    void emptyMessages() {
        assertEquals("[]", serializer.serializeMessages(List.of()));
        assertEquals("[]", serializer.serializeMessages(null));
    }

    @Test
    @DisplayName("长消息列表触发截断，长度不超过上限且带截断说明")
    void longMessagesTruncated() throws Exception {
        String longText = "甲".repeat(30_000);
        String json = serializer.serializeMessages(List.of(UserMessage.from(longText)));
        assertTrue(json.length() <= MessageSerializer.MAX_MESSAGES_LENGTH,
                "截断后长度应 ≤ 20k, 实际: " + json.length());
        assertTrue(json.endsWith("]"));
        assertTrue(json.contains("截断"));
    }

    @Test
    @DisplayName("truncate 单元：超限截断、不超限原样返回")
    void truncate() {
        assertEquals("abc", serializer.truncate("abc", 10));
        String truncated = serializer.truncate("x".repeat(100), 50);
        assertTrue(truncated.length() <= 50);
        assertTrue(truncated.contains("截断"));
        assertNull(serializer.truncate(null, 50));
    }

    @Test
    @DisplayName("usageDetails → input/output/unit 结构")
    void usageDetails() throws Exception {
        JsonNode node = objectMapper.readTree(serializer.usageDetails(new TokenUsage(100, 20)));
        assertEquals(100, node.get("input").asInt());
        assertEquals(20, node.get("output").asInt());
        assertEquals("TOKENS", node.get("unit").asText());
    }

    @Test
    @DisplayName("modelParameters → 有值写入、无值省略")
    void modelParameters() throws Exception {
        JsonNode node = objectMapper.readTree(serializer.modelParameters(0.7, 2048, 1.0));
        assertEquals(0.7, node.get("temperature").asDouble());
        assertEquals(2048, node.get("maxTokens").asInt());
        assertEquals(1.0, node.get("topP").asDouble());

        JsonNode empty = objectMapper.readTree(serializer.modelParameters(null, null, null));
        assertEquals(0, empty.size());
    }
}
