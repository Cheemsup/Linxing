package org.linxing.linxing_agent.agent.memory.projection.snip;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.memory.window.projection.snip.SkipTurnReActLoop;
import org.linxing.linxing_agent.agent.memory.window.runtime.AgentMemory;
import org.linxing.linxing_agent.agent.memory.window.runtime.AgentMemoryFactory;
import org.linxing.linxing_agent.agent.memory.window.builder.ContextBuilder;
import org.linxing.linxing_agent.agent.memory.window.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.memory.window.recovery.TurnBoundary;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RuleSet;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RuleSetStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Snip 阶段端到端单测（真实 LLM + 真实 DB 组装链路，@SpringBootTest）。
 *
 * <p>构造一段主题明显分裂的"跑题"历史：Turn 1 讲烹饪食谱（与后续主题无关的寒暄式离题），
 * Turn 2-3 讲 Agent 上下文管理（真实主题）。引导 LLM 判定 Turn 1 低价值、产出 SkipTurnRule。
 *
 * <p>三段断言：
 * <ol>
 *   <li>产出：{@link SkipTurnReActLoop#run} 跑完后 batch 中应有 ≥1 条 SkipTurnRule，turnId 指向 Turn 1。</li>
 *   <li>落 RuleSet：{@link RuleSetStore#apply} 后该 turnId 命中 {@link RuleSet#shouldSkipTurn}。</li>
 *   <li>Builder 消费：{@link ContextBuilder#buildMessages(AgentContext, RecoveredHistory)}
 *       产出的消息列表中，Turn 1 的所有消息（user+ai）应被整段跳过、不出现在结果里；
 *       Turn 2-3 原样保留。</li>
 * </ol>
 *
 * <p>历史数据为手工构造的 RecoveredHistory（messages + turnBoundaries），不依赖 parent 链——
 * 等价于"跨 session 拼接"的跑题语义但结构完整。turnId 用占位 DB id（9001/9002/9003），
 * RuleSetStore 按 sessionId 隔离，不与真实会话冲突。
 */
@SpringBootTest
@DisplayName("Snip 阶段：LLM 产出 SkipTurnRule + Builder 整 Turn 跳过")
class SnipRuleTest {

    @Autowired
    private SkipTurnReActLoop skipTurnReActLoop;
    @Autowired
    private RuleSetStore ruleSetStore;
    @Autowired
    private ContextBuilder contextBuilder;
    @Autowired
    private AgentMemoryFactory memoryFactory;

    /**
     * 构造跑题历史：Turn 1 烹饪食谱（离题），Turn 2-3 Agent 上下文管理（主题）。
     * LLM 应判定 Turn 1 低价值并产 SkipTurnRule。
     */
    private RecoveredHistory buildOffTopicHistory() {
        List<ChatMessage> msgs = new ArrayList<>();
        List<TurnBoundary> boundaries = new ArrayList<>();

        // Turn 1（turnId=9001）：离题的烹饪食谱，与后续主题无关
        int t1Start = msgs.size();
        msgs.add(UserMessage.from("你好，先问个无关的：番茄炒蛋怎么做？放糖还是放盐？"));
        msgs.add(AiMessage.from("番茄炒蛋：先炒蛋盛出，再炒番茄出汁，回锅蛋翻炒，盐糖按口味少量加。"));
        boundaries.add(TurnBoundary.builder().turnStartMessageId(9001)
                .startIdx(t1Start).endIdx(msgs.size()).build());

        // Turn 2（turnId=9002）：真实主题 Agent 上下文管理
        int t2Start = msgs.size();
        msgs.add(UserMessage.from("回到正题：详细讲讲 Agent 上下文管理的三段式机制（Rewrite/Snip/Summary）。"));
        msgs.add(AiMessage.from("三段式：Rewrite 精简 tool 结果、Snip 跳过低价值 Turn、Summary 同步压缩历史。"));
        boundaries.add(TurnBoundary.builder().turnStartMessageId(9002)
                .startIdx(t2Start).endIdx(msgs.size()).build());

        // Turn 3（turnId=9003）：含 tool 调用的真实主题
        int t3Start = msgs.size();
        msgs.add(UserMessage.from("再讲讲 Recovery 的 mirror-first 读路径。"));
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_t3_1").name("search_knowledge_base").arguments("{\"q\":\"Recovery mirror\"}").build();
        msgs.add(AiMessage.builder()
                .text("我先检索一下知识库。")
                .toolExecutionRequests(List.of(req))
                .build());
        msgs.add(ToolExecutionResultMessage.from(req,
                "Recovery mirror-first：两 Hash 皆命中则内存回溯，miss 退化到 DB，cache-aside 热身。"));
        msgs.add(AiMessage.from("Recovery 的 mirror-first 读路径：入口 PK 查后从 mirror:msgs/steps 两 Hash "
                + "内存回溯，任一 miss 退化 DB 并热身 mirror。"));
        boundaries.add(TurnBoundary.builder().turnStartMessageId(9003)
                .startIdx(t3Start).endIdx(msgs.size()).build());

        return RecoveredHistory.builder()
                .messages(msgs)
                .pathEntities(List.of())
                .summaryEntity(null)
                .pathEndMessageId(9003)
                .turnBoundaries(boundaries)
                .build();
    }

    @Test
    @DisplayName("LLM 应对离题 Turn 1 产出 SkipTurnRule，Builder 整 Turn 跳过")
    void testSnipProducesSkipRuleAndBuilderSkipsTurn() throws Exception {
        // 用一个不与真实会话冲突的 sessionId（99999）隔离 RuleSet
        Integer sessionId = 99999;
        ruleSetStore.clear(sessionId); // 确保干净起点

        RecoveredHistory recovered = buildOffTopicHistory();
        RuleSetStore.RuleUpdateBatch batch = new RuleSetStore.RuleUpdateBatch();

        // 当前轮问题（Snip 判定锚点）：明显只与 Turn 2/3 主题相关，与 Turn 1（番茄炒蛋）无关
        String currentQuery = "请基于前面讨论的三段式机制和 Recovery mirror 路径，总结上下文管理的核心设计。";

        // 阶段 2：真实 LLM 跑 Snip 小循环，把 SkipTurnRule 攒进 batch（注入当前轮锚点）
        skipTurnReActLoop.run(recovered, sessionId, currentQuery, batch);

        // 断言 1：应产出至少 1 条 SkipTurnRule
        assertTrue(batch.size() > 0, "LLM 应对离题 Turn 1 产出 SkipTurnRule，实际 batch size=" + batch.size());
        System.out.println("[SnipRuleTest] batch size = " + batch.size());

        // apply 到 RuleSetStore
        RuleSet rs = ruleSetStore.apply(sessionId, batch);
        System.out.println("[SnipRuleTest] skipTurnRules=" + rs.getSkipTurnRules());

        // 断言 2：Turn 1（turnId=9001）应被判定为低价值（命中 shouldSkipTurn）
        // LLM 也可能把 Turn 1 和其他 Turn 一起标记，但 9001 必须在其中
        assertTrue(rs.shouldSkipTurn(9001),
                "Turn 1（离题烹饪食谱）应被标记跳过。实际 skipTurnStartIds=" + rs.skippedTurnStartIds());

        // 断言 3：Builder 消费——buildMessages 产出的列表中，Turn 1 的消息应整段消失
        AgentMemory memory = memoryFactory.create();
        memory.addAll(recovered.getMessages()); // history 段直接填入（模拟 ChatServiceImpl 的 memory.addAll）
        AgentContext context = new AgentContext(1, sessionId, memory, "当前轮追加问题");

        List<ChatMessage> built = contextBuilder.buildMessages(context, recovered);

        // built[0] 是 SystemMessage，从 index 1 起是 history+当前轮
        // Turn 1 的两条消息文本（番茄炒蛋相关）不应出现在 built 中
        boolean hasTomato = built.stream().anyMatch(m -> {
            String t = textOf(m);
            return t != null && t.contains("番茄炒蛋");
        });
        assertFalse(hasTomato, "Builder 产出的消息中不应再含 Turn 1 的番茄炒蛋内容（应被 SkipTurnRule 跳过）");

        // Turn 2/3 的关键内容应保留
        boolean hasThreeStage = built.stream().anyMatch(m -> {
            String t = textOf(m);
            return t != null && t.contains("三段式");
        });
        assertTrue(hasThreeStage, "Turn 2 的三段式内容应保留");
        boolean hasMirror = built.stream().anyMatch(m -> {
            String t = textOf(m);
            return t != null && t.contains("mirror-first");
        });
        assertTrue(hasMirror, "Turn 3 的 mirror-first 内容应保留");

        System.out.println("[SnipRuleTest] built size=" + built.size() + "（已跳过 Turn 1，保留 Turn 2/3）");

        ruleSetStore.clear(sessionId); // 清理
    }

    private String textOf(ChatMessage m) {
        if (m instanceof UserMessage um) return um.singleText();
        if (m instanceof AiMessage am) return am.text();
        if (m instanceof ToolExecutionResultMessage tm) return tm.text();
        return null;
    }
}
