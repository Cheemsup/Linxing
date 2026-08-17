package org.linxing.linxing_agent.observability;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 测试用 SpanExporter：把导出的 SpanData 收集进内存列表，供断言 span 结构/属性。
 * 配合 {@code SimpleSpanProcessor}（同步导出）使用，end 后立即可断言。
 */
public class CollectingSpanExporter implements SpanExporter {

    private final List<SpanData> spans = new ArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> spanDataList) {
        spans.addAll(spanDataList);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    public List<SpanData> getSpans() {
        return spans;
    }
}
