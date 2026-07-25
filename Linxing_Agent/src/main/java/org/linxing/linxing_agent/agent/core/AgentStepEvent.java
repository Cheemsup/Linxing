package org.linxing.linxing_agent.agent.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStepEvent {

    private String eventType;
    private int stepNumber;
    private String phase;
    private String label;
    private Map<String, Object> stepData;
    private String answer;
    private String error;
    private boolean finalStep;
    /** 0724 改造C：层级字段，供 SSE 流式推送时前端实时归集到树（与 DB 的 parent_step_id/agent_id 同源）。
     *  由 StepRecorder 在 pushSse 前按当前上下文栈填充，SseChatAdapter 透传给前端。 */
    private Integer parentStepId;
    private String agentId;
    /** 0724 改造C：本 step 落库后的 id，供前端流式按 parentStepId 挂载子节点时定位父节点。
     *  由 StepRecorder 在 persist 后回填。 */
    private Integer stepId;
}
