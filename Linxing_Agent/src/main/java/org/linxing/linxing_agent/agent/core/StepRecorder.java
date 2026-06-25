package org.linxing.linxing_agent.agent.core;

import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.scope.AgenticScope;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.entity.AgentStep;
import org.linxing.linxing_agent.agent.mapper.AgentStepMapper;
import org.linxing.linxing_agent.agent.vo.AgentStepVO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一步骤记录器：负责 SSE 推送 + agent_steps 持久化 + 内存累积 VO。
 * 每次 chat() 创建一个实例，主循环与工作流共享同一实例，保证一次会话内 step_order 单调递增、无空缺。chat_message_id 先为 null，最终由 ChatServiceImpl 根据 session_id 统一回填。
 */
@Slf4j
public class StepRecorder {

    private final AgentStepListener listener;
    private final AgentStepMapper agentStepMapper;
    private final Integer sessionId;
    private final AtomicInteger orderSeq;
    private final List<AgentStepVO> recordedSteps;

    public StepRecorder(AgentStepListener listener, AgentStepMapper agentStepMapper, Integer sessionId) {
        this.listener = listener;
        this.agentStepMapper = agentStepMapper;
        this.sessionId = sessionId;
        this.orderSeq = new AtomicInteger(1);
        this.recordedSteps = new ArrayList<>();
    }

    /**
     * 统一记录一个步骤事件：SSE 推送 + agent_steps 持久化（final 类型不入库）+ 累积 VO。
     * DB step_order 由本方法内部分配（orderSeq 递增），与 event.stepNumber 解耦。
     * final 类型按 schema 设计不入库（最终回答唯一存储在 chat_messages），仅推送 SSE。
     *
     * @param event 步骤事件，stepNumber 仅供 SSE 推送使用
     */
    public void record(AgentStepEvent event) {
        // SSE 推送
        if (listener != null) {
            listener.onStep(event);
        }
        // final 类型不入库（schema 设计），其他类型持久化 + 累积 VO
        if (AgentStepTypes.FINAL.equals(event.getEventType())) {
            return;
        }
        persistWithNextOrder(event);
    }

    /**
     * 仅分配下一个 step_order 序号，不做 SSE 推送和持久化。
     * 供需要取连续序号但自行控制持久化的场景使用。
     */
    public int nextOrder() {
        return orderSeq.getAndIncrement();
    }

    /**
     * 记录 thinking 步骤的完整推理文本：仅 DB 持久化 + 累积 VO，不推送 SSE。
     * thinking 步骤的 SSE 与 DB 语义分离，不能用 {@link #record(AgentStepEvent)} 统一处理
     * thiking内容的SSE推送通过专门的实现来进行，若用 record() 会导致 SSE 重复推送（循环开头一次 + record 内部一次）。
     * 本方法仅完成 DB 持久化 + VO 累积，step_order 从共享 orderSeq 分配保证连续。
     *
     * @param content  完整推理文本（建议调用方截断）
     * @param stepData 结构化数据（如 thinking_tokens）
     * @return 持久化后的 AgentStep（失败返回 null）
     */
    public AgentStep recordThinkingContent(String content, Map<String, Object> stepData) {
        try {
            int order = orderSeq.getAndIncrement();
            AgentStep step = AgentStep.builder()
                    .chatMessageId(null)
                    .sessionId(sessionId)
                    .stepOrder(order)
                    .stepType(AgentStepTypes.THINKING)
                    .content(content != null ? content : "")
                    .stepData(stepData != null ? stepData : Map.of())
                    .build();
            agentStepMapper.insert(step);
            recordedSteps.add(AgentStepVO.builder()
                    .id(step.getId())
                    .stepOrder(step.getStepOrder())
                    .stepType(step.getStepType())
                    .content(step.getContent())
                    .stepData(step.getStepData())
                    .createdAt(step.getCreatedAt())
                    .build());
            return step;
        } catch (Exception e) {
            log.warn("thinking 步骤持久化失败，继续执行: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 内部持久化逻辑：用 nextOrder 分配 step_order，insert + 累积 VO。
     */
    private void persistWithNextOrder(AgentStepEvent event) {
        try {
            int order = orderSeq.getAndIncrement();
            String content = event.getAnswer() != null ? event.getAnswer()
                    : (event.getError() != null ? event.getError() : "");
            AgentStep step = AgentStep.builder()
                    .chatMessageId(null)
                    .sessionId(sessionId)
                    .stepOrder(order)
                    .stepType(event.getEventType())
                    .content(content)
                    .stepData(event.getStepData() != null ? event.getStepData() : Map.of())
                    .build();
            agentStepMapper.insert(step);
            recordedSteps.add(AgentStepVO.builder()
                    .id(step.getId())
                    .stepOrder(step.getStepOrder())
                    .stepType(step.getStepType())
                    .content(step.getContent())
                    .stepData(step.getStepData())
                    .createdAt(step.getCreatedAt())
                    .build());
        } catch (Exception e) {
            log.warn("步骤持久化失败，继续执行: eventType={}, sessionId={}, error={}",
                    event.getEventType(), sessionId, e.getMessage());
        }
    }

    /**
     * 便捷重载：按字段构建事件并记录。SSE stepNumber 固定为 0（工作流场景，前端不依赖归组）。
     * TODO：为提升速度，后续可以将持久化改为异步
     */
    public void record(String eventType, String phase, Map<String, Object> stepData,
                       String answer, String error, boolean finalStep) {
        record(AgentStepEvent.builder()
                .eventType(eventType)
                .stepNumber(0)
                .phase(phase)
                .stepData(stepData)
                .answer(answer)
                .error(error)
                .finalStep(finalStep)
                .build());
    }

    /**
     * 返回已记录的步骤 VO 列表（按写入顺序，不可变视图）。
     * 供 AgentExecutor 末尾取回填充 AgentResult.steps。
     * 外部不应直接修改列表，thinking 等特殊步骤请用 {@link #recordThinkingContent} 等专门方法。
     */
    public List<AgentStepVO> getRecordedSteps() {
        return java.util.Collections.unmodifiableList(recordedSteps);
    }

    // ==================== 静态辅助方法 ====================
    // TODO：createListener/buildSubAgentData 配合任务5（展示名源头化）一并重构；
    //       readBooleanState 与 step 记录无关，后续可迁到 SubAgentContext 或工具类。

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
                recorder.record(AgentStepTypes.SUB_AGENT, phase,
                        buildSubAgentData(agentName, agentRole, true, outputKey, true, null),
                        null, null, false);
            }

            @Override
            public void onAgentInvocationError(AgentInvocationError error) {
                recorder.record(AgentStepTypes.SUB_AGENT, phase,
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
