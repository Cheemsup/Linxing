package org.linxing.linxing_agent.agent.memory.projection;

/**
 * 上下文 Projection 策略枚举（
 * <p>
 * 按当前路径历史 token 占 {@code max_context} 的比例，Builder 在 Recovery 后选择策略：
 * <ul>
 *   <li>{@link #FULL} —— 未超阈值，原样重放全部消息（含完整 tool 调用/结果）。</li>
 *   <li>{@link #REWRITE_TOOL} —— 中度压力，对读性质 tool 的结果正文做 Rewrite（丢 content 留简要字段）。</li>
 *   <li>{@link #SNIP_LOWVALUE} —— 高度压力，跳过低价值消息（greeting/progress/重试）。</li>
 *   <li>{@link #SUMMARY} —— 超阈值，同步压缩为 Summary 节点并落库（见 thePlan P1-2）。</li>
 * </ul>
 * 仅 {@link #SUMMARY} 同步执行；{@link #REWRITE_TOOL}/{@link #SNIP_LOWVALUE} 异步预计算，允许"落后一轮"。
 */
public enum ProjectionPolicy {
    FULL,
    REWRITE_TOOL,
    SNIP_LOWVALUE,
    SUMMARY
}
