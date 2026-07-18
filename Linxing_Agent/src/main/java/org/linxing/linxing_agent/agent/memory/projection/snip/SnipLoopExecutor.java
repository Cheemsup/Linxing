package org.linxing.linxing_agent.agent.memory.projection.snip;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.projection.ProjectionPolicy;
import org.linxing.linxing_agent.agent.memory.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.memory.ruleset.RuleSetStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Snip/Rewrite 异步小循环编排入口（thePlan P2-2/P2-3，0717 终稿第十~十一节）。
 *
 * <p>对外提供 {@link #shouldTrigger(ProjectionPolicy)} / {@link #tryStart(Integer)} /
 * {@link #executeAsync(Integer, RecoveredHistory)} 三方法，由 {@code ChatServiceImpl.chat}
 * 在 Recovery 后调用。主流程不等待小循环——"允许落后一轮"（0717 终稿）。
 *
 * <h3>编排流程</h3>
 * <ol>
 *   <li>建一个共享 {@link RuleSetStore.RuleUpdateBatch}</li>
 *   <li>阶段 1（纯规则，不调 LLM）：{@link RewriteRuleAnalyzer#analyze} 把 RewriteToolRule 攒进 batch</li>
 *   <li>阶段 2（LLM ReAct，可选）：{@link SkipTurnReActLoop#run} 把 SkipTurnRule 攒进<b>同一</b> batch</li>
 *   <li>循环结束一次性 {@link RuleSetStore#apply}（WriteLock 原子应用）</li>
 * </ol>
 * 两阶段共用同一 batch，一次 apply——符合"批量原子应用"（§6-4 规则 2）。
 *
 * <h3>并发与异常</h3>
 * <ul>
 *   <li><b>per-session 去重</b>：{@link java.util.concurrent.atomic.AtomicBoolean#compareAndSet}，
 *       同 session 同时只有一个小循环运行，已在跑则跳过本轮触发。finally 释放标志，异常不泄漏。</li>
 *   <li><b>异常安全</b>：任一阶段抛异常 → catch → 不 apply → batch 被 GC → 旧 RuleSet 保持完整
 *      （"中途中断整批丢弃"，§6-4 规则 2）。</li>
 *   <li><b>best-effort</b>：小循环是上下文优化、非正确性必需，线程池满时 DiscardPolicy 丢弃。</li>
 * </ul>
 *
 * <p>去重放本类而非 {@link RuleSetStore}：RuleSetStore 是纯存储层（用户拍板不动存储层），
 * 去重是调度关注点，放 executor 合理，{@code runningFlags} 与 RuleSetStore.store 物理分离。
 */
@Slf4j
@Component
public class SnipLoopExecutor {

    private final ConcurrentHashMap<Integer, AtomicBoolean> runningFlags = new ConcurrentHashMap<>();
    private final RewriteRuleAnalyzer rewriteRuleAnalyzer;
    private final SkipTurnReActLoop skipTurnReActLoop;
    private final RuleSetStore ruleSetStore;
    private final Executor snipExecutor;

    @Value("${agent.projection.snip.enabled:true}")
    private boolean enabled;
    @Value("${agent.projection.snip.skip-turn-llm-enabled:true}")
    private boolean skipTurnLlmEnabled;

    public SnipLoopExecutor(RewriteRuleAnalyzer rewriteRuleAnalyzer,
                            SkipTurnReActLoop skipTurnReActLoop,
                            RuleSetStore ruleSetStore,
                            @Qualifier("snipTaskExecutor") Executor snipExecutor) {
        this.rewriteRuleAnalyzer = rewriteRuleAnalyzer;
        this.skipTurnReActLoop = skipTurnReActLoop;
        this.ruleSetStore = ruleSetStore;
        this.snipExecutor = snipExecutor;
    }

    /** 是否应触发小循环：开启且策略为 REWRITE_TOOL 或 SNIP_LOWVALUE（SUMMARY 区间走同步 Summary，不触发）。 */
    public boolean shouldTrigger(ProjectionPolicy policy) {
        return enabled && (policy == ProjectionPolicy.REWRITE_TOOL
                || policy == ProjectionPolicy.SNIP_LOWVALUE);
    }

    /**
     * per-session CAS 去重：同 session 已有小循环在跑则返回 false。
     * <p>调用方应仅当返回 true 时才调 {@link #executeAsync}。
     */
    public boolean tryStart(Integer sessionId) {
        AtomicBoolean flag = runningFlags.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
        return flag.compareAndSet(false, true);
    }

    /**
     * 异步提交小循环（立即返回，主流程不等待）。
     * <p>调用前应已通过 {@link #shouldTrigger} 与 {@link #tryStart} 筛选。
     */
    public void executeAsync(Integer sessionId, RecoveredHistory recovered) {
        CompletableFuture.runAsync(() -> {
            try {
                RuleSetStore.RuleUpdateBatch batch = new RuleSetStore.RuleUpdateBatch();
                // 阶段 1：纯规则 RewriteToolRule（不调 LLM）
                rewriteRuleAnalyzer.analyze(recovered, batch);
                // 阶段 2：LLM ReAct SkipTurnRule（可关，allow落后一轮）
                if (skipTurnLlmEnabled) {
                    skipTurnReActLoop.run(recovered, sessionId, batch);
                }
                // 一次性原子应用（空 batch 也安全，RuleSetStore.apply 内部判空）
                ruleSetStore.apply(sessionId, batch);
            } catch (Exception e) {
                log.warn("[SnipLoop] sessionId={} 小循环异常，batch 丢弃不应用: {}",
                        sessionId, e.getMessage());
                // 不 apply → 旧 RuleSet 保持完整
            } finally {
                AtomicBoolean flag = runningFlags.get(sessionId);
                if (flag != null) {
                    flag.set(false);
                }
            }
        }, snipExecutor);
    }
}
