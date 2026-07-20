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
 * Snip/Rewrite 异步小循环编排入口
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
     */
    public boolean tryStart(Integer sessionId) {
        AtomicBoolean flag = runningFlags.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
        return flag.compareAndSet(false, true);
    }

    /**
     * 异步提交小循环（立即返回，主流程不等待）。
     *
     * @param sessionId      会话 id
     * @param recovered      Recovery 结果
     * @param currentQuery   当前轮用户问题（Snip 判定锚点，透传给 SkipTurnReActLoop 渲染进 prompt）；
     *                       null 时 Snip 退化为仅按历史本身判定
     */
    public void executeAsync(Integer sessionId, RecoveredHistory recovered, String currentQuery) {
        //TODO：分阶段触发——REWRITE_TOOL 仅跑阶段1（纯规则 rewrite），SNIP_LOWVALUE 才同时跑阶段1+阶段2（snip的删除可能与rewrite重合，以更重量级的snip为高优先级），最大化精简。
        //TODO：当前阶段2 仅看全局开关未按 policy 分级。且rewrite耦合到了SnipLoopExecutor中，后续需要拆分
        CompletableFuture.runAsync(() -> {//新线程执行
            try {
                // per-call 临时增量容器：本次小循环的 rule 变更攒入 batch，结束时由 RuleSetStore 原子应用即弃
                // 不可改为单例——跨 session 串用、并发小循环互相污染、破坏原子应用语义
                RuleSetStore.RuleUpdateBatch batch = new RuleSetStore.RuleUpdateBatch();
                // 纯规则 RewriteToolRule（不调 LLM）
                rewriteRuleAnalyzer.analyze(recovered, batch);
                // LLM ReAct SkipTurnRule
                if (skipTurnLlmEnabled) {
                    skipTurnReActLoop.run(recovered, sessionId, currentQuery, batch);
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
