package org.linxing.linxing_agent.agent.memory.window.projection.snip;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.Getter;
import org.linxing.linxing_agent.agent.memory.window.projection.snip.rules.ReadCurrentRulesTool;
import org.linxing.linxing_agent.agent.memory.window.projection.snip.rules.UpdateSkipTurnRuleTool;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RuleSetStore;
import tools.jackson.databind.ObjectMapper;

/**
 * SkipTurn ReAct 小循环的 per-loop 载体。
 *
 * <p>小循环不构造主 {@code AgentContext}（那是主流程的，含 stepListener/stepRecorder/recovered，
 * 不应混用）。本类是小循环专属轻量载体，持有：
 * <ul>
 *   <li>{@code batch}：攒 rule 更新操作的批次（单线程独占，循环结束由 SnipLoopExecutor 原子应用）</li>
 *   <li>{@code ruleSetStore}：供 read_current_rules 只读当前 RuleSet</li>
 *   <li>{@code objectMapper}：解析 tool arguments JSON</li>
 * </ul>
 *
 * <p>{@link #executeTool(ToolExecutionRequest)} 按 name 路由到内部 rule 更新 tool
 *（{@code UpdateSkipTurnRuleTool}/{@code ReadCurrentRulesTool}），手工分派——
 * 这两把 tool 不实现主 {@code Tool} 接口、不进 ToolRegistry，避免污染主 Agent 目录。
 */
public class SkipTurnReActContext {

    @Getter
    private final Integer sessionId;
    @Getter
    private final RuleSetStore ruleSetStore;
    @Getter
    private final ObjectMapper objectMapper;
    @Getter
    private final RuleSetStore.RuleUpdateBatch batch;

    /**
     * 小循环默认构造：自建新 batch（典型用于独立运行场景）。
     */
    public SkipTurnReActContext(Integer sessionId, RuleSetStore ruleSetStore, ObjectMapper objectMapper) {
        this(sessionId, ruleSetStore, objectMapper, new RuleSetStore.RuleUpdateBatch());
    }

    /**
     * 共享 batch 构造：传入外部 batch，Snip 的 SkipTurnRule 操作与 Rewrite 的 RewriteToolRule
     * 操作攒进同一 batch，由 SnipLoopExecutor 统一原子应用。
     */
    public SkipTurnReActContext(Integer sessionId, RuleSetStore ruleSetStore, ObjectMapper objectMapper,
                                RuleSetStore.RuleUpdateBatch batch) {
        this.sessionId = sessionId;
        this.ruleSetStore = ruleSetStore;
        this.objectMapper = objectMapper;
        this.batch = batch;
    }

    /**
     * 按 tool name 分派到对应内部 rule tool 执行，返回给 LLM 的结果文本。
     * <p>未知 tool 返回 error 文本（LLM 可据以纠正）。
     */
    public String executeTool(ToolExecutionRequest req) {
        return switch (req.name()) {
            case "update_skip_turn_rule" -> UpdateSkipTurnRuleTool.execute(this, req.arguments());
            case "read_current_rules" -> ReadCurrentRulesTool.execute(this);
            default -> "error: 未知工具 " + req.name();
        };
    }
}
