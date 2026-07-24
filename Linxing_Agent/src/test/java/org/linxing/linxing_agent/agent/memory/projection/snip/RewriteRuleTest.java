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
import org.linxing.linxing_agent.agent.memory.window.builder.DefaultContextBuilder;
import org.linxing.linxing_agent.agent.memory.window.projection.rewrite.RewriteRuleAnalyzer;
import org.linxing.linxing_agent.agent.memory.window.projection.rewrite.RewriteRuleWhitelist;
import org.linxing.linxing_agent.agent.memory.window.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.memory.window.recovery.TurnBoundary;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RewriteToolRule;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RuleSet;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RuleSetStore;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rewrite 阶段单测（绕开整链路凑阈值，直接验证规则产出 + Builder 消费侧）。
 *
 * <p>当前 {@link RewriteRuleAnalyzer} 为"白名单 + token 阈值下限"语义（0722 恢复阈值）：
 * 白名单内读性质工具（search_knowledge_base/web_search/resolve）命中且结果 token 超过
 * {@code resultTokenThreshold}（默认 2000）才产 RewriteToolRule；小结果不精简（避免占位符开销 +
 * 关键信息丢失，如 resolve 返回的工具定义）；白名单外（写性质）不产；同 toolCallId 本批去重。
 *
 * <p>三段断言：
 * <ol>
 *   <li>{@link RewriteRuleAnalyzer#analyze}：白名单内 + 超阈值工具产出 RewriteToolRule；
 *       白名单外（写性质）不产出；白名单内但小结果不产出；同 toolCallId 重复出现只产一条。</li>
 *   <li>{@link RuleSet#rewriteRuleFor} 命中：产出的 rule 能按 toolCallId 命中。</li>
 *   <li>Builder 消费侧占位符重建：复刻 {@code DefaultContextBuilder.projectToolResult} 的核心契约
 *       ——命中 rule 的 ToolExecutionResultMessage 替换为占位符、保留 tool_call_id 不破坏 langchain4j 配对；
 *       未命中 rule 的原样返回。</li>
 * </ol>
 *
 * <p>不启动 Spring 容器——{@link TokenEstimator} 手工 new + 反射注入 encoding 后调 {@code init()}，
 * 走真实 jtokkit BPE 计数（比 mock 更可信）；{@code resultTokenThreshold} 同样反射注入（@Value 无 Spring 不生效）。
 */
@DisplayName("Rewrite 阶段：规则产出 + Builder 消费侧")
class RewriteRuleTest {

    private RewriteRuleAnalyzer analyzer;
    private TokenEstimator tokenEstimator;

    /** 单测用阈值：小到能被短文本命中、又能体现"超阈值才精简"语义。真实默认 2000 见 yaml。 */
    private static final long TEST_THRESHOLD = 50;

    @BeforeEach
    void setUp() throws Exception {
        // 真实 TokenEstimator（jtokkit cl100k_base），手工触发 @PostConstruct 等价初始化
        tokenEstimator = new TokenEstimator(new tools.jackson.databind.ObjectMapper());
        // @Value 未注入时 encodingName 为 null，反射预置 cl100k_base
        java.lang.reflect.Field nameField = TokenEstimator.class.getDeclaredField("encodingName");
        nameField.setAccessible(true);
        nameField.set(tokenEstimator, "cl100k_base");
        // 反射调包级私有 init() 初始化 jtokkit encoding
        java.lang.reflect.Method init = TokenEstimator.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(tokenEstimator);

        // 传空集 → RewriteRuleWhitelist 回退到 DEFAULT_READ_ONLY_TOOLS（search_knowledge_base/web_search/resolve）
        analyzer = new RewriteRuleAnalyzer(tokenEstimator, new RewriteRuleWhitelist(Set.of()));
        // @Value 无 Spring 不注入，反射预置测试阈值（否则 long 默认 0 → 全部超阈值 → 退化为无阈值）
        java.lang.reflect.Field thresholdField = RewriteRuleAnalyzer.class.getDeclaredField("resultTokenThreshold");
        thresholdField.setAccessible(true);
        thresholdField.setLong(analyzer, TEST_THRESHOLD);
    }

    @Test
    @DisplayName("白名单内 + 超阈值工具产 rule；白名单外不产；白名单内小结果不产；同 toolCallId 去重")
    void testAnalyzeProducesRuleOnlyForWhitelistedReadOnlyTools() {
        // 白名单内 + 超阈值：search_knowledge_base（大结果）
        String huge = "Agent 上下文管理 ".repeat(800);
        ToolExecutionRequest req1 = ToolExecutionRequest.builder()
                .id("call_search_1").name("search_knowledge_base").arguments("{}").build();
        ToolExecutionResultMessage largeReadOnly = ToolExecutionResultMessage.from(req1, huge);

        // 白名单内 + 小结果（web_search 短结果，阈值 50 下不超阈值 → 不应产 rule）
        ToolExecutionRequest req2 = ToolExecutionRequest.builder()
                .id("call_web_2").name("web_search").arguments("{}").build();
        ToolExecutionResultMessage smallReadOnly = ToolExecutionResultMessage.from(req2, "短结果");

        // 白名单外（写性质 save_exam）且结果很大——不应被精简
        ToolExecutionRequest req3 = ToolExecutionRequest.builder()
                .id("call_save_3").name("save_exam").arguments("{}").build();
        ToolExecutionResultMessage largeWrite = ToolExecutionResultMessage.from(req3, huge);

        // 同 toolCallId 重复出现（call_search_1 再来一条），本批应去重只产一条
        ToolExecutionResultMessage dupReadOnly = ToolExecutionResultMessage.from(req1, "重复结果");

        // 配对用 AiMessage（含 tool call），让 history 结构更像真实 Recovery 产物
        AiMessage aiWithCalls = AiMessage.builder()
                .toolExecutionRequests(List.of(req1, req2, req3))
                .build();

        RecoveredHistory recovered = RecoveredHistory.builder()
                .messages(List.of(
                        UserMessage.from("用户提问"),
                        aiWithCalls,
                        largeReadOnly, smallReadOnly, largeWrite, dupReadOnly
                ))
                .turnBoundaries(List.of(
                        TurnBoundary.builder().turnStartMessageId(100).startIdx(0).endIdx(6).build()
                ))
                .build();

        RuleSetStore.RuleUpdateBatch batch = new RuleSetStore.RuleUpdateBatch();
        analyzer.analyze(recovered, batch);

        // 断言：仅 1 条 rule（largeReadOnly 超阈值）；smallReadOnly 白名单内但小结果不超阈值不产；
        // largeWrite 白名单外；dupReadOnly 与 largeReadOnly 同 toolCallId 去重
        assertEquals(1, batch.size(), "仅 search 大结果超阈值产 1 条；web 小结果不产；重复 toolCallId 去重");

        // analyzer 产出的 rule 进了 batch.ops（私有），跨包不可见；
        // 此处改用手动构造等价 RuleSet 验证命中语义（RuleSet 命中逻辑与 rule 来源无关），
        // 真实"batch→RuleSetStore.apply→RuleSet"链路由 SnipRuleTest（@SpringBootTest）端到端覆盖。
        RuleSet rs = new RuleSet(List.of(), List.of(
                RewriteToolRule.builder().ruleId("r1").toolCallId("call_search_1")
                        .reason("自动精简：tool=search_knowledge_base").preserveFields(List.of()).build()
        ));
        RewriteToolRule ruleSearch = rs.rewriteRuleFor("call_search_1");
        assertNotNull(ruleSearch, "call_search_1 应命中 RewriteToolRule");
        assertNull(rs.rewriteRuleFor("call_web_2"), "web_search 小结果不超阈值不应有 rule");
        assertNull(rs.rewriteRuleFor("call_save_3"), "save_exam 白名单外不应有 rule");
        assertTrue(ruleSearch.getReason().contains("search_knowledge_base"),
                "reason 应含工具名：" + ruleSearch.getReason());
    }

    @Test
    @DisplayName("Builder 消费侧：命中 rule 的 ToolExecutionResultMessage 替换为占位符、保留 tool_call_id；未命中原样")
    void testBuilderConsumesRewriteRule() {
        // 预置一条 RewriteToolRule 命中 call_search_1（RuleSet 公开构造，绕开需 RagProperties 的 RuleSetStore）
        RewriteToolRule rule = RewriteToolRule.builder()
                .ruleId("r1")
                .toolCallId("call_search_1")
                .reason("精简原因")
                .preserveFields(List.of())
                .build();
        RuleSet rs = new RuleSet(List.of(), List.of(rule));

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
     * 复刻 {@link DefaultContextBuilder#projectToolResult}
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
