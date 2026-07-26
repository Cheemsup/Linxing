package org.linxing.linxing_agent.agent.core;

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
 *
 * <p>每次 chat() 创建一个实例，主循环与工作流共享同一实例，保证一次会话内 step_order 单调递增、无空缺。
 * chatMessageId 先为 null，最终由 ChatServiceImpl 按 sessionId 统一回填。
 *
 * <p>层级归属（parent 栈 + agent 上下文栈，ThreadLocal）：
 * <ul>
 *   <li>{@link #pushParentStepId}/{@link #popParentStepId} 与 {@link #pushAgentId}/{@link #popAgentId}
 *       成对维护子 Agent 层级上下文，由 {@link SubAgentStepListener} 在 Agent 生命周期钩子中调用。</li>
 *   <li>sub_agent 拆 start/end 两个事件配对：前端靠同 agent_id 配对成可折叠面板。</li>
 *   <li>recordedSteps 用 CopyOnWriteArrayList，orderSeq 用 AtomicInteger，顺序场景线程安全。</li>
 * </ul>
 *
 * <p><b>架构约束</b>：parent/agent 上下文栈基于 ThreadLocal，仅适用于顺序子 Agent。
 * 未来引入并行子 Agent（parallelBuilder）前，必须把上下文迁移到 AgenticScope（线程安全），
 * parent 归属改靠 agent_id 分组重建。
 *
 * <p>TODO：readBooleanState 与 step 记录无关，后续可迁到新建的 AgenticScopes 工具类（专门处理 AgenticScope 读取的类型兼容），
 * 不应放入 SubAgentContext（后者职责是 ThreadLocal 绑定的、不暴露给 LLM 的业务上下文，与 AgenticScope 是两回事）。
 * <p>TODO：buildSubAgentData 是 stepData 构造工具，与 step 记录核心职责正交，后续可抽到独立的 stepData builder。
 */
@Slf4j
public class StepRecorder {

    /** step_data 中存储前端展示名的 key。表结构无独立 label 字段，展示名暂通过 step_data 传递。 */
    public static final String KEY_DISPLAY_LABEL = "display_label";

    /** 主 Agent 的 agent_id 标识 */
    public static final String MAIN_AGENT_ID = "main";

    private final AgentStepListener listener;
    private final AgentStepMapper agentStepMapper;
    private final Integer sessionId;
    private final IRuntimeMirrorService runtimeMirrorService; // step 落库后即时镜像（nullable，测试可传 null）
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
     * 统一记录一个步骤事件：SSE 推送 + agent_steps 持久化 + 累积 VO。
     *
     * <p>DB step_order 由本方法内部分配（orderSeq 递增），与 event.stepNumber 解耦。
     * final 类型不入库（最终回答唯一存储在 chat_messages），仅推送 SSE。
     * heartbeat 类型（TOOL_PROGRESS）仅 SSE 推送，不入库不进 recordedSteps 不分配 order。
     *
     * <p>持久化时自动写入 parent_step_id（取当前 parent 栈顶）与 agent_id（取当前 agent 上下文栈顶，默认 main）。
     * 先持久化拿 step id，回填到 event 后再 pushSse，使前端流式能拿到 stepId 按 parentStepId 归集到树（与 DB 层级同源）。
     *
     * @return 落库后的 step id，供 tool_call 压栈场景使用；final/heartbeat 场景或失败返回 null，其余调用方可忽略
     */
    public Integer record(AgentStepEvent event) {
        if (AgentStepTypes.TOOL_PROGRESS.equals(event.getEventType())) {
            recordHeartbeatOnly(event);
            return null;
        }
        if (AgentStepTypes.FINAL.equals(event.getEventType())) {
            pushSse(event);
            return null;
        }
        AgentStep step = persist(event, currentParentStepId(), currentAgentId());
        if (step != null && step.getId() != null) {
            event.setStepId(step.getId());
            event.setParentStepId(step.getParentStepId());
            event.setAgentId(step.getAgentId());
        }
        pushSse(event);
        return step != null ? step.getId() : null;
    }

    /**
     * 仅 SSE 推送心跳事件：不入库、不进 recordedSteps、不分配 orderSeq。
     * <p>心跳是驱动前端动画 + 防中间件空闲超时的保活信号，非业务步骤，不污染 DB。
     * <p>ToolExecutionTimeout 的心跳任务直接调用此入口，不走 record()。
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
     * 压入 parent step id（主循环 tool_call 落盘后调用，
     * 使工作流子 Agent 的 sub_agent start 能挂到该 tool_call 下）。
     */
    public void pushParentStepId(Integer stepId) {
        if (stepId != null) {
            parentStepIdStack.get().push(stepId);
        }
    }

    /**
     * 弹出 parent step id 栈顶（主循环 tool_result 落盘前调用，
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
     * 压入当前 agent_id（子 Agent 进入前由 {@link SubAgentStepListener#beforeAgentInvocation} 调用），
     * 使后续子 step 的 agent_id 取栈顶。栈空时 currentAgentId() 兜底返回 {@link #MAIN_AGENT_ID}。
     */
    public void pushAgentId(String agentName) {
        if (agentName != null) {
            agentIdStack.get().push(agentName);
        }
    }

    /**
     * 弹出 agent_id 栈顶（子 Agent 结束时由 {@link SubAgentStepListener} 调用），切回外层 agent。
     * 防御：栈顶非传入 agentName 也弹一层，避免栈泄漏（与原 popParentIfPresent 兜底逻辑一致）。
     */
    public void popAgentId(String agentName) {
        Deque<String> stack = agentIdStack.get();
        if (stack.isEmpty()) {
            return;
        }
        if (agentName != null && agentName.equals(stack.peek())) {
            stack.pop();
        } else {
            // 兜底：栈顶非预期也弹一层，避免栈泄漏
            stack.pop();
        }
    }

    /**
     * 记录 thinking 步骤的完整推理文本：仅 DB 持久化 + 累积 VO，不推送 SSE。
     * <p>thinking 的 SSE 推送由专门实现负责，若用 record() 会导致 SSE 重复推送（循环开头一次 + record 内部一次）。
     *
     * @param content  完整推理文本（建议调用方截断）
     * @param stepData 结构化数据（如 thinking_tokens）
     * @param label    前端展示名，如"思考中"
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
            recordedSteps.add(toVO(step, label));
            return step;
        } catch (Exception e) {
            log.warn("thinking 步骤持久化失败，继续执行: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 持久化并返回落库后的 AgentStep（含分配的 id 与自动写入的 parent_step_id/agent_id）。
     * <p>调用方据此回填 event 后再 pushSse，使前端流式能拿到 stepId 按 parentStepId 归集到树。
     * parentStepId/agentId 由调用方显式传入（通常取 currentParentStepId()/currentAgentId()，
     * start 事件等需特殊归属的场景亦可显式指定）。
     */
    AgentStep persist(AgentStepEvent event, Integer parentStepId, String agentId) {
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
            recordedSteps.add(toVO(step, event.getLabel()));
            return step;
        } catch (Exception e) {
            log.warn("步骤持久化失败: eventType={}, sessionId={}, error={}",
                    event.getEventType(), sessionId, e.getMessage());
            return null;
        }
    }

    /** 把 AgentStep 转为 VO 并提取 label（优先 event.label，其次 step_data 中的 display_label）。 */
    private AgentStepVO toVO(AgentStep step, String label) {
        return AgentStepVO.builder()
                .id(step.getId())
                .stepOrder(step.getStepOrder())
                .stepType(step.getStepType())
                .content(step.getContent())
                .label(label)
                .stepData(step.getStepData())
                .parentStepId(step.getParentStepId())
                .agentId(step.getAgentId())
                .createdAt(step.getCreatedAt())
                .build();
    }

    /**
     * 把刚落库的 step 即时镜像到 mirror:steps:{sessionId}（即时写 + 末尾补丁 chatMessageId）。
     * <p>此时 step.chatMessageId 仍为 null（尚未由 {@code updateChatMessageId} 回填），
     * {@code ChatServiceImpl.runAgentLoop} 末尾会按 assistantMsgId 过滤重写补丁。
     * <p>降级：失败仅 log.warn，绝不影响主流程（Mirror 是性能优化，正确性依赖 DB）。
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

    // ==================== parent 栈 + agent 上下文 ====================

    /** 当前 parent_step_id：栈顶，栈空返回 null（根层 step）。package-private 供同包的 listener 适配器读取。 */
    Integer currentParentStepId() {
        Deque<Integer> stack = parentStepIdStack.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /** 当前 agent_id：栈顶，栈空返回 main。package-private 供同包的 listener 适配器读取。 */
    String currentAgentId() {
        Deque<String> stack = agentIdStack.get();
        return stack.isEmpty() ? MAIN_AGENT_ID : stack.peek();
    }

    /**
     * 仅 SSE 推送（不入库），供 listener 钩子内已自行持久化后补推 SSE。
     * <p>推送前按当前上下文栈填充 parentStepId/agentId（仅当调用方未显式设置时），
     * 使前端流式 onStep 能实时归集到树（与 DB 落盘的层级同源）。
     */
    void pushSse(AgentStepEvent event) {
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
}
