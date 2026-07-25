package org.linxing.linxing_agent.agent.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStep {

    private Integer id;
    private Integer chatMessageId;
    private Integer sessionId;
    private Integer stepOrder;
    private String stepType;
    private String content;
    private Map<String, Object> stepData;
    /** 0724新增：父步骤ID，NULL表示根层（主Agent step）。表达树形嵌套 */
    private Integer parentStepId;
    /** 0724新增：所属Agent标识（main/plan_generator/exam_generator等），并行子Agent分组用 */
    private String agentId;
    private OffsetDateTime createdAt;
}
