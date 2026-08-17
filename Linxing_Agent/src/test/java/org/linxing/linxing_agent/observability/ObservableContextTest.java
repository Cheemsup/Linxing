package org.linxing.linxing_agent.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ObservableContext} 单测：ThreadLocal 栈生命周期（makeCurrent/close）、step 号、子上下文属性携带。
 * 这是 0816 改造「span 引用跨线程传播」的核心机制，见参考文档 3.5。
 */
class ObservableContextTest {

    private static ObservableContext ctx(String session, String user) {
        return ObservableContext.of(Span.getInvalid(),
                new ObservableContext.TraceAttrs(session, user, null, null, null));
    }

    @Test
    @DisplayName("makeCurrent/close 维护线程栈：close 后 current() 恢复上层或清空")
    void stackLifecycle() {
        assertNull(ObservableContext.current(), "初始无上下文");

        ObservableContext outer = ctx("s1", "u1");
        ObservableContext inner = ctx("s2", "u2");

        Scope outerScope = outer.makeCurrent();
        assertEquals(outer, ObservableContext.current());

        Scope innerScope = inner.makeCurrent();
        assertEquals(inner, ObservableContext.current());

        innerScope.close();
        assertEquals(outer, ObservableContext.current(), "close 内层后应回退到外层");

        outerScope.close();
        assertNull(ObservableContext.current(), "全部 close 后栈应清空");
    }

    @Test
    @DisplayName("step 号 ThreadLocal：读写与清理")
    void stepHolder() {
        assertNull(ObservableContext.getCurrentStep());
        ObservableContext.setCurrentStep(3);
        assertEquals(3, ObservableContext.getCurrentStep());
        ObservableContext.clearCurrentStep();
        assertNull(ObservableContext.getCurrentStep());
    }

    @Test
    @DisplayName("childOf 复制父上下文 trace 属性，供 applyTraceAttrs 传播")
    void childCarriesAttrs() {
        ObservableContext parent = ObservableContext.of(Span.getInvalid(),
                new ObservableContext.TraceAttrs("s1", "u1", "r1", "q1", null));
        try (Scope ignored = parent.makeCurrent()) {
            ObservableContext child = ObservableContext.childOf(Span.getInvalid(), parent);
            assertNotNull(child.getAttrs());
            assertEquals("s1", child.getAttrs().sessionId);
            assertEquals("u1", child.getAttrs().userId);
            assertEquals("r1", child.getAttrs().requestId);
            assertEquals("q1", child.getAttrs().question);
        }
    }

    @Test
    @DisplayName("无父上下文时 childOf 不抛异常，attrs 为 null（no-op 路径）")
    void childOfWithoutParent() {
        ObservableContext child = ObservableContext.childOf(Span.getInvalid(), null);
        assertNull(child.getAttrs());
    }
}
