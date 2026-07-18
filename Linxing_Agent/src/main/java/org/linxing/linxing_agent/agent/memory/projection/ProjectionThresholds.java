package org.linxing.linxing_agent.agent.memory.projection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Projection 各级阈值常量（thePlan 第五节，以 {@code MAX_CONTEXT_TOKENS=200_000} 为上限反推）。
 * <p>
 * 阈值与阶段 1 Summary 触发、阶段 2 Runtime Projection 共用——阶段 0 仅声明、不消费，
 * 待阶段 2 ContextBuilder 实装后接入。Summary maxTokens 取 {@code max_context * 0.5}。
 * <p>
 * 比例为占 {@code maxContextTokens} 的百分比，运行时由 {@link #policyFor(long, long)} 解析为策略。
 */
@Component
public class ProjectionThresholds {

    /** 模型上下文最高上限（批注 #2）：200K。 */
    @Value("${agent.token.max-context:200000}")
    private long maxContextTokens;

    /** FULL → REWRITE_TOOL 线（60%）。 */
    public static final double FULL_TO_REWRITE = 0.60;
    /** REWRITE_TOOL → SNIP_LOWVALUE 线（80%）。 */
    public static final double REWRITE_TO_SNIP = 0.80;
    /** SNIP_LOWVALUE → SUMMARY 线（90%）。 */
    public static final double SNIP_TO_SUMMARY = 0.90;
    /** Summary 压缩后历史约占一半窗口（max_context * 0.5）。 */
    public static final double SUMMARY_MAX_RATIO = 0.5;

    public long getMaxContextTokens() {
        return maxContextTokens;
    }

    /** Summary 压缩目标 token（max_context * 0.5）。 */
    public long getSummaryMaxTokens() {
        return (long) (maxContextTokens * SUMMARY_MAX_RATIO);
    }

    /**
     * 按当前路径历史 token 占比解析应采用的 Projection 策略。
     * 阶段 0 仅供阶段 2 ContextBuilder 预留，暂无调用方。
     *
     * @param historyTokens 当前路径历史估算 token 数
     * @return 命中的策略；未超 60% 返回 {@link ProjectionPolicy#FULL}
     */
    public ProjectionPolicy policyFor(long historyTokens) {
        double ratio = (double) historyTokens / maxContextTokens;
        if (ratio >= SNIP_TO_SUMMARY) {
            return ProjectionPolicy.SUMMARY;
        }
        if (ratio >= REWRITE_TO_SNIP) {
            return ProjectionPolicy.SNIP_LOWVALUE;
        }
        if (ratio >= FULL_TO_REWRITE) {
            return ProjectionPolicy.REWRITE_TOOL;
        }
        return ProjectionPolicy.FULL;
    }
}
