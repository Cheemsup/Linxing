package org.linxing.linxing_agent.config;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek reasoning_content 回传机制探索测试
 * <p>
 * 测试目标：
 * 1. 直接 HTTP 调用 DeepSeek API，观察原始 JSON 响应中 reasoning_content 的结构
 * 2. 使用 LangChain4j OpenAiChatModel，观察 AiMessage 中各字段的值
 * 3. 模拟 tool call 两轮对话，观察回传/不回传 reasoning_content 的行为差异
 */
public class DeepSeekReasoningTest {

    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    private static final String DEEPSEEK_API_KEY = System.getenv("DEEPSEEK_API_KEY");
    private static final String DEEPSEEK_MODEL = "deepseek-v4-flash";

    private final RestTemplate restTemplate = new RestTemplate();

    // ==================== 一、Raw HTTP 测试 ====================

    /**
     * 测试1：不带 tools 的简单对话 → 观察 reasoning_content
     */
    @Test
    @SuppressWarnings("unchecked")
    void testRawHttpSimpleChat() throws Exception {
        System.out.println("========== 测试1：简单对话（无tools） ==========");

        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", "9.11和9.8哪个更大？请一步步推理")
        );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", DEEPSEEK_MODEL);
        body.put("messages", messages);
        body.put("max_tokens", 1024);
        body.put("temperature", 0.3);

        Map<String, Object> extraBody = new LinkedHashMap<>();
        extraBody.put("thinking", Map.of("type", "enabled"));
        body.put("extra_body", extraBody);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(DEEPSEEK_API_KEY);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                DEEPSEEK_BASE_URL + "/v1/chat/completions", request, Map.class);

        System.out.println("--- 完整响应 ---");
        prettyPrint(response.getBody());

        Map<String, Object> choice = ((List<Map<String, Object>>) response.getBody().get("choices")).get(0);
        Map<String, Object> message = (Map<String, Object>) choice.get("message");

        System.out.println("\n--- 关键字段 ---");
        System.out.println("role             = " + message.get("role"));
        System.out.println("content          = " + truncate(String.valueOf(message.get("content")), 200));
        System.out.println("reasoning_content= " + truncate(String.valueOf(message.get("reasoning_content")), 300));
        System.out.println("reasoning_tokens = " + extractReasoningTokens(response.getBody()));
        System.out.println("tool_calls       = " + message.get("tool_calls"));
        System.out.println("finish_reason    = " + choice.get("finish_reason"));
    }

    /**
     * 测试2：带 tools 调用（第一轮）→ 观察 reasoning_content + tool_calls
     */
    @Test
    @SuppressWarnings("unchecked")
    void testRawHttpWithTools_Turn1() throws Exception {
        System.out.println("========== 测试2：带tools调用（第一轮） ==========");

        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", "北京今天天气怎么样？")
        );

        List<Map<String, Object>> tools = List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "get_weather",
                                "description", "获取指定城市的天气信息",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "city", Map.of("type", "string", "description", "城市名称")
                                        ),
                                        "required", List.of("city")
                                )
                        )
                )
        );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", DEEPSEEK_MODEL);
        body.put("messages", messages);
        body.put("tools", tools);
        body.put("max_tokens", 1024);
        body.put("temperature", 0.3);

        Map<String, Object> extraBody = new LinkedHashMap<>();
        extraBody.put("thinking", Map.of("type", "enabled"));
        body.put("extra_body", extraBody);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(DEEPSEEK_API_KEY);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                DEEPSEEK_BASE_URL + "/v1/chat/completions", request, Map.class);

        System.out.println("--- 完整响应 ---");
        prettyPrint(response.getBody());

        Map<String, Object> choice = ((List<Map<String, Object>>) response.getBody().get("choices")).get(0);
        Map<String, Object> message = (Map<String, Object>) choice.get("message");

        System.out.println("\n--- 关键字段 ---");
        System.out.println("role             = " + message.get("role"));
        System.out.println("content          = " + message.get("content"));
        System.out.println("reasoning_content= " + truncate(String.valueOf(message.get("reasoning_content")), 500));
        System.out.println("tool_calls       = ");
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
        if (toolCalls != null) {
            for (Map<String, Object> tc : toolCalls) {
                System.out.println("  id     = " + tc.get("id"));
                System.out.println("  type   = " + tc.get("type"));
                Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                System.out.println("  name   = " + fn.get("name"));
                System.out.println("  args   = " + fn.get("arguments"));
            }
        }
        System.out.println("\nfinish_reason    = " + choice.get("finish_reason"));
    }

    /**
     * 测试3：两轮 tool call → 手动回传 reasoning_content 到第二轮
     */
    @Test
    @SuppressWarnings("unchecked")
    void testRawHttpTwoTurnWithReasoningContent() throws Exception {
        System.out.println("========== 测试3：两轮tool call — 手动回传reasoning_content ==========");

        // ---- 第一轮 ----
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "帮我查一下杭州明天的天气，如果下雨就提醒我带伞"));

        List<Map<String, Object>> tools = List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "get_weather",
                                "description", "获取指定城市和日期的天气信息",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "city", Map.of("type", "string", "description", "城市名称"),
                                                "date", Map.of("type", "string", "description", "日期，格式YYYY-mm-dd")
                                        ),
                                        "required", List.of("city", "date")
                                )
                        )
                )
        );

        Map<String, Object> body = buildBody(DEEPSEEK_MODEL, messages, tools);
        HttpHeaders headers = buildHeaders();

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response1 = restTemplate.postForEntity(
                DEEPSEEK_BASE_URL + "/v1/chat/completions", request, Map.class);

        Map<String, Object> choice1 = ((List<Map<String, Object>>) response1.getBody().get("choices")).get(0);
        Map<String, Object> assistantMsg1 = (Map<String, Object>) choice1.get("message");

        String reasoningContent1 = (String) assistantMsg1.get("reasoning_content");
        List<Map<String, Object>> toolCalls1 = (List<Map<String, Object>>) assistantMsg1.get("tool_calls");

        System.out.println("--- 第一轮 ---");
        System.out.println("reasoning_content  = " + truncate(String.valueOf(reasoningContent1), 500));
        System.out.println("tool_calls         = " + (toolCalls1 != null ? toolCalls1.size() : 0) + " 个");
        if (toolCalls1 != null) {
            for (Map<String, Object> tc : toolCalls1) {
                Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                System.out.println("  → " + fn.get("name") + "(" + fn.get("arguments") + ")");
            }
        }

        if (toolCalls1 == null || toolCalls1.isEmpty()) {
            System.out.println("模型没有发起tool call，测试终止。");
            return;
        }

        // ---- 构造第二轮消息（包含 reasoning_content） ----
        messages.add(assistantMsg1);

        for (Map<String, Object> tc : toolCalls1) {
            Map<String, Object> fn = (Map<String, Object>) tc.get("function");
            String toolResult = "{\"weather\": \"多云转晴\", \"temperature\": \"15~22°C\", \"rain\": false}";
            messages.add(Map.of(
                    "role", "tool",
                    "tool_call_id", tc.get("id"),
                    "content", toolResult
            ));
        }

        Map<String, Object> body2 = buildBody(DEEPSEEK_MODEL, messages, tools);
        HttpEntity<Map<String, Object>> request2 = new HttpEntity<>(body2, buildHeaders());

        try {
            ResponseEntity<Map> response2 = restTemplate.postForEntity(
                    DEEPSEEK_BASE_URL + "/v1/chat/completions", request2, Map.class);

            Map<String, Object> choice2 = ((List<Map<String, Object>>) response2.getBody().get("choices")).get(0);
            Map<String, Object> assistantMsg2 = (Map<String, Object>) choice2.get("message");

            System.out.println("\n--- 第二轮（含reasoning_content）---");
            System.out.println("✅ 请求成功！");
            System.out.println("finish_reason = " + choice2.get("finish_reason"));
            System.out.println("content       = " + truncate(String.valueOf(assistantMsg2.get("content")), 300));
            System.out.println("reasoning     = " + truncate(String.valueOf(assistantMsg2.get("reasoning_content")), 300));

        } catch (Exception e) {
            System.out.println("\n--- 第二轮（含reasoning_content）---");
            System.out.println("❌ 请求失败: " + e.getMessage());
        }

        // ---- 对比：第二轮不含 reasoning_content ----
        System.out.println("\n\n========== 对比实验：第二轮不含reasoning_content ==========");

        List<Map<String, Object>> messages2 = new ArrayList<>();
        messages2.add(Map.of("role", "user", "content", "帮我查一下杭州明天的天气，如果下雨就提醒我带伞"));

        Map<String, Object> assistantMsgWithoutReasoning = new LinkedHashMap<>(assistantMsg1);
        assistantMsgWithoutReasoning.remove("reasoning_content");
        messages2.add(assistantMsgWithoutReasoning);

        for (Map<String, Object> tc : toolCalls1) {
            String toolResult = "{\"weather\": \"多云转晴\", \"temperature\": \"15~22°C\", \"rain\": false}";
            messages2.add(Map.of(
                    "role", "tool",
                    "tool_call_id", tc.get("id"),
                    "content", toolResult
            ));
        }

        Map<String, Object> body3 = buildBody(DEEPSEEK_MODEL, messages2, tools);
        HttpEntity<Map<String, Object>> request3 = new HttpEntity<>(body3, buildHeaders());

        try {
            ResponseEntity<Map> response3 = restTemplate.postForEntity(
                    DEEPSEEK_BASE_URL + "/v1/chat/completions", request3, Map.class);
            System.out.println("✅ 请求成功（不含reasoning_content也能通过）");

            Map<String, Object> choice3 = ((List<Map<String, Object>>) response3.getBody().get("choices")).get(0);
            System.out.println("finish_reason = " + choice3.get("finish_reason"));
            System.out.println("content       = " + truncate(
                    String.valueOf(((Map<String, Object>) choice3.get("message")).get("content")), 200));

        } catch (Exception e) {
            System.out.println("❌ 请求失败: " + e.getMessage());
            Throwable cause = e;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            System.out.println("根因: " + cause.getMessage());
        }
    }

    // ==================== 二、LangChain4j AiMessage 字段探测 ====================

    /**
     * 测试4：LangChain4j ChatResponse → 探测 AiMessage 各字段
     */
    @Test
    void testLangChain4jAiMessageFields() {
        System.out.println("========== 测试4：LangChain4j AiMessage 字段探测 ==========");

        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(DEEPSEEK_BASE_URL)
                .apiKey(DEEPSEEK_API_KEY)
                .modelName(DEEPSEEK_MODEL)
                .returnThinking(true)
                .sendThinking(true)
                .temperature(0.3)
                .maxTokens(1024)
                .logRequests(true)
                .logResponses(true)
                .build();

        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("get_weather")
                .description("获取指定城市的天气信息")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("city", "城市名称")
                        .required("city")
                        .build())
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("北京今天天气怎么样？"));

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(List.of(toolSpec))
                .build();

        ChatResponse chatResponse = model.chat(chatRequest);
        AiMessage aiMessage = chatResponse.aiMessage();

        System.out.println("--- AiMessage 核心字段 ---");
        System.out.println("text()                    = " + aiMessage.text());
        System.out.println("thinking()                = " + truncate(String.valueOf(aiMessage.thinking()), 500));
        System.out.println("hasToolExecutionRequests()= " + aiMessage.hasToolExecutionRequests());

        if (aiMessage.hasToolExecutionRequests()) {
            System.out.println("toolExecutionRequests():");
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                System.out.println("  id       = " + req.id());
                System.out.println("  name     = " + req.name());
                System.out.println("  arguments= " + req.arguments());
            }
        }

        System.out.println("--- 构建第二轮消息（手动注入 reasoning_content？） ---");
        System.out.println("AiMessage 是不可变对象，无法动态追加 reasoning_content。");
        System.out.println("需要从构造层面解决，例如通过 AiMessage.from() 或自定义 CustomMessage");
    }

    /**
     * 测试5：LangChain4j 两轮 tool call → 模拟重现 400 错误
     */
    @Test
    void testLangChain4jTwoTurnToolCall() {
        System.out.println("========== 测试5：LangChain4j 两轮 tool call（重现400错误场景） ==========");

        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(DEEPSEEK_BASE_URL)
                .apiKey(DEEPSEEK_API_KEY)
                .modelName(DEEPSEEK_MODEL)
                .returnThinking(true)
                .sendThinking(true)
                .temperature(0.3)
                .maxTokens(1024)
                .logRequests(true)
                .logResponses(true)
                .build();

        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("calculate")
                .description("执行数学计算，支持加减乘除")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("expression", "数学表达式，如 123*456")
                        .required("expression")
                        .build())
                .build();

        // --- 第一轮 ---
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("请帮我计算 123 * 456 等于多少"));

        ChatRequest request1 = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(List.of(toolSpec))
                .build();

        String thinking1 = null;
        String toolCallId1 = null;

        try {
            ChatResponse response1 = model.chat(request1);
            AiMessage aiMsg1 = response1.aiMessage();

            System.out.println("--- 第一轮 ---");
            System.out.println("text()          = " + aiMsg1.text());
            thinking1 = String.valueOf(aiMsg1.thinking());
            System.out.println("thinking()      = " + truncate(thinking1, 500));

            if (aiMsg1.hasToolExecutionRequests()) {
                messages.add(aiMsg1);
                for (ToolExecutionRequest req : aiMsg1.toolExecutionRequests()) {
                    toolCallId1 = req.id();
                    System.out.println("tool request    : " + req.name() + "(" + req.arguments() + ")");

                    String toolResult = "{\"result\": 56088}";
                    ToolExecutionResultMessage resultMsg = ToolExecutionResultMessage.from(req, toolResult);
                    messages.add(resultMsg);
                }
            } else {
                System.out.println("模型未发起tool call，无法继续测试第二轮。");
                return;
            }
        } catch (Exception e) {
            System.out.println("❌ 第一轮失败: " + e.getMessage());
            return;
        }

        // --- 第二轮 ---
        System.out.println("\n--- 第二轮（LangChain4j自动序列化）---");
        System.out.println("关键问题: AiMessage.thinking() 会被序列化到请求中的 reasoning_content 字段吗？");

        try {
            ChatRequest request2 = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(List.of(toolSpec))
                    .build();

            ChatResponse response2 = model.chat(request2);
            AiMessage aiMsg2 = response2.aiMessage();

            System.out.println("✅ 第二轮成功！");
            System.out.println("content = " + truncate(aiMsg2.text(), 300));

        } catch (Exception e) {
            System.out.println("❌ 第二轮失败: " + e.getMessage());
            Throwable cause = e;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            System.out.println("根因: " + cause.getMessage());
        }
    }

    // ==================== 三、LangChain4j 源码行为推断 ====================

    /**
     * 测试6：探测 AiMessage 的无参构造 / attributes 是否可携带 reasoning_content
     */
    @Test
    void testAiMessageConstructorOptions() {
        System.out.println("========== 测试6：AiMessage 构造方式 ==========");

        AiMessage fromText = AiMessage.from("hello");
        System.out.println("AiMessage.from(text) → text=" + fromText.text() + ", thinking=" + fromText.thinking());

        ToolExecutionRequest dummyReq = ToolExecutionRequest.builder()
                .id("call_1")
                .name("test_tool")
                .arguments("{}")
                .build();

        AiMessage fromToolReq = AiMessage.from(dummyReq);
        System.out.println("AiMessage.from(ToolExecutionRequest) → text=" + fromToolReq.text()
                + ", thinking=" + fromToolReq.thinking()
                + ", hasTools=" + fromToolReq.hasToolExecutionRequests());

        AiMessage fromToolReqs = AiMessage.from(dummyReq, dummyReq);
        System.out.println("AiMessage.from(req1, req2) → text=" + fromToolReqs.text()
                + ", thinking=" + fromToolReqs.thinking()
                + ", toolCount=" + fromToolReqs.toolExecutionRequests().size());

        System.out.println("\n--- 关键发现 ---");
        System.out.println("AiMessage 的 from() 工厂方法不接收 thinking 参数。");
        System.out.println("thinking 字段只能由 LangChain4j 框架在反序列化 HTTP 响应时设置。");
        System.out.println("业务代码层面无法在第二轮请求中手动为 AiMessage 注入 reasoning_content。");
    }

    // ==================== 四、可行方案探索 ====================

    /**
     * 测试7：Raw HTTP 完整两轮 — 验证手动回传 reasoning_content 能否工作
     */
    @Test
    @SuppressWarnings("unchecked")
    void testRawHttpTwoTurnFullFlow() throws Exception {
        System.out.println("========== 测试7：Raw HTTP 完整两轮流（手动管理 reasoning_content） ==========");

        // ---- 第一轮 ----
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user",
                "content", "先查一下北京今天的天气，再帮我计算 456 * 789"));

        List<Map<String, Object>> tools = List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "get_weather",
                                "description", "获取指定城市的天气信息",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "city", Map.of("type", "string", "description", "城市名称")
                                        ),
                                        "required", List.of("city")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "calculate",
                                "description", "执行数学计算",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "expression", Map.of("type", "string", "description", "数学表达式")
                                        ),
                                        "required", List.of("expression")
                                )
                        )
                )
        );

        Map<String, Object> body1 = buildBody(DEEPSEEK_MODEL, messages, tools);
        ResponseEntity<Map> resp1 = restTemplate.postForEntity(
                DEEPSEEK_BASE_URL + "/v1/chat/completions",
                new HttpEntity<>(body1, buildHeaders()), Map.class);

        Map<String, Object> choice1 = ((List<Map<String, Object>>) resp1.getBody().get("choices")).get(0);
        Map<String, Object> msg1 = (Map<String, Object>) choice1.get("message");
        List<Map<String, Object>> tc1 = (List<Map<String, Object>>) msg1.get("tool_calls");

        System.out.println("--- 第一轮响应 ---");
        System.out.println("reasoning_content = " + truncate(String.valueOf(msg1.get("reasoning_content")), 300));
        System.out.println("finish_reason     = " + choice1.get("finish_reason"));
        if (tc1 != null) {
            for (Map<String, Object> tc : tc1) {
                Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                System.out.println("  → " + fn.get("name") + "(" + fn.get("arguments") + ")");
            }
        }

        if (tc1 == null || tc1.isEmpty()) {
            System.out.println("无 tool call，测试结束。");
            return;
        }

        // 将第一轮的 assistant 消息（含 reasoning_content）加入 messages
        messages.add(msg1);

        // 模拟工具执行结果
        for (Map<String, Object> tc : tc1) {
            String callId = (String) tc.get("id");
            Map<String, Object> fn = (Map<String, Object>) tc.get("function");
            String name = (String) fn.get("name");
            String result;
            if ("get_weather".equals(name)) {
                result = "{\"city\": \"北京\", \"weather\": \"晴\", \"temperature\": \"25°C\", \"humidity\": \"45%\"}";
            } else {
                result = "{\"result\": " + (456 * 789) + "}";
            }
            messages.add(Map.of("role", "tool", "tool_call_id", callId, "content", result));
        }

        System.out.println("\n--- 第二轮请求（含 reasoning_content） ---");
        Map<String, Object> body2 = buildBody(DEEPSEEK_MODEL, messages, tools);

        try {
            ResponseEntity<Map> resp2 = restTemplate.postForEntity(
                    DEEPSEEK_BASE_URL + "/v1/chat/completions",
                    new HttpEntity<>(body2, buildHeaders()), Map.class);

            Map<String, Object> choice2 = ((List<Map<String, Object>>) resp2.getBody().get("choices")).get(0);
            Map<String, Object> msg2 = (Map<String, Object>) choice2.get("message");

            System.out.println("✅ 第二轮成功！");
            System.out.println("finish_reason     = " + choice2.get("finish_reason"));
            System.out.println("content           = " + truncate(String.valueOf(msg2.get("content")), 500));

            List<Map<String, Object>> tc2 = (List<Map<String, Object>>) msg2.get("tool_calls");
            if (tc2 != null && !tc2.isEmpty()) {
                System.out.println("还有 " + tc2.size() + " 个 tool call，继续...");
            }

        } catch (Exception e) {
            System.out.println("❌ 第二轮失败:");
            e.printStackTrace();
        }
    }

    // ==================== 辅助方法 ====================

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(DEEPSEEK_API_KEY);
        return headers;
    }

    private Map<String, Object> buildBody(String model, List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        body.put("max_tokens", 2048);
        body.put("temperature", 0.3);

        Map<String, Object> extraBody = new LinkedHashMap<>();
        extraBody.put("thinking", Map.of("type", "enabled"));
        body.put("extra_body", extraBody);

        return body;
    }

    private void prettyPrint(Map<?, ?> map) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(map);
            String[] lines = json.split("\n");
            for (String line : lines) {
                if (line.contains("\"content\"") && line.length() > 150) {
                    System.out.println(line.substring(0, 147) + "...");
                } else if (line.contains("\"reasoning_content\"") && line.length() > 200) {
                    System.out.println(line.substring(0, 197) + "...");
                } else {
                    System.out.println(line);
                }
            }
        } catch (Exception e) {
            System.out.println(map);
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...(截断，全长" + s.length() + "字符)";
    }

    @SuppressWarnings("unchecked")
    private String extractReasoningTokens(Map<?, ?> responseBody) {
        try {
            Map<String, Object> usage = (Map<String, Object>) responseBody.get("usage");
            if (usage == null) return "N/A";
            Map<String, Object> details = (Map<String, Object>) usage.get("completion_tokens_details");
            if (details == null) return "N/A";
            return String.valueOf(details.get("reasoning_tokens"));
        } catch (Exception e) {
            return "N/A";
        }
    }
}
