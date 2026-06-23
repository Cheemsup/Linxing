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

    /** 澄清是否被触发（needs_clarification=true 时为 true） */
    private boolean clarificationTriggered;

    /** 澄清是否超时（用户未在 25 分钟内回复，使用默认值继续） */
    private boolean clarificationTimedOut;

    /** 计划生成重试次数（首次解析失败后重试，0 表示一次成功） */
    private int planRetryCount;

    /** 计划解析错误信息（清洗后仍无法解析时记录，便于诊断） */
    private String planParseError;

    /** 测验解析错误信息（清洗后仍无法解析时记录，便于诊断） */
    private String examParseError;
}

