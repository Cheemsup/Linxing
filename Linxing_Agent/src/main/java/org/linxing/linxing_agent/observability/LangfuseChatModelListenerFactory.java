package org.linxing.linxing_agent.observability;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.stereotype.Component;

/**
 * 按 provider 创建 {@link LangfuseChatModelListener} 的工厂。
 * LlmManager 在每个 provider 的 model builder 上调用 {@link #create(provider)} 挂载。
 */
@Component
public class LangfuseChatModelListenerFactory {

    private final Tracer tracer;
    private final MessageSerializer serializer;
    private final LangfuseProperties props;
    private final AgentObservability agentObservability;

    public LangfuseChatModelListenerFactory(Tracer tracer, MessageSerializer serializer,
                                            LangfuseProperties props, AgentObservability agentObservability) {
        this.tracer = tracer;
        this.serializer = serializer;
        this.props = props;
        this.agentObservability = agentObservability;
    }

    public ChatModelListener create(String provider) {
        return new LangfuseChatModelListener(tracer, serializer, props, agentObservability, provider);
    }
}
