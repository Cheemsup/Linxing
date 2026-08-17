package org.linxing.linxing_agent.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;

/**
 * OTel SDK 直连 Langfuse OTLP 端点（0816 改造，路线选型见 reference/TODOS/langfuse/0816LangfuseObservability.md 第四节）。
 * <p>只暴露一个 {@link Tracer} Bean：自定义 ChatModelListener / AgentObservability 从它创建 span，
 * span 属性按 Langfuse v4 OTLP 语义约定写入（trace 级字段在各 span 上冗余，见 3.2）。
 * <p>enabled=false 时直接返回 no-op Tracer，不创建 exporter、不开任何线程，系统零开销。
 */
@Configuration
@Slf4j
public class OtelTraceConfig {

    private static final String INSTRUMENTATION_SCOPE_NAME = "org.linxing.linxing_agent";
    private static final String SERVICE_NAME = "linxing-agent";
    /** Langfuse v4 摄取版本头，官方要求必须携带 */
    private static final String LANGFUSE_INGESTION_HEADER = "x-langfuse-ingestion-version";
    private static final String LANGFUSE_INGESTION_VERSION = "4";

    @Bean
    public Tracer langfuseTracer(LangfuseProperties props) {
        if (!props.isEnabled()) {
            log.info("[Langfuse] 观测已禁用 (enabled=false)，Tracer 为 no-op，不透传任何 span");
            return TracerProvider.noop().get(INSTRUMENTATION_SCOPE_NAME);
        }

        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), SERVICE_NAME,
                AttributeKey.stringKey("deployment.environment"), props.getEnvironment())));

        // OTel Java SDK 程序化 setEndpoint() 按字面使用路径，不会像环境变量自动装配那样自动追加信号路径
        // （其默认端点即含完整路径 http://localhost:4318/v1/traces）。Langfuse OTLP 只服务完整路径，
        // base 端点 .../api/public/otel 直 POST 会 404，故此处统一补全 /v1/traces。
        String endpoint = resolveEndpoint(props.getEndpoint());
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .addHeader("Authorization", basicAuth(props.getPublicKey(), props.getSecretKey()))
                .addHeader(LANGFUSE_INGESTION_HEADER, LANGFUSE_INGESTION_VERSION)
                .build();

        log.info("[Langfuse] OTLP exporter 已构建: endpoint={}, environment={}, version={}, publicKey={}",
                endpoint, props.getEnvironment(), props.getVersion(), maskKey(props.getPublicKey()));

        // 导出结果观测：OTel BatchSpanProcessor 默认把导出失败静默吞掉（JUL→slf4j 级别低，不易察觉）。
        // 包一层 SpanExporter，把每批 export 的成败显式打到日志，冒烟期定位「span 建了但 Langfuse 没收到」。
        SpanExporter loggingExporter = new LoggingSpanExporter(exporter);

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(loggingExporter).build())
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close));
        return tracerProvider.get(INSTRUMENTATION_SCOPE_NAME);
    }

    private String basicAuth(String publicKey, String secretKey) {
        String cred = publicKey + ":" + secretKey;
        return "Basic " + Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 补全 OTLP 信号路径：程序化 {@code OtlpHttpSpanExporter.setEndpoint()} 按字面使用传入 URL，不会自动追加
     * {@code /v1/traces}（仅环境变量自动装配 OTEL_EXPORTER_OTLP_ENDPOINT 会追加）。Langfuse OTLP 端点只服务
     * 完整路径 {@code .../api/public/otel/v1/traces}，base 端点直 POST 会 404，故统一规整：
     * <ul>
     *   <li>base 形式 {@code .../api/public/otel} → 追加 {@code /v1/traces}</li>
     *   <li>完整形式 {@code .../api/public/otel/v1/traces} → 原样使用（也容忍尾斜杠）</li>
     * </ul>
     */
    private String resolveEndpoint(String endpoint) {
        String base = endpoint.replaceAll("/+$", "");
        return base.endsWith("/v1/traces") ? base : base + "/v1/traces";
    }

    /** 打日志时脱敏 key：只留前 6 位，便于确认加载的是预期凭据又不泄露 */
    private String maskKey(String key) {
        if (key == null || key.length() <= 6) {
            return key == null ? "null" : "***";
        }
        return key.substring(0, 6) + "***";
    }

    /**
     * 观测用 exporter 包装：把每批 {@link #export(Collection)} 的结果显式打到日志。
     * OTel 自身会把导出失败记在 JUL→slf4j 桥的 WARN 上，这里补一条带 {@code [Langfuse]} 前缀的
     * INFO/ERROR，冒烟期一眼看出「trace 是否真正送达 Langfuse」。
     */
    private static final class LoggingSpanExporter implements SpanExporter {
        private final SpanExporter delegate;

        LoggingSpanExporter(SpanExporter delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            CompletableResultCode result = delegate.export(spans);
            result.whenComplete(() -> {
                if (result.isSuccess()) {
                    log.info("[Langfuse] span 批量导出成功: {} spans", spans.size());
                } else {
                    log.error("[Langfuse] span 批量导出失败: {} spans（检查网络可达性/端点/凭据，详见其后 OTel 日志）",
                            spans.size());
                }
            });
            return result;
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }
    }
}
