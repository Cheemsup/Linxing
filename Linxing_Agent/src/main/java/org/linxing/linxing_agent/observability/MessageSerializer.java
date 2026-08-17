package org.linxing.linxing_agent.observability;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 LangChain4j 消息 / ChatResponse 序列化为 OpenAI-compatible JSON，供 Langfuse observation.input/output 写入。
 * <p>0816 改造，目标结构与字段约定见 reference/TODOS/langfuse/0816LangfuseObservability.md 3.3。
 * 图片 content 摘要化（不落 base64 原文）；输出统一截断防 span 属性超 Langfuse 限制。
 * <p>与 usage_details / model.parameters 的 JSON 序列化共用，P1 的 ChatModelListener 直接调用。
 */
@Component
public class MessageSerializer {

    /** 消息列表 JSON 最大长度（3.3：input 截断 ~20k chars） */
    public static final int MAX_MESSAGES_LENGTH = 20_000;
    /** 响应 JSON 最大长度（3.3：output 截断 ~20k chars） */
    public static final int MAX_RESPONSE_LENGTH = 20_000;

    private static final String TRUNCATE_SUFFIX = "\n...[截断, 原长度 %d chars]";

    private final ObjectMapper objectMapper;

    public MessageSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * ChatMessage 列表 → OpenAI-compatible 消息数组 JSON。
     * 未知消息类型（如 CustomMessage）跳过；序列化失败返回 {@code {"error":...}}。
     */
    public String serializeMessages(List<? extends ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            Map<String, Object> map = toOpenAiMessage(message);
            if (map != null) {
                out.add(map);
            }
        }
        return truncate(writeJson(out), MAX_MESSAGES_LENGTH);
    }

    /** ChatResponse → 单条 assistant 消息 JSON（content + reasoning_content + tool_calls） */
    public String serializeResponse(ChatResponse response) {
        if (response == null) {
            return "{}";
        }
        return truncate(writeJson(toOpenAiAiMessage(response.aiMessage())), MAX_RESPONSE_LENGTH);
    }

    /** AiMessage → OpenAI assistant 消息结构（不截断，供内部组合） */
    public Map<String, Object> toOpenAiAiMessage(AiMessage aiMessage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", "assistant");
        map.put("content", aiMessage.text() != null ? aiMessage.text() : "");
        String thinking = aiMessage.thinking();
        if (thinking != null && !thinking.isBlank()) {
            map.put("reasoning_content", thinking);
        }
        if (aiMessage.hasToolExecutionRequests()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", req.name());
                fn.put("arguments", req.arguments() != null ? req.arguments() : "");
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", req.id() != null ? req.id() : "");
                call.put("type", "function");
                call.put("function", fn);
                calls.add(call);
            }
            if (!calls.isEmpty()) {
                map.put("tool_calls", calls);
            }
        }
        return map;
    }

    /** usage 明细 JSON：langfuse.observation.usage_details 官方结构（供成本明细） */
    public String usageDetails(TokenUsage usage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("input", usage != null ? usage.inputTokenCount() : 0);
        map.put("output", usage != null ? usage.outputTokenCount() : 0);
        map.put("unit", "TOKENS");
        return writeJson(map);
    }

    /** 模型参数 JSON：langfuse.observation.model.parameters（有则写） */
    public String modelParameters(Double temperature, Integer maxTokens, Double topP) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (temperature != null) {
            map.put("temperature", temperature);
        }
        if (maxTokens != null) {
            map.put("maxTokens", maxTokens);
        }
        if (topP != null) {
            map.put("topP", topP);
        }
        return writeJson(map);
    }

    /** 通用对象 → JSON 字符串（retrieval span 的 input/metadata/scores 等非消息结构，0816 Phase2 改进3） */
    public String toJson(Object value) {
        return writeJson(value);
    }

    /** 通用截断：超过 maxLength 时截断并附加原长度说明，便于观测侧定位溢出 */
    public String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        String suffix = String.format(TRUNCATE_SUFFIX, text.length());
        int keep = Math.max(0, maxLength - suffix.length());
        return text.substring(0, keep) + suffix;
    }

    private Map<String, Object> toOpenAiMessage(ChatMessage message) {
        if (message instanceof SystemMessage sys) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", "system");
            map.put("content", sys.text() != null ? sys.text() : "");
            return map;
        }
        if (message instanceof UserMessage user) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", "user");
            map.put("content", userContent(user));
            return map;
        }
        if (message instanceof AiMessage ai) {
            return toOpenAiAiMessage(ai);
        }
        if (message instanceof ToolExecutionResultMessage tool) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", "tool");
            map.put("tool_call_id", tool.id() != null ? tool.id() : "");
            map.put("content", tool.text() != null ? tool.text() : "");
            return map;
        }
        return null;
    }

    /**
     * 用户消息 content：单文本 → 字符串；多模态（文本+图片）→ OpenAI content 数组。
     * 图片不落 base64 原文，摘要化为「〔图片〕标记 + 来源/长度」防属性超限。
     */
    private Object userContent(UserMessage user) {
        List<Content> contents = user.contents();
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        if (contents.size() == 1 && contents.get(0) instanceof TextContent text) {
            return text.text() != null ? text.text() : "";
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        for (Content content : contents) {
            if (content instanceof TextContent text) {
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("type", "text");
                part.put("text", text.text() != null ? text.text() : "");
                parts.add(part);
            } else if (content instanceof ImageContent image) {
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("type", "image_url");
                Map<String, Object> imageSummary = new LinkedHashMap<>();
                imageSummary.put("url", truncate(imageSummary(image), 500));
                part.put("image_url", imageSummary);
                parts.add(part);
            }
        }
        return parts;
    }

    private String imageSummary(ImageContent image) {
        if (image == null || image.image() == null) {
            return "[图片]";
        }
        String url = image.image().url() != null ? image.image().url().toString() : null;
        String data = image.image().base64Data();
        String mime = image.image().mimeType();
        if (url != null && !url.isBlank()) {
            return "[图片 url=" + url + "]";
        }
        if (data != null && !data.isBlank()) {
            return "[图片 base64长度=" + data.length() + " mime=" + mime + "]";
        }
        return "[图片]";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return "{\"error\":\"serialize failed: " + e.getMessage() + "\"}";
        }
    }
}
