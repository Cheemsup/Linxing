package org.linxing.linxing_agent.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 观测上下文（ThreadLocal 栈），解决「span 引用 + trace 级属性不跨线程」的问题。
 * <p>0816 改造，机制见 reference/TODOS/langfuse/0816LangfuseObservability.md 3.5：
 * <ul>
 *   <li>agent 线程：ChatServiceImpl 建 root span 后 {@link #makeCurrent()} 入栈并置为 OTel 当前上下文；</li>
 *   <li>tool-exec 线程：AgentExecutor 在工具提交前捕获本上下文，工具线程 {@link #makeCurrent()} 恢复，
 *       工具内 LLM 调用（onRequest）读 ThreadLocal 顶拿到父 span，子 Agent 钩子同理；</li>
 *   <li>流式 generation 的 onResponse/onError 在 OpenAI 回调线程：不依赖本类，用 attributes map 取 span 引用。</li>
 * </ul>
 * 另持有一个轻量 {@link ThreadLocal}{@code <Integer>} 记录主循环当前 step 号（AgentExecutor 每轮 chat() 前设置，
 * listener onRequest 读取写入 generation span 的 {@code langfuse.observation.metadata.step_number}）。
 */
public final class ObservableContext {

    /**
     * trace 级属性（root 建一次，子上下文引用共享——只读）。
     * version/release/environment/tags 由 {@link AgentObservability#applyTraceAttrs} 直接取自配置，不在此冗余。
     */
    public static final class TraceAttrs {
        public final String sessionId;
        public final String userId;
        public final String requestId;
        public final String question;
        public final String provider;

        public TraceAttrs(String sessionId, String userId, String requestId, String question, String provider) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.requestId = requestId;
            this.question = question;
            this.provider = provider;
        }
    }

    private final Span span;
    /** span 作为当前 span 的 OTel Context 快照（含祖先链），供跨线程 {@link #makeCurrent()} 恢复 */
    private final Context otelContext;
    private final TraceAttrs attrs;

    private static final ThreadLocal<Deque<ObservableContext>> STACK = new ThreadLocal<>();
    private static final ThreadLocal<Integer> CURRENT_STEP = new ThreadLocal<>();

    private ObservableContext(Span span, Context otelContext, TraceAttrs attrs) {
        this.span = span;
        this.otelContext = otelContext;
        this.attrs = attrs;
    }

    /**
     * 新建上下文：{@code otelContext} = 当前 OTel 上下文 + {@code span} 作为当前 span。
     * 供 root（无父）创建使用。
     */
    public static ObservableContext of(Span span, TraceAttrs attrs) {
        return new ObservableContext(span, span.storeInContext(Context.current()), attrs);
    }

    /**
     * 新建子上下文：复制父上下文 trace 级属性，span 挂在当前 OTel 上下文下（父 span 在其祖先链中）。
     * 供 tool / 子 Agent span 使用。
     */
    public static ObservableContext childOf(Span span, ObservableContext parent) {
        return new ObservableContext(span, span.storeInContext(Context.current()),
                parent != null ? parent.attrs : null);
    }

    /**
     * 在本线程上恢复本上下文：入 ThreadLocal 栈 + 置为 OTel 当前上下文。
     * 返回的 Scope 在 close 时弹栈并恢复先前 OTel 上下文（与 Context.makeCurrent 同一 close 语义）。
     */
    public Scope makeCurrent() {
        Deque<ObservableContext> stack = STACK.get();
        final Deque<ObservableContext> s;
        if (stack == null) {
            s = new ArrayDeque<>();
            STACK.set(s);
        } else {
            s = stack;
        }
        s.push(this);
        Scope otelScope = otelContext.makeCurrent();
        return new Scope() {
            @Override
            public void close() {
                if (s.peek() == ObservableContext.this) {
                    s.pop();
                }
                if (s.isEmpty()) {
                    STACK.remove();
                }
                otelScope.close();
            }
        };
    }

    /** 当前线程栈顶上下文；无观测上下文（离线调用）时返回 null */
    public static ObservableContext current() {
        Deque<ObservableContext> stack = STACK.get();
        return (stack == null || stack.isEmpty()) ? null : stack.peek();
    }

    public Span getSpan() {
        return span;
    }

    public TraceAttrs getAttrs() {
        return attrs;
    }

    // ---- 主循环 step 号（供 generation span 写 metadata.step_number） ----

    public static void setCurrentStep(Integer step) {
        CURRENT_STEP.set(step);
    }

    public static Integer getCurrentStep() {
        return CURRENT_STEP.get();
    }

    public static void clearCurrentStep() {
        CURRENT_STEP.remove();
    }
}
