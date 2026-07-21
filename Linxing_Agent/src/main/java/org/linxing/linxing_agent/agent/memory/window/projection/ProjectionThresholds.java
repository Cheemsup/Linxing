package org.linxing.linxing_agent.agent.memory.window.projection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Projection 各级阈值常量
 *
 * <p>各级阈值与模型上下文上限均可经 yaml 调整，避免硬编码导致预算/阈值不可调
 * （见 context-projection-budget-deadlock：Recovery 预算曾卡在 Rewrite 阈值下死锁）。
 */
@Component
public class ProjectionThresholds {

    /** 模型上下文最高上限（200K 待模型选型后调研修正）。 */
    //TODO：200K只是待定，后续还需要进行调研修正
    @Value("${agent.token.max-context:200000}")
    private long maxContextTokens;

    /** FULL → REWRITE_TOOL 线（60%）。 */
    @Value("${agent.projection.thresholds.full-to-rewrite:0.60}")
    private double fullToRewrite;

    /** REWRITE_TOOL → SNIP_LOWVALUE 线（80%）。 */
    @Value("${agent.projection.thresholds.rewrite-to-snip:0.80}")
    private double rewriteToSnip;

    /** SNIP_LOWVALUE → SUMMARY 线（90%）。 */
    @Value("${agent.projection.thresholds.snip-to-summary:0.90}")
    private double snipToSummary;

    /** Summary 压缩后历史约占一半窗口（max_context * 0.5）。 */
    @Value("${agent.projection.thresholds.summary-max-ratio:0.5}")
    private double summaryMaxRatio;

    public long getMaxContextTokens() {
        return maxContextTokens;
    }

    /** Summary 压缩目标 token（max_context * summaryMaxRatio）。 */
    public long getSummaryMaxTokens() {
        return (long) (maxContextTokens * summaryMaxRatio);
    }

    /**
     * 按当前路径历史 token 占比解析应采用的 Projection 策略。
     *
     * @param historyTokens 当前路径历史估算 token 数
     * @return 命中的策略；未超 full-to-rewrite 返回 {@link ProjectionPolicy#FULL}
     */
    public ProjectionPolicy policyFor(long historyTokens) {
        double ratio = (double) historyTokens / maxContextTokens;
        if (ratio >= snipToSummary) {
            return ProjectionPolicy.SUMMARY;
        }
        if (ratio >= rewriteToSnip) {
            return ProjectionPolicy.SNIP_LOWVALUE;
        }
        if (ratio >= fullToRewrite) {
            return ProjectionPolicy.REWRITE_TOOL;
        }
        return ProjectionPolicy.FULL;
    }
}
