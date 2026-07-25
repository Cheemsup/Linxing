package org.linxing.linxing_agent.agent.core;

import dev.langchain4j.agentic.observability.AfterAgentToolExecution;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.observability.BeforeAgentToolExecution;
import dev.langchain4j.agentic.scope.AgenticScope;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.entity.AgentStep;
import org.linxing.linxing_agent.agent.mapper.AgentStepMapper;
import org.linxing.linxing_agent.agent.service.IRuntimeMirrorService;
import org.linxing.linxing_agent.agent.vo.AgentStepVO;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一步骤记录器：负责 SSE 推送 + agent_steps 持久化 + 内存累积 VO。
 * 每次 chat() 创建一个实例，主循环与工作流共享同一实例，保证一次会话内 step_order 单调递增、无空缺。chat_message_id 先为 null，最终由 ChatServiceImpl 根据 session_id 统一回填。
 *
 * <p>0724 改进四：支持子 Agent step 层级归属。
 * <ul>
 *   <li>parent 栈 + agent 上下文（ThreadLocal）：beforeAgentInvocation 切子 Agent name 并 push parent step id，
 *       afterAgentInvocation 弹栈切回。子 Agent 内部 step 的 parent_step_id 取栈顶、agent_id 取当前上下文。</li>
 *   <li>sub_agent 拆 start/end 两个事件配对：beforeAgentInvocation 推 start（is_start=true），
 *       afterAgentInvocation/onAgentInvocationError 推 end（is_start=false, success/error）。前端靠同 agent_id 配对成可折叠面板。</li>
 *   <li>重写 beforeAgentToolExecution/afterAgentToolExecution：子 Agent 内部工具调用转成 tool_call/tool_result step，
 *       agent_id=agentInstance.name()，parent_step_id=当前 sub_agent step id。</li>
 *   <li>recordedSteps 换 CopyOnWriteArrayList，orderSeq 已是 AtomicInteger，顺序场景线程安全。</li>
 * </ul>
 * <p><b>技术债</b>：parent 栈用 ThreadLocal，顺序子 Agent 可用；未来引入并行子 Agent（parallelBuilder）前，
 * 必须把 SubAgentContext 迁移到 AgenticScope（线程安全），parent 归属改靠 agent_id 分组重建。
 */
@Slf4j
public class StepRecorder {

    /**
     * step_data 中存储前端展示名的 key。
     * 为兼容现有 agent_steps 表结构（无独立 label 字段），展示名暂通过 step_data 传递。
     */
    public static final String KEY_DISPLAY_LABEL = "display_label";

    /** 主 Agent 的 agent_id 标识 */
    public static final String MAIN_AGENT_ID = "main";

    private final AgentStepListener listener;
    private final AgentStepMapper agentStepMapper;
    private final Integer sessionId;
    private final IRuntimeMirrorService runtimeMirrorService; // P3 Runtime Mirror：step 落库后即时镜像（nullable，测试可传 null）
    private final AtomicInteger orderSeq;
    private final List<AgentStepVO> recordedSteps;

    /** 当前活跃 sub_agent step id 栈：beforeAgentInvocation push，afterAgentInvocation pop。子 step 的 parent_step_id 取栈顶。 */
    private final ThreadLocal<Deque<Integer>> parentStepIdStack = ThreadLocal.withInitial(ArrayDeque::new);
    /** 当前 agent_id 上下文栈：主循环默认 main，beforeAgentInvocation push 子 Agent name，afterAgentInvocation pop。 */
    private final ThreadLocal<Deque<String>> agentIdStack = ThreadLocal.withInitial(ArrayDeque::new);

    public StepRecorder(AgentStepListener listener, AgentStepMapper agentStepMapper, Integer sessionId,
                        IRuntimeMirrorService runtimeMirrorService) {
        this.listener = listener;
        this.agentStepMapper = agentStepMapper;
        this.sessionId = sessionId;
        this.runtimeMirrorService = runtimeMirrorService;
        this.orderSeq = new AtomicInteger(1);
        this.recordedSteps = new CopyOnWriteArrayList<>();
    }

    /**
     * 统一记录一个步骤事件：SSE 推送 + agent_steps 持久化（final 类型不入库）+ 累积 VO。
     * DB step_order 由本方法内部分配（orderSeq 递增），与 event.stepNumber 解耦。
     * final 类型按 schema 设计不入库（最终回答唯一存储在 chat_messages），仅推送 SSE。
     *
     * <p>0724 改进四：持久化时自动写入 parent_step_id（取当前 parent 栈顶）与 agent_id（取当前 agent 上下文栈顶，默认 main）。
     *
     * @param event 步骤事件，stepNumber 仅供 SSE 推送使用
     */
    public void record(AgentStepEvent event) {
        // 心跳类型仅 SSE 推送，不入库不进 recordedSteps 不分配 order
        if (AgentStepTypes.TOOL_PROGRESS.equals(event.getEventType())) {
            recordHeartbeatOnly(event);
            return;
        }
        // final 类型不入库（schema 设计），仅推送 SSE
        if (AgentStepTypes.FINAL.equals(event.getEventType())) {
            pushSse(event);
            return;
        }
        // 0724 改造C：先持久化拿 step id，回填到 event 后再 pushSse，
        // 使前端流式能拿到 stepId 按 parentStepId 归集到树（与 DB 层级同源）。
        AgentStep step = persistAndReturn(event, currentParentStepId(), currentAgentId());
        if (step != null && step.getId() != null) {
            event.setStepId(step.getId());
            event.setParentStepId(step.getParentStepId());
            event.setAgentId(step.getAgentId());
        }
        pushSse(event);
    }

    /**
     * 仅 SSE 推送心跳事件：不入库、不进 recordedSteps、不分配 orderSeq。
     * <p>心跳是驱动前端动画 + 防中间件空闲超时的保活信号，非业务步骤，不污染 DB。
     * <p>统一入口：未来若 heartbeat 需附加逻辑（如限频/采样）有单一注入点。
     */
    public void recordHeartbeatOnly(AgentStepEvent event) {
        if (listener != null) {
            try {
                listener.onStep(event);
            } catch (Exception e) {
                log.debug("[StepRecorder] 心跳推送失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 仅分配下一个 step_order 序号，不做 SSE 推送和持久化。
     * 供需要取连续序号但自行控制持久化的场景使用。
     */
    public int nextOrder() {
        return orderSeq.getAndIncrement();
    }

    /**
     * 记录 tool_call 事件并返回落库后的 step id（含 SSE 推送）。
     * <p>0724 改造B：供主循环在 tool_call 落盘后把 step id 压入 parent 栈，
     * 使后续工作流子 Agent 的 sub_agent start 能挂到该 tool_call 下（parent_step_id 不再为 NULL）。
     * 与 {@link #record} 的区别：返回 step id（record 返回 void），调用方据此压栈。
     * agent_id/parent_step_id 仍由当前上下文栈提供（主循环场景为 main/NULL）。
     *
     * @return 落库后的 step id，失败返回 null
     */
    public Integer recordToolCallReturningId(AgentStepEvent event) {
        if (AgentStepTypes.FINAL.equals(event.getEventType())) {
            pushSse(event);
            return null;
        }
        // 0724 改造C：先持久化拿 step id，回填到 event 后再 pushSse，
        // 使前端流式能拿到 stepId 按 parentStepId 归集到树。返回 id 供主循环压 parent 栈。
        AgentStep step = persistAndReturn(event, currentParentStepId(), currentAgentId());
        if (step != null && step.getId() != null) {
            event.setStepId(step.getId());
            event.setParentStepId(step.getParentStepId());
            event.setAgentId(step.getAgentId());
        }
        pushSse(event);
        return step != null ? step.getId() : null;
    }

    /**
     * 压入 parent step id（0724 改造B：主循环 tool_call 落盘后调用，
     * 使工作流子 Agent 的 sub_agent start 能挂到该 tool_call 下）。
     */
    public void pushParentStepId(Integer stepId) {
        if (stepId != null) {
            parentStepIdStack.get().push(stepId);
        }
    }

    /**
     * 弹出 parent step id 栈顶（0724 改造B：主循环 tool_result 落盘前调用，
     * 使 tool_result 的 parent 取到 NULL，与 tool_call 平级）。
     * 栈空时不操作（防御性，避免误弹上层）。
     */
    public void popParentStepId() {
        Deque<Integer> stack = parentStepIdStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }

    /**
     * 记录 thinking 步骤的完整推理文本：仅 DB 持久化 + 累积 VO，不推送 SSE。
     * thiking内容的SSE推送通过专门的实现来进行，若用 record() 会导致 SSE 重复推送（循环开头一次 + record 内部一次）。
     *
     * @param content  完整推理文本（建议调用方截断）
     * @param stepData 结构化数据（如 thinking_tokens）
     * @return 持久化后的 AgentStep（失败返回 null）
     */
    public AgentStep recordThinkingContent(String content, Map<String, Object> stepData) {
        return recordThinkingContent(content, stepData, null);
    }

    /**
     * 记录 thinking 步骤的完整推理文本，并指定前端展示名。
     *
     * @param content 完整推理文本（建议调用方截断）
     * @param stepData 结构化数据（如 thinking_tokens）
     * @param label 前端展示名，如"思考中"
     * @return 持久化后的 AgentStep（失败返回 null）
     */
    public AgentStep recordThinkingContent(String content, Map<String, Object> stepData, String label) {
        try {
            int order = orderSeq.getAndIncrement();
            Map<String, Object> data = stepData != null ? new HashMap<>(stepData) : new HashMap<>();
            if (label != null && !label.isBlank()) {
                data.put(KEY_DISPLAY_LABEL, label);
            }
            AgentStep step = AgentStep.builder()
                    .chatMessageId(null)
                    .sessionId(sessionId)
                    .stepOrder(order)
                    .stepType(AgentStepTypes.THINKING)
                    .content(content != null ? content : "")
                    .stepData(data)
                    .parentStepId(currentParentStepId())
                    .agentId(currentAgentId())
                    .build();
            agentStepMapper.insert(step);
            mirrorStep(step);
            recordedSteps.add(AgentStepVO.builder()
                    .id(step.getId())
                    .stepOrder(step.getStepOrder())
                    .stepType(step.getStepType())
                    .content(step.getContent())
                    .label(label)
                    .stepData(step.getStepData())
                    .parentStepId(step.getParentStepId())
                    .agentId(step.getAgentId())
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
     * 若事件携带 {@link AgentStepEvent#getLabel()}，会写入 step_data 的 {@link #KEY_DISPLAY_LABEL} 并回显到 VO。
     * <p>0724 改进四：自动写入 parent_step_id（当前栈顶）与 agent_id（当前上下文，默认 main）。
     */
    private void persistWithNextOrder(AgentStepEvent event) {
        try {
            int order = orderSeq.getAndIncrement();
            String content = event.getAnswer() != null ? event.getAnswer()
                    : (event.getError() != null ? event.getError() : "");
            Map<String, Object> stepData = event.getStepData() != null
                    ? new HashMap<>(event.getStepData()) : new HashMap<>();
            if (event.getLabel() != null && !event.getLabel().isBlank()) {
                stepData.put(KEY_DISPLAY_LABEL, event.getLabel());
            }
            AgentStep step = AgentStep.builder()
                    .chatMessageId(null)
                    .sessionId(sessionId)
                    .stepOrder(order)
                    .stepType(event.getEventType())
                    .content(content)
                    .stepData(stepData)
                    .parentStepId(currentParentStepId())
                    .agentId(currentAgentId())
                    .build();
            agentStepMapper.insert(step);
            mirrorStep(step);
            recordedSteps.add(AgentStepVO.builder()
                    .id(step.getId())
                    .stepOrder(step.getStepOrder())
                    .stepType(step.getStepType())
                    .content(step.getContent())
                    .label(event.getLabel())
                    .stepData(step.getStepData())
                    .parentStepId(step.getParentStepId())
                    .agentId(step.getAgentId())
                    .createdAt(step.getCreatedAt())
                    .build());
        } catch (Exception e) {
            log.warn("步骤持久化失败，继续执行: eventType={}, sessionId={}, error={}",
                    event.getEventType(), sessionId, e.getMessage());
        }
    }

    /**
     * 持久化并返回落库后的 AgentStep（含分配的 id），供 beforeAgentInvocation 拿到 sub_agent start step id 压栈。
     * <p>与 {@link #persistWithNextOrder} 的区别：返回 step（含 id），不入 agentId/parent 自动上下文——
     * 调用方可显式指定 parentStepId（sub_agent start 的 parent 是外层 tool_call 或上层 sub_agent）。
     */
    private AgentStep persistAndReturn(AgentStepEvent event, Integer parentStepId, String agentId) {
        try {
            int order = orderSeq.getAndIncrement();
            String content = event.getAnswer() != null ? event.getAnswer()
                    : (event.getError() != null ? event.getError() : "");
            Map<String, Object> stepData = event.getStepData() != null
                    ? new HashMap<>(event.getStepData()) : new HashMap<>();
            if (event.getLabel() != null && !event.getLabel().isBlank()) {
                stepData.put(KEY_DISPLAY_LABEL, event.getLabel());
            }
            AgentStep step = AgentStep.builder()
                    .chatMessageId(null)
                    .sessionId(sessionId)
                    .stepOrder(order)
                    .stepType(event.getEventType())
                    .content(content)
                    .stepData(stepData)
                    .parentStepId(parentStepId)
                    .agentId(agentId != null ? agentId : MAIN_AGENT_ID)
                    .build();
            agentStepMapper.insert(step);
            mirrorStep(step);
            recordedSteps.add(AgentStepVO.builder()
                    .id(step.getId())
                    .stepOrder(step.getStepOrder())
                    .stepType(step.getStepType())
                    .content(step.getContent())
                    .label(event.getLabel())
                    .stepData(step.getStepData())
                    .parentStepId(step.getParentStepId())
                    .agentId(step.getAgentId())
                    .createdAt(step.getCreatedAt())
                    .build());
            return step;
        } catch (Exception e) {
            log.warn("步骤持久化失败(带返回): eventType={}, sessionId={}, error={}",
                    event.getEventType(), sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 把刚落库的 step 即时镜像到 mirror:steps:{sessionId}（thePlan P3 决策：即时写 + 末尾补丁 chatMessageId）。
     * <p>
     * 此时 step.chatMessageId 仍为 null（尚未由 {@code updateChatMessageId} 回填），
     * {@code ChatServiceImpl.runAgentLoop} 末尾会按 assistantMsgId 过滤重写补丁。
     * <p>
     * 降级：失败仅 log.warn，绝不影响主流程（Mirror 是性能优化，正确性依赖 DB）。
     */
    private void mirrorStep(AgentStep step) {
        if (runtimeMirrorService == null || step == null || step.getId() == null) {
            return;
        }
        try {
            runtimeMirrorService.appendStep(sessionId, step);
        } catch (Exception e) {
            log.warn("[Mirror] step 即时镜像失败, sessionId={}, stepId={}: {}",
                    sessionId, step.getId(), e.getMessage());
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
        return Collections.unmodifiableList(recordedSteps);
    }

    // ==================== 0724 改进四：parent 栈 + agent 上下文 ====================

    /** 当前 parent_step_id：栈顶，栈空返回 null（根层 step）。 */
    private Integer currentParentStepId() {
        Deque<Integer> stack = parentStepIdStack.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /** 当前 agent_id：栈顶，栈空返回 main。 */
    private String currentAgentId() {
        Deque<String> stack = agentIdStack.get();
        return stack.isEmpty() ? MAIN_AGENT_ID : stack.peek();
    }

    // ==================== 静态辅助方法 ====================
    // TODO：readBooleanState 与 step 记录无关，后续可迁到 SubAgentContext 或工具类。

    /**
     * 为子 Agent 创建 AgentListener，采集子 Agent 内部工具调用并推送 sub_agent start/end 配对事件。
     *
     * <p>0724 改进四重写：
     * <ul>
     *   <li>{@code beforeAgentInvocation}：切 agent 上下文为子 Agent name，推 sub_agent start 事件（is_start=true, triggered=true），
     *       拿到 step id 压入 parent 栈，作为后续子 step 的 parent。</li>
     *   <li>{@code afterAgentInvocation}/{@code onAgentInvocationError}：推 sub_agent end 事件（is_start=false, success/error），弹栈切回外层 agent。</li>
     *   <li>{@code beforeAgentToolExecution}/{@code afterAgentToolExecution}：把子 Agent 内部工具调用转成 tool_call/tool_result step，
     *       agent_id=agentInstance.name()，parent_step_id=当前栈顶（该子 Agent 的 start step id）。</li>
     *   <li>{@code inheritedBySubagents()} 返回 true：让父 listener 继承到嵌套子 Agent，保证深层子 Agent 也能采集。</li>
     * </ul>
     *
     * @param agentName    Agent 名称（内部标识，作为 agent_id）
     * @param agentRole    Agent 角色（用于前端兜底映射）
     * @param displayLabel 前端展示名（如"收集资料"）
     * @param outputKey    输出 key（可 null）
     * @param recorder     步骤记录器
     * @param phase        阶段标识（用于 step 事件分组）
     */
    public static AgentListener createListener(String agentName, String agentRole,
                                               String displayLabel,
                                               String outputKey, StepRecorder recorder,
                                               String phase) {
        return new AgentListener() {
            @Override
            public void beforeAgentInvocation(dev.langchain4j.agentic.observability.AgentRequest agentRequest) {
                // 切换 agent 上下文为子 Agent name（后续子 step 的 agent_id）
                recorder.agentIdStack.get().push(agentName);
                // 推 sub_agent start 事件，拿到 step id 作为子 step 的 parent
                Map<String, Object> startData = buildSubAgentData(agentName, agentRole, displayLabel,
                        true, outputKey, true, null);
                startData.put(AgentStepTypes.KEY_IS_START, true);
                AgentStepEvent startEvent = AgentStepEvent.builder()
                        .eventType(AgentStepTypes.SUB_AGENT)
                        .stepNumber(0)
                        .phase(phase)
                        .label(displayLabel)
                        .stepData(startData)
                        .build();
                // start 事件的 parent 取当前栈顶（外层 tool_call 或上层 sub_agent），agent_id 用刚 push 的 agentName
                Integer outerParent = recorder.currentParentStepId();
                AgentStep startStep = recorder.persistAndReturn(startEvent,
                        outerParent, agentName);
                // 0724 改造C：回填 step id 与层级后再推送 SSE——注意 start 事件的 parent 是外层（非自身），
                // 故不能走 pushSse（其 currentParentStepId 此时仍是外层，但为防歧义显式回填）。
                if (startStep != null && startStep.getId() != null) {
                    startEvent.setStepId(startStep.getId());
                    startEvent.setParentStepId(startStep.getParentStepId());
                    startEvent.setAgentId(startStep.getAgentId());
                }
                recorder.pushSse(startEvent);
                if (startStep != null && startStep.getId() != null) {
                    // 压栈：子 Agent 内部 step 的 parent 指向此 start step
                    recorder.parentStepIdStack.get().push(startStep.getId());
                }
            }

            @Override
            public void afterAgentInvocation(AgentResponse response) {
                // 注意：end 事件必须先 record 再弹栈——record 时 agent 上下文仍是子 Agent，
                // 这样 end 事件的 agent_id 取到子 Agent name，前端可按 agent_id 配对 start/end。
                // parent_step_id 此时取栈顶（=本子 Agent start id 的外层 parent），与 start 一致。
                Map<String, Object> endData = buildSubAgentData(agentName, agentRole, displayLabel,
                        true, outputKey, true, null);
                endData.put(AgentStepTypes.KEY_IS_START, false);
                recorder.record(AgentStepEvent.builder()
                        .eventType(AgentStepTypes.SUB_AGENT)
                        .stepNumber(0)
                        .phase(phase)
                        .label(displayLabel)
                        .stepData(endData)
                        .build());
                // 弹栈：结束本子 Agent 的子 step 归属 + agent 上下文切回外层
                popParentIfPresent(recorder, agentName);
            }

            @Override
            public void onAgentInvocationError(AgentInvocationError error) {
                Map<String, Object> endData = buildSubAgentData(agentName, agentRole, displayLabel,
                        true, outputKey, false, null);
                endData.put(AgentStepTypes.KEY_IS_START, false);
                recorder.record(AgentStepEvent.builder()
                        .eventType(AgentStepTypes.SUB_AGENT)
                        .stepNumber(0)
                        .phase(phase)
                        .label(displayLabel)
                        .stepData(endData)
                        .error(getErrorMessage(error))
                        .build());
                popParentIfPresent(recorder, agentName);
            }

            @Override
            public void beforeAgentToolExecution(BeforeAgentToolExecution before) {
                // 子 Agent 内部工具调用开始：转成 tool_call step。
                // agent_id 由 currentAgentId() 栈顶提供（= beforeAgentInvocation 压入的 agentName 业务编排名），
                // parent 由 currentParentStepId() 栈顶提供（= 本子 Agent start step id）。
                // 不读 agentInstance.name()、不临时 push/pop——避免 sub_agent 容器与内部 step 出现双轨 agent_id。
                String toolName = before.toolExecution().request().name();
                String toolCallId = before.toolExecution().request().id();
                String arguments = before.toolExecution().request().arguments();
                Map<String, Object> data = new HashMap<>();
                data.put(AgentStepTypes.KEY_TOOL_CALL_ID, toolCallId);
                data.put(AgentStepTypes.KEY_TOOL_NAME, toolName);
                data.put(AgentStepTypes.KEY_TOOL_KIND, AgentStepTypes.TOOL_KIND_FUNCTION);
                data.put("arguments", arguments);
                AgentStepEvent event = AgentStepEvent.builder()
                        .eventType(AgentStepTypes.TOOL_CALL)
                        .stepNumber(0)
                        .phase(phase)
                        .label(toolName)
                        .answer(arguments)
                        .stepData(data)
                        .build();
                recorder.pushSse(event);
                recorder.persistWithNextOrder(event);
            }

            @Override
            public void afterAgentToolExecution(AfterAgentToolExecution after) {
                // 子 Agent 内部工具调用结束：转成 tool_result step。
                // agent_id 复用栈顶（同 beforeAgentToolExecution），不读 agentInstance.name()。
                String toolName = after.toolExecution().request().name();
                String toolCallId = after.toolExecution().request().id();
                boolean failed = after.toolExecution().hasFailed();
                String resultText = after.toolExecution().result();
                Map<String, Object> data = new HashMap<>();
                data.put(AgentStepTypes.KEY_TOOL_CALL_ID, toolCallId);
                data.put(AgentStepTypes.KEY_TOOL_NAME, toolName);
                data.put(AgentStepTypes.KEY_TOOL_KIND, AgentStepTypes.TOOL_KIND_FUNCTION);
                data.put(AgentStepTypes.KEY_IS_SUCCESS, !failed);
                AgentStepEvent event = AgentStepEvent.builder()
                        .eventType(AgentStepTypes.TOOL_RESULT)
                        .stepNumber(0)
                        .phase(phase)
                        .label(toolName)
                        .answer(!failed ? resultText : null)
                        .error(failed ? resultText : null)
                        .stepData(data)
                        .build();
                recorder.pushSse(event);
                recorder.persistWithNextOrder(event);
            }

            @Override
            public boolean inheritedBySubagents() {
                // 让父 listener 继承到嵌套子 Agent，保证深层子 Agent 内部 step 也被采集
                return true;
            }
        };
    }

    /** 弹出 parent 栈顶（仅当栈非空），用于 afterAgentInvocation/onAgentInvocationError 结束子 Agent 归属。 */
    private static void popParentIfPresent(StepRecorder recorder, String agentName) {
        Deque<Integer> pStack = recorder.parentStepIdStack.get();
        if (!pStack.isEmpty()) {
            pStack.pop();
        }
        Deque<String> aStack = recorder.agentIdStack.get();
        // 弹出 beforeAgentInvocation push 的 agentName（防御：仅弹栈顶匹配项）
        if (!aStack.isEmpty() && agentName.equals(aStack.peek())) {
            aStack.pop();
        } else if (!aStack.isEmpty()) {
            // 兜底：栈顶非预期也弹一层，避免栈泄漏
            aStack.pop();
        }
    }

    /** 仅 SSE 推送（不入库），供 listener 钩子内已自行持久化后补推 SSE。
     *  <p>0724 改造C：推送前按当前上下文栈填充 parentStepId/agentId（仅当调用方未显式设置时），
     *  使前端流式 onStep 能实时归集到树（与 DB 落盘的层级同源）。 */
    private void pushSse(AgentStepEvent event) {
        if (listener != null) {
            try {
                if (event.getParentStepId() == null) {
                    event.setParentStepId(currentParentStepId());
                }
                if (event.getAgentId() == null) {
                    event.setAgentId(currentAgentId());
                }
                listener.onStep(event);
            } catch (Exception e) {
                log.debug("[StepRecorder] SSE 推送失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 构建 sub_agent 步骤的 stepData。
     *
     * @param displayLabel 前端展示名，会写入 {@link #KEY_DISPLAY_LABEL}
     */
    public static Map<String, Object> buildSubAgentData(String agentName, String agentRole,
                                                        String displayLabel,
                                                        boolean triggered, String outputKey,
                                                        boolean success, String question) {
        Map<String, Object> data = new HashMap<>();
        data.put(AgentStepTypes.KEY_AGENT_NAME, agentName);
        data.put(AgentStepTypes.KEY_AGENT_ROLE, agentRole);
        if (displayLabel != null && !displayLabel.isBlank()) {
            data.put(KEY_DISPLAY_LABEL, displayLabel);
        }
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
