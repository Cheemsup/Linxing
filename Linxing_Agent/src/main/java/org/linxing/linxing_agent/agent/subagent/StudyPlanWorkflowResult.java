package org.linxing.linxing_agent.agent.subagent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * study_plan 工作流执行结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudyPlanWorkflowResult {

    private Integer planId;
    private Integer examId;
    private int phaseCount;
    private int questionCount;

    /** 计划是否保存成功 */
    private boolean planSaved;

    /** 测验是否保存成功（未触发时为 false） */
    private boolean examSaved;

    /** exam sub_agent 是否被触发 */
    private boolean examTriggered;

    /** 错误信息（部分成功场景下记录失败原因） */
    private String error;
}
