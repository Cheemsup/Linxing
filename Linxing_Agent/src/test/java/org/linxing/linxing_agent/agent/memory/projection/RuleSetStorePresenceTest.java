package org.linxing.linxing_agent.agent.memory.projection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RuleSet;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RuleSetStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RuleSetStore 缓存条目存在性（hasEntry）与空 batch apply 行为单测。
 */
@SpringBootTest
@DisplayName("RuleSetStore: 区分 MISS / HIT-but-empty")
class RuleSetStorePresenceTest {

    private static final int SID = 770001;

    @Autowired
    private RuleSetStore ruleSetStore;

    @Test
    @DisplayName("空 batch apply 仍写入条目，hasEntry 可区分 miss 与 empty-hit")
    void emptyBatchApplyCreatesCacheableEntry() {
        ruleSetStore.clear(SID);
        assertFalse(ruleSetStore.hasEntry(SID), "clear 后应无条目");
        assertEquals(RuleSet.EMPTY, ruleSetStore.get(SID), "miss 时 get 返回 EMPTY");

        ruleSetStore.apply(SID, new RuleSetStore.RuleUpdateBatch());
        assertTrue(ruleSetStore.hasEntry(SID), "空 batch apply 后应有条目");
        assertEquals(RuleSet.EMPTY, ruleSetStore.get(SID), "空 batch 写入 EMPTY");
    }

    @Test
    @DisplayName("空 batch apply 不覆盖已有非空 RuleSet")
    void emptyBatchApplyDoesNotClobberExisting() {
        ruleSetStore.clear(SID);
        RuleSetStore.RuleUpdateBatch nonEmpty = new RuleSetStore.RuleUpdateBatch()
                .addSkipTurnRule(9001, "离题");
        ruleSetStore.apply(SID, nonEmpty);
        assertTrue(ruleSetStore.hasEntry(SID));
        assertTrue(ruleSetStore.get(SID).shouldSkipTurn(9001), "非空 rule 应已落盘");

        ruleSetStore.apply(SID, new RuleSetStore.RuleUpdateBatch());
        assertTrue(ruleSetStore.hasEntry(SID), "空 batch 后条目仍在");
        assertTrue(ruleSetStore.get(SID).shouldSkipTurn(9001), "空 batch 不应覆盖已有 rule");

        ruleSetStore.clear(SID);
    }
}
