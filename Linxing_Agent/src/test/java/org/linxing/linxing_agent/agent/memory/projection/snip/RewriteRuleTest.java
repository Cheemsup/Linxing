package org.linxing.linxing_agent.agent.memory.projection.snip;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.linxing.linxing_agent.agent.memory.TokenEstimator;
import org.linxing.linxing_agent.agent.memory.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.memory.recovery.TurnBoundary;
import org.linxing.linxing_agent.agent.memory.ruleset.RewriteToolRule;
import org.linxing.linxing_agent.agent.memory.ruleset.RuleSet;
import org.linxing.linxing_agent.agent.memory.ruleset.RuleSetStore;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rewrite 阶段单测（绕开整链路凑阈值，直接验证规则产出 + Builder 消费侧）。
 *
 * <p>三段断言：
 * <ol>
 *   <li>{@link RewriteRuleAnalyzer#analyze}：构造"白名单内 + 结果超阈值"的 ToolExecutionResultMessage，
 *       断言产出 RewriteToolRule；同时构造"白名单外（写性质）"与"未超阈值"两条，断言不产出。</li>
 *   <li>{@link RuleSet#rewriteRuleFor} 命中：产出的 rule 能按 toolCallId 命中。</li>
 *   <li>Builder 消费侧占位符重建：复刻 {@code DefaultContextBuilder.projectToolResult} 的核心契约
 *       ——命中 rule 的 ToolExecutionResultMessage 替换为占位符、保留 tool_call_id 不破坏 langchain4j 配对；
 *       未命中 rule 的原样返回。</li>
 * </ol>
 *
 * <p>不启动 Spring 容器——{@link TokenEstimator} 手工 new + 反射注入 encoding 后调 {@code init()}，
 * 走真实 jtokkit BPE 计数（比 mock 更可信）。
 */
@DisplayName("Rewrite 阶段：规则产出 + Builder 消费侧")
class RewriteRuleTest {

    private RewriteRuleAnalyzer analyzer;
    private TokenEstimator tokenEstimator;

    @BeforeEach
    void setUp() throws Exception {
        // 真实 TokenEstimator（jtokkit cl100k_base），手工触发 @PostConstruct 等价初始化
        tokenEstimator = new TokenEstimator();
        // @Value 未注入时 encodingName 为 null，反射预置 cl100k_base
        java.lang.reflect.Field nameField = TokenEstimator.class.getDeclaredField("encodingName");
        nameField.setAccessible(true);
        nameField.set(tokenEstimator, "cl100k_base");
        // 反射调包级私有 init() 初始化 jtokkit encoding
        java.lang.reflect.Method init = TokenEstimator.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(tokenEstimator);

        analyzer = new RewriteRuleAnalyzer(tokenEstimator, new RewriteRuleWhitelist(Set.of()));
        // result-token-threshold 默认 2000，反射注入确保测试独立于配置漂移
        java.lang.reflect.Field thrField = RewriteRuleAnalyzer.class.getDeclaredField("resultTokenThreshold");
        thrField.setAccessible(true);
        thrField.setLong(analyzer, 2000L);
    }

    @Test
    @DisplayName("白名单内 + 结果超阈值 → 产出 RewriteToolRule；白名单外/未超阈值 → 不产出")
    void testAnalyzeProducesRuleOnlyForLargeReadOnlyToolResults() {
        // 构造一条超 2000 token 的 search_knowledge_base 结果（重复长文本撑 token）
        String huge = "Agent 上下文管理 ".repeat(800); // 约 6400 字符，jtokkit 约 3000+ token
        ToolExecutionRequest req1 = ToolExecutionRequest.builder()
                .id("call_search_1").name("search_knowledge_base").arguments("{}").build();
        ToolExecutionResultMessage largeReadOnly = ToolExecutionResultMessage.from(req1, huge);

        // 白名单内但结果很小（未超阈值）
        ToolExecutionRequest req2 = ToolExecutionRequest.builder()
                .id("call_web_2").name("web_search").arguments("{}").build();
        ToolExecutionResultMessage smallReadOnly = ToolExecutionResultMessage.from(req2, "短结果");

        // 白名单外（写性质 save_exam）且结果很大——不应被精简
        ToolExecutionRequest req3 = ToolExecutionRequest.builder()
                .id("call_save_3").name("save_exam").arguments("{}").build();
        ToolExecutionResultMessage largeWrite = ToolExecutionResultMessage.from(req3, huge);

        // 配对用 AiMessage（含 tool call），让 history 结构更像真实 Recovery 产物
        AiMessage aiWithCalls = AiMessage.builder()
                .toolExecutionRequests(List.of(req1, req2, req3))
                .build();

        RecoveredHistory recovered = RecoveredHistory.builder()
                .messages(List.of(
                        UserMessage.from("用户提问"),
                        aiWithCalls,
                        largeReadOnly, smallReadOnly, largeWrite
                ))
                .turnBoundaries(List.of(
                        TurnBoundary.builder().turnStartMessageId(100).startIdx(0).endIdx(5).build()
                ))
                .build();

        RuleSetStore.RuleUpdateBatch batch = new RuleSetStore.RuleUpdateBatch();
        analyzer.analyze(recovered, batch);

        // 断言：仅 1 条 rule（largeReadOnly），smallReadOnly 未超阈值、largeWrite 白名单外
        assertEquals(1, batch.size(), "仅 largeReadOnly 应产 rule");
        // apply 到 RuleSet 后验证命中
        RuleSetStore store = new RuleSetStore();
        RuleSet rs = store.apply(999, batch);
        RewriteToolRule rule = rs.rewriteRuleFor("call_search_1");
        assertNotNull(rule, "call_search_1 应命中 RewriteToolRule");
        assertNull(rs.rewriteRuleFor("call_web_2"), "web_search 小结果不应有 rule");
        assertNull(rs.rewriteRuleFor("call_save_3"), "save_exam 白名单外不应有 rule");
        assertTrue(rule.getReason().contains("search_knowledge_base"),
                "reason 应含工具名：" + rule.getReason());
    }

    @Test
    @DisplayName("Builder 消费侧：命中 rule 的 ToolExecutionResultMessage 替换为占位符、保留 tool_call_id；未命中原样")
    void testBuilderConsumesRewriteRule() {
        // 预置一条 RewriteToolRule 命中 call_search_1
        RuleSetStore store = new RuleSetStore();
        RuleSetStore.RuleUpdateBatch batch = new RuleSetStore.RuleUpdateBatch();
        batch.addRewriteToolRule("call_search_1", "精简原因", List.of());
        RuleSet rs = store.apply(42, batch);

        ToolExecutionRequest hitReq = ToolExecutionRequest.builder()
                .id("call_search_1").name("search_knowledge_base").arguments("{}").build();
        ToolExecutionResultMessage hitMsg = ToolExecutionResultMessage.from(hitReq, "超长结果内容应被丢弃".repeat(500));

        ToolExecutionRequest missReq = ToolExecutionRequest.builder()
                .id("call_save_2").name("save_exam").arguments("{}").build();
        ToolExecutionResultMessage missMsg = ToolExecutionResultMessage.from(missReq, "写性质结果保留");

        // 复刻 DefaultContextBuilder.projectToolResult 的核心契约（private，此处直接验证 RuleSet 命中 + 占位符重建等价性）
        ChatMessage projectedHit = projectToolResult(hitMsg, rs);
        ChatMessage projectedMiss = projectToolResult(missMsg, rs);

        // 命中：变为占位符，但 tool_call_id/name 保留（不破坏 langchain4j AiMessage↔ToolResult 配对硬约束）
        assertInstanceOf(ToolExecutionResultMessage.class, projectedHit, "投影后仍是 ToolExecutionResultMessage");
        ToolExecutionResultMessage projected = (ToolExecutionResultMessage) projectedHit;
        assertEquals("call_search_1", projected.id(), "tool_call_id 必须保留以维持配对");
        assertEquals("search_knowledge_base", projected.toolName(), "tool_name 必须保留");
        assertTrue(projected.text().contains("精简"), "占位符应含 reason 提示");
        assertTrue(projected.text().length() < hitMsg.text().length() / 10,
                "占位符应远小于原始结果");

        // 未命中：原样返回
        assertSame(missMsg, projectedMiss, "未命中 rule 的消息应原样返回");
    }

    /**
     * 复刻 {@link org.linxing.linxing_agent.agent.memory.builder.DefaultContextBuilder#projectToolResult}
     * 的核心契约。原方法 private，此处用同样逻辑验证 RuleSet 命中→占位符重建链路；
     * 真实 Builder 的整链路（buildMessages）在 {@code SnipRuleTest}（@SpringBootTest）端到端验证。
     */
    private ChatMessage projectToolResult(ChatMessage msg, RuleSet ruleSet) {
        if (!(msg instanceof ToolExecutionResultMessage term)) {
            return msg;
        }
        String toolCallId = term.id() != null ? term.id() : term.toolName();
        RewriteToolRule rule = ruleSet.rewriteRuleFor(toolCallId);
        if (rule == null) {
            return msg;
        }
        String placeholder = "[此工具结果已被 Projection 精简：toolCallId=" + toolCallId
                + (rule.getReason() != null && !rule.getReason().isBlank()
                ? ", reason=" + rule.getReason() : "")
                + "]";
        return ToolExecutionResultMessage.from(
                ToolExecutionRequest.builder().id(term.id()).name(term.toolName()).build(),
                placeholder);
    }
}
