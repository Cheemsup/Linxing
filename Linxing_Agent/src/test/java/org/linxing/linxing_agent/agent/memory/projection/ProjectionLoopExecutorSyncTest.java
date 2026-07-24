package org.linxing.linxing_agent.agent.memory.projection;

import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.linxing.linxing_agent.agent.memory.window.projection.ProjectionLoopExecutor;
import org.linxing.linxing_agent.agent.memory.window.projection.ProjectionPolicy;
import org.linxing.linxing_agent.agent.memory.window.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.memory.window.recovery.TurnBoundary;
import org.linxing.linxing_agent.agent.memory.window.ruleset.RuleSetStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProjectionLoopExecutor.executeSync 同步重建行为单测。
 *
 * <p>用 REWRITE_TOOL policy：只跑纯规则 Rewrite（不调 LLM），验证同步执行写条目、
 * flag 复位、CAS 失败返回 false。
 */
@SpringBootTest
@DisplayName("ProjectionLoopExecutor: executeSync 同步重建")
class ProjectionLoopExecutorSyncTest {

    private static final int SID = 770002;

    @Autowired
    private ProjectionLoopExecutor projectionLoopExecutor;
    @Autowired
    private RuleSetStore ruleSetStore;

    private RecoveredHistory buildHistory() {
        List<dev.langchain4j.data.message.ChatMessage> msgs = new java.util.ArrayList<>();
        msgs.add(UserMessage.from("测试问题一"));
        msgs.add(dev.langchain4j.data.message.AiMessage.from("测试回答一"));
        List<TurnBoundary> boundaries = new java.util.ArrayList<>();
        boundaries.add(TurnBoundary.builder().turnStartMessageId(8001).startIdx(0).endIdx(2).build());
        return RecoveredHistory.builder()
                .messages(msgs)
                .pathEndMessageId(8001)
                .turnBoundaries(boundaries)
                .build();
    }

    @AfterEach
    void cleanup() throws Exception {
        ruleSetStore.clear(SID);
        // 复位可能残留的 runningFlags（CAS 失败测试会预占 flag 且无法自复位）
        java.lang.reflect.Field flagsField = ProjectionLoopExecutor.class.getDeclaredField("runningFlags");
        flagsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.atomic.AtomicBoolean> flags =
                (java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.atomic.AtomicBoolean>) flagsField.get(projectionLoopExecutor);
        java.util.concurrent.atomic.AtomicBoolean flag = flags.get(SID);
        if (flag != null) {
            flag.set(false);
        }
    }

    @Test
    @DisplayName("MISS 时 executeSync 同步执行并写入条目、flag 复位")
    void executeSyncWritesEntryAndResetsFlag() {
        ruleSetStore.clear(SID);
        assertFalse(ruleSetStore.hasEntry(SID));

        boolean ran = projectionLoopExecutor.executeSync(
                SID, buildHistory(), "测试问题", ProjectionPolicy.REWRITE_TOOL);

        assertTrue(ran, "无竞争时应实际运行返回 true");
        assertTrue(ruleSetStore.hasEntry(SID), "同步执行后应写入条目");
        // flag 复位：tryStart 应能再次成功
        assertTrue(projectionLoopExecutor.tryStart(SID), "flag 应已复位，tryStart 可再次成功");
    }

    @Test
    @DisplayName("CAS 失败时 executeSync 返回 false 且不写条目")
    void executeSyncReturnsFalseWhenCasFails() {
        ruleSetStore.clear(SID);
        // 预占 flag，模拟已有循环在跑
        assertTrue(projectionLoopExecutor.tryStart(SID));

        boolean ran = projectionLoopExecutor.executeSync(
                SID, buildHistory(), "测试问题", ProjectionPolicy.REWRITE_TOOL);

        assertFalse(ran, "flag 被占时应返回 false");
        assertFalse(ruleSetStore.hasEntry(SID), "CAS 失败不应写条目");
        // 预占的 flag 由 @AfterEach 反射复位，避免污染后续测试
    }
}
