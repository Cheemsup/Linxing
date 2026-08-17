package org.linxing.linxing_agent.agent.core;

import dev.langchain4j.agentic.observability.AfterAgentToolExecution;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.observability.BeforeAgentToolExecution;
import org.linxing.linxing_agent.agent.entity.AgentStep;
import org.linxing.linxing_agent.agent.subagent.SubAgentContext;
import org.linxing.linxing_agent.observability.AgentObservability;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 子 Agent 步骤监听器：把 langchain4j 的 {@link AgentListener} 生命周期钩子适配成 step 事件，
 * 采集子 Agent 内部工具调用并推送 sub_agent start/end 配对事件。
 *
 * <p>与 {@link StepRecorder} 的职责边界：
 * <ul>
 *   <li>{@link StepRecorder} 负责无状态的数据流：{@code AgentStepEvent} → 持久化 → SSE 推送。</li>
 *   <li>本类负责有状态的钩子适配：把 Agent 生命周期回调翻译成 {@link AgentStepEvent}，
 *       并通过 {@link StepRecorder#pushAgentId}/{@link StepRecorder#popAgentId} 与
 *       {@link StepRecorder#pushParentStepId}/{@link StepRecorder#popParentStepId} 维护层级上下文栈。</li>
 * </ul>
 *
 * <p>钩子时序与栈操作配对：
 * <ul>
 *   <li>{@code beforeAgentInvocation}：push agent 上下文为子 Agent name，走 {@link StepRecorder#record} 推
 *       sub_agent start 事件（is_start=true），用返回的 stepId 压入 parent 栈，作为后续子 step 的 parent。</li>
 *   <li>{@code afterAgentInvocation}/{@code onAgentInvocationError}：走 {@link StepRecorder#record} 推
 *       sub_agent end 事件（is_start=false, success/error），弹 parent/agent 栈切回外层。</li>
 *   <li>{@code beforeAgentToolExecution}/{@code afterAgentToolExecution}：把子 Agent 内部工具调用转成
 *       tool_call/tool_result step，agent_id=栈顶（子 Agent name），parent_step_id=栈顶（本子 Agent start step id）。
 *       先 persist 回填 stepId 再 pushSse，使前端流式实时归集。</li>
 *   <li>{@code inheritedBySubagents()} 返回 true：让父 listener 继承到嵌套子 Agent，保证深层子 Agent 也能采集。</li>
 * </ul>
 *
 * <p><b>架构约束</b>：依赖 {@link StepRecorder} 基于 ThreadLocal 的上下文栈，仅适用于顺序子 Agent。
 * 未来引入并行子 Agent（parallelBuilder）前，栈管理需迁移到 AgenticScope（线程安全）。
 */
public class SubAgentStepListener implements AgentListener {

    private final StepRecorder recorder;
    private final String agentName;
    private final String agentRole;
    private final String displayLabel;
    private final String outputKey;
    private final String phase;
    /** 0816 Langfuse：观测门面，非空时在 before/after/error 钩子建/闭子 Agent span；可为 null（不追踪） */
    private final AgentObservability agentObservability;

    /** 嵌套子 Agent span 句柄栈（inheritedBySubagents 使父 listener 可被深层子 Agent 复用，用栈保证配对） */
    private final ThreadLocal<Deque<AgentObservability.SubAgentHandle>> obsStack =
            ThreadLocal.withInitial(ArrayDeque::new);

    private SubAgentStepListener(StepRecorder recorder, String agentName, String agentRole,
                                 String displayLabel, String outputKey, String phase,
                                 AgentObservability agentObservability) {
        this.recorder = recorder;
        this.agentName = agentName;
        this.agentRole = agentRole;
        this.displayLabel = displayLabel;
        this.outputKey = outputKey;
        this.phase = phase;
        this.agentObservability = agentObservability;
    }

    /**
     * 工厂入口：为子 Agent 创建监听器实例。
     *
     * @param agentName           Agent 名称（内部标识，作为 agent_id）
     * @param agentRole           Agent 角色（用于前端兜底映射）
     * @param displayLabel        前端展示名（如"收集资料"）
     * @param outputKey           输出 key（可 null）
     * @param recorder            步骤记录器
     * @param phase               阶段标识（用于 step 事件分组）
     * @param agentObservability  Langfuse 观测门面（0816，可传 null 关闭子 Agent span 追踪）
     */
    public static AgentListener create(String agentName, String agentRole,
                                       String displayLabel,
                                       String outputKey, StepRecorder recorder,
                                       String phase, AgentObservability agentObservability) {
        return new SubAgentStepListener(recorder, agentName, agentRole, displayLabel, outputKey, phase,
                agentObservability);
    }

    @Override
    public void beforeAgentInvocation(dev.langchain4j.agentic.observability.AgentRequest agentRequest) {
        // 0816 Langfuse：建子 Agent span（Agent: xxx）并置为当前上下文，使子 Agent 内部 LLM 调用挂其下
        AgentObservability.SubAgentHandle obs = agentObservability != null
                ? agentObservability.beginSubAgent(agentName, agentRole, extractInput(agentRequest))
                : null;
        obsStack.get().push(obs);

        // 切换 agent 上下文为子 Agent name（后续子 step 的 agent_id）
        recorder.pushAgentId(agentName);
        // 推 sub_agent start 事件并拿到 step id 作为子 step 的 parent。
        // record() 内 currentParentStepId() 取外层（parentStepIdStack 尚未压 start.id），
        // currentAgentId() 取刚 push 的 agentName —— parent/agent 均正确。
        Map<String, Object> startData = StepRecorder.buildSubAgentData(agentName, agentRole, displayLabel,
                true, outputKey, true, null);
        startData.put(AgentStepTypes.KEY_IS_START, true);
        AgentStepEvent startEvent = AgentStepEvent.builder()
                .eventType(AgentStepTypes.SUB_AGENT)
                .stepNumber(0)
                .phase(phase)
                .label(displayLabel)
                .stepData(startData)
                .build();
        Integer startStepId = recorder.record(startEvent);
        if (startStepId != null) {
            // 压栈：子 Agent 内部 step 的 parent 指向此 start step
            recorder.pushParentStepId(startStepId);
        }
    }

    @Override
    public void afterAgentInvocation(AgentResponse response) {
        // 注意：end 事件必须先 record 再弹栈——record 时 agent 上下文仍是子 Agent，
        // 这样 end 事件的 agent_id 取到子 Agent name，前端可按 agent_id 配对 start/end。
        // parent_step_id 此时取栈顶（=本子 Agent start id 的外层 parent），与 start 一致。
        Map<String, Object> endData = StepRecorder.buildSubAgentData(agentName, agentRole, displayLabel,
                true, outputKey, true, null);
        endData.put(AgentStepTypes.KEY_IS_START, false);
        recorder.record(AgentStepEvent.builder()
                .eventType(AgentStepTypes.SUB_AGENT)
                .stepNumber(0)
                .phase(phase)
                .label(displayLabel)
                .stepData(endData)
                .build());
        closeSubAgentObs(extractOutput(response), null);
        popContextIfPresent();
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError error) {
        Map<String, Object> endData = StepRecorder.buildSubAgentData(agentName, agentRole, displayLabel,
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
        closeSubAgentObs(null, error.error());
        popContextIfPresent();
    }

    @Override
    public void beforeAgentToolExecution(BeforeAgentToolExecution before) {
        // 子 Agent 内部工具调用开始：转成 tool_call step。
        // agent_id 由 currentAgentId() 栈顶提供（= beforeAgentInvocation 压入的 agentName），
        // parent 由 currentParentStepId() 栈顶提供（= 本子 Agent start step id）。
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
        // 先 persist 回填 stepId/层级，再 pushSse，使前端流式能拿到 stepId 实时归集到树。
        AgentStep step = recorder.persist(event, recorder.currentParentStepId(), recorder.currentAgentId());
        if (step != null && step.getId() != null) {
            event.setStepId(step.getId());
            event.setParentStepId(step.getParentStepId());
            event.setAgentId(step.getAgentId());
        }
        recorder.pushSse(event);
    }

    @Override
    public void afterAgentToolExecution(AfterAgentToolExecution after) {
        // 子 Agent 内部工具调用结束：转成 tool_result step。
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
        AgentStep step = recorder.persist(event, recorder.currentParentStepId(), recorder.currentAgentId());
        if (step != null && step.getId() != null) {
            event.setStepId(step.getId());
            event.setParentStepId(step.getParentStepId());
            event.setAgentId(step.getAgentId());
        }
        recorder.pushSse(event);
    }

    @Override
    public boolean inheritedBySubagents() {
        // 让父 listener 继承到嵌套子 Agent，保证深层子 Agent 内部 step 也被采集
        return true;
    }

    /**
     * 弹栈并闭子 Agent span（栈顶 = 最近一次 beforeAgentInvocation 建的句柄）。
     * 无 handle（before 未建 span 或嵌套未配对）时 endSubAgent 内部判空跳过。
     */
    private void closeSubAgentObs(String output, Throwable error) {
        Deque<AgentObservability.SubAgentHandle> stack = obsStack.get();
        AgentObservability.SubAgentHandle obs = stack.isEmpty() ? null : stack.poll();
        if (agentObservability != null) {
            agentObservability.endSubAgent(obs, output, error);
        }
        if (stack.isEmpty()) {
            obsStack.remove();
        }
    }

    /** 子 Agent 问题：inputs map 序列化为可读文本（空则回退 agent 名） */
    private String extractInput(AgentRequest agentRequest) {
        Map<String, Object> inputs = agentRequest != null ? agentRequest.inputs() : null;
        if (inputs != null && !inputs.isEmpty()) {
            return String.valueOf(inputs);
        }
        return agentName;
    }

    /**
     * 子 Agent 输出：优先取 {@link SubAgentContext} 中的观测输出（Save 工具组装的全量 JSON），
     * 回退 {@code AgentResponse.output()}（plan/exam 受 prompt 约束仅返回 container_id）。
     * 0816 Phase2 改进2，见 reference/TODOS/langfuse/0816LangfuseObservabilityPhase2.md。
     */
    private String extractOutput(AgentResponse response) {
        SubAgentContext ctx = SubAgentContext.current();
        if (ctx != null) {
            Object observed = ctx.getAttribute(SubAgentContext.ATTR_OBSERVATION_OUTPUT);
            if (observed != null) {
                return String.valueOf(observed);
            }
        }
        Object output = response != null ? response.output() : null;
        return output != null ? String.valueOf(output) : null;
    }

    /**
     * 弹出 parent/agent 上下文栈顶（仅当栈非空），用于 afterAgentInvocation/onAgentInvocationError 结束子 Agent 归属。
     */
    private void popContextIfPresent() {
        recorder.popParentStepId();
        recorder.popAgentId(agentName);
    }

    private static String getErrorMessage(AgentInvocationError error) {
        Throwable ex = error.error();
        return ex != null ? ex.getMessage() : "unknown error";
    }
}
