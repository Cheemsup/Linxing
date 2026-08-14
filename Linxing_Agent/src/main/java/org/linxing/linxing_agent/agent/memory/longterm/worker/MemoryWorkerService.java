package org.linxing.linxing_agent.agent.memory.longterm.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentResult;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryWorkspace;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

/**
 * Memory Worker：回答完成后异步触发长期记忆维护。
 *
 * @deprecated 2026.08.06 决策 7：对话后自动触发已移除（{@code ChatServiceImpl} 不再调用本类）。
 *             长期记忆改写仅由用户主动 HTTP 编辑 / 用户在对话里显式让 Agent 调 {@code write_memory} 两条路径，
 *             历史 cron 合并由 {@link HistoryMergeScheduler} 承担。本类保留待评估是否复活。
 */
@Deprecated
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryWorkerService {

    private final MemoryWorkspace memoryWorkspace;
    private final MemoryWorkerReActLoop memoryWorkerReActLoop;
    @Qualifier("memoryWorkerExecutor")
    private final Executor memoryWorkerExecutor;

    /**
     * 主循环返回后异步触发入口
     */
    public void runAfterConversation(Integer userId, Integer sessionId, String query, AgentResult result) {
        if (userId == null) {
            log.warn("[MemoryWorker] userId 为空，跳过本次 Memory 更新: sessionId={}", sessionId);
            return;
        }
        memoryWorkerExecutor.execute(() ->
                doRun(userId, sessionId, query, result == null ? null : result.getAnswer()));
    }

    /**
     * 实际 Worker 逻辑（在 memoryWorkerExecutor 线程内执行）。
     * <p>token 预算：只送本轮 query + answer 摘要，不送全量记忆文件——LLM 自行 read_memory 按需读取。
     */
    private void doRun(Integer userId, Integer sessionId, String query, String answer) {
        if (query == null || query.isBlank() || answer == null || answer.isBlank()) {
            return;//无有效对话内容，跳过
        }
        try {
            memoryWorkspace.initUserWorkspaceIfAbsent(userId);//确保用户 workspace 与模板文件存在
            memoryWorkerReActLoop.run(userId, sessionId, query, answer);
        } catch (Exception e) {
            log.error("[MemoryWorker] 异步更新失败 userId={} sessionId={}: {}", userId, sessionId, e.getMessage(), e);
        }
    }
}
