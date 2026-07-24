package org.linxing.linxing_agent.agent.memory.window.ruleset;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 会话级 RuleSet 存储：按 sessionId 维护一份 SkipTurnRule + RewriteToolRule 集合。
 *
 * <p>0721 改造：底层由 {@code ConcurrentHashMap + ReadWriteLock} 换为 Caffeine {@link Cache}，
 * 获得两项能力：
 * <ul>
 *   <li><b>TTL 自动过期</b>：{@code expireAfterAccess(mirrorTtl)}，与 Redis 镜像同生命周期——
 *       镜像失效重建时 RuleSet 也同步过期，避免 rule 长期驻留失配。每次 {@link #get}/{@link #apply}
 *       访问即续期（滑动过期，语义同 mirror 的每次写 expire）。</li>
 *   <li><b>容量兜底</b>：{@code maximumSize} 防止切走不删的 session 无限累积导致内存泄漏
 *       （纯切换不触发 deleteSession 的场景靠 LRU 兜底回收）。</li>
 * </ul>
 *
 * <p>原子性：{@link #apply} 用 {@code asMap().compute} 在 Caffeine 内部锁下完成
 * 「读当前值→重放 batch→替换引用」，等价于原 WriteLock 串行化语义；
 * {@link #get} 用 {@code getIfPresent}（Caffeine 自带并发安全），读无锁。
 *
 * <p>Snip/Rewrite 小循环通过 {@link RuleUpdateBatch} 攒一批增量改动，结束时一次性提交。
 */
@Slf4j
@Component
public class RuleSetStore {

    private final Cache<Integer, RuleSet> store;

    public RuleSetStore(RagProperties ragProperties) {
        int ttlSeconds = ragProperties.getCache().getMirrorTtl();
        this.store = Caffeine.newBuilder()
                .expireAfterAccess(ttlSeconds, TimeUnit.SECONDS)
                .maximumSize(500)
                .build();
    }

    /** 读取会话当前 RuleSet（无记录或已过期返回 EMPTY）。访问即续期。 */
    public RuleSet get(Integer sessionId) {
        RuleSet rs = store.getIfPresent(sessionId);
        return rs != null ? rs : RuleSet.EMPTY;
    }

    /** 缓存条目是否存在（区分"过期/从未计算"与"已计算但结果为 EMPTY"）。访问即续期。 */
    public boolean hasEntry(Integer sessionId) {
        return store.getIfPresent(sessionId) != null;
    }

    /**
     * 原子应用一批 rule 变更。空 batch 也写入（当前值或 EMPTY），保证 hasEntry 能区分
     * "已计算但空"与"从未计算"。用 {@code asMap().compute} 在 Caffeine 内部锁下完成「读-重放-替换」。
     */
    public RuleSet apply(Integer sessionId, RuleUpdateBatch batch) {
        RuleSet[] resultHolder = new RuleSet[1];
        store.asMap().compute(sessionId, (k, current) -> {
            RuleSet cur = current != null ? current : RuleSet.EMPTY;
            RuleSet next = (batch == null || batch.isEmpty()) ? cur : batch.applyTo(cur);
            resultHolder[0] = next;
            return next;
        });
        return resultHolder[0] != null ? resultHolder[0] : RuleSet.EMPTY;
    }

    /** 生成新 rule id（UUID）。 */
    public static String newRuleId() {
        return UUID.randomUUID().toString();
    }

    /** 清除会话的 RuleSet（会话销毁/deleteSession 联动调用）。 */
    public void clear(Integer sessionId) {
        store.invalidate(sessionId);
    }

    /**
     * rule 变更批次：攒 add/remove/replace 操作，结束时一次性原子应用。
     *
     * <p>提供两类 rule 的增删改接口；{@link #applyTo(RuleSet)} 基于 current 重放出全新 RuleSet（不改 current）。
     */
    public static final class RuleUpdateBatch {

        private final java.util.List<Op> ops = new java.util.ArrayList<>();

        public RuleUpdateBatch addSkipTurnRule(Integer turnStartMessageId, String reason) {
            ops.add(new Op(OpType.ADD_SKIP, newRuleId(), turnStartMessageId, null, reason, null));
            return this;
        }

        public RuleUpdateBatch addRewriteToolRule(String toolCallId, String reason, java.util.List<String> preserveFields) {
            ops.add(new Op(OpType.ADD_REWRITE, newRuleId(), null, toolCallId, reason,
                    preserveFields != null ? java.util.List.copyOf(preserveFields) : java.util.List.of()));
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
                                                      java.util.List<String> preserveFields) {
            ops.add(new Op(OpType.REPLACE_REWRITE, ruleId, null, toolCallId, reason,
                    preserveFields != null ? java.util.List.copyOf(preserveFields) : java.util.List.of()));
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
            java.util.List<SkipTurnRule> skips = new java.util.ArrayList<>(current.getSkipTurnRules());
            java.util.List<RewriteToolRule> rewrites = new java.util.ArrayList<>(current.getRewriteToolRules());
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
                          String toolCallId, String reason, java.util.List<String> preserveFields) {
        }
    }
}
