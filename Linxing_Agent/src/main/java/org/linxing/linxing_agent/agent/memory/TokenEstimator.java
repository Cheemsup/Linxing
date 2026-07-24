package org.linxing.linxing_agent.agent.memory;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 jtokkit 的真实 BPE token 计数器，替代旧的 {@code text.length()/2} 启发式。
 * <p>
 * 用于 Summary/Projection/Window 的阈值判定——是所有上下文管理决策的前提。
 * <p>
 * 编码默认 {@code cl100k_base}（OpenAI 兼容 BPE）；DeepSeek/GLM/Kimi/Minimax 均走 OpenAI 兼容，
 * BPE 近似可接受（批注 #1 已确认选用 jtokkit）。
 */
@Component
public class TokenEstimator {

    private static final Logger log = LoggerFactory.getLogger(TokenEstimator.class);

    private final ObjectMapper objectMapper;

    public TokenEstimator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Value("${agent.token.encoding:cl100k_base}")
    private String encodingName;

    private Encoding encoding;

    @PostConstruct
    void init() {
        // 惰性注册表：首次访问才加载，避免启动期开销
        EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
        // 先按字符串名匹配（支持 cl100k_base / o200k_base / p50k_base / r50k_base）
        Optional<Encoding> byName = registry.getEncoding(encodingName);
        if (byName.isPresent()) {
            this.encoding = byName.get();
        } else {
            // 退化到 cl100k_base 兜底（OpenAI 系列及多数兼容模型的通用 BPE）
            log.warn("[TokenEstimator] 未知 encoding='{}'，退化到 'cl100k_base'", encodingName);
            this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
        }
        log.info("[TokenEstimator] 初始化完成，encoding={}", encoding.getName());
    }

    /**
     * 估算单条消息的 token 数。对 {@link AiMessage} 含工具调用请求时，
     * 把 {@code name}+{@code arguments} 一并计入（调用意图对后续 prompt 开销重要）。
     */
    public long estimate(ChatMessage message) {
        if (message == null) {
            return 0;
        }
        long count = 0;
        // 文本主体
        String text = extractText(message);
        if (text != null && !text.isEmpty()) {
            count += encoding.countTokens(text);
        }
        // AiMessage 的工具调用参数（不在 singleText 内，需单独计）
        if (message instanceof AiMessage ai) {
            if (ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    String name = req.name();
                    if (name != null && !name.isEmpty()) {
                        count += encoding.countTokens(name);
                    }
                    String args = req.arguments();
                    if (args != null && !args.isEmpty()) {
                        count += encoding.countTokens(args);
                    }
                }
            }
        }
        return count;
    }

    /**
     * 估算消息列表的总 token 数。
     */
    public long estimate(List<? extends ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (ChatMessage msg : messages) {
            total += estimate(msg);
        }
        return total;
    }

    /**
     * 估算工具规格段 token 数。把每个 ToolSpecification 的 name/description/parameters
     * 序列化为 JSON 文本后 BPE 计数，近似实际发 LLM 的 tools JSON 开销。
     */
    public long estimateToolSpecs(List<ToolSpecification> specs) {
        if (specs == null || specs.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (ToolSpecification spec : specs) {
            total += encoding.countTokens(serializeToolSpec(spec));
        }
        return total;
    }

    /**
     * 估算完整上下文 token 数 = 消息段 + 工具规格段。
     */
    public long estimateContext(List<? extends ChatMessage> messages, List<ToolSpecification> specs) {
        return estimate(messages) + estimateToolSpecs(specs);
    }

    private String serializeToolSpec(ToolSpecification spec) {
        try {
            Map<String, Object> functionProps = new LinkedHashMap<>();
            functionProps.put("name", spec.name());
            functionProps.put("description", spec.description());
            functionProps.put("parameters", spec.parameters());
            return objectMapper.writeValueAsString(functionProps);
        } catch (Exception e) {
            log.warn("[TokenEstimator] 序列化工具规格失败，降级用 toString: {}", spec.name());
            return String.valueOf(spec);
        }
    }

    private String extractText(ChatMessage msg) {
        if (msg instanceof UserMessage um) {
            return um.singleText();
        }
        if (msg instanceof AiMessage am) {
            return am.text();   // null when pure tool-call
        }
        if (msg instanceof SystemMessage sm) {
            return sm.text();
        }
        if (msg instanceof ToolExecutionResultMessage tm) {
            return tm.text();
        }
        return null;
    }
}
