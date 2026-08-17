package org.linxing.linxing_agent.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Langfuse 观测配置，对应 application.yaml 平级的 `langfuse:` 段。
 * 凭据从旧 quarkus 段迁移而来（0816 改造，见 reference/TODOS/langfuse/0816LangfuseObservability.md）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseProperties {

    /** 总开关：false 时 {@link OtelTraceConfig} 构建 no-op Tracer，不建 exporter、不透传任何 span */
    private boolean enabled = false;

    /**
     * OTLP HTTP 端点（Langfuse 控制台的 /api/public/otel，程序化 setEndpoint() 必须带完整信号路径
     * /v1/traces；base 形式由 OtelTraceConfig#resolveEndpoint 自动补全，见该处说明）
     */
    private String endpoint;

    /** Langfuse 公钥（Basic auth 的 user 部分） */
    private String publicKey;

    /** Langfuse 私钥（Basic auth 的 password 部分） */
    private String secretKey;

    /** 部署环境，写入 langfuse.environment（trace 级属性） */
    private String environment = "dev";

    /** 应用版本，写入 langfuse.version / langfuse.release（trace 级属性） */
    private String version = "0.0.1-SNAPSHOT";

    /** 离线 LLM 调用（RAG 增强/摘要/后台 worker）是否入 trace。首期未接入，预留开关 */
    private boolean traceOfflineCalls = false;
}
