package org.linxing.linxing_agent.agent.memory.ruleset;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Rule Set 进程内存储（thePlan P2-3，0717 终稿第十~十一节）。
 *
 * <p>每会话一份 {@link RuleSet}，落点为进程内 {@link ConcurrentHashMap}（不落库、不进 Redis Mirror）。
 *
 * <h3>并发模型</h3>
 * <ul>
 *   <li><b>Builder 读</b>（2-D 起）：调 {@link #get(Integer)} 取 Read Lock 读取当前引用；
 *       由于 {@link RuleSet} 不可变，拿到即完整快照，无中间态。</li>
 *   <li><b>Snip/Rewrite 批量提交</b>（2-E 起）：rule 更新 tool 在小循环内攒一批变更到
 *       {@link RuleUpdateBatch}，循环结束时调 {@link #apply(Integer, RuleUpdateBatch)}
 *       取 Write Lock 一次性原子应用——产出新 RuleSet 替换旧引用。
 *       <b>锁仅覆盖引用替换瞬间，不覆盖 LLM 分析与 tool 调用阶段</b>（0717 终稿第十一节）。</li>
 * </ul>
 *
 * <h3>批量原子</h3>
 * {@link RuleUpdateBatch} 攒 add/remove/replace 操作，{@link #apply} 在 WriteLock 内
 * 基于当前 RuleSet 重放出全新 RuleSet 再替换；中途被中断则整批丢弃、RuleSet 保持旧版完整，
 * 避免"改了一半"的破损中间态（thePlan P2-3 与 §6-4 规则 2 一致）。
 *
 * <p>本类只提供数据模型与存储并发骨架；rule 的产出（Snip/Rewrite 小循环）属 2-E，
 * Builder 消费属 2-D，本轮均未接入。
 */
@Slf4j
@Component
public class RuleSetStore {

    private final ConcurrentHashMap<Integer, RuleSet> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ReadWriteLock> locks = new ConcurrentHashMap<>();

    /**
     * 读取某会话当前 RuleSet（Builder 消费用，取 Read Lock）。
     * <p>无记录返回 {@link RuleSet#EMPTY}。
     */
    public RuleSet get(Integer sessionId) {
        ReadWriteLock lock = lockFor(sessionId);
        lock.readLock().lock();
        try {
            RuleSet rs = store.get(sessionId);
            return rs != null ? rs : RuleSet.EMPTY;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 原子应用一批 rule 变更（Snip/Rewrite 小循环结束时调用，取 Write Lock）。
     * <p>基于当前 RuleSet 重放出新 RuleSet 替换旧引用。整批原子：任一操作失败即整批丢弃、保持旧版。
     *
     * @param sessionId 会话 id
     * @param batch     待提交的一批 add/remove/replace 操作
     * @return 提交后的新 RuleSet
     */
    public RuleSet apply(Integer sessionId, RuleUpdateBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return get(sessionId);
        }
        ReadWriteLock lock = lockFor(sessionId);
        lock.writeLock().lock();
        try {
            RuleSet current = store.getOrDefault(sessionId, RuleSet.EMPTY);
            RuleSet next = batch.applyTo(current);
            store.put(sessionId, next);
            log.debug("[RuleSetStore] sessionId={} 应用 {} 项变更：skip {}→{}，rewrite {}→{}",
                    sessionId, batch.size(),
                    current.getSkipTurnRules().size(), next.getSkipTurnRules().size(),
                    current.getRewriteToolRules().size(), next.getRewriteToolRules().size());
            return next;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 生成一个新 rule id（UUID 字符串），供 rule 更新 tool 的 add 操作使用。 */
    public static String newRuleId() {
        return UUID.randomUUID().toString();
    }

    /** 清除某会话的 RuleSet（测试或会话销毁时用）。 */
    public void clear(Integer sessionId) {
        ReadWriteLock lock = lockFor(sessionId);
        lock.writeLock().lock();
        try {
            store.remove(sessionId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private ReadWriteLock lockFor(Integer sessionId) {
        return locks.computeIfAbsent(sessionId, k -> new ReentrantReadWriteLock());
    }

    /**
     * 待提交的 rule 变更批次（Snip/Rewrite 小循环内攒、结束时一次性原子应用）。
     * <p>不可变记录序列，{@link #applyTo(RuleSet)} 基于 current 重放出全新 RuleSet。
     */
    public static final class RuleUpdateBatch {

        private final List<Op> ops = new ArrayList<>();

        public RuleUpdateBatch addSkipTurnRule(Integer turnStartMessageId, String reason) {
            ops.add(new Op(OpType.ADD_SKIP, newRuleId(), turnStartMessageId, null, reason, null));
            return this;
        }

        public RuleUpdateBatch addRewriteToolRule(String toolCallId, String reason, List<String> preserveFields) {
            ops.add(new Op(OpType.ADD_REWRITE, newRuleId(), null, toolCallId, reason,
                    preserveFields != null ? List.copyOf(preserveFields) : List.of()));
            return this;
        }

        /** 按 ruleId 删除任一类 rule。 */
        public RuleUpdateBatch remove(String ruleId) {
            ops.add(new Op(OpType.REMOVE, ruleId, null, null, null, null));
            return this;
        }

        /** 按 ruleId 替换（先删后加同 id）。SkipTurnRule 用本方法重设 reason/turn。 */
        public RuleUpdateBatch replaceSkipTurnRule(String ruleId, Integer turnStartMessageId, String reason) {
            ops.add(new Op(OpType.REPLACE_SKIP, ruleId, turnStartMessageId, null, reason, null));
            return this;
        }

        /** 按 ruleId 替换 RewriteToolRule。 */
        public RuleUpdateBatch replaceRewriteToolRule(String ruleId, String toolCallId, String reason,
                                                      List<String> preserveFields) {
            ops.add(new Op(OpType.REPLACE_REWRITE, ruleId, null, toolCallId, reason,
                    preserveFields != null ? List.copyOf(preserveFields) : List.of()));
            return this;
        }

        public boolean isEmpty() {
            return ops.isEmpty();
        }

        public int size() {
            return ops.size();
        }

        /** 基于当前 RuleSet 重放出全新 RuleSet（不修改 current）。 */
        RuleSet applyTo(RuleSet current) {
            List<SkipTurnRule> skips = new ArrayList<>(current.getSkipTurnRules());
            List<RewriteToolRule> rewrites = new ArrayList<>(current.getRewriteToolRules());
            for (Op op : ops) {
                switch (op.type) {
                    case ADD_SKIP:
                        skips.add(SkipTurnRule.builder()
                                .ruleId(op.ruleId)
                                .turnStartMessageId(op.turnStartMessageId)
                                .reason(op.reason)
                                .build());
                        break;
                    case ADD_REWRITE:
                        rewrites.add(RewriteToolRule.builder()
                                .ruleId(op.ruleId)
                                .toolCallId(op.toolCallId)
                                .reason(op.reason)
                                .preserveFields(op.preserveFields)
                                .build());
                        break;
                    case REMOVE:
                        skips.removeIf(r -> op.ruleId.equals(r.getRuleId()));
                        rewrites.removeIf(r -> op.ruleId.equals(r.getRuleId()));
                        break;
                    case REPLACE_SKIP:
                        skips.removeIf(r -> op.ruleId.equals(r.getRuleId()));
                        skips.add(SkipTurnRule.builder()
                                .ruleId(op.ruleId)
                                .turnStartMessageId(op.turnStartMessageId)
                                .reason(op.reason)
                                .build());
                        break;
                    case REPLACE_REWRITE:
                        rewrites.removeIf(r -> op.ruleId.equals(r.getRuleId()));
                        rewrites.add(RewriteToolRule.builder()
                                .ruleId(op.ruleId)
                                .toolCallId(op.toolCallId)
                                .reason(op.reason)
                                .preserveFields(op.preserveFields)
                                .build());
                        break;
                    default:
                        throw new IllegalStateException("未知 OpType: " + op.type);
                }
            }
            return new RuleSet(skips, rewrites);
        }

        private enum OpType {
            ADD_SKIP, ADD_REWRITE, REMOVE, REPLACE_SKIP, REPLACE_REWRITE
        }

        private record Op(OpType type, String ruleId, Integer turnStartMessageId,
                          String toolCallId, String reason, List<String> preserveFields) {
        }
    }
}
