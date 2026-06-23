package org.linxing.linxing_agent.agent.subagent.common;

import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.scope.AgenticScope;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentStepEvent;
import org.linxing.linxing_agent.agent.core.AgentStepListener;
import org.linxing.linxing_agent.agent.core.AgentStepTypes;
import org.linxing.linxing_agent.agent.entity.AgentStep;
import org.linxing.linxing_agent.agent.mapper.AgentStepMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工作流步骤记录器：统一向 SSE 推送并持久化到 agent_steps。
 * 作为公共组件供所有工作流 Service 使用。step_order 从 {@link #WORKFLOW_STEP_ORDER_BASE}
 * 开始递增，避免与主 ReAct 循环的步骤号冲突。chat_message_id 先为 null，
 * 最终由 ChatServiceImpl 根据 session_id 统一回填。
 *
 * TODO：考虑将其移动到主循环相关包下（表明这是真正“公用”），改造现有主循环的记录step的方法、实现本组件的复用
 */
@Slf4j
public class StepRecorder {

    /** 工作流步骤序号基座，避免与主 ReAct 循环的步骤号冲突 */
    //TODO：合并后考虑序列号的协调工作
    public static final int WORKFLOW_STEP_ORDER_BASE = 1000;

    private final AgentStepListener listener;
    private final AgentStepMapper mapper;
    private final Integer sessionId;
    private final AtomicInteger orderSeq;

    public StepRecorder(AgentStepListener listener, AgentStepMapper mapper, Integer sessionId) {
        this.listener = listener;
        this.mapper = mapper;
        this.sessionId = sessionId;
        this.orderSeq = new AtomicInteger(WORKFLOW_STEP_ORDER_BASE);
    }

    /**
     * 推送一个工作流步骤事件：同时发送 SSE + 持久化到 agent_steps 表。
     * TODO：为提升速度，后续可以将持久化改为异步
     *
     * @param eventType  事件类型（见 {@link AgentStepTypes}）
     * @param phase      阶段标识
     * @param stepData   结构化步骤数据
     * @param answer     步骤详情文本（可 null）
     * @param error      错误信息（可 null）
     * @param finalStep  是否为最终步骤
     */
    public void emit(String eventType, String phase, Map<String, Object> stepData,
                     String answer, String error, boolean finalStep) {
        AgentStepEvent event = AgentStepEvent.builder()
                .eventType(eventType)
                .stepNumber(0)
                .phase(phase)
                .stepData(stepData)
                .answer(answer)
                .error(error)
                .finalStep(finalStep)
                .build();
        if (listener != null) {
            listener.onStep(event);//记录步骤
        }
        try {
            int order = orderSeq.getAndIncrement();
            AgentStep step = AgentStep.builder()
                    .chatMessageId(null)
                    .sessionId(sessionId)
                    .stepOrder(order)
                    .stepType(eventType)
                    .content(answer != null ? answer : (error != null ? error : ""))
                    .stepData(stepData)
                    .build();
            mapper.insert(step);//持久化
        } catch (Exception e) {
            log.warn("工作流步骤持久化失败，继续执行: eventType={}, sessionId={}, error={}",
                    eventType, sessionId, e.getMessage());
        }
    }

    // ==================== 静态辅助方法 ====================
    //TODO：清除此处以下的不再使用的代码

    /**
     * 为子 Agent 创建 AgentListener，在执行完成后推送 sub_agent 事件。
     *
     * @param agentName  Agent 名称（用于展示）
     * @param agentRole  Agent 角色（用于前端 roleMap 映射）
     * @param outputKey  输出 key（可 null）
     * @param recorder   步骤记录器
     */
    public static AgentListener createListener(String agentName, String agentRole,
                                               String outputKey, StepRecorder recorder) {
        return createListener(agentName, agentRole, outputKey, recorder, "study_plan");
    }

    /**
     * 为子 Agent 创建 AgentListener，在执行完成后推送 sub_agent 事件。
     *
     * @param phase      阶段标识（用于 step 事件分组）
     */
    public static AgentListener createListener(String agentName, String agentRole,
                                               String outputKey, StepRecorder recorder,
                                               String phase) {
        return new AgentListener() {
            @Override
            public void afterAgentInvocation(AgentResponse response) {
                recorder.emit(AgentStepTypes.SUB_AGENT, phase,
                        buildSubAgentData(agentName, agentRole, true, outputKey, true, null),
                        null, null, false);
            }

            @Override
            public void onAgentInvocationError(AgentInvocationError error) {
                recorder.emit(AgentStepTypes.SUB_AGENT, phase,
                        buildSubAgentData(agentName, agentRole, true, outputKey, false, null),
                        null, getErrorMessage(error), false);
            }
        };
    }

    /**
     * 构建 sub_agent 步骤的 stepData。
     */
    public static Map<String, Object> buildSubAgentData(String agentName, String agentRole,
                                                        boolean triggered, String outputKey,
                                                        boolean success, String question) {
        Map<String, Object> data = new HashMap<>();
        data.put(AgentStepTypes.KEY_AGENT_NAME, agentName);
        data.put(AgentStepTypes.KEY_AGENT_ROLE, agentRole);
        data.put(AgentStepTypes.KEY_TRIGGERED, triggered);
        if (outputKey != null) {
            data.put(AgentStepTypes.KEY_OUTPUT_KEY, outputKey);
        }
        data.put(AgentStepTypes.KEY_SUCCESS, success);
        if (question != null) {
            data.put(AgentStepTypes.KEY_QUESTION, question);
        }
        return data;
    }

    /**
     * 防御性读取 Boolean 状态：兼容 AgenticScope 内部将 Boolean 存为 String/其他类型的情况，
     * 避免 conditional predicate 因类型不一致误判为 false 而跳过子 Agent。
     */
    public static boolean readBooleanState(AgenticScope scope, String key, boolean defaultValue) {
        Object raw = scope.readState(key);
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    private static String getErrorMessage(AgentInvocationError error) {
        Throwable ex = error.error();
        return ex != null ? ex.getMessage() : "unknown error";
    }
}
