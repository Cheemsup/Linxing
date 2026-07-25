package org.linxing.linxing_agent.agent.memory.window.builder;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.linxing.linxing_agent.agent.core.StepRecorder;
import org.linxing.linxing_agent.agent.memory.window.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;

import java.util.List;

public interface ContextBuilder {

    /**
     * 一次性装配 + token 估算 + 策略判定 + 同步/异步 Projection 触发
     * @param sessionId
     * @param recovered
     * @param userId
     * @param currentQuery
     * @return
     */
    ContextAssembly build(int sessionId, RecoveredHistory recovered, Integer userId, String currentQuery);

    /**
     * 构建第一轮的 toolSpecifications
     * @return
     */
    List<ToolSpecification> buildInitialToolSpecs();

    /**
     * 构建每轮对话的 toolSpecifications
     * @param sessionId
     * @return
     */
    List<ToolSpecification> buildRoundToolSpecs(int sessionId);

    /**
     * 工具执行结果回调，由 Builder 判定是否激活新工具。
     * <p>0724 改造：渐进披露模式下 resolve 成功激活技能时，通过 recorder 推送 skill_activated 事件，
     * 携带 skill_name(displayName) 与 tool_names，告知前端"技能 X 已激活、关联工具已注入"。
     * @param sessionId
     * @param toolName
     * @param result
     * @param arguments
     * @param recorder  步骤记录器（用于 skill_activated 事件推送；非渐进/非 resolve 场景可不用）
     */
    void onToolExecuted(int sessionId, String toolName, ToolCallResult result, String arguments, StepRecorder recorder);

    /**
     * 请求结束清理，移除本会话的 per-session 激活集
     * @param sessionId
     */
    void clearSession(int sessionId);
}
