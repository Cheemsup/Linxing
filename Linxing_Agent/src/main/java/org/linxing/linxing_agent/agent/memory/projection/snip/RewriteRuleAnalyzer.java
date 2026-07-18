package org.linxing.linxing_agent.agent.memory.projection.snip;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.TokenEstimator;
import org.linxing.linxing_agent.agent.memory.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.memory.ruleset.RuleSetStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RewriteToolRule 纯规则产出器（thePlan P2-2 / nowRefact §6-4）。
 *
 * <p>REWRITE_TOOL 策略<b>不调 LLM</b>（与 SNIP_LOWVALUE 调 LLM 是不同工作阶段，0717 终稿）。
 * 本类按规则遍历历史中的 {@link ToolExecutionResultMessage}，对"读性质工具 + 结果 token 超阈值"
 * 的条目产 {@code addRewriteToolRule}，丢 content 留 tool_call_id（Builder 占位重建，不破坏配对）。
 *
 * <p>判定依据（消化阶段 2 "待讨论②"）：采 {@code tool_name} 白名单
 *（{@link RewriteRuleWhitelist}）+ 结果 token 阈值，不依赖 ToolSpec 元标记。
 *
 * <p>preserveFields 暂传空列表（Builder 占位符不保留字段；阶段 3 可按需细化保留
 * {@code tool_call_id}/{@code tool_name}/{@code is_success}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RewriteRuleAnalyzer {

    private final TokenEstimator tokenEstimator;

    /** 读性质工具结果 token 超此阈值才产 RewriteToolRule。 */
    @Value("${agent.projection.snip.rewrite.result-token-threshold:2000}")
    private long resultTokenThreshold;

    /**
     * 遍历 history 的 ToolExecutionResultMessage，按白名单+阈值产 addRewriteToolRule 到 batch。
     *
     * @param recovered Recovery 结果（含 history messages）；为空或无消息直接返回
     * @param batch     待填充的批次（调用方持有，最终由 SnipLoopExecutor 原子应用）
     */
    public void analyze(RecoveredHistory recovered, RuleSetStore.RuleUpdateBatch batch) {
        if (recovered == null || recovered.getMessages() == null || recovered.getMessages().isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();//本批已产 rewrite 的 toolCallId，避免重复
        int produced = 0;
        for (ChatMessage msg : recovered.getMessages()) {
            if (!(msg instanceof ToolExecutionResultMessage term)) {
                continue;
            }
            String toolName = term.toolName();
            String toolCallId = term.id() != null ? term.id() : toolName;
            if (toolCallId == null) {
                continue;
            }
            if (!RewriteRuleWhitelist.READ_ONLY_TOOLS.contains(toolName)) {
                continue;//仅读性质工具可精简
            }
            if (seen.contains(toolCallId)) {
                continue;//本批已产，去重
            }
            long tokens = tokenEstimator.estimate(msg);
            if (tokens < resultTokenThreshold) {
                continue;//未超阈值不精简
            }
            batch.addRewriteToolRule(toolCallId,
                    "自动精简：tool=" + toolName + " 结果 token=" + tokens + " 超阈值 " + resultTokenThreshold,
                    List.of());
            seen.add(toolCallId);
            produced++;
        }
        if (produced > 0) {
            log.info("[RewriteRuleAnalyzer] 产出 {} 条 RewriteToolRule（阈值={}）", produced, resultTokenThreshold);
        }
    }
}
