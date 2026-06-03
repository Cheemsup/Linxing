package org.linxing.linxing_agent.agent.core;

public interface AgentStepListener {

    /**
     * LLM的操作记录：tool、skill……
     * @param event
     */
    void onStep(AgentStepEvent event);

    /**
     * 流式token回调，携带 stepNumber 以便前端精确归组
     * @param token 当前token
     * @param stepNumber 当前所属的Agent循环步骤编号
     */
    default void onStream(String token, int stepNumber) {
        // 默认空实现，不关心流式token的监听器可忽略
    }
}
