package org.linxing.linxing_agent.agent.memory.window.projection.rewrite;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.TokenEstimator;
import org.linxing.linxing_agent.agent.memory.window.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RuleSetStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RewriteRuleAnalyzer {

    private final TokenEstimator tokenEstimator;
    private final RewriteRuleWhitelist rewriteRuleWhitelist;

    /**
     * 读性质工具结果 token 超此阈值才产 RewriteToolRule。
     * <p>下限保护：小于此阈值的结果（如 resolve 返回的工具定义，通常仅几十~两百 token）原样保留不精简
     */
    @Value("${agent.projection.snip.rewrite.result-token-threshold}")
    private long resultTokenThreshold;

    /**
     * 按白名单 + token 阈值产出 RewriteToolRule 到 batch。
     * <p>白名单命中（读性质工具）且结果 token 超过 {@link #resultTokenThreshold} 才产 rule；
     * @param recovered
     * @param batch
     */
    public void analyze(RecoveredHistory recovered, RuleSetStore.RuleUpdateBatch batch) {
        if (recovered == null || recovered.getMessages() == null || recovered.getMessages().isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();//本批已产 rewrite 的 toolCallId，避免重复
        int produced = 0;
        for (ChatMessage msg : recovered.getMessages()) {
            if (!(msg instanceof ToolExecutionResultMessage term)) {//rewrite只做tool的精简（0719），后续可能扩充到skill/mcp的精简
                continue;
            }
            String toolName = term.toolName();
            String toolCallId = term.id() != null ? term.id() : toolName;
            if (toolCallId == null) {
                continue;
            }
            if (!rewriteRuleWhitelist.contains(toolName)) {
                continue;//仅读性质工具可精简
            }
            if (seen.contains(toolCallId)) {
                continue;//本批已产，去重
            }
            long tokens = tokenEstimator.estimate(msg);
            // 阈值下限保护：小结果（如 resolve 工具定义）不精简，避免占位符开销 + 关键信息丢失
            if (tokens < resultTokenThreshold) {
                continue;
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
