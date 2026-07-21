package org.linxing.linxing_agent.agent.memory.window.projection.rewrite;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.window.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RuleSetStore;
import org.springframework.stereotype.Component;

/**
 * 纯规则 Rewrite 阶段执行器（Projection 阶段 1，无 LLM）。
 *
 * <p>职责：按 {@link RewriteRuleAnalyzer} 的白名单 + 阈值规则，把读性质工具的超长结果
 * 产为 {@code RewriteToolRule} 攒入外部 batch。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RewriteLoopExecutor {

    private final RewriteRuleAnalyzer rewriteRuleAnalyzer;

    /**
     * 执行纯规则 Rewrite 阶段，把 RewriteToolRule 攒入 batch。
     *
     * @param recovered Recovery 结果
     * @param batch     外部传入的增量容器（由编排者统一 apply）
     */
    public void run(RecoveredHistory recovered, RuleSetStore.RuleUpdateBatch batch) {
        rewriteRuleAnalyzer.analyze(recovered, batch);
    }
}
