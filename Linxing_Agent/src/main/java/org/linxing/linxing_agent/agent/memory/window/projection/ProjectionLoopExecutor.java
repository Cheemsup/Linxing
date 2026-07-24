package org.linxing.linxing_agent.agent.memory.window.projection;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.window.projection.rewrite.RewriteLoopExecutor;
import org.linxing.linxing_agent.agent.memory.window.projection.snip.SkipTurnReActLoop;
import org.linxing.linxing_agent.agent.memory.window.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RuleSetStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Snip/Rewrite 异步小循环编排入口
 *
 * <p>编排两阶段：
 * <ul>
 *   <li><b>阶段 1 · Rewrite</b>（{@link RewriteLoopExecutor}）：纯规则，不调 LLM，
 *       把读性质工具超长结果产为 RewriteToolRule。REWRITE_TOOL / SNIP_LOWVALUE 区间均触发。</li>
 *   <li><b>阶段 2 · Snip</b>（{@link SkipTurnReActLoop}）：LLM ReAct，按 Turn 判定低价值并产 SkipTurnRule。
 *       仅 SNIP_LOWVALUE 区间触发（更重量级精简，与 rewrite 重叠时以 snip 为高优先级）。</li>
 * </ul>
 * 两阶段攒入同一 {@link RuleSetStore.RuleUpdateBatch}，结束时一次性原子应用。
 * 
 * TODO:考虑更名或者再拆分组装
 */
@Slf4j
@Component
public class ProjectionLoopExecutor {

    private final ConcurrentHashMap<Integer, AtomicBoolean> runningFlags = new ConcurrentHashMap<>();
    private final RewriteLoopExecutor rewriteLoopExecutor;
    private final SkipTurnReActLoop skipTurnReActLoop;
    private final RuleSetStore ruleSetStore;
    private final Executor snipExecutor;

    @Value("${agent.projection.snip.enabled:true}")
    private boolean enabled;
    @Value("${agent.projection.snip.skip-turn-llm-enabled:true}")
    private boolean skipTurnLlmEnabled;

    public ProjectionLoopExecutor(RewriteLoopExecutor rewriteLoopExecutor,
                                  SkipTurnReActLoop skipTurnReActLoop,
                                  RuleSetStore ruleSetStore,
                                  @Qualifier("snipTaskExecutor") Executor snipExecutor) {
        this.rewriteLoopExecutor = rewriteLoopExecutor;
        this.skipTurnReActLoop = skipTurnReActLoop;
        this.ruleSetStore = ruleSetStore;
        this.snipExecutor = snipExecutor;
    }

    /** 是否应触发 Rewrite 阶段：开启且策略为 REWRITE_TOOL 或 SNIP_LOWVALUE。 */
    public boolean shouldTriggerRewrite(ProjectionPolicy policy) {
        return enabled && (policy == ProjectionPolicy.REWRITE_TOOL
                || policy == ProjectionPolicy.SNIP_LOWVALUE);
    }

    /** 是否应触发 Snip 阶段：开启、LLM 开关开、且策略为 SNIP_LOWVALUE（REWRITE_TOOL 区间不跑 LLM）。 */
    public boolean shouldTriggerSnip(ProjectionPolicy policy) {
        return enabled && skipTurnLlmEnabled && policy == ProjectionPolicy.SNIP_LOWVALUE;
    }

    /** 兼容旧调用：是否应触发小循环（Rewrite 或 Snip 任一）。SUMMARY 区间走同步 Summary，不触发。 */
    public boolean shouldTrigger(ProjectionPolicy policy) {
        return shouldTriggerRewrite(policy) || shouldTriggerSnip(policy);
    }

    /**
     * per-session CAS 去重：同 session 已有小循环在跑则返回 false。
     */
    public boolean tryStart(Integer sessionId) {
        AtomicBoolean flag = runningFlags.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
        return flag.compareAndSet(false, true);
    }

    /**
     * 异步提交小循环（立即返回，主流程不等待）。按 policy 分阶段触发，攒同一 batch 原子应用。
     *
     * @param sessionId      会话 id
     * @param recovered      Recovery 结果
     * @param currentQuery   当前轮用户问题（Snip 判定锚点，透传给 SkipTurnReActLoop 渲染进 prompt）；
     *                       null 时 Snip 退化为仅按历史本身判定
     * @param policy         当前 Projection 策略，决定跑哪些阶段
     */
    public void executeAsync(Integer sessionId, RecoveredHistory recovered, String currentQuery,
                             ProjectionPolicy policy) {
        CompletableFuture.runAsync(() -> {//新线程执行
            try {
                runProjection(sessionId, recovered, currentQuery, policy);
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

    /**
     * 同步执行小循环（调用线程阻塞至完成）。先 {@link #tryStart} CAS 去重，finally 复位 flag。
     *
     * @return true 表示已实际运行；false 表示 tryStart 失败（同 session 已有循环在跑）
     */
    public boolean executeSync(Integer sessionId, RecoveredHistory recovered, String currentQuery,
                               ProjectionPolicy policy) {
        if (!tryStart(sessionId)) {
            return false;
        }
        try {
            runProjection(sessionId, recovered, currentQuery, policy);
        } catch (Exception e) {
            log.warn("[SnipLoop-Sync] sessionId={} 同步小循环异常，batch 丢弃不应用: {}",
                    sessionId, e.getMessage());
        } finally {
            AtomicBoolean flag = runningFlags.get(sessionId);
            if (flag != null) {
                flag.set(false);
            }
        }
        return true;
    }

    /**
     * 小循环主体：按 policy 分阶段触发，攒同一 batch 原子应用。
     */
    private void runProjection(Integer sessionId, RecoveredHistory recovered, String currentQuery,
                               ProjectionPolicy policy) {
        // per-call 临时增量容器：本次小循环的 rule 变更攒入 batch，结束时由 RuleSetStore 原子应用即弃
        RuleSetStore.RuleUpdateBatch batch = new RuleSetStore.RuleUpdateBatch();
        // 纯规则 Rewrite
        if (shouldTriggerRewrite(policy)) {
            rewriteLoopExecutor.run(recovered, batch);
        }
        // LLM ReAct Snip
        if (shouldTriggerSnip(policy)) {
            skipTurnReActLoop.run(recovered, sessionId, currentQuery, batch);
        }
        // 一次性原子应用（空 batch 也安全，RuleSetStore.apply 内部判空且写条目）
        ruleSetStore.apply(sessionId, batch);
    }
}
