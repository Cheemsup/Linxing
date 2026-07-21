package org.linxing.linxing_agent.agent.memory.window.projection.rewrite;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.TokenEstimator;
import org.linxing.linxing_agent.agent.memory.window.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RuleSetStore;
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
     * 按白名单产出 RewriteToolRule 到 batch
     * <p>0721 起取消 token 阈值：决定 rewrite（命中白名单）则必定精简，不再设体积门槛。
     * 保留 tokenEstimator 仅用于 reason 审计文本，便于观察精简量。
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
            //0721：取消 resultTokenThreshold 阈值——白名单命中即精简
            long tokens = tokenEstimator.estimate(msg);
            batch.addRewriteToolRule(toolCallId,
                    "自动精简：tool=" + toolName + " 结果 token=" + tokens,
                    List.of());
            seen.add(toolCallId);
            produced++;
        }
        if (produced > 0) {
            log.info("[RewriteRuleAnalyzer] 产出 {} 条 RewriteToolRule（无阈值，白名单命中即精简）", produced);
        }
    }
}
