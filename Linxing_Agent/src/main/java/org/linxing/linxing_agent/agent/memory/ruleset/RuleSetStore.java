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
 * 会话级 RuleSet 存储：按 sessionId 维护一份 SkipTurnRule + RewriteToolRule 集合。
 *
 * <p>读写分离锁保证：读 {@link #get} 并发无阻塞，写 {@link #apply} 串行化原子应用一批变更。
 * Snip/Rewrite 小循环通过 {@link RuleUpdateBatch} 攒一批增量改动，结束时一次性提交，避免逐条写 DB。
 */
@Slf4j
@Component
public class RuleSetStore {

    private final ConcurrentHashMap<Integer, RuleSet> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ReadWriteLock> locks = new ConcurrentHashMap<>();

    /** 读取会话当前 RuleSet（无记录返回 EMPTY）。 */
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
     * 取 WriteLock 原子应用一批 rule 变更（空 batch 直接返回当前值）。
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

    /** 生成新 rule id（UUID）。 */
    public static String newRuleId() {
        return UUID.randomUUID().toString();
    }

    /** 清除会话的 RuleSet（测试/会话销毁用）。 */
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
     * rule 变更批次：攒 add/remove/replace 操作，结束时一次性原子应用。
     *
     * <p>提供两类 rule 的增删改接口；{@link #applyTo(RuleSet)} 基于 current 重放出全新 RuleSet（不改 current）。
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

        /** 按 ruleId 替换 SkipTurnRule（先删后加同 id）。 */
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

        /** 基于 current 逐条回放 ops，生成全新 RuleSet（不改 current）。 */
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
